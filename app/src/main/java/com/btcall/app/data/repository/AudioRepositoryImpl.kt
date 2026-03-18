package com.btcall.app.data.repository

import com.btcall.app.data.audio.AudioCaptureEngine
import com.btcall.app.data.audio.AudioPlaybackEngine
import com.btcall.app.domain.model.AudioPacket
import com.btcall.app.domain.repository.AudioRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioRepositoryImpl @Inject constructor(
    private val captureEngine: AudioCaptureEngine,
    private val playbackEngine: AudioPlaybackEngine
) : AudioRepository {

    override val capturedAudioPackets: Flow<AudioPacket>
        get() = captureEngine.capturedPackets

    override suspend fun startCapture(): Result<Unit> = captureEngine.start()

    override fun stopCapture() = captureEngine.stop()

    override fun enqueueForPlayback(packet: AudioPacket) = playbackEngine.enqueue(packet)

    override suspend fun startPlayback(): Result<Unit> = playbackEngine.start()

    override fun stopPlayback() = playbackEngine.stop()

    override fun setMuted(muted: Boolean) = captureEngine.setMuted(muted)

    override val isMuted: Boolean get() = captureEngine.isMuted()

    private val _latency = MutableStateFlow(0)
    override val estimatedLatencyMs: Flow<Int> = _latency.asStateFlow()

    fun updateLatency() {
        _latency.value = playbackEngine.getLatencyMs()
    }
}
