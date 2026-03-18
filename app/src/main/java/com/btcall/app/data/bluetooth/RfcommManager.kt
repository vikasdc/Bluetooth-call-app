package com.btcall.app.data.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the Bluetooth Classic RFCOMM socket used for real-time audio streaming.
 *
 * RFCOMM provides a reliable, ordered byte stream — ideal for audio packets.
 * We use a fixed UUID on both sides so pairing isn't required (insecure channel).
 *
 * Design:
 * - Callee side:  calls [acceptConnection] → opens BluetoothServerSocket
 * - Caller side:  calls [connectToDevice] → connects BluetoothSocket to callee
 * - After connect: [startReading] drains the stream into [incomingData]
 * - Sending:      [sendData] writes bytes to the output stream
 *
 * The RFCOMM socket is separate from BLE — it operates on Bluetooth Classic (BR/EDR).
 * Both devices must be paired OR we use createInsecureRfcommSocketToServiceRecord
 * which doesn't require pairing (acceptable for this app).
 */
@Singleton
class RfcommManager @Inject constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var readJob: Job? = null

    // Raw bytes received from the remote peer (contains [AudioPacket] frames)
    private val _incomingData = Channel<ByteArray>(capacity = Channel.BUFFERED)
    val incomingData: Flow<ByteArray> = _incomingData.receiveAsFlow()

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    // ── Callee side: accept incoming connection ────────────────────────────

    /**
     * Opens a listening RFCOMM server socket and waits for the caller to connect.
     * Blocks until a connection arrives or throws.
     *
     * @param timeoutMs Max time to wait for a connection (default 15s)
     */
    suspend fun acceptConnection(timeoutMs: Long = 15_000L) = withContext(Dispatchers.IO) {
        Timber.d("RFCOMM: opening server socket")

        val btAdapter = adapter ?: throw IllegalStateException("Bluetooth not available")

        // Insecure RFCOMM — no pairing required
        serverSocket = try {
            btAdapter.listenUsingInsecureRfcommWithServiceRecord(
                "BTCallAudio",
                BleConstants.RFCOMM_UUID
            )
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException creating server socket")
            throw e
        }

        Timber.d("RFCOMM: waiting for client connection (timeout=${timeoutMs}ms)")

        clientSocket = try {
            // accept() blocks until connection or timeout
            serverSocket?.accept(timeoutMs.toInt())
        } catch (e: IOException) {
            Timber.e(e, "RFCOMM accept failed or timed out")
            throw e
        } finally {
            // Server socket only needed for the initial accept; close to free resources
            try { serverSocket?.close() } catch (_: IOException) {}
            serverSocket = null
        }

        Timber.d("RFCOMM: client connected from ${safeAddress(clientSocket?.remoteDevice)}")
        startReading()
    }

    // ── Caller side: connect to the callee ────────────────────────────────

    /**
     * Connects to the callee's RFCOMM server socket.
     * Uses insecure channel to avoid pairing prompts.
     *
     * On failure, tries reflection fallback (some devices have channel assignment issues).
     */
    suspend fun connectToDevice(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        Timber.d("RFCOMM: connecting to ${safeAddress(device)}")

        val socket = try {
            device.createInsecureRfcommSocketToServiceRecord(BleConstants.RFCOMM_UUID)
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException creating RFCOMM socket")
            throw e
        } catch (e: IOException) {
            Timber.w(e, "createInsecureRfcommSocketToServiceRecord failed, trying reflection")
            createSocketViaReflection(device)
        }

        // Cancel ongoing discovery to speed up connection
        try {
            adapter?.cancelDiscovery()
        } catch (_: SecurityException) {}

        try {
            socket.connect()
        } catch (e: IOException) {
            Timber.e(e, "RFCOMM connect failed")
            try { socket.close() } catch (_: IOException) {}
            throw e
        }

        clientSocket = socket
        Timber.d("RFCOMM: connected to ${safeAddress(device)}")
        startReading()
    }

    // ── Data I/O ──────────────────────────────────────────────────────────

    /**
     * Send raw bytes over the RFCOMM socket.
     * Prefixes each write with a 2-byte length header so the reader can
     * reconstruct exact packet boundaries from the stream.
     *
     * Frame format: [len_high][len_low][payload...]
     */
    suspend fun sendData(data: ByteArray) = withContext(Dispatchers.IO) {
        val socket = clientSocket ?: throw IOException("No active RFCOMM connection")
        val stream = socket.outputStream

        try {
            val len = data.size
            // Length-prefix framing: 2 bytes big-endian length + payload
            val frame = ByteArray(2 + len)
            frame[0] = ((len shr 8) and 0xFF).toByte()
            frame[1] = (len and 0xFF).toByte()
            System.arraycopy(data, 0, frame, 2, len)
            stream.write(frame)
            stream.flush()
        } catch (e: IOException) {
            Timber.e(e, "RFCOMM write failed")
            throw e
        }
    }

    /**
     * Continuously reads from the RFCOMM input stream.
     * Reassembles framed packets using the 2-byte length prefix protocol.
     * Emits complete packets to [incomingData].
     */
    private fun startReading() {
        readJob?.cancel()
        readJob = scope.launch(Dispatchers.IO) {
            val socket = clientSocket ?: return@launch
            val stream = socket.inputStream
            val lenBuf = ByteArray(2)

            Timber.d("RFCOMM: starting read loop")

            try {
                while (isActive) {
                    // Read 2-byte length header
                    var bytesRead = 0
                    while (bytesRead < 2) {
                        val n = stream.read(lenBuf, bytesRead, 2 - bytesRead)
                        if (n == -1) throw IOException("Stream closed by remote")
                        bytesRead += n
                    }

                    val len = ((lenBuf[0].toInt() and 0xFF) shl 8) or
                              (lenBuf[1].toInt() and 0xFF)

                    if (len <= 0 || len > 65535) {
                        Timber.w("RFCOMM: invalid frame length $len, skipping")
                        continue
                    }

                    // Read full payload
                    val payload = ByteArray(len)
                    bytesRead = 0
                    while (bytesRead < len) {
                        val n = stream.read(payload, bytesRead, len - bytesRead)
                        if (n == -1) throw IOException("Stream closed mid-frame")
                        bytesRead += n
                    }

                    _incomingData.trySend(payload)
                }
            } catch (e: IOException) {
                Timber.e(e, "RFCOMM read loop ended")
                // Signal disconnection — ViewModel will handle state transition
                _incomingData.close(e)
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    fun disconnect() {
        readJob?.cancel()
        readJob = null

        try { clientSocket?.close() } catch (_: IOException) {}
        clientSocket = null

        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null

        Timber.d("RFCOMM disconnected")
    }

    fun isConnected(): Boolean = clientSocket?.isConnected == true

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Reflection-based socket creation for devices where service record lookup fails.
     * Creates an RFCOMM socket on channel 1 directly.
     */
    private fun createSocketViaReflection(device: BluetoothDevice): BluetoothSocket {
        return try {
            val method = device.javaClass.getMethod(
                "createInsecureRfcommSocket",
                Int::class.javaPrimitiveType
            )
            method.invoke(device, 1) as BluetoothSocket
        } catch (e: Exception) {
            Timber.e(e, "Reflection socket creation failed")
            throw IOException("Cannot create RFCOMM socket", e)
        }
    }

    private fun safeAddress(device: BluetoothDevice?): String {
        return try { device?.address ?: "null" } catch (e: SecurityException) { "UNKNOWN" }
    }

    fun cleanup() {
        disconnect()
        scope.cancel()
    }
}
