package com.btcall.app.data.repository

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import java.io.IOException
import com.btcall.app.data.bluetooth.BleAdvertiser
import com.btcall.app.data.bluetooth.BleScanner
import com.btcall.app.data.bluetooth.DeviceIdProvider
import com.btcall.app.data.bluetooth.GattClient
import com.btcall.app.data.bluetooth.GattServer
import com.btcall.app.data.bluetooth.RfcommManager
import com.btcall.app.domain.model.PeerDevice
import com.btcall.app.domain.model.SignalMessage
import com.btcall.app.domain.repository.BluetoothRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete implementation of [BluetoothRepository].
 *
 * Manages the peer device list with automatic expiry:
 * - A peer is added/refreshed when a BLE scan result arrives
 * - A peer is removed if no scan result received within [PEER_EXPIRY_MS]
 *
 * Sends signaling messages via [GattClient] (connects to remote GATT server).
 * Receives signaling messages via [GattServer] (local GATT server callback).
 */
@Singleton
class BluetoothRepositoryImpl @Inject constructor(
    private val context: Context,
    private val bleAdvertiser: BleAdvertiser,
    private val bleScanner: BleScanner,
    private val gattServer: GattServer,
    private val rfcommManager: RfcommManager,
    private val deviceIdProvider: DeviceIdProvider
) : BluetoothRepository {

    companion object {
        private const val PEER_EXPIRY_MS = 15_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Map of deviceId → (PeerDevice, lastSeenMs)
    private val peerMap = mutableMapOf<String, Pair<PeerDevice, Long>>()
    private val _nearbyDevices = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val nearbyDevices: Flow<List<PeerDevice>> = _nearbyDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: Flow<Boolean> = _isScanning.asStateFlow()

    override val incomingSignals: Flow<SignalMessage> = gattServer.incomingSignals
    override val incomingAudioData: Flow<ByteArray> = rfcommManager.incomingData

    private val gattClient = GattClient(context)

    // Periodically evict stale peers
    init {
        scope.launch {
            while (true) {
                delay(3_000L)
                evictStalePeers()
            }
        }
        // Start GATT server immediately
        gattServer.start()
    }

    // ── Discovery ──────────────────────────────────────────────────────────

    override suspend fun startAdvertising(): Result<Unit> {
        return if (bleAdvertiser.startAdvertising(lowPower = true)) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Failed to start BLE advertising"))
        }
    }

    override fun stopAdvertising() = bleAdvertiser.stopAdvertising()

    override suspend fun startDiscovery(): Result<Unit> {
        _isScanning.value = true
        bleScanner.scanFlow()
            .onEach { peer -> onPeerDiscovered(peer) }
            .launchIn(scope)
        return Result.success(Unit)
    }

    override fun stopDiscovery() {
        _isScanning.value = false
        // The scan flow auto-cancels when its coroutine is cancelled
    }

    private fun onPeerDiscovered(peer: PeerDevice) {
        val now = System.currentTimeMillis()
        peerMap[peer.deviceId] = Pair(peer, now)
        publishPeerList()
    }

    private fun evictStalePeers() {
        val now = System.currentTimeMillis()
        val staleKeys = peerMap.entries
            .filter { (_, v) -> now - v.second > PEER_EXPIRY_MS }
            .map { it.key }

        if (staleKeys.isNotEmpty()) {
            staleKeys.forEach { peerMap.remove(it) }
            publishPeerList()
        }
    }

    private fun publishPeerList() {
        _nearbyDevices.update {
            peerMap.values
                .map { it.first }
                .sortedByDescending { it.rssi }  // Closest first
        }
    }

    fun markPeerBusy(deviceId: String) {
        peerMap[deviceId]?.let { (peer, ts) ->
            peerMap[deviceId] = Pair(peer.copy(isAvailable = false), ts)
            publishPeerList()
        }
    }

    // ── Signaling ──────────────────────────────────────────────────────────

    override suspend fun sendSignal(target: PeerDevice, message: SignalMessage): Result<Unit> {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter

        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(target.macAddress)
        } catch (e: Exception) {
            Timber.e(e, "Cannot get remote device: ${target.macAddress}")
            return Result.failure(e)
        }

        val success = gattClient.sendSignal(device, message)
        return if (success) {
            Result.success(Unit)
        } else {
            Result.failure(IOException("GATT signal write failed"))
        }
    }

    // ── RFCOMM ────────────────────────────────────────────────────────────

    override suspend fun connectRfcomm(target: PeerDevice): Result<Unit> {
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val device = bluetoothManager.adapter.getRemoteDevice(target.macAddress)
            rfcommManager.connectToDevice(device)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "RFCOMM connect failed to ${target.macAddress}")
            Result.failure(e)
        }
    }

    override suspend fun acceptRfcomm(): Result<Unit> {
        return try {
            rfcommManager.acceptConnection()
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "RFCOMM accept failed")
            Result.failure(e)
        }
    }

    override suspend fun sendAudioData(data: ByteArray): Result<Unit> {
        return try {
            rfcommManager.sendData(data)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun disconnectRfcomm() = rfcommManager.disconnect()

    // ── Device Info ───────────────────────────────────────────────────────

    override fun isBluetoothEnabled(): Boolean {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return mgr.adapter?.isEnabled == true
    }

    override fun getLocalDeviceId(): String = deviceIdProvider.getDeviceId()

    override fun getLocalDeviceName(): String {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return try {
            mgr.adapter?.name ?: android.os.Build.MODEL
        } catch (e: SecurityException) {
            android.os.Build.MODEL
        }
    }

    override fun getLocalMacAddress(): String {
        // On Android 6+, WifiInfo/BT MAC is randomised. We return an empty string here
        // because the caller's GATT server address is known from the BLE connection.
        return ""
    }
}

