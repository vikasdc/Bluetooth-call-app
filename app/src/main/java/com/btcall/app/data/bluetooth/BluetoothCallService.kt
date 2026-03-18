package com.btcall.app.data.bluetooth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.btcall.app.R
import com.btcall.app.domain.model.AudioPacket
import com.btcall.app.domain.model.CallDirection
import com.btcall.app.domain.model.CallState
import com.btcall.app.domain.model.EndReason
import com.btcall.app.domain.model.PeerDevice
import com.btcall.app.domain.model.SignalMessage
import com.btcall.app.domain.repository.AudioRepository
import com.btcall.app.domain.repository.BluetoothRepository
import com.btcall.app.domain.usecase.EndCallUseCase
import com.btcall.app.presentation.MainActivity
import com.btcall.app.presentation.incoming.IncomingCallActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that owns the Bluetooth call lifecycle.
 *
 * Responsibilities:
 * - Keeps BLE advertising and scanning alive (even with app in background)
 * - Receives incoming signaling messages and drives call state machine
 * - Manages RFCOMM audio pipeline (start/stop capture + playback)
 * - Emits [callState] to ViewModels via bound service interface
 * - Shows persistent notification during active call
 *
 * State Machine:
 * IDLE → [receive CALL_REQUEST] → RINGING
 * IDLE → [user initiates call] → CALLING
 * CALLING → [receive CALL_ACCEPT] → CONNECTED
 * CALLING → [receive CALL_REJECT] → ENDED → IDLE
 * CALLING → [receive CALL_BUSY]   → ENDED → IDLE
 * RINGING → [user accepts]        → CONNECTED
 * RINGING → [user rejects]        → IDLE
 * CONNECTED → [receive CALL_END]  → ENDED → IDLE
 * CONNECTED → [user hangs up]     → ENDED → IDLE
 */
@AndroidEntryPoint
class BluetoothCallService : LifecycleService() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "btcall_channel"
        private const val CHANNEL_NAME = "Bluetooth Call"
        private const val CALL_REQUEST_TIMEOUT_MS = 30_000L
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
    }

    @Inject lateinit var bluetoothRepository: BluetoothRepository
    @Inject lateinit var audioRepository: AudioRepository
    @Inject lateinit var endCallUseCase: EndCallUseCase

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private var callStartTimestamp = 0L
    private var callTimerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var audioSendJob: Job? = null
    private var audioReceiveJob: Job? = null
    private var callTimeoutJob: Job? = null
    private var currentDirection = CallDirection.INCOMING

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothCallService = this@BluetoothCallService
    }

    private val binder = LocalBinder()

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildIdleNotification())
        startBluetoothStack()
        observeIncomingSignals()
        observeIncomingAudio()
        Timber.d("BluetoothCallService created")
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothRepository.stopDiscovery()
        bluetoothRepository.stopAdvertising()
        audioRepository.stopCapture()
        audioRepository.stopPlayback()
        bluetoothRepository.disconnectRfcomm()
        Timber.d("BluetoothCallService destroyed")
    }

    // ── BT Stack Init ─────────────────────────────────────────────────────

    private fun startBluetoothStack() {
        lifecycleScope.launch {
            bluetoothRepository.startAdvertising()
            bluetoothRepository.startDiscovery()
        }
    }

    // ── Signal Handling ───────────────────────────────────────────────────

    private fun observeIncomingSignals() {
        bluetoothRepository.incomingSignals.onEach { msg ->
            Timber.d("Service received signal: ${msg.type} from ${msg.senderId.take(8)}")
            when (msg.type) {
                SignalMessage.MessageType.CALL_REQUEST -> handleCallRequest(msg)
                SignalMessage.MessageType.CALL_ACCEPT  -> handleCallAccept(msg)
                SignalMessage.MessageType.CALL_REJECT  -> handleCallReject(msg)
                SignalMessage.MessageType.CALL_BUSY    -> handleCallBusy(msg)
                SignalMessage.MessageType.CALL_END     -> handleCallEnd(msg)
                SignalMessage.MessageType.HEARTBEAT    -> handleHeartbeat(msg)
            }
        }.launchIn(lifecycleScope)
    }

    private fun handleCallRequest(msg: SignalMessage) {
        val current = _callState.value
        if (current !is CallState.Idle) {
            // We're busy — send CALL_BUSY back
            lifecycleScope.launch {
                val peer = PeerDevice(msg.senderId, msg.senderName, msg.senderMac, 0)
                val busyMsg = SignalMessage(
                    type = SignalMessage.MessageType.CALL_BUSY,
                    senderId = bluetoothRepository.getLocalDeviceId(),
                    senderName = "",
                    senderMac = ""
                )
                bluetoothRepository.sendSignal(peer, busyMsg)
            }
            return
        }

        val callerPeer = PeerDevice(msg.senderId, msg.senderName, msg.senderMac, -50)
        _callState.value = CallState.Ringing(callerPeer)
        currentDirection = CallDirection.INCOMING

        // Launch IncomingCallActivity over lock screen
        val incomingIntent = Intent(this, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(IncomingCallActivity.EXTRA_CALLER_ID, callerPeer.deviceId)
            putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, callerPeer.displayName)
            putExtra(IncomingCallActivity.EXTRA_CALLER_MAC, callerPeer.macAddress)
        }
        startActivity(incomingIntent)

        // Auto-reject if not answered within timeout
        callTimeoutJob?.cancel()
        callTimeoutJob = lifecycleScope.launch {
            delay(CALL_REQUEST_TIMEOUT_MS)
            if (_callState.value is CallState.Ringing) {
                rejectCall(callerPeer)
            }
        }
    }

    private fun handleCallAccept(msg: SignalMessage) {
        val current = _callState.value
        if (current !is CallState.Calling) return

        callTimeoutJob?.cancel()
        val peer = current.remotePeer

        lifecycleScope.launch {
            // Connect RFCOMM to callee
            bluetoothRepository.connectRfcomm(peer).onSuccess {
                startAudioPipeline(peer)
            }.onFailure { err ->
                Timber.e(err, "RFCOMM connect failed after CALL_ACCEPT")
                _callState.value = CallState.Ended(EndReason.CONNECTION_LOST)
                resetToIdle()
            }
        }
    }

    private fun handleCallReject(msg: SignalMessage) {
        if (_callState.value !is CallState.Calling) return
        callTimeoutJob?.cancel()
        _callState.value = CallState.Ended(EndReason.REJECTED)
        resetToIdle()
    }

    private fun handleCallBusy(msg: SignalMessage) {
        if (_callState.value !is CallState.Calling) return
        callTimeoutJob?.cancel()
        _callState.value = CallState.Ended(EndReason.BUSY)
        resetToIdle()
    }

    private fun handleCallEnd(msg: SignalMessage) {
        val current = _callState.value
        if (current !is CallState.Connected) return

        lifecycleScope.launch {
            endCallInternal(current.remotePeer, EndReason.REMOTE_HANGUP, sendSignal = false)
        }
    }

    private fun handleHeartbeat(msg: SignalMessage) {
        // Reset heartbeat miss counter — connection is alive
        lastHeartbeatMs = System.currentTimeMillis()
    }

    private var lastHeartbeatMs = 0L

    // ── Call Actions (called by ViewModel) ────────────────────────────────

    /**
     * Initiates an outgoing call to [peer].
     * Sends CALL_REQUEST via BLE GATT and transitions to CALLING state.
     */
    fun initiateCall(peer: PeerDevice) {
        if (_callState.value !is CallState.Idle) {
            Timber.w("Cannot initiate: not in IDLE state")
            return
        }

        _callState.value = CallState.Calling(peer)
        currentDirection = CallDirection.OUTGOING

        lifecycleScope.launch {
            val msg = SignalMessage(
                type = SignalMessage.MessageType.CALL_REQUEST,
                senderId = bluetoothRepository.getLocalDeviceId(),
                senderName = bluetoothRepository.getLocalDeviceName(),
                senderMac = ""
            )
            bluetoothRepository.sendSignal(peer, msg).onFailure { err ->
                Timber.e(err, "Failed to send CALL_REQUEST")
                _callState.value = CallState.Ended(EndReason.CONNECTION_LOST)
                resetToIdle()
            }

            // Timeout if no response
            callTimeoutJob?.cancel()
            callTimeoutJob = launch {
                delay(CALL_REQUEST_TIMEOUT_MS)
                if (_callState.value is CallState.Calling) {
                    _callState.value = CallState.Ended(EndReason.TIMEOUT)
                    resetToIdle()
                }
            }
        }
    }

    /**
     * Accepts an incoming call while in RINGING state.
     * Opens RFCOMM server socket and starts audio pipeline.
     */
    fun acceptCall(callerPeer: PeerDevice) {
        if (_callState.value !is CallState.Ringing) return
        callTimeoutJob?.cancel()

        lifecycleScope.launch {
            val acceptMsg = SignalMessage(
                type = SignalMessage.MessageType.CALL_ACCEPT,
                senderId = bluetoothRepository.getLocalDeviceId(),
                senderName = bluetoothRepository.getLocalDeviceName(),
                senderMac = ""
            )
            bluetoothRepository.sendSignal(callerPeer, acceptMsg)

            // Wait for caller to connect RFCOMM
            bluetoothRepository.acceptRfcomm().onSuccess {
                startAudioPipeline(callerPeer)
            }.onFailure { err ->
                Timber.e(err, "RFCOMM accept failed")
                _callState.value = CallState.Ended(EndReason.CONNECTION_LOST)
                resetToIdle()
            }
        }
    }

    /**
     * Rejects an incoming call while in RINGING state.
     */
    fun rejectCall(callerPeer: PeerDevice) {
        if (_callState.value !is CallState.Ringing) return
        callTimeoutJob?.cancel()

        lifecycleScope.launch {
            val rejectMsg = SignalMessage(
                type = SignalMessage.MessageType.CALL_REJECT,
                senderId = bluetoothRepository.getLocalDeviceId(),
                senderName = "",
                senderMac = ""
            )
            bluetoothRepository.sendSignal(callerPeer, rejectMsg)
            _callState.value = CallState.Idle
        }
    }

    /**
     * Ends an active call.
     */
    fun endCall() {
        val current = _callState.value
        if (current !is CallState.Connected && current !is CallState.Calling) return

        val peer = when (current) {
            is CallState.Connected -> current.remotePeer
            is CallState.Calling   -> current.remotePeer
            else -> return
        }

        callTimeoutJob?.cancel()
        lifecycleScope.launch {
            endCallInternal(peer, EndReason.LOCAL_HANGUP, sendSignal = true)
        }
    }

    fun setMuted(muted: Boolean) {
        audioRepository.setMuted(muted)
        _callState.update { state ->
            if (state is CallState.Connected) state.copy(isMuted = muted) else state
        }
    }

    // ── Audio Pipeline ────────────────────────────────────────────────────

    private fun startAudioPipeline(peer: PeerDevice) {
        callStartTimestamp = System.currentTimeMillis()
        _callState.value = CallState.Connected(peer)

        lifecycleScope.launch {
            audioRepository.startPlayback()
            audioRepository.startCapture()
        }

        // Send captured audio over RFCOMM
        audioSendJob = lifecycleScope.launch {
            audioRepository.capturedAudioPackets.collect { packet ->
                bluetoothRepository.sendAudioData(packet.toBytes())
                    .onFailure { Timber.v("Audio send error (may be normal on hangup)") }
            }
        }

        // Receive audio from RFCOMM and enqueue into jitter buffer
        audioReceiveJob = lifecycleScope.launch {
            bluetoothRepository.incomingAudioData.collect { bytes ->
                val packet = AudioPacket.fromBytes(bytes)
                if (packet != null) {
                    audioRepository.enqueueForPlayback(packet)
                }
            }
        }

        // Call timer — update duration every second
        callTimerJob = lifecycleScope.launch {
            while (true) {
                delay(1_000L)
                val elapsed = (System.currentTimeMillis() - callStartTimestamp) / 1000L
                _callState.update { state ->
                    if (state is CallState.Connected) state.copy(durationSeconds = elapsed) else state
                }
            }
        }

        // Heartbeat sender
        heartbeatJob = lifecycleScope.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                val current = _callState.value
                if (current !is CallState.Connected) break
                val hb = SignalMessage(
                    type = SignalMessage.MessageType.HEARTBEAT,
                    senderId = bluetoothRepository.getLocalDeviceId(),
                    senderName = "",
                    senderMac = ""
                )
                bluetoothRepository.sendSignal(current.remotePeer, hb)

                // Check if remote is still alive
                if (lastHeartbeatMs > 0) {
                    val missedMs = System.currentTimeMillis() - lastHeartbeatMs
                    if (missedMs > HEARTBEAT_INTERVAL_MS * BleConstants.HEARTBEAT_MISS_THRESHOLD) {
                        Timber.w("Heartbeat timeout — remote disconnected")
                        endCallInternal(current.remotePeer, EndReason.CONNECTION_LOST, sendSignal = false)
                        break
                    }
                }
            }
        }

        updateNotification("In call with ${peer.displayName}")
    }

    private suspend fun endCallInternal(
        peer: PeerDevice,
        reason: EndReason,
        sendSignal: Boolean
    ) {
        audioSendJob?.cancel()
        audioReceiveJob?.cancel()
        callTimerJob?.cancel()
        heartbeatJob?.cancel()

        endCallUseCase(peer, currentDirection, callStartTimestamp, reason, sendSignal)
        _callState.value = CallState.Ended(reason)
        resetToIdle()
    }

    private fun resetToIdle() {
        lifecycleScope.launch {
            delay(2_000L)  // Show ended state briefly
            _callState.value = CallState.Idle
        }
        updateNotification("Ready")
    }

    private fun observeIncomingAudio() {
        // Handled in startAudioPipeline's audioReceiveJob
    }

    // ── Notifications ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Bluetooth call status"
            setShowBadge(false)
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildIdleNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BTCall")
            .setContentText("Discoverable — waiting for calls")
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notification = buildIdleNotification().let {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("BTCall")
                .setContentText(status)
                .setSmallIcon(R.drawable.ic_bluetooth)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
        nm.notify(NOTIFICATION_ID, notification)
    }
}
