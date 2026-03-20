package com.btcall.app.data.repository

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import com.btcall.app.data.bluetooth.BleAdvertiser
import com.btcall.app.data.bluetooth.BleScanner
import com.btcall.app.data.bluetooth.DeviceIdProvider
import com.btcall.app.data.bluetooth.GattClient
import com.btcall.app.data.bluetooth.GattServer
import com.btcall.app.data.wifi.WifiDirectManager
import com.btcall.app.domain.model.PeerDevice
import com.btcall.app.domain.model.SignalMessage
import com.btcall.app.domain.repository.BluetoothRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * Concrete [BluetoothRepository].
 *
 * BLE layer  → peer discovery (BleScanner / BleAdvertiser) + signaling (GattServer / GattClient)
 * WiFi layer → audio transport (WifiDirectManager — TCP over WiFi Direct)
 */
@Singleton
class BluetoothRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleAdvertiser: BleAdvertiser,
    private val bleScanner: BleScanner,
    private val gattServer: GattServer,
    private val wifiDirectManager: WifiDirectManager,
    private val deviceIdProvider: DeviceIdProvider
) : BluetoothRepository {

    companion object {
        private const val PEER_EXPIRY_MS = 15_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null

    private val peerMap = java.util.concurrent.ConcurrentHashMap<String, Pair<PeerDevice, Long>>()
    private val _nearbyDevices = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val nearbyDevices: Flow<List<PeerDevice>> = _nearbyDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: Flow<Boolean> = _isScanning.asStateFlow()

    override val incomingSignals: Flow<SignalMessage> = gattServer.incomingSignals
    override val incomingAudioData: Flow<ByteArray>   = wifiDirectManager.incomingData
    override val audioConnected: Flow<Boolean>        = wifiDirectManager.isConnected

    private val gattClient = GattClient(context)

    init {
        // Periodically evict peers that have stopped advertising
        scope.launch {
            while (true) {
                delay(3_000L)
                evictStalePeers()
            }
        }
        // Rotate BLE scan every 25 s to avoid Android throttling
        scope.launch {
            while (true) {
                delay(25_000L)
                if (scanJob?.isActive == true) {
                    Timber.d("Rotating BLE scan")
                    scanJob?.cancel()
                    scanJob = bleScanner.scanFlow()
                        .onEach { peer -> onPeerDiscovered(peer) }
                        .launchIn(scope)
                }
            }
        }
        gattServer.start()
    }

    // ── BLE Discovery ─────────────────────────────────────────────────────

    override suspend fun startAdvertising(): Result<Unit> =
        if (bleAdvertiser.startAdvertising(lowPower = true)) Result.success(Unit)
        else Result.failure(IllegalStateException("Failed to start BLE advertising"))

    override fun stopAdvertising() = bleAdvertiser.stopAdvertising()

    override suspend fun startDiscovery(): Result<Unit> {
        scanJob?.cancel()
        _isScanning.value = true
        scanJob = bleScanner.scanFlow()
            .onEach { peer -> onPeerDiscovered(peer) }
            .launchIn(scope)
        return Result.success(Unit)
    }

    override fun stopDiscovery() {
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
    }

    private fun onPeerDiscovered(peer: PeerDevice) {
        peerMap[peer.deviceId] = Pair(peer, System.currentTimeMillis())
        publishPeerList()
    }

    private fun evictStalePeers() {
        val now = System.currentTimeMillis()
        val stale = peerMap.entries.filter { (_, v) -> now - v.second > PEER_EXPIRY_MS }.map { it.key }
        if (stale.isNotEmpty()) {
            stale.forEach { peerMap.remove(it) }
            publishPeerList()
        }
    }

    private fun publishPeerList() {
        _nearbyDevices.update { peerMap.values.map { it.first }.sortedByDescending { it.rssi } }
    }

    // ── BLE Signaling ─────────────────────────────────────────────────────

    override suspend fun sendSignal(target: PeerDevice, message: SignalMessage): Result<Unit> {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(target.macAddress)
        } catch (e: Exception) {
            Timber.e(e, "Cannot get remote device: ${target.macAddress}")
            return Result.failure(e)
        }
        return if (gattClient.sendSignal(device, message)) Result.success(Unit)
        else Result.failure(IOException("GATT signal write failed"))
    }

    // ── WiFi Direct Audio Transport ───────────────────────────────────────

    override suspend fun createAudioGroup(): Result<Pair<String, String>> =
        wifiDirectManager.createGroupAndGetCredentials()

    override suspend fun acceptAudioConnection(): Result<Unit> =
        wifiDirectManager.acceptAudioConnection()

    override suspend fun connectToAudioGroup(ssid: String, passphrase: String): Result<Unit> =
        wifiDirectManager.connectToGroup(ssid, passphrase)

    override suspend fun connectAudioSocket(): Result<Unit> =
        wifiDirectManager.connectAudioSocket()

    override suspend fun sendAudioData(data: ByteArray): Result<Unit> =
        try {
            wifiDirectManager.sendData(data)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }

    override fun disconnectAudio() = wifiDirectManager.disconnect()

    // ── Device Info ───────────────────────────────────────────────────────

    override fun isBluetoothEnabled(): Boolean {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return mgr.adapter?.isEnabled == true
    }

    override fun getLocalDeviceId(): String = deviceIdProvider.getDeviceId()

    override fun getLocalDeviceName(): String {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return try { mgr.adapter?.name ?: android.os.Build.MODEL } catch (_: SecurityException) { android.os.Build.MODEL }
    }

    override fun getLocalMacAddress(): String = ""
}
