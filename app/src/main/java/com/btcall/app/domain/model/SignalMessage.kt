package com.btcall.app.domain.model

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Lightweight signaling protocol messages exchanged over BLE GATT
 * (or piggybacked on the RFCOMM control channel pre-audio).
 *
 * Wire format (binary, max 512 bytes to fit BLE MTU):
 * ┌──────────┬────────────────────┬──────────────────────────────────────┐
 * │ 1 byte   │ 36 bytes           │ up to 475 bytes                      │
 * │ Type     │ Sender UUID        │ JSON payload (optional)              │
 * └──────────┴────────────────────┴──────────────────────────────────────┘
 */
data class SignalMessage(
    val type: MessageType,
    val senderId: String,          // Stable UUID of the sender device
    val senderName: String = "",   // Display name (in CALL_REQUEST)
    val senderMac: String = ""     // MAC address for RFCOMM connection setup
) {

    enum class MessageType(val code: Byte) {
        CALL_REQUEST(0x01),
        CALL_ACCEPT(0x02),
        CALL_REJECT(0x03),
        CALL_BUSY(0x04),
        CALL_END(0x05),
        HEARTBEAT(0x06);   // Sent every 5s to detect connection loss

        companion object {
            fun fromCode(code: Byte) = values().firstOrNull { it.code == code }
        }
    }

    /**
     * Serialise to bytes for BLE characteristic write.
     * Layout: [type:1][senderIdLen:1][senderId:N][senderNameLen:1][senderName:M][mac:17]
     */
    fun toBytes(): ByteArray {
        val senderIdBytes = senderId.toByteArray(StandardCharsets.UTF_8)
        val senderNameBytes = senderName.toByteArray(StandardCharsets.UTF_8)
        val macBytes = senderMac.toByteArray(StandardCharsets.UTF_8)

        val buf = ByteBuffer.allocate(
            1 + 1 + senderIdBytes.size + 1 + senderNameBytes.size + 1 + macBytes.size
        )
        buf.put(type.code)
        buf.put(senderIdBytes.size.toByte())
        buf.put(senderIdBytes)
        buf.put(senderNameBytes.size.toByte())
        buf.put(senderNameBytes)
        buf.put(macBytes.size.toByte())
        buf.put(macBytes)
        return buf.array()
    }

    companion object {
        fun fromBytes(bytes: ByteArray): SignalMessage? {
            return try {
                val buf = ByteBuffer.wrap(bytes)
                val typeCode = buf.get()
                val type = MessageType.fromCode(typeCode) ?: return null

                val idLen = buf.get().toInt() and 0xFF
                val idBytes = ByteArray(idLen)
                buf.get(idBytes)
                val senderId = String(idBytes, StandardCharsets.UTF_8)

                val nameLen = buf.get().toInt() and 0xFF
                val nameBytes = ByteArray(nameLen)
                buf.get(nameBytes)
                val senderName = String(nameBytes, StandardCharsets.UTF_8)

                val macLen = buf.get().toInt() and 0xFF
                val macBytes = ByteArray(macLen)
                buf.get(macBytes)
                val mac = String(macBytes, StandardCharsets.UTF_8)

                SignalMessage(type, senderId, senderName, mac)
            } catch (e: Exception) {
                null
            }
        }
    }
}
