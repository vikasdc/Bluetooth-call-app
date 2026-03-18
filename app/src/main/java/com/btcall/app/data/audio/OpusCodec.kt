package com.btcall.app.data.audio

import timber.log.Timber

/**
 * Opus codec wrapper using Android's built-in native Opus via JNI.
 *
 * Android 10+ includes libopus in the media framework. We access it via
 * the NDK MediaCodec API or directly through JNI bindings in the native layer.
 *
 * Since bundling a full JNI wrapper adds significant complexity, this class
 * implements a PCM passthrough fallback with the same interface, and documents
 * the path to add true Opus encoding.
 *
 * === To enable real Opus encoding ===
 * 1. Add dependency: implementation("io.github.umutsobe:opus4android:1.5.2")
 * 2. Replace PcmFallback with the Opus JNI calls below
 *
 * Audio parameters:
 * - Sample rate: 16000 Hz (SILK mode — optimal for voice, lowest latency)
 * - Channels: 1 (mono)
 * - Frame size: 320 samples = 20ms at 16kHz
 * - Bitrate: 24kbps (voice mode)
 * - Complexity: 5 (balanced CPU/quality for real-time)
 */
object OpusCodec {

    const val SAMPLE_RATE = 16000          // 16kHz SILK band
    const val CHANNELS = 1                 // Mono
    const val FRAME_SIZE_SAMPLES = 320     // 20ms frame @ 16kHz
    const val FRAME_SIZE_BYTES = FRAME_SIZE_SAMPLES * 2   // 16-bit PCM = 2 bytes/sample
    const val TARGET_BITRATE_BPS = 24_000  // 24 kbps

    // Opus application type: VOIP optimises for low latency voice
    private const val OPUS_APPLICATION_VOIP = 2048

    // Whether true Opus JNI is available (set by tryInitOpus())
    private var opusAvailable = false
    private var encoderHandle = 0L
    private var decoderHandle = 0L

    /**
     * Attempt to initialise the Opus native library.
     * Falls back gracefully to PCM if unavailable.
     */
    fun tryInit(): Boolean {
        return try {
            System.loadLibrary("opus")
            // If opus4android or similar is bundled:
            // encoderHandle = opusEncoderCreate(SAMPLE_RATE, CHANNELS, OPUS_APPLICATION_VOIP)
            // decoderHandle = opusDecoderCreate(SAMPLE_RATE, CHANNELS)
            opusAvailable = false  // Set to true when JNI is wired
            Timber.d("Opus codec: using PCM fallback (Opus JNI not linked)")
            false
        } catch (e: UnsatisfiedLinkError) {
            Timber.d("Opus native library not found — using PCM passthrough")
            opusAvailable = false
            false
        }
    }

    /**
     * Encode a 20ms PCM frame (320 samples × 2 bytes = 640 bytes) to Opus.
     *
     * @param pcmFrame  640 bytes of signed 16-bit little-endian PCM samples
     * @return Opus-encoded bytes (typically 40–80 bytes at 24kbps) or PCM if fallback
     */
    fun encode(pcmFrame: ByteArray): ByteArray {
        if (pcmFrame.size != FRAME_SIZE_BYTES) {
            Timber.w("Opus encode: unexpected frame size ${pcmFrame.size}, expected $FRAME_SIZE_BYTES")
        }

        return if (opusAvailable) {
            // JNI call: encodeOpus(encoderHandle, pcmFrame, FRAME_SIZE_SAMPLES, outputBuf)
            // Placeholder — replace when JNI is integrated:
            pcmFrame.copyOf()
        } else {
            // PCM passthrough: return raw PCM bytes
            // This achieves ~256kbps — acceptable for Bluetooth Classic (max ~2Mbps)
            // but higher than Opus's 24kbps target
            pcmFrame.copyOf()
        }
    }

    /**
     * Decode an Opus packet back to PCM samples.
     *
     * @param opusData  Opus-encoded bytes (or PCM in fallback mode)
     * @return 640 bytes of 16-bit signed PCM, or silence on decode error
     */
    fun decode(opusData: ByteArray): ByteArray {
        return if (opusAvailable) {
            // JNI call: decodeOpus(decoderHandle, opusData, outputBuf, FRAME_SIZE_SAMPLES, 0)
            opusData.copyOf()
        } else {
            // PCM passthrough
            if (opusData.size == FRAME_SIZE_BYTES) {
                opusData.copyOf()
            } else {
                // Packet loss concealment: return silence
                ByteArray(FRAME_SIZE_BYTES)
            }
        }
    }

    /**
     * Generate a comfort noise / silence frame for packet loss concealment.
     * Returns 20ms of silence.
     */
    fun generateSilenceFrame(): ByteArray = ByteArray(FRAME_SIZE_BYTES)

    fun release() {
        if (opusAvailable) {
            // opusEncoderDestroy(encoderHandle)
            // opusDecoderDestroy(decoderHandle)
        }
        opusAvailable = false
    }
}
