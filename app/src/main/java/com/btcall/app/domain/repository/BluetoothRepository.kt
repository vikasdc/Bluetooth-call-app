package com.btcall.app.domain.repository

import com.btcall.app.domain.model.CallState
import com.btcall.app.domain.model.PeerDevice
import com.btcall.app.domain.model.SignalMessage
import kotlinx.coroutines.flow.Flow

/**
 * Contract for all Bluetooth operations.
 * Implementations: BluetoothRepositoryImpl (BLE + RFCOMM).
 */
interface BluetoothRepository {

    // ── Discovery ──────────────────────────────────────────────────────────

    /** Flow emitting the current list of visible nearby peers. */
    val nearbyDevices: Flow<List<PeerDevice>>

    /** Whether BLE is currently scanning. */
    val isScanning: Flow<Boolean>

    /** Start BLE advertising so other devices can see us. */
    suspend fun startAdvertising(): Result<Unit>

    /** Stop BLE advertising. */
    fun stopAdvertising()

    /** Start BLE scan for peers running this app. */
    suspend fun startDiscovery(): Result<Unit>

    /** Stop BLE scan. */
    fun stopDiscovery()

    // ── Signaling ──────────────────────────────────────────────────────────

    /** Flow of inbound signaling messages (BLE GATT writes from peers). */
    val incomingSignals: Flow<SignalMessage>

    /**
     * Send a signaling message to a specific peer's GATT characteristic.
     * Used for CALL_REQUEST, CALL_ACCEPT, CALL_REJECT, CALL_BUSY, CALL_END.
     */
    suspend fun sendSignal(target: PeerDevice, message: SignalMessage): Result<Unit>

    // ── RFCOMM Audio Channel ───────────────────────────────────────────────

    /**
     * Connect an RFCOMM socket to the target device (caller connects TO callee).
     * Blocks until connected or fails.
     */
    suspend fun connectRfcomm(target: PeerDevice): Result<Unit>

    /**
     * Accept an incoming RFCOMM connection (callee side).
     * Blocks until a client connects or timeout.
     */
    suspend fun acceptRfcomm(): Result<Unit>

    /**
     * Send raw bytes over the established RFCOMM socket.
     * @param data Audio packet bytes (see [AudioPacket.toBytes])
     */
    suspend fun sendAudioData(data: ByteArray): Result<Unit>

    /** Flow of raw byte arrays received over RFCOMM. */
    val incomingAudioData: Flow<ByteArray>

    /** True while an RFCOMM socket is open and the read loop is alive. */
    val rfcommConnected: Flow<Boolean>

    /** Close the RFCOMM socket and free resources. */
    fun disconnectRfcomm()

    // ── State ──────────────────────────────────────────────────────────────

    /** Whether Bluetooth is enabled on this device. */
    fun isBluetoothEnabled(): Boolean

    /** This device's unique stable ID (persisted across restarts). */
    fun getLocalDeviceId(): String

    /** This device's display name. */
    fun getLocalDeviceName(): String

    /** This device's Bluetooth MAC address. */
    fun getLocalMacAddress(): String
}
