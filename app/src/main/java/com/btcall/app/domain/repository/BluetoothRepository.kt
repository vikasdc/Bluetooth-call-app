package com.btcall.app.domain.repository

import com.btcall.app.domain.model.PeerDevice
import com.btcall.app.domain.model.SignalMessage
import kotlinx.coroutines.flow.Flow

/**
 * Contract for all Bluetooth + WiFi Direct operations.
 *
 * Transport architecture:
 * - BLE  → peer discovery and call signaling
 * - WiFi Direct → audio streaming (TCP socket over P2P group)
 *
 * Call setup sequence:
 *  Callee: createAudioGroup() → share SSID+pass in CALL_ACCEPT → acceptAudioConnection()
 *  Caller: connectToAudioGroup(ssid, pass) → connectAudioSocket()
 */
interface BluetoothRepository {

    // ── BLE Discovery ─────────────────────────────────────────────────────

    val nearbyDevices: Flow<List<PeerDevice>>
    val isScanning: Flow<Boolean>

    suspend fun startAdvertising(): Result<Unit>
    fun stopAdvertising()
    suspend fun startDiscovery(): Result<Unit>
    fun stopDiscovery()

    // ── BLE Signaling ─────────────────────────────────────────────────────

    val incomingSignals: Flow<SignalMessage>
    suspend fun sendSignal(target: PeerDevice, message: SignalMessage): Result<Unit>

    // ── WiFi Direct Audio Transport ───────────────────────────────────────

    /**
     * Callee: create a WiFi Direct group and return (SSID, passphrase).
     * Include the result in the CALL_ACCEPT BLE message.
     */
    suspend fun createAudioGroup(): Result<Pair<String, String>>

    /**
     * Callee: open a TCP server socket and block until the caller connects.
     * Call this concurrently with sending CALL_ACCEPT.
     */
    suspend fun acceptAudioConnection(): Result<Unit>

    /**
     * Caller: join the callee's WiFi Direct group using credentials from CALL_ACCEPT.
     */
    suspend fun connectToAudioGroup(ssid: String, passphrase: String): Result<Unit>

    /**
     * Caller: TCP-connect to the callee's audio server (Group Owner at 192.168.49.1).
     * Call after [connectToAudioGroup] succeeds.
     */
    suspend fun connectAudioSocket(): Result<Unit>

    /** Send raw audio packet bytes over the TCP audio socket. */
    suspend fun sendAudioData(data: ByteArray): Result<Unit>

    /** Flow of raw audio packet bytes received from the remote peer. */
    val incomingAudioData: Flow<ByteArray>

    /** True while the TCP audio socket is alive. */
    val audioConnected: Flow<Boolean>

    /** Tear down the audio socket and WiFi Direct group. */
    fun disconnectAudio()

    // ── Device Info ───────────────────────────────────────────────────────

    fun isBluetoothEnabled(): Boolean
    fun getLocalDeviceId(): String
    fun getLocalDeviceName(): String
    fun getLocalMacAddress(): String
}
