package com.btcall.app.data.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.btcall.app.domain.model.AudioPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures audio from the microphone, applies DSP effects, encodes with Opus,
 * and emits [AudioPacket] frames at 20ms intervals.
 *
 * DSP pipeline:
 * Microphone → [AEC] → [NS] → [AGC] → PCM Buffer → Opus Encoder → AudioPacket
 *
 * AEC (Acoustic Echo Canceler): Removes speaker audio from mic (hardware-accelerated)
 * NS  (Noise Suppressor):       Reduces background noise (hardware-accelerated)
 * AGC (Automatic Gain Control): Normalises microphone level
 *
 * Threading: Capture loop runs on a dedicated coroutine (IO dispatcher).
 * AudioRecord uses VOICE_COMMUNICATION source for best echo cancellation.
 */
@Singleton
class AudioCaptureEngine @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    private val _packets = Channel<AudioPacket>(capacity = 32)
    val capturedPackets: Flow<AudioPacket> = _packets.receiveAsFlow()

    private val isMuted = AtomicBoolean(false)
    private val sequenceCounter = AtomicInteger(0)

    /**
     * Start microphone capture.
     * Initialises AudioRecord with VOICE_COMMUNICATION source and attaches
     * hardware DSP effects if available.
     */
    fun start(): Result<Unit> {
        if (captureJob?.isActive == true) {
            Timber.d("AudioCaptureEngine already running")
            return Result.success(Unit)
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            OpusCodec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            return Result.failure(IllegalStateException("Cannot determine AudioRecord buffer size"))
        }

        // Use 4× min buffer to reduce the chance of overruns
        val bufferSize = maxOf(minBufferSize * 4, OpusCodec.FRAME_SIZE_BYTES * 4)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,  // Best for AEC
                OpusCodec.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException creating AudioRecord — missing RECORD_AUDIO?")
            return Result.failure(e)
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return Result.failure(IllegalStateException("AudioRecord failed to initialise"))
        }

        audioRecord = record
        val sessionId = record.audioSessionId

        // Attach hardware DSP effects if available
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                enabled = true
                Timber.d("AEC attached (hardware echo cancellation active)")
            }
        } else {
            Timber.w("AEC not available on this device — echo may occur")
        }

        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {
                enabled = true
                Timber.d("Noise Suppressor attached")
            }
        }

        if (AutomaticGainControl.isAvailable()) {
            gainControl = AutomaticGainControl.create(sessionId)?.apply {
                enabled = true
                Timber.d("AGC attached")
            }
        }

        record.startRecording()
        Timber.d("AudioCaptureEngine: recording started at ${OpusCodec.SAMPLE_RATE}Hz mono")

        captureJob = scope.launch(Dispatchers.IO) {
            runCaptureLoop(record)
        }

        return Result.success(Unit)
    }

    private suspend fun runCaptureLoop(record: AudioRecord) {
        val pcmBuffer = ByteArray(OpusCodec.FRAME_SIZE_BYTES)

        while (isActive) {
            // Read exactly one 20ms frame
            var bytesRead = 0
            while (bytesRead < OpusCodec.FRAME_SIZE_BYTES && isActive) {
                val n = record.read(
                    pcmBuffer,
                    bytesRead,
                    OpusCodec.FRAME_SIZE_BYTES - bytesRead
                )
                when {
                    n > 0  -> bytesRead += n
                    n == AudioRecord.ERROR_INVALID_OPERATION -> break
                    n == AudioRecord.ERROR_BAD_VALUE -> break
                    else   -> break
                }
            }

            if (bytesRead < OpusCodec.FRAME_SIZE_BYTES) continue

            // Apply mute: replace with silence if muted
            val frameToEncode = if (isMuted.get()) {
                ByteArray(OpusCodec.FRAME_SIZE_BYTES)  // Silence
            } else {
                pcmBuffer.copyOf()
            }

            // Encode with Opus (or PCM fallback)
            val encoded = OpusCodec.encode(frameToEncode)

            val packet = AudioPacket(
                sequenceNumber = sequenceCounter.getAndIncrement(),
                timestampMs = System.currentTimeMillis(),
                encodedPayload = encoded
            )

            _packets.trySend(packet)
        }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null

        echoCanceler?.release()
        noiseSuppressor?.release()
        gainControl?.release()
        echoCanceler = null
        noiseSuppressor = null
        gainControl = null

        audioRecord?.apply {
            try { stop() } catch (_: IllegalStateException) {}
            release()
        }
        audioRecord = null

        Timber.d("AudioCaptureEngine stopped")
    }

    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
        Timber.d("AudioCaptureEngine: muted=$muted")
    }

    fun isMuted() = isMuted.get()

    fun cleanup() {
        stop()
        scope.cancel()
    }
}
