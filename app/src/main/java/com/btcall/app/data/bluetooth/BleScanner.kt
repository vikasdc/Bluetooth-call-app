package com.btcall.app.data.bluetooth

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.btcall.app.domain.model.PeerDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE scanner that discovers nearby devices advertising BTCall's SERVICE_UUID.
 *
 * Uses SCAN_MODE_LOW_LATENCY for real-time peer discovery.
 * Extracts device ID from manufacturer data in the advertisement packet.
 *
 * Returns a Flow<ScanResult> — the caller manages the device list.
 */
@Singleton
class BleScanner @Inject constructor(
    private val context: Context,
    private val deviceIdProvider: DeviceIdProvider
) {
    private var isScanning = false

    /**
     * Starts BLE scan and returns a cold Flow of [PeerDevice] objects.
     * The flow terminates when the scanner is stopped or an error occurs.
     */
    fun scanFlow(): Flow<PeerDevice> = callbackFlow {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter

        if (!adapter.isEnabled) {
            close(IllegalStateException("Bluetooth is disabled"))
            return@callbackFlow
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("BLE scanner not available"))
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val peer = extractPeerDevice(result) ?: return
                // Don't include ourselves
                if (peer.deviceId == deviceIdProvider.getDeviceId()) return
                trySend(peer)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result ->
                    val peer = extractPeerDevice(result) ?: return@forEach
                    if (peer.deviceId == deviceIdProvider.getDeviceId()) return@forEach
                    trySend(peer)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Timber.e("BLE scan failed: errorCode=$errorCode")
                close(IllegalStateException("BLE scan failed: errorCode=$errorCode"))
            }
        }

        // Only scan for devices advertising our service UUID
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
                .build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)  // Report immediately (no batching)
            .build()

        try {
            scanner.startScan(filters, settings, callback)
            isScanning = true
            Timber.d("BLE scan started")
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException starting BLE scan")
            close(e)
            return@callbackFlow
        }

        awaitClose {
            try {
                scanner.stopScan(callback)
                isScanning = false
                Timber.d("BLE scan stopped")
            } catch (e: SecurityException) {
                Timber.e(e, "SecurityException stopping BLE scan")
            }
        }
    }

    /**
     * Extracts a [PeerDevice] from a BLE [ScanResult].
     *
     * Device ID is read from manufacturer data (first 16 bytes → UUID string).
     * Falls back to using the device address as ID if no manufacturer data.
     */
    private fun extractPeerDevice(result: ScanResult): PeerDevice? {
        return try {
            val device = result.device
            val record = result.scanRecord

            // Try to get stable device ID from manufacturer data
            val manufacturerData = record?.getManufacturerSpecificData(BleConstants.MANUFACTURER_ID)
            val deviceId = if (manufacturerData != null && manufacturerData.size >= 16) {
                // Reconstruct UUID string from 16 bytes
                val buf = ByteBuffer.wrap(manufacturerData, 0, 16)
                val high = buf.long
                val low = buf.long
                java.util.UUID(high, low).toString()
            } else {
                // Fallback: use MAC address as stable ID (less ideal)
                device.address.replace(":", "")
            }

            val name = record?.deviceName
                ?: try { device.name } catch (e: SecurityException) { null }
                ?: "Unknown (${device.address.takeLast(5)})"

            PeerDevice(
                deviceId = deviceId,
                displayName = name,
                macAddress = device.address,
                rssi = result.rssi,
                isAvailable = true
            )
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException reading scan result")
            null
        } catch (e: Exception) {
            Timber.e(e, "Error parsing scan result")
            null
        }
    }

    fun isScanning() = isScanning
}
