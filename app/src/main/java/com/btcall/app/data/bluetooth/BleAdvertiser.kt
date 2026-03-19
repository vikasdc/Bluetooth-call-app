package com.btcall.app.data.bluetooth

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages BLE advertising so other devices running BTCall can discover us.
 *
 * Advertisement packet contains:
 * - Service UUID (SERVICE_UUID) — primary filter for scanners
 * - Manufacturer data: [deviceId bytes] so peers can extract our stable ID
 *   without needing to connect to GATT
 *
 * Uses LOW_LATENCY mode during active calls / LOW_POWER otherwise.
 */
@Singleton
class BleAdvertiser @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceIdProvider: DeviceIdProvider
) {
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Timber.d("BLE advertising started")
            isAdvertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            Timber.e("BLE advertising failed: errorCode=$errorCode")
            isAdvertising = false
        }
    }

    /**
     * Start BLE advertising with our service UUID and device metadata.
     *
     * @param lowPower Use LOW_POWER mode (idle/background) vs LOW_LATENCY (active)
     */
    fun startAdvertising(lowPower: Boolean = true): Boolean {
        if (isAdvertising) return true

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter

        if (!adapter.isEnabled) {
            Timber.w("Cannot advertise: Bluetooth is off")
            return false
        }

        if (!adapter.isMultipleAdvertisementSupported) {
            Timber.w("Device does not support multiple BLE advertisement")
            // Some devices still advertise via single advertisement slot
        }

        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Timber.e("BluetoothLeAdvertiser is null — BLE advertising not supported")
            return false
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)          // Must be connectable for GATT server
            .setTimeout(0)                 // Advertise indefinitely
            .build()

        // Build manufacturer data: full 16-byte device UUID so scanners can
        // deduplicate peers even when BLE MAC address is randomized (Android 6+).
        // UUID string is 32 hex chars (after stripping hyphens) → 16 bytes.
        val deviceIdBytes = deviceIdProvider.getDeviceId()
            .replace("-", "")
            .take(32)                       // All 32 hex chars = 16 bytes
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

        // Keep advertise data small (≤31 bytes): flags(3)+UUID(4)+mfr(20)=27 bytes.
        // Device name goes in scan response (separate 31-byte budget).
        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .addManufacturerData(BleConstants.MANUFACTURER_ID, deviceIdBytes)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        val scanResponseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)    // Broadcast Bluetooth device name
            .build()

        try {
            advertiser?.startAdvertising(settings, advertiseData, scanResponseData, advertiseCallback)
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException starting BLE advertisement — missing BLUETOOTH_ADVERTISE?")
            return false
        }

        return true
    }

    fun stopAdvertising() {
        if (!isAdvertising) return
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException stopping BLE advertisement")
        } finally {
            isAdvertising = false
            advertiser = null
            Timber.d("BLE advertising stopped")
        }
    }

    fun isAdvertising() = isAdvertising
}
