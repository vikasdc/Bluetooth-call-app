package com.btcall.app.data.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.btcall.app.domain.model.SignalMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GATT Server hosted on this device.
 *
 * Other peers connect to this server to write signaling messages.
 * We also write back to connected peers via notifications or direct writes.
 *
 * Services:
 * ┌─────────────────────────────────────────────────────────────┐
 * │ Service: SERVICE_UUID                                        │
 * │  ├─ Characteristic: SIGNAL_CHARACTERISTIC_UUID              │
 * │  │    Properties: WRITE_NO_RESPONSE | WRITE                 │
 * │  │    Permissions: PERMISSION_WRITE                         │
 * │  │    → Peers write SIGNAL messages here                    │
 * │  │                                                          │
 * │  └─ Characteristic: PRESENCE_CHARACTERISTIC_UUID            │
 * │       Properties: READ | NOTIFY                             │
 * │       Permissions: PERMISSION_READ                          │
 * │       → Peers read our availability status                  │
 * └─────────────────────────────────────────────────────────────┘
 */
@Singleton
class GattServer @Inject constructor(
    private val context: Context,
    private val deviceIdProvider: DeviceIdProvider
) {
    private var gattServer: BluetoothGattServer? = null
    private val connectedDevices = mutableMapOf<String, BluetoothDevice>()

    // Channel for incoming signal messages
    private val _incomingSignals = Channel<SignalMessage>(Channel.BUFFERED)
    val incomingSignals: Flow<SignalMessage> = _incomingSignals.receiveAsFlow()

    private lateinit var signalCharacteristic: BluetoothGattCharacteristic
    private lateinit var presenceCharacteristic: BluetoothGattCharacteristic

    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val addr = try { device.address } catch (e: SecurityException) { "?" }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevices[addr] = device
                    Timber.d("GATT client connected: $addr")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevices.remove(addr)
                    Timber.d("GATT client disconnected: $addr")
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == BleConstants.SIGNAL_CHARACTERISTIC_UUID) {
                val msg = SignalMessage.fromBytes(value)
                if (msg != null) {
                    Timber.d("GATT received signal: ${msg.type} from ${msg.senderId.take(8)}")
                    _incomingSignals.trySend(msg)
                } else {
                    Timber.w("GATT received unrecognised signal payload")
                }
            }

            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                } catch (e: SecurityException) {
                    Timber.e(e, "SecurityException sending GATT response")
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                } catch (e: SecurityException) {
                    Timber.e(e, "SecurityException sending descriptor response")
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Timber.d("GATT MTU changed to $mtu for ${try { device.address } catch (e: SecurityException) { "?" }}")
        }
    }

    /**
     * Initialise and open the GATT server.
     * Must be called once on app start (from BluetoothCallService).
     */
    fun start(): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        try {
            gattServer = bluetoothManager.openGattServer(context, serverCallback)
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException opening GATT server")
            return false
        }

        if (gattServer == null) {
            Timber.e("Failed to open GATT server")
            return false
        }

        // Create signal characteristic (write-only)
        signalCharacteristic = BluetoothGattCharacteristic(
            BleConstants.SIGNAL_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // Create presence characteristic (read + notify)
        presenceCharacteristic = BluetoothGattCharacteristic(
            BleConstants.PRESENCE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).also { char ->
            // Add CCCD descriptor required for notifications
            val cccd = BluetoothGattDescriptor(
                BleConstants.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            char.addDescriptor(cccd)
        }

        // Build service and add to server
        val service = BluetoothGattService(
            BleConstants.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        ).apply {
            addCharacteristic(signalCharacteristic)
            addCharacteristic(presenceCharacteristic)
        }

        try {
            gattServer?.addService(service)
            Timber.d("GATT server started with BTCall service")
            return true
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException adding GATT service")
            return false
        }
    }

    /**
     * Write a signaling message to a connected peer's signal characteristic.
     * This is used when WE initiate a connection back to a peer's GATT server.
     * The actual outbound signaling is handled by [GattClient].
     */
    fun updatePresence(available: Boolean) {
        val value = byteArrayOf(if (available) 0x01 else 0x00)
        presenceCharacteristic.value = value
        try {
            connectedDevices.values.forEach { device ->
                gattServer?.notifyCharacteristicChanged(device, presenceCharacteristic, false)
            }
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException notifying presence change")
        }
    }

    fun stop() {
        try {
            gattServer?.close()
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException closing GATT server")
        } finally {
            gattServer = null
            connectedDevices.clear()
            Timber.d("GATT server stopped")
        }
    }
}
