package com.btcall.app.data.bluetooth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
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
        private const val INCOMING_CALL_NOTIFICATION_ID = 1002
        private const val INCOMING_CALL_CHANNEL_ID = "btcall_incoming_call"
        private const val INCOMING_CALL_CHANNEL_NAME = "Incoming Calls"
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
    private var rfcommDisconnectJob: Job? = null
    private var currentDirection = CallDirection.INCOMING

    private val audioManager: AudioManager by lazy {
        getSystemService(AUDIO_SERVICE) as AudioManager
    }

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
        stopRingbackTone()
        stopRingtone()
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

        startRingtone()

        // Show incoming call over lock screen using a full-screen intent notification.
        // On Android 10+ startActivity() from a background service is blocked; the
        // full-screen notification approach is the correct replacement.
        val incomingIntent = Intent(this, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(IncomingCallActivity.EXTRA_CALLER_ID, callerPeer.deviceId)
            putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, callerPeer.displayName)
            putExtra(IncomingCallActivity.EXTRA_CALLER_MAC, callerPeer.macAddress)
        }
        val fullScreenPi = PendingIntent.getActivity(
            this, INCOMING_CALL_NOTIFICATION_ID, incomingIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val incomingNotification = NotificationCompat.Builder(this, INCOMING_CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentTitle("Incoming Call")
            .setContentText("${callerPeer.displayName} is calling…")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPi, true)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(INCOMING_CALL_NOTIFICATION_ID, incomingNotification)

        // Auto-reject if not answered within timeout
        callTimeoutJob?.cancel()
        callTimeoutJob = lifecycleScope.launch {
            delay(CALL_REQUEST_TIMEOUT_MS)
            if (_callState.value is CallState.Ringing) {
                cancelIncomingCallNotification()
                stopRingtone()
                rejectCall(callerPeer)
            }
        }
    }

    private fun handleCallAccept(msg: SignalMessage) {
        val current = _callState.value
        if (current !is CallState.Calling) return

        callTimeoutJob?.cancel()
        stopRingbackTone()
        val peer = current.remotePeer

        lifecycleScope.launch {
            // RFCOMM connect with retries.
            // BT Classic ACL establishment can take 3-10s on first connect.
            // Also cancel BLE discovery first — it interferes with the ACL setup on 2.4GHz.
            bluetoothRepository.stopDiscovery()
            bluetoothRepository.stopAdvertising()

            var connected = false
            for (attempt in 1..5) {
                Timber.d("RFCOMM connect attempt $attempt/5")
                val result = bluetoothRepository.connectRfcomm(peer)
                if (result.isSuccess) {
                    connected = true
                    break
                }
                Timber.w("RFCOMM connect attempt $attempt failed: ${result.exceptionOrNull()?.message}")
                if (attempt < 5) delay(3_000L)  // 3s between attempts — gives BT stack time to recover
            }
            if (connected) {
                startAudioPipeline(peer)
            } else {
                Timber.e("RFCOMM connect failed after 5 attempts")
                startBluetoothStack()  // Resume BLE since we never reached startAudioPipeline
                _callState.value = CallState.Ended(EndReason.CONNECTION_LOST)
                resetToIdle()
            }
        }
    }

    private fun handleCallReject(msg: SignalMessage) {
        if (_callState.value !is CallState.Calling) return
        callTimeoutJob?.cancel()
        stopRingbackTone()
        _callState.value = CallState.Ended(EndReason.REJECTED)
        resetToIdle()
    }

    private fun handleCallBusy(msg: SignalMessage) {
        if (_callState.value !is CallState.Calling) return
        callTimeoutJob?.cancel()
        stopRingbackTone()
        _callState.value = CallState.Ended(EndReason.BUSY)
        resetToIdle()
    }

    private fun handleCallEnd(msg: SignalMessage) {
        val current = _callState.value
        when (current) {
            is CallState.Connected -> {
                lifecycleScope.launch {
                    endCallInternal(current.remotePeer, EndReason.REMOTE_HANGUP, sendSignal = false)
                }
            }
            is CallState.Ringing -> {
                // Caller hung up while we were ringing
                callTimeoutJob?.cancel()
                cancelIncomingCallNotification()
                stopRingtone()
                _callState.value = CallState.Ended(EndReason.REMOTE_HANGUP)
                resetToIdle()
            }
            is CallState.Calling -> {
                // Remote ended while we were calling
                callTimeoutJob?.cancel()
                stopRingbackTone()
                _callState.value = CallState.Ended(EndReason.REMOTE_HANGUP)
                resetToIdle()
            }
            else -> {}
        }
    }

    private fun handleHeartbeat(msg: SignalMessage) {
        // Reset heartbeat miss counter — connection is alive
        lastHeartbeatMs = System.currentTimeMillis()
    }

    private var lastHeartbeatMs = 0L
    private var incomingRingtone: Ringtone? = null
    private var ringbackTone: ToneGenerator? = null
    private var callWakeLock: PowerManager.WakeLock? = null

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
        startRingbackTone()

        lifecycleScope.launch {
            val msg = SignalMessage(
                type = SignalMessage.MessageType.CALL_REQUEST,
                senderId = bluetoothRepository.getLocalDeviceId(),
                senderName = bluetoothRepository.getLocalDeviceName(),
                senderMac = ""
            )
            bluetoothRepository.sendSignal(peer, msg).onFailure { err ->
                Timber.e(err, "Failed to send CALL_REQUEST")
                stopRingbackTone()
                _callState.value = CallState.Ended(EndReason.CONNECTION_LOST)
                resetToIdle()
            }

            // Timeout if no response
            callTimeoutJob?.cancel()
            callTimeoutJob = launch {
                delay(CALL_REQUEST_TIMEOUT_MS)
                if (_callState.value is CallState.Calling) {
                    stopRingbackTone()
                    _callState.value = CallState.Ended(EndReason.TIMEOUT)
                    resetToIdle()
                }
            }
        }
    }

    /**
     * Accepts an incoming call while in RINGING state.
     * Opens RFCOMM server socket and starts audio pipeline.
     *
     * Order matters: RFCOMM server socket must be open BEFORE we send CALL_ACCEPT,
     * otherwise the caller may attempt to connect before we are listening.
     */
    fun acceptCall(callerPeer: PeerDevice) {
        if (_callState.value !is CallState.Ringing) return
        callTimeoutJob?.cancel()
        cancelIncomingCallNotification()
        stopRingtone()

        lifecycleScope.launch {
            // Stop BLE before opening RFCOMM — prevents 2.4GHz interference on callee side too
            bluetoothRepository.stopDiscovery()
            bluetoothRepository.stopAdvertising()

            // Step 1: Start accepting RFCOMM connections in the background so
            // the server socket is bound before the caller gets CALL_ACCEPT.
            val rfcommJob = launch {
                bluetoothRepository.acceptRfcomm().onSuccess {
                    startAudioPipeline(callerPeer)
                }.onFailure { err ->
                    Timber.e(err, "RFCOMM accept failed")
                    if (_callState.value !is CallState.Connected) {
                        startBluetoothStack()  // Resume BLE since audio never started
                        _callState.value = CallState.Ended(EndReason.CONNECTION_LOST)
                        resetToIdle()
                    }
                }
            }

            // Step 2: Give the server socket a moment to bind, then notify caller.
            delay(400)

            // Step 3: Send CALL_ACCEPT — caller will now connect RFCOMM.
            val acceptMsg = SignalMessage(
                type = SignalMessage.MessageType.CALL_ACCEPT,
                senderId = bluetoothRepository.getLocalDeviceId(),
                senderName = bluetoothRepository.getLocalDeviceName(),
                senderMac = ""
            )
            val signalResult = bluetoothRepository.sendSignal(callerPeer, acceptMsg)
            if (signalResult.isFailure) {
                Timber.e(signalResult.exceptionOrNull(), "Failed to send CALL_ACCEPT")
                rfcommJob.cancel()
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
        cancelIncomingCallNotification()
        stopRingtone()

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
        stopRingbackTone()
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
        lastHeartbeatMs = System.currentTimeMillis()
        _callState.value = CallState.Connected(peer)

        // Route audio through earpiece/speakerphone optimised for voice calls
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true

        // Acquire a partial wake lock so the CPU stays awake during the call.
        // RFCOMM socket will drop if the CPU enters deep sleep.
        callWakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BTCall::CallWakeLock")
            .also { it.acquire(30 * 60 * 1000L) } // 30-minute safety cap

        // Stop BLE scan and advertising for the duration of the call.
        // SCAN_MODE_LOW_LATENCY aggressively sweeps 2.4GHz channels and directly
        // interferes with Bluetooth Classic (RFCOMM) — this is the primary cause of
        // audio dropping after a few seconds.
        bluetoothRepository.stopDiscovery()
        bluetoothRepository.stopAdvertising()

        lifecycleScope.launch {
            val playbackResult = audioRepository.startPlayback()
            if (playbackResult.isFailure) {
                Timber.e(playbackResult.exceptionOrNull(), "Audio playback failed to start")
            }
            val captureResult = audioRepository.startCapture()
            if (captureResult.isFailure) {
                Timber.e(captureResult.exceptionOrNull(), "Audio capture failed to start")
            }
        }

        // Send captured audio over RFCOMM — must run on IO to avoid blocking the main thread
        audioSendJob = lifecycleScope.launch(Dispatchers.IO) {
            audioRepository.capturedAudioPackets.collect { packet ->
                bluetoothRepository.sendAudioData(packet.toBytes())
                    .onFailure { Timber.v("Audio send error (may be normal on hangup)") }
            }
        }

        // Receive audio from RFCOMM and enqueue into jitter buffer — IO to avoid 20ms blocking
        audioReceiveJob = lifecycleScope.launch(Dispatchers.IO) {
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

        // Observe RFCOMM connection — end call when socket drops.
        // dropWhile { !it } skips any initial false value and waits until we first
        // see true (confirmed connected), then filter { !it } catches the drop.
        // This prevents a race where _isRfcommConnected is still false for a brief
        // moment between connectToDevice() returning and this coroutine launching.
        rfcommDisconnectJob = lifecycleScope.launch {
            bluetoothRepository.rfcommConnected
                .dropWhile { !it }   // Wait for confirmed connected (true)
                .filter { !it }      // Then detect when it drops (false)
                .first()
            val current = _callState.value
            if (current is CallState.Connected) {
                Timber.w("RFCOMM socket dropped — ending call")
                endCallInternal(current.remotePeer, EndReason.CONNECTION_LOST, sendSignal = false)
            }
        }

        updateNotification("In call with ${peer.displayName}")
    }

    fun setSpeakerphone(on: Boolean) {
        audioManager.isSpeakerphoneOn = on
        _callState.update { state ->
            if (state is CallState.Connected) state.copy(isSpeakerOn = on) else state
        }
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
        rfcommDisconnectJob?.cancel()
        stopRingbackTone()
        stopRingtone()

        // Release wake lock so the CPU can sleep again
        if (callWakeLock?.isHeld == true) {
            callWakeLock?.release()
        }
        callWakeLock = null

        // Restore normal audio mode
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL

        // Update state BEFORE the potentially blocking signal send so the UI
        // transitions immediately instead of freezing for up to 10 seconds.
        _callState.value = CallState.Ended(reason)
        endCallUseCase(peer, currentDirection, callStartTimestamp, reason, sendSignal)
        resetToIdle()
    }

    private fun resetToIdle() {
        lifecycleScope.launch {
            delay(2_000L)  // Show ended state briefly
            _callState.value = CallState.Idle
        }
        // Restart BLE scan/advertising now that RFCOMM is done.
        // Small delay to let RFCOMM teardown complete before re-activating 2.4GHz radio.
        lifecycleScope.launch {
            delay(1_000L)
            startBluetoothStack()
        }
        updateNotification("Ready")
    }

    // ── Ringtone ──────────────────────────────────────────────────────────

    private fun startRingtone() {
        stopRingtone()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        incomingRingtone = RingtoneManager.getRingtone(this, uri)?.also { ringtone ->
            ringtone.isLooping = true
            ringtone.play()
        }
    }

    private fun stopRingtone() {
        incomingRingtone?.stop()
        incomingRingtone = null
    }

    private fun startRingbackTone() {
        stopRingbackTone()
        try {
            ringbackTone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, ToneGenerator.MAX_VOLUME)
            ringbackTone?.startTone(ToneGenerator.TONE_SUP_RINGTONE)
        } catch (e: Exception) {
            Timber.w(e, "Could not start ringback tone")
        }
    }

    private fun stopRingbackTone() {
        ringbackTone?.stopTone()
        ringbackTone?.release()
        ringbackTone = null
    }

    private fun cancelIncomingCallNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(INCOMING_CALL_NOTIFICATION_ID)
    }

    private fun observeIncomingAudio() {
        // Handled in startAudioPipeline's audioReceiveJob
    }

    // ── Notifications ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Persistent foreground service channel (low importance — no sound/heads-up)
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Bluetooth call status"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)

        // Incoming call channel — IMPORTANCE_HIGH is required for heads-up and full-screen intent
        val incomingChannel = NotificationChannel(
            INCOMING_CALL_CHANNEL_ID,
            INCOMING_CALL_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming Bluetooth call alerts"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(incomingChannel)
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
