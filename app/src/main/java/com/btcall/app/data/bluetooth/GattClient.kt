package com.btcall.app.data.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.btcall.app.domain.model.SignalMessage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * GATT Client that connects to a peer's GATT server to write signaling messages.
 *
 * Each send operation:
 * 1. Connect to peer's GATT server
 * 2. Discover services
 * 3. Negotiate MTU (signal messages are ~60-90 bytes, default BLE MTU is only 20 bytes payload)
 * 4. Write to SIGNAL_CHARACTERISTIC
 * 5. Disconnect
 */
class GattClient(private val context: Context) {

    /**
     * Send a [SignalMessage] to a remote device's GATT server.
     * Establishes a temporary GATT connection, writes the message, and closes.
     *
     * @return true if the write was confirmed
     */
    suspend fun sendSignal(
        device: BluetoothDevice,
        message: SignalMessage,
        timeoutMs: Long = 10_000L
    ): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            sendSignalInternal(device, message)
        } ?: run {
            Timber.w("GATT signal send timed out to ${safeAddress(device)}")
            false
        }
    }

    private suspend fun sendSignalInternal(
        device: BluetoothDevice,
        message: SignalMessage
    ): Boolean = suspendCancellableCoroutine { cont ->
        var gatt: BluetoothGatt? = null
        // Guard against double-write: onMtuChanged and the fallback in onServicesDiscovered
        // can both attempt to write. AtomicBoolean ensures exactly one write happens.
        val writeStarted = AtomicBoolean(false)

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Timber.d("GattClient connected to ${safeAddress(device)}, discovering services")
                    try {
                        g.discoverServices()
                    } catch (e: SecurityException) {
                        Timber.e(e, "SecurityException discovering services")
                        g.close()
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (cont.isActive && !cont.isCompleted) {
                        cont.resume(false)
                    }
                    try { g.close() } catch (_: Exception) {}
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Timber.e("Service discovery failed: status=$status")
                    g.close()
                    if (cont.isActive) cont.resume(false)
                    return
                }

                val service = g.getService(BleConstants.SERVICE_UUID)
                if (service == null) {
                    Timber.w("BTCall service not found on ${safeAddress(device)}")
                    g.close()
                    if (cont.isActive) cont.resume(false)
                    return
                }

                val characteristic = service.getCharacteristic(BleConstants.SIGNAL_CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    Timber.w("Signal characteristic not found")
                    g.close()
                    if (cont.isActive) cont.resume(false)
                    return
                }

                // Signal messages are ~60-90 bytes but the default BLE ATT MTU is only
                // 23 bytes (20 payload). We MUST negotiate a larger MTU before writing.
                val mtuRequested = try {
                    g.requestMtu(512)
                } catch (e: SecurityException) {
                    Timber.w(e, "SecurityException requesting MTU")
                    false
                }

                if (!mtuRequested) {
                    // requestMtu failed to initiate — write immediately with whatever MTU
                    // we have. May fail for large messages, but at least we try.
                    Timber.w("requestMtu() returned false, writing with default MTU")
                    if (writeStarted.compareAndSet(false, true)) {
                        writeCharacteristic(g, characteristic, message)
                    }
                }
                // If mtuRequested == true, onMtuChanged will fire and trigger the write.
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                Timber.d("MTU negotiated: $mtu (status=$status)")
                // Write regardless of MTU status — even if negotiation "failed," the
                // Android stack often still increases the MTU from the default.
                if (writeStarted.compareAndSet(false, true)) {
                    val service = g.getService(BleConstants.SERVICE_UUID)
                    val characteristic = service?.getCharacteristic(BleConstants.SIGNAL_CHARACTERISTIC_UUID)
                    if (service == null || characteristic == null) {
                        Timber.e("Lost service/characteristic reference after MTU change")
                        g.close()
                        if (cont.isActive) cont.resume(false)
                        return
                    }
                    writeCharacteristic(g, characteristic, message)
                }
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                val success = status == BluetoothGatt.GATT_SUCCESS
                Timber.d("GATT write ${if (success) "OK" else "FAILED (status=$status)"}")
                try {
                    g.disconnect()
                } catch (_: SecurityException) {}
                if (cont.isActive) cont.resume(success)
            }

            private fun writeCharacteristic(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                msg: SignalMessage
            ) {
                characteristic.value = msg.toBytes()
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                try {
                    val result = g.writeCharacteristic(characteristic)
                    if (!result) {
                        Timber.e("writeCharacteristic returned false")
                        g.close()
                        if (cont.isActive) cont.resume(false)
                    }
                } catch (e: SecurityException) {
                    Timber.e(e, "SecurityException writing characteristic")
                    g.close()
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }

        try {
            gatt = device.connectGatt(
                context,
                false,  // autoConnect=false for faster connection
                callback,
                BluetoothDevice.TRANSPORT_LE
            )
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException in connectGatt")
            if (cont.isActive) cont.resumeWithException(e)
            return@suspendCancellableCoroutine
        }

        cont.invokeOnCancellation {
            try {
                gatt?.disconnect()
                gatt?.close()
            } catch (_: Exception) {}
        }
    }

    private fun safeAddress(device: BluetoothDevice): String {
        return try { device.address } catch (e: SecurityException) { "UNKNOWN" }
    }
}
