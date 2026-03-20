package com.btcall.app.domain.model

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Signaling messages exchanged over BLE GATT during call setup.
 *
 * CALL_ACCEPT now carries WiFi Direct group credentials (SSID + passphrase) so
 * the caller can join the callee's WiFi Direct group and open the audio TCP socket.
 * All other message types leave wifiSsid/wifiPassphrase empty.
 *
 * Wire format (fits within 512-byte BLE MTU):
 * [type:1]
 * [senderIdLen:1][senderId:N]
 * [senderNameLen:1][senderName:M]
 * [macLen:1][mac:P]
 * [ssidLen:1][ssid:Q]        ← WiFi Direct SSID (CALL_ACCEPT only)
 * [passLen:1][passphrase:R]  ← WiFi Direct passphrase (CALL_ACCEPT only)
 */
data class SignalMessage(
    val type: MessageType,
    val senderId: String,
    val senderName: String = "",
    val senderMac: String = "",
    val wifiSsid: String = "",        // WiFi Direct group SSID (CALL_ACCEPT only)
    val wifiPassphrase: String = ""   // WiFi Direct group passphrase (CALL_ACCEPT only)
) {

    enum class MessageType(val code: Byte) {
        CALL_REQUEST(0x01),
        CALL_ACCEPT(0x02),
        CALL_REJECT(0x03),
        CALL_BUSY(0x04),
        CALL_END(0x05),
        HEARTBEAT(0x06);

        companion object {
            fun fromCode(code: Byte) = values().firstOrNull { it.code == code }
        }
    }

    fun toBytes(): ByteArray {
        val senderIdBytes   = senderId.toByteArray(StandardCharsets.UTF_8)
        val senderNameBytes = senderName.toByteArray(StandardCharsets.UTF_8)
        val macBytes        = senderMac.toByteArray(StandardCharsets.UTF_8)
        val ssidBytes       = wifiSsid.toByteArray(StandardCharsets.UTF_8)
        val passBytes       = wifiPassphrase.toByteArray(StandardCharsets.UTF_8)

        val buf = ByteBuffer.allocate(
            1 +
            1 + senderIdBytes.size +
            1 + senderNameBytes.size +
            1 + macBytes.size +
            1 + ssidBytes.size +
            1 + passBytes.size
        )
        buf.put(type.code)
        buf.put(senderIdBytes.size.toByte());   buf.put(senderIdBytes)
        buf.put(senderNameBytes.size.toByte()); buf.put(senderNameBytes)
        buf.put(macBytes.size.toByte());        buf.put(macBytes)
        buf.put(ssidBytes.size.toByte());       buf.put(ssidBytes)
        buf.put(passBytes.size.toByte());       buf.put(passBytes)
        return buf.array()
    }

    companion object {
        fun fromBytes(bytes: ByteArray): SignalMessage? {
            return try {
                val buf = ByteBuffer.wrap(bytes)
                val type = MessageType.fromCode(buf.get()) ?: return null

                fun readField(): String {
                    val len = buf.get().toInt() and 0xFF
                    val b = ByteArray(len); buf.get(b)
                    return String(b, StandardCharsets.UTF_8)
                }

                val senderId       = readField()
                val senderName     = readField()
                val senderMac      = readField()
                // wifiSsid/wifiPassphrase are optional — absent in older message formats
                val wifiSsid       = if (buf.hasRemaining()) readField() else ""
                val wifiPassphrase = if (buf.hasRemaining()) readField() else ""

                SignalMessage(type, senderId, senderName, senderMac, wifiSsid, wifiPassphrase)
            } catch (e: Exception) {
                null
            }
        }
    }
}
