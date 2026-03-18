package com.btcall.app.domain.model

import java.nio.ByteBuffer

/**
 * Audio packet transmitted over RFCOMM socket.
 *
 * Wire format:
 * ┌────────────┬──────────────┬───────────────┬──────────────┐
 * │ Magic [2]  │ Sequence [4] │ Timestamp [8] │ Payload [N]  │
 * └────────────┴──────────────┴───────────────┴──────────────┘
 *
 * Magic bytes: 0xBCAA (Bluetooth Call Audio Artifact)
 * Total header: 14 bytes
 * Max payload: ~320 bytes (20ms @ 16kHz Opus)
 *
 * @param sequenceNumber Monotonically increasing; used for jitter buffer ordering
 * @param timestampMs    Sender's System.currentTimeMillis() at capture time
 * @param encodedPayload Opus-encoded (or raw PCM) audio bytes
 */
data class AudioPacket(
    val sequenceNumber: Int,
    val timestampMs: Long,
    val encodedPayload: ByteArray
) {
    companion object {
        const val MAGIC_1: Byte = 0xBC.toByte()
        const val MAGIC_2: Byte = 0xAA.toByte()
        const val HEADER_SIZE = 14  // 2 magic + 4 seq + 8 ts

        fun fromBytes(data: ByteArray): AudioPacket? {
            if (data.size < HEADER_SIZE) return null
            val buf = ByteBuffer.wrap(data)

            val m1 = buf.get()
            val m2 = buf.get()
            if (m1 != MAGIC_1 || m2 != MAGIC_2) return null

            val seq = buf.int
            val ts = buf.long
            val payload = ByteArray(data.size - HEADER_SIZE)
            buf.get(payload)
            return AudioPacket(seq, ts, payload)
        }
    }

    fun toBytes(): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + encodedPayload.size)
        buf.put(MAGIC_1)
        buf.put(MAGIC_2)
        buf.putInt(sequenceNumber)
        buf.putLong(timestampMs)
        buf.put(encodedPayload)
        return buf.array()
    }

    // ByteArray fields require manual equals/hashCode
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioPacket) return false
        return sequenceNumber == other.sequenceNumber &&
                timestampMs == other.timestampMs &&
                encodedPayload.contentEquals(other.encodedPayload)
    }

    override fun hashCode(): Int {
        var result = sequenceNumber
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + encodedPayload.contentHashCode()
        return result
    }
}
