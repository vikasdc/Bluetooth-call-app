package com.btcall.app.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.btcall.app.domain.model.AudioPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays decoded PCM audio from the jitter buffer using AudioTrack in streaming mode.
 *
 * Architecture:
 * ┌─────────────────┐     ┌──────────────────┐     ┌──────────────┐
 * │ JitterBuffer    │────►│ PlaybackEngine    │────►│  AudioTrack  │
 * │ (sorted queue)  │     │ (drain @ 20ms)   │     │  (speaker)   │
 * └─────────────────┘     └──────────────────┘     └──────────────┘
 *
 * AudioTrack uses STREAM mode with VOICE_CALL usage for:
 * - Routing to earpiece/speaker automatically
 * - Lower OS scheduling priority (not mixed with media)
 * - AEC reference signal is routed correctly
 *
 * The drain loop runs every 20ms to match the Opus frame interval.
 * Between frames: if jitter buffer has a packet → decode and write;
 * if empty (underrun) → write silence (PLC).
 */
@Singleton
class AudioPlaybackEngine @Inject constructor(
    private val jitterBuffer: JitterBuffer
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playbackJob: Job? = null
    private var audioTrack: AudioTrack? = null

    /**
     * Start the AudioTrack and the 20ms drain loop.
     */
    fun start(): Result<Unit> {
        if (playbackJob?.isActive == true) {
            Timber.d("AudioPlaybackEngine already running")
            return Result.success(Unit)
        }

        val minBufferSize = AudioTrack.getMinBufferSize(
            OpusCodec.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize == AudioTrack.ERROR || minBufferSize == AudioTrack.ERROR_BAD_VALUE) {
            return Result.failure(IllegalStateException("Cannot determine AudioTrack buffer size"))
        }

        // 2 frames of buffer is sufficient — we drain every 20ms
        val bufferSize = maxOf(minBufferSize, OpusCodec.FRAME_SIZE_BYTES * 2)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(OpusCodec.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return Result.failure(IllegalStateException("AudioTrack failed to initialise"))
        }

        track.play()
        audioTrack = track

        jitterBuffer.reset()
        Timber.d("AudioPlaybackEngine: playback started")

        playbackJob = scope.launch(Dispatchers.IO) {
            runDrainLoop(track)
        }

        return Result.success(Unit)
    }

    private suspend fun runDrainLoop(track: AudioTrack) {
        val frameDurationMs = (OpusCodec.FRAME_SIZE_SAMPLES * 1000L) / OpusCodec.SAMPLE_RATE  // = 20ms
        val silenceFrame = OpusCodec.generateSilenceFrame()

        while (currentCoroutineContext().isActive) {
            val startMs = System.currentTimeMillis()

            val packet = jitterBuffer.dequeue()
            val pcmFrame = if (packet != null) {
                OpusCodec.decode(packet.encodedPayload)
            } else {
                // Underrun / packet loss — play silence (PLC)
                silenceFrame
            }

            // Write PCM to AudioTrack (blocking write)
            val written = track.write(pcmFrame, 0, pcmFrame.size)
            if (written < 0) {
                Timber.e("AudioTrack write error: $written")
            }

            // Sleep for the remainder of the frame window
            val elapsed = System.currentTimeMillis() - startMs
            val remaining = frameDurationMs - elapsed
            if (remaining > 0) delay(remaining)
        }
    }

    fun enqueue(packet: AudioPacket) {
        jitterBuffer.enqueue(packet)
    }

    fun stop() {
        playbackJob?.cancel()
        playbackJob = null

        audioTrack?.apply {
            try { stop() } catch (_: IllegalStateException) {}
            release()
        }
        audioTrack = null

        jitterBuffer.reset()
        Timber.d("AudioPlaybackEngine stopped")
    }

    fun getLatencyMs(): Int = jitterBuffer.getEstimatedLatencyMs()

    fun cleanup() {
        stop()
        scope.cancel()
    }
}
