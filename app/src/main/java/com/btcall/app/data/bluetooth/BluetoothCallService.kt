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
 * Transport architecture (article-inspired mesh approach):
 *   BLE         → peer discovery + call signaling (CALL_REQUEST / CALL_ACCEPT / …)
 *   WiFi Direct → audio streaming over TCP socket (replaces broken RFCOMM)
 *
 * Why this fixes the 3-second audio cutout:
 *   BLE lives on the Bluetooth chip; WiFi Direct lives on the WiFi chip.
 *   They use completely separate radios, so there is zero 2.4GHz contention.
 *
 * State Machine:
 * IDLE → [CALL_REQUEST received]   → RINGING
 * IDLE → [user initiates]          → CALLING
 * CALLING → [CALL_ACCEPT received] → CONNECTED
 * CALLING → [CALL_REJECT/BUSY]     → ENDED → IDLE
 * RINGING → [user accepts]         → CONNECTED
 * RINGING → [user rejects]         → IDLE
 * CONNECTED → [CALL_END received]  → ENDED → IDLE
 * CONNECTED → [user hangs up]      → ENDED → IDLE
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
    private var audioDisconnectJob: Job? = null
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
        bluetoothRepository.disconnectAudio()
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
            Timber.d("Signal: ${msg.type} from ${msg.senderId.take(8)}")
            when (msg.type) {
                SignalMessage.MessageType.CALL_REQUEST -> handleCallRequest(msg)
                SignalMessage.MessageType.CALL_ACCEPT  -> handleCallAccept(msg)
                SignalMessage.MessageType.CALL_REJECT  -> handleCallReject()
                SignalMessage.MessageType.CALL_BUSY    -> handleCallBusy()
                SignalMessage.MessageType.CALL_END     -> handleCallEnd()
                SignalMessage.MessageType.HEARTBEAT    -> lastHeartbeatMs = System.currentTimeMillis()
            }
        }.launchIn(lifecycleScope)
    }

    private fun handleCallRequest(msg: SignalMessage) {
        val current = _callState.value
        if (current !is CallState.Idle) {
            lifecycleScope.launch {
                bluetoothRepository.sendSignal(
                    PeerDevice(msg.senderId, msg.senderName, msg.senderMac, 0),
                    SignalMessage(
                        type = SignalMessage.MessageType.CALL_BUSY,
                        senderId = bluetoothRepository.getLocalDeviceId()
                    )
                )
            }
            return
        }

        val callerPeer = PeerDevice(msg.senderId, msg.senderName, msg.senderMac, -50)
        _callState.value = CallState.Ringing(callerPeer)
        currentDirection = CallDirection.INCOMING
        startRingtone()

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
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(
            INCOMING_CALL_NOTIFICATION_ID,
            NotificationCompat.Builder(this, INCOMING_CALL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_bluetooth)
                .setContentTitle("Incoming Call")
                .setContentText("${callerPeer.displayName} is calling…")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(fullScreenPi, true)
                .setAutoCancel(false)
                .setOngoing(true)
                .build()
        )

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

    /**
     * Caller side: received CALL_ACCEPT carrying WiFi Direct credentials.
     *
     * 1. Stop BLE scan/advertising (saves battery; not needed during call)
     * 2. Join callee's WiFi Direct group via WifiNetworkSpecifier
     * 3. TCP-connect to callee's audio server (Group Owner IP = 192.168.49.1)
     * 4. Start audio pipeline
     */
    private fun handleCallAccept(msg: SignalMessage) {
        val current = _callState.value
        if (current !is CallState.Calling) return

        callTimeoutJob?.cancel()
        stopRingbackTone()
        val peer = current.remotePeer

        lifecycleScope.launch {
            bluetoothRepository.stopDiscovery()
            bluetoothRepository.stopAdvertising()

            val ssid = msg.wifiSsid
            val pass = msg.wifiPassphrase

            if (ssid.isBlank() || pass.isBlank()) {
                Timber.e("CALL_ACCEPT missing WiFi Direct credentials")
                _callState.value = CallState.Ended(EndReason.CONNECTION_LOST)
                resetToIdle()
                return@launch
            }

            Timber.d("Joining WiFi Direct group SSID=$ssid")
            val joinResult = bluetoothRepository.connectToAudioGroup(ssid, pass)
            if (joinResult.isFailure) {
                Timber.e(joinResult.exceptionOrNull(), "Failed to join WiFi Direct group")
                startBluetoothStack()
                _callState.value = CallState.Ended(EndReason.CONNECTION_LOST)
                resetToIdle()
                return@launch
            }

            val socketResult = bluetoothRepository.connectAudioSocket()
            if (socketResult.isSuccess) {
                startAudioPipeline(peer)
            } else {
                Timber.e(socketResult.exceptionOrNull(), "TCP audio connect failed")
                startBluetoothStack()
                _callState.value = CallState.Ended(EndReason.CONNECTION_LOST)
                resetToIdle()
            }
        }
    }

    private fun handleCallReject() {
        if (_callState.value !is CallState.Calling) return
        callTimeoutJob?.cancel()
        stopRingbackTone()
        _callState.value = CallState.Ended(EndReason.REJECTED)
        resetToIdle()
    }

    private fun handleCallBusy() {
        if (_callState.value !is CallState.Calling) return
        callTimeoutJob?.cancel()
        stopRingbackTone()
        _callState.value = CallState.Ended(EndReason.BUSY)
        resetToIdle()
    }

    private fun handleCallEnd() {
        val current = _callState.value
        when (current) {
            is CallState.Connected -> {
                lifecycleScope.launch {
                    endCallInternal(current.remotePeer, EndReason.REMOTE_HANGUP, sendSignal = false)
                }
            }
            is CallState.Ringing -> {
                callTimeoutJob?.cancel()
                cancelIncomingCallNotification()
                stopRingtone()
                _callState.value = CallState.Ended(EndReason.REMOTE_HANGUP)
                resetToIdle()
            }
            is CallState.Calling -> {
                callTimeoutJob?.cancel()
                stopRingbackTone()
                _callState.value = CallState.Ended(EndReason.REMOTE_HANGUP)
                resetToIdle()
            }
            else -> {}
        }
    }

    private var lastHeartbeatMs = 0L
    private var incomingRingtone: Ringtone? = null
    private var ringbackTone: ToneGenerator? = null
    private var callWakeLock: PowerManager.WakeLock? = null

    // ── Call Actions (called by ViewModel) ────────────────────────────────

    fun initiateCall(peer: PeerDevice) {
        if (_callState.value !is CallState.Idle) {
            Timber.w("Cannot initiate: not in IDLE state")
            return
        }
        _callState.value = CallState.Calling(peer)
        currentDirection = CallDirection.OUTGOING
        startRingbackTone()

        lifecycleScope.launch {
            bluetoothRepository.sendSignal(
                peer,
                SignalMessage(
                    type = SignalMessage.MessageType.CALL_REQUEST,
                    senderId = bluetoothRepository.getLocalDeviceId(),
                    senderName = bluetoothRepository.getLocalDeviceName()
                )
            ).onFailure { err ->
                Timber.e(err, "Failed to send CALL_REQUEST")
                stopRingbackTone()
                _callState.value = CallState.Ended(EndReason.CONNECTION_LOST)
                resetToIdle()
            }

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
     * Callee side: user accepts the incoming call.
     *
     * 1. Create WiFi Direct group → get (SSID, passphrase)
     * 2. Open TCP ServerSocket in background
     * 3. Send CALL_ACCEPT via BLE with SSID + passphrase embedded
     * 4. When caller connects TCP → start audio pipeline
     */
    fun acceptCall(callerPeer: PeerDevice) {
        if (_callState.value !is CallState.Ringing) return
        callTimeoutJob?.cancel()
        cancelIncomingCallNotification()
        stopRingtone()

        lifecycleScope.launch {
            bluetoothRepository.stopDiscovery()
            bluetoothRepository.stopAdvertising()

            // Step 1: Create WiFi Direct group
            val groupResult = bluetoothRepository.createAudioGroup()
            if (groupResult.isFailure) {
                Timber.e(groupResult.exceptionOrNull(), "Failed to create WiFi Direct group")
                startBluetoothStack()
                _callState.value = CallState.Ended(EndReason.CONNECTION_LOST)
                resetToIdle()
                return@launch
            }
            val (ssid, passphrase) = groupResult.getOrThrow()
            Timber.d("WiFi Direct group created SSID=$ssid")

            // Step 2: Start TCP server concurrently — must be listening before caller gets CALL_ACCEPT
            val tcpJob = launch {
                bluetoothRepository.acceptAudioConnection().onSuccess {
                    startAudioPipeline(callerPeer)
                }.onFailure { err ->
                    Timber.e(err, "TCP audio accept failed")
                    if (_callState.value !is CallState.Connected) {
                        startBluetoothStack()
                        _callState.value = CallState.Ended(EndReason.CONNECTION_LOST)
                        resetToIdle()
                    }
                }
            }

            // Step 3: Give server socket time to bind, then notify caller
            delay(300)
            val signalResult = bluetoothRepository.sendSignal(
                callerPeer,
                SignalMessage(
                    type = SignalMessage.MessageType.CALL_ACCEPT,
                    senderId = bluetoothRepository.getLocalDeviceId(),
                    senderName = bluetoothRepository.getLocalDeviceName(),
                    wifiSsid = ssid,
                    wifiPassphrase = passphrase
                )
            )
            if (signalResult.isFailure) {
                Timber.e(signalResult.exceptionOrNull(), "Failed to send CALL_ACCEPT")
                tcpJob.cancel()
                bluetoothRepository.disconnectAudio()
                _callState.value = CallState.Ended(EndReason.CONNECTION_LOST)
                resetToIdle()
            }
        }
    }

    fun rejectCall(callerPeer: PeerDevice) {
        if (_callState.value !is CallState.Ringing) return
        callTimeoutJob?.cancel()
        cancelIncomingCallNotification()
        stopRingtone()

        lifecycleScope.launch {
            bluetoothRepository.sendSignal(
                callerPeer,
                SignalMessage(
                    type = SignalMessage.MessageType.CALL_REJECT,
                    senderId = bluetoothRepository.getLocalDeviceId()
                )
            )
            _callState.value = CallState.Idle
        }
    }

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
        _callState.update { if (it is CallState.Connected) it.copy(isMuted = muted) else it }
    }

    fun setSpeakerphone(on: Boolean) {
        audioManager.isSpeakerphoneOn = on
        _callState.update { if (it is CallState.Connected) it.copy(isSpeakerOn = on) else it }
    }

    // ── Audio Pipeline ────────────────────────────────────────────────────

    private fun startAudioPipeline(peer: PeerDevice) {
        callStartTimestamp = System.currentTimeMillis()
        lastHeartbeatMs = System.currentTimeMillis()
        _callState.value = CallState.Connected(peer)

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true

        callWakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BTCall::CallWakeLock")
            .also { it.acquire(30 * 60 * 1000L) }

        // Start audio capture + playback engines
        lifecycleScope.launch {
            audioRepository.startPlayback().onFailure { Timber.e(it, "Playback start failed") }
            audioRepository.startCapture().onFailure  { Timber.e(it, "Capture start failed") }
        }

        // Send captured audio over WiFi Direct TCP socket
        audioSendJob = lifecycleScope.launch(Dispatchers.IO) {
            audioRepository.capturedAudioPackets.collect { packet ->
                bluetoothRepository.sendAudioData(packet.toBytes())
                    .onFailure { Timber.v("Audio send error (may be normal on hangup)") }
            }
        }

        // Receive audio from WiFi Direct and enqueue into jitter buffer
        audioReceiveJob = lifecycleScope.launch(Dispatchers.IO) {
            bluetoothRepository.incomingAudioData.collect { bytes ->
                val packet = AudioPacket.fromBytes(bytes)
                if (packet != null) audioRepository.enqueueForPlayback(packet)
            }
        }

        // Call timer — update duration every second
        callTimerJob = lifecycleScope.launch {
            while (true) {
                delay(1_000L)
                val elapsed = (System.currentTimeMillis() - callStartTimestamp) / 1000L
                _callState.update { s ->
                    if (s is CallState.Connected) s.copy(durationSeconds = elapsed) else s
                }
            }
        }

        // Observe WiFi Direct socket health — end call on drop
        audioDisconnectJob = lifecycleScope.launch {
            bluetoothRepository.audioConnected.first { it }   // wait for confirmed live
            bluetoothRepository.audioConnected.first { !it }  // detect drop
            val current = _callState.value
            if (current is CallState.Connected) {
                Timber.w("WiFi Direct audio socket dropped — ending call")
                endCallInternal(current.remotePeer, EndReason.CONNECTION_LOST, sendSignal = false)
            }
        }

        updateNotification("In call with ${peer.displayName}")
    }

    // ── Call Teardown ─────────────────────────────────────────────────────

    private suspend fun endCallInternal(
        peer: PeerDevice,
        reason: EndReason,
        sendSignal: Boolean
    ) {
        audioSendJob?.cancel()
        audioReceiveJob?.cancel()
        callTimerJob?.cancel()
        heartbeatJob?.cancel()
        audioDisconnectJob?.cancel()
        stopRingbackTone()
        stopRingtone()

        if (callWakeLock?.isHeld == true) callWakeLock?.release()
        callWakeLock = null

        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL

        _callState.value = CallState.Ended(reason)
        endCallUseCase(peer, currentDirection, callStartTimestamp, reason, sendSignal)
        resetToIdle()
    }

    private fun resetToIdle() {
        lifecycleScope.launch {
            delay(2_000L)
            _callState.value = CallState.Idle
        }
        lifecycleScope.launch {
            delay(1_000L)
            startBluetoothStack()
        }
        updateNotification("Ready")
    }

    // ── Ringtones ─────────────────────────────────────────────────────────

    private fun startRingtone() {
        stopRingtone()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        incomingRingtone = RingtoneManager.getRingtone(this, uri)?.also {
            it.isLooping = true
            it.play()
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
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(INCOMING_CALL_NOTIFICATION_ID)
    }

    // ── Notifications ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "Bluetooth call status"; setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(INCOMING_CALL_CHANNEL_ID, INCOMING_CALL_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming Bluetooth call alerts"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun buildIdleNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BTCall")
            .setContentText("Discoverable — waiting for calls")
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(status: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("BTCall")
                .setContentText(status)
                .setSmallIcon(R.drawable.ic_bluetooth)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )
    }
}
