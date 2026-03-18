package com.btcall.app.domain.repository

import com.btcall.app.domain.model.AudioPacket
import kotlinx.coroutines.flow.Flow

/**
 * Contract for audio capture, encoding, decoding, and playback.
 */
interface AudioRepository {

    /**
     * Flow of encoded audio packets captured from the microphone.
     * Each packet is a 20ms frame encoded with Opus (or PCM fallback).
     * Callers should send these over RFCOMM.
     */
    val capturedAudioPackets: Flow<AudioPacket>

    /**
     * Start microphone capture and Opus encoding.
     * Emits to [capturedAudioPackets].
     */
    suspend fun startCapture(): Result<Unit>

    /** Stop microphone capture. */
    fun stopCapture()

    /**
     * Enqueue a received [AudioPacket] into the jitter buffer for playback.
     * The jitter buffer reorders packets and handles gaps.
     */
    fun enqueueForPlayback(packet: AudioPacket)

    /**
     * Start the playback engine (AudioTrack + jitter buffer drain loop).
     * Must be called before [enqueueForPlayback].
     */
    suspend fun startPlayback(): Result<Unit>

    /** Stop playback and flush jitter buffer. */
    fun stopPlayback()

    /** Mute/unmute local microphone (capture continues but sends silence). */
    fun setMuted(muted: Boolean)

    /** Whether local mic is muted. */
    val isMuted: Boolean

    /** Current estimated round-trip latency in milliseconds (for diagnostics). */
    val estimatedLatencyMs: Flow<Int>
}
