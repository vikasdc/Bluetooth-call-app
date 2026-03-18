package com.btcall.app.data.audio

import com.btcall.app.domain.model.AudioPacket
import timber.log.Timber
import java.util.TreeMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Adaptive jitter buffer for real-time audio playback.
 *
 * Problem: Bluetooth introduces variable latency (jitter). If we play packets
 * as they arrive, gaps cause glitches. If we buffer too much, latency increases.
 *
 * Solution: Adaptive jitter buffer
 * - Maintains a sorted queue of packets ordered by sequence number
 * - Tracks network jitter (variance in arrival delay)
 * - Targets a buffer depth of max(MIN_DEPTH, jitter × JITTER_MULTIPLIER) frames
 * - On underrun → plays silence (PLC - packet loss concealment)
 * - On overflow → drops oldest packet(s)
 *
 * Parameters tuned for Bluetooth Classic voice:
 * - MIN_BUFFER_DEPTH = 2 frames (40ms)
 * - MAX_BUFFER_DEPTH = 8 frames (160ms)
 * - JITTER_MULTIPLIER = 2.0
 *
 * Thread-safety: All operations are lock-protected.
 */
class JitterBuffer {

    companion object {
        private const val MIN_BUFFER_DEPTH = 2      // frames (40ms at 20ms/frame)
        private const val MAX_BUFFER_DEPTH = 8      // frames (160ms)
        private const val JITTER_ALPHA = 0.1        // EWMA smoothing for jitter estimate
        private const val TARGET_FILL_MS = 60L      // Initial target buffer fill (3 frames)
    }

    // Sorted by sequence number for reordering
    private val buffer = TreeMap<Int, AudioPacket>()
    private val lock = ReentrantLock()

    private var nextExpectedSeq = -1
    private var smoothedJitterMs = 0.0
    private var lastArrivalMs = 0L
    private var lastPacketTimestampMs = 0L
    private var targetDepth = MIN_BUFFER_DEPTH

    // Stats for diagnostics
    var packetsReceived = 0L; private set
    var packetsLost = 0L; private set
    var packetsDropped = 0L; private set

    /**
     * Enqueue an incoming audio packet.
     * Out-of-order packets are inserted at the correct position.
     */
    fun enqueue(packet: AudioPacket) = lock.withLock {
        packetsReceived++

        // Measure arrival jitter
        val now = System.currentTimeMillis()
        if (lastArrivalMs > 0) {
            val arrivalDelta = now - lastArrivalMs
            val sendDelta = packet.timestampMs - lastPacketTimestampMs
            val jitter = Math.abs(arrivalDelta - sendDelta).toDouble()
            smoothedJitterMs = JITTER_ALPHA * jitter + (1 - JITTER_ALPHA) * smoothedJitterMs
        }
        lastArrivalMs = now
        lastPacketTimestampMs = packet.timestampMs

        // Update adaptive target depth (in frames)
        val jitterFrames = (smoothedJitterMs / 20.0).toInt() + 1  // 20ms per frame
        targetDepth = jitterFrames.coerceIn(MIN_BUFFER_DEPTH, MAX_BUFFER_DEPTH)

        // Drop very old packets (more than MAX_BUFFER_DEPTH behind next expected)
        if (nextExpectedSeq >= 0 && packet.sequenceNumber < nextExpectedSeq - MAX_BUFFER_DEPTH) {
            Timber.v("JitterBuffer: dropping late packet seq=${packet.sequenceNumber}")
            packetsDropped++
            return@withLock
        }

        buffer[packet.sequenceNumber] = packet

        // Enforce max buffer size by dropping oldest
        while (buffer.size > MAX_BUFFER_DEPTH * 2) {
            buffer.pollFirstEntry()
            packetsDropped++
        }
    }

    /**
     * Retrieve the next packet for playback.
     *
     * Returns:
     * - The next in-sequence packet if available
     * - Null (silence/PLC) if buffer is empty or not yet sufficiently filled
     */
    fun dequeue(): AudioPacket? = lock.withLock {
        if (buffer.isEmpty()) {
            // Complete underrun
            if (nextExpectedSeq >= 0) {
                packetsLost++
                nextExpectedSeq++  // Advance past the gap
            }
            return@withLock null
        }

        // Prebuffering phase: wait until we have targetDepth frames
        if (nextExpectedSeq < 0) {
            if (buffer.size < targetDepth) {
                return@withLock null  // Still filling initial buffer
            }
            // Start playback: begin from the earliest packet in buffer
            nextExpectedSeq = buffer.firstKey()
        }

        // Get packet at expected sequence, or handle gap
        val packet = buffer.remove(nextExpectedSeq)
        return@withLock if (packet != null) {
            nextExpectedSeq++
            packet
        } else {
            // Gap detected: play silence for this slot (PLC)
            packetsLost++
            nextExpectedSeq++
            null  // Caller will play silence
        }
    }

    /**
     * Check if the buffer has enough frames to start playing.
     * Useful for the initial prebuffering phase.
     */
    fun isReadyToPlay(): Boolean = lock.withLock {
        buffer.size >= MIN_BUFFER_DEPTH || nextExpectedSeq >= 0
    }

    fun reset() = lock.withLock {
        buffer.clear()
        nextExpectedSeq = -1
        smoothedJitterMs = 0.0
        lastArrivalMs = 0L
        packetsReceived = 0
        packetsLost = 0
        packetsDropped = 0
    }

    fun getDepth(): Int = lock.withLock { buffer.size }

    fun getEstimatedLatencyMs(): Int = lock.withLock {
        (smoothedJitterMs + (targetDepth * 20)).toInt()
    }

    fun getStats(): String = lock.withLock {
        "depth=${buffer.size}/$targetDepth jitter=${smoothedJitterMs.toInt()}ms " +
        "rcv=$packetsReceived lost=$packetsLost drop=$packetsDropped"
    }
}
