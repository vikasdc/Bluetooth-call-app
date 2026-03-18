package com.btcall.app.domain.model

/**
 * Represents a nearby peer device discovered via BLE advertising.
 *
 * @param deviceId     Unique stable ID derived from Bluetooth MAC (or random UUID advertised)
 * @param displayName  Human-readable name shown in UI
 * @param macAddress   Bluetooth MAC address used for RFCOMM connection
 * @param rssi         Signal strength in dBm; used for proximity ranking
 * @param isAvailable  False when the peer is already in a call (CALL_BUSY)
 */
data class PeerDevice(
    val deviceId: String,
    val displayName: String,
    val macAddress: String,
    val rssi: Int,
    val isAvailable: Boolean = true
)
