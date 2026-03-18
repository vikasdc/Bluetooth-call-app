package com.btcall.app.data.bluetooth

import java.util.UUID

/**
 * All BLE UUIDs and constants for the BTCall service.
 *
 * Architecture:
 * - We run a GATT server on each device
 * - One primary service with two characteristics:
 *   1. SIGNAL_CHARACTERISTIC  (write-without-response) — for signaling messages
 *   2. PRESENCE_CHARACTERISTIC (read + notify)          — broadcasts device info
 *
 * BLE Advertisement:
 * - Advertises SERVICE_UUID so peers can filter scans
 * - Includes device name and UUID in manufacturer data
 */
object BleConstants {

    /** Primary service UUID — identifies this app's BLE service */
    val SERVICE_UUID: UUID = UUID.fromString("0000A2B3-0000-1000-8000-00805F9B34FB")

    /** Write-without-response characteristic for signaling messages */
    val SIGNAL_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000A2B4-0000-1000-8000-00805F9B34FB")

    /** Readable characteristic broadcasting presence/availability */
    val PRESENCE_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000A2B5-0000-1000-8000-00805F9B34FB")

    /** Standard GATT Client Characteristic Configuration Descriptor */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    /** Manufacturer ID in advertisement packet (arbitrary, identifies our app) */
    const val MANUFACTURER_ID = 0xBCA1

    /** BLE advertisement TX power */
    const val TX_POWER_LEVEL = 0  // Medium power

    /** Scan window / interval for balanced discovery */
    const val SCAN_INTERVAL_MS = 500L

    /** How long a device stays in the peer list without a fresh scan result */
    const val PEER_EXPIRY_MS = 15_000L

    /** RFCOMM service UUID — must match on both sides */
    val RFCOMM_UUID: UUID = UUID.fromString("0000A2B6-0000-1000-8000-00805F9B34FB")

    /** RFCOMM channel timeout */
    const val RFCOMM_CONNECT_TIMEOUT_MS = 10_000L

    /** Heartbeat interval to detect dropped connections */
    const val HEARTBEAT_INTERVAL_MS = 5_000L

    /** How many missed heartbeats before declaring connection lost */
    const val HEARTBEAT_MISS_THRESHOLD = 3
}
