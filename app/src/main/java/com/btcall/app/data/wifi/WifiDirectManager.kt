package com.btcall.app.data.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Manages WiFi Direct audio transport for P2P voice calls.
 *
 * Why WiFi Direct instead of Bluetooth Classic RFCOMM:
 * - Uses a separate radio (WiFi chip) from BLE (Bluetooth chip) — zero radio contention
 * - Much higher bandwidth (~250 Mbps vs ~1 Mbps RFCOMM) — no packet drops under load
 * - No Bluetooth Classic ACL timing issues that caused audio to cut out after ~3 seconds
 *
 * Architecture:
 * - Callee:  createGroup() → gets SSID + passphrase → shares via BLE CALL_ACCEPT
 *            → opens TCP ServerSocket → waits for caller to connect
 * - Caller:  connects to callee's WiFi Direct group via WifiNetworkSpecifier (API 29+)
 *            → TCP-connects to Group Owner IP (always 192.168.49.1)
 * - Both:    stream audio packets as length-prefixed frames over the TCP socket
 *
 * Frame format: [len_high:1][len_low:1][payload:N]  (same as old RFCOMM protocol)
 */
@Singleton
class WifiDirectManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        const val AUDIO_PORT = 50007
        const val GROUP_OWNER_IP = "192.168.49.1"  // Android Group Owner always gets this IP
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val wifiP2pManager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val p2pChannel =
        wifiP2pManager.initialize(context, context.mainLooper, null)
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var serverSocket: ServerSocket? = null
    private var audioSocket: Socket? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var readJob: Job? = null

    // Raw audio bytes received from the remote peer
    private val _incomingData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incomingData: Flow<ByteArray> = _incomingData.asSharedFlow()

    // True while the TCP audio socket is connected
    private val _isConnected = MutableStateFlow(false)
    val isConnected: Flow<Boolean> = _isConnected

    // ── Callee side ───────────────────────────────────────────────────────

    /**
     * Creates a persistent WiFi Direct group on this device (callee becomes Group Owner).
     * Returns (SSID, passphrase) to be shared with the caller via BLE CALL_ACCEPT.
     */
    suspend fun createGroupAndGetCredentials(): Result<Pair<String, String>> =
        suspendCancellableCoroutine { cont ->
            // Remove any existing group first so createGroup doesn't fail
            wifiP2pManager.removeGroup(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = doCreateGroup(cont)
                override fun onFailure(reason: Int) = doCreateGroup(cont) // proceed anyway
            })
        }

    private fun doCreateGroup(cont: kotlin.coroutines.Continuation<Result<Pair<String, String>>>) {
        wifiP2pManager.createGroup(p2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                wifiP2pManager.requestGroupInfo(p2pChannel) { group ->
                    if (group != null) {
                        Timber.d("WiFiDirect: group created SSID=${group.networkName}")
                        if (!cont.isCompleted)
                            cont.resume(Result.success(Pair(group.networkName, group.passphrase)))
                    } else {
                        if (!cont.isCompleted)
                            cont.resume(Result.failure(IOException("requestGroupInfo returned null")))
                    }
                }
            }

            override fun onFailure(reason: Int) {
                if (!cont.isCompleted)
                    cont.resume(Result.failure(IOException("createGroup failed: reason=$reason")))
            }
        })
    }

    /**
     * Callee opens a TCP server socket and blocks until the caller connects.
     * Must be called after [createGroupAndGetCredentials] succeeds.
     */
    suspend fun acceptAudioConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val srv = ServerSocket(AUDIO_PORT)
            serverSocket = srv
            Timber.d("WiFiDirect: TCP server listening on port $AUDIO_PORT")
            val client = srv.accept()
            audioSocket = client
            _isConnected.value = true
            Timber.d("WiFiDirect: caller connected from ${client.inetAddress.hostAddress}")
            startReading(client)
            try { srv.close() } catch (_: IOException) {}
            serverSocket = null
            Result.success(Unit)
        } catch (e: IOException) {
            Timber.e(e, "WiFiDirect: TCP accept failed")
            _isConnected.value = false
            Result.failure(e)
        }
    }

    // ── Caller side ───────────────────────────────────────────────────────

    /**
     * Connects this device to the callee's WiFi Direct group using the credentials
     * shared over BLE. Uses WifiNetworkSpecifier (API 29+) — no user interaction needed.
     */
    suspend fun connectToGroup(ssid: String, passphrase: String): Result<Unit> =
        suspendCancellableCoroutine { cont ->
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(passphrase)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Timber.d("WiFiDirect: WiFi network available")
                    if (!cont.isCompleted) cont.resume(Result.success(Unit))
                }

                override fun onUnavailable() {
                    Timber.e("WiFiDirect: network unavailable")
                    if (!cont.isCompleted)
                        cont.resume(Result.failure(IOException("WiFi Direct network unavailable")))
                }

                override fun onLost(network: Network) {
                    Timber.w("WiFiDirect: network lost")
                    _isConnected.value = false
                }
            }

            networkCallback = callback
            try {
                connectivityManager.requestNetwork(request, callback)
                cont.invokeOnCancellation {
                    try { connectivityManager.unregisterNetworkCallback(callback) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                if (!cont.isCompleted) cont.resume(Result.failure(e))
            }
        }

    /**
     * Connects a TCP socket to the callee's audio server (Group Owner at 192.168.49.1).
     * Must be called after [connectToGroup] returns success.
     */
    suspend fun connectAudioSocket(): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            Timber.d("WiFiDirect: connecting TCP to $GROUP_OWNER_IP:$AUDIO_PORT")
            val socket = Socket(GROUP_OWNER_IP, AUDIO_PORT)
            audioSocket = socket
            _isConnected.value = true
            Timber.d("WiFiDirect: TCP connected to callee")
            startReading(socket)
            Result.success(Unit)
        } catch (e: IOException) {
            Timber.e(e, "WiFiDirect: TCP connect failed")
            Result.failure(e)
        }
    }

    // ── Data I/O ──────────────────────────────────────────────────────────

    /**
     * Send raw bytes over the audio TCP socket.
     * Frames with a 2-byte big-endian length prefix.
     */
    suspend fun sendData(data: ByteArray) = withContext(Dispatchers.IO) {
        val socket = audioSocket ?: throw IOException("No active WiFi Direct audio connection")
        val len = data.size
        val frame = ByteArray(2 + len)
        frame[0] = ((len shr 8) and 0xFF).toByte()
        frame[1] = (len and 0xFF).toByte()
        System.arraycopy(data, 0, frame, 2, len)
        try {
            socket.outputStream.write(frame)
            socket.outputStream.flush()
        } catch (e: IOException) {
            Timber.e(e, "WiFiDirect: send failed")
            throw e
        }
    }

    private fun startReading(socket: Socket) {
        readJob?.cancel()
        readJob = scope.launch(Dispatchers.IO) {
            val stream = socket.inputStream
            val lenBuf = ByteArray(2)
            Timber.d("WiFiDirect: starting read loop")
            try {
                while (isActive) {
                    // Read 2-byte length header
                    var n: Int
                    var read = 0
                    while (read < 2) {
                        n = stream.read(lenBuf, read, 2 - read)
                        if (n == -1) throw IOException("Stream closed by remote")
                        read += n
                    }
                    val len = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
                    if (len <= 0 || len > 65535) continue

                    // Read payload
                    val payload = ByteArray(len)
                    read = 0
                    while (read < len) {
                        n = stream.read(payload, read, len - read)
                        if (n == -1) throw IOException("Stream closed mid-frame")
                        read += n
                    }
                    _incomingData.tryEmit(payload)
                }
            } catch (e: IOException) {
                Timber.e(e, "WiFiDirect: read loop ended — ${e.message}")
                _isConnected.value = false
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    fun disconnect() {
        _isConnected.value = false
        readJob?.cancel()
        readJob = null

        try { audioSocket?.close() } catch (_: IOException) {}
        audioSocket = null

        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null

        networkCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
            networkCallback = null
        }

        try {
            wifiP2pManager.removeGroup(p2pChannel, null)
        } catch (_: Exception) {}

        Timber.d("WiFiDirect: disconnected")
    }

    fun cleanup() {
        disconnect()
        scope.cancel()
    }
}
