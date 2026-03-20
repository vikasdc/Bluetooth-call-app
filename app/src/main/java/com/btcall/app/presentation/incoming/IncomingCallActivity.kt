package com.btcall.app.presentation.incoming

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.btcall.app.data.bluetooth.BluetoothCallService
import com.btcall.app.databinding.ActivityIncomingCallBinding
import com.btcall.app.domain.model.CallState
import com.btcall.app.domain.model.PeerDevice
import com.btcall.app.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Screen 2: Incoming Call
 *
 * Shown when a CALL_REQUEST is received — appears over the lock screen.
 * Contains Accept and Reject buttons.
 *
 * This is a separate Activity (not a Fragment) so it can be shown
 * over the lock screen via [WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED].
 */
@AndroidEntryPoint
class IncomingCallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CALLER_ID   = "caller_id"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CALLER_MAC  = "caller_mac"
        private const val INCOMING_CALL_NOTIFICATION_ID = 1002
    }

    private lateinit var binding: ActivityIncomingCallBinding
    private val viewModel: IncomingCallViewModel by viewModels()

    private var callService: BluetoothCallService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            callService = (binder as BluetoothCallService.LocalBinder).getService()
            binding.btnAccept.isEnabled = true
            binding.btnReject.isEnabled = true
            observeCallState()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            callService = null
            binding.btnAccept.isEnabled = false
            binding.btnReject.isEnabled = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lock screen and turn on screen
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        binding = ActivityIncomingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val callerId   = intent.getStringExtra(EXTRA_CALLER_ID)   ?: ""
        val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "Unknown"
        val callerMac  = intent.getStringExtra(EXTRA_CALLER_MAC)  ?: ""

        viewModel.setCaller(callerId, callerName, callerMac)

        binding.tvCallerName.text = callerName
        binding.tvCallType.text = "Bluetooth Voice Call"

        // Disable until service is bound — prevents crash if user taps immediately
        binding.btnAccept.isEnabled = false
        binding.btnReject.isEnabled = false

        binding.btnAccept.setOnClickListener { acceptCall() }
        binding.btnReject.setOnClickListener { rejectCall() }

        // Activity is now visible — dismiss the incoming call notification from the shade
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(INCOMING_CALL_NOTIFICATION_ID)

        bindService(
            Intent(this, BluetoothCallService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )

        observeUiState()
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (state.isDecided) finish()
                }
            }
        }
    }

    private fun observeCallState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                callService?.callState?.collect { state ->
                    when (state) {
                        is CallState.Connected -> {
                            // Navigate to MainActivity which will show ActiveCallFragment
                            startActivity(
                                Intent(this@IncomingCallActivity, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                                }
                            )
                            finish()
                        }
                        is CallState.Ended -> {
                            // Accept flow failed (RFCOMM or signal error) — let user retry
                            binding.btnAccept.isEnabled = true
                            binding.btnReject.isEnabled = true
                            Toast.makeText(
                                this@IncomingCallActivity,
                                "Connection failed. Try accepting again.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        is CallState.Idle -> finish()
                        else -> {}
                    }
                }
            }
        }
    }

    private fun acceptCall() {
        val peer = viewModel.uiState.value.callerPeer ?: return
        Timber.d("User accepted call from ${peer.displayName}")
        // Disable buttons to prevent double-tap; do NOT call onDecisionMade() here —
        // that would trigger finish() immediately and kill observeCallState() before it
        // can detect Connected and navigate to MainActivity.
        binding.btnAccept.isEnabled = false
        binding.btnReject.isEnabled = false
        callService?.acceptCall(peer)
    }

    private fun rejectCall() {
        val peer = viewModel.uiState.value.callerPeer ?: return
        Timber.d("User rejected call from ${peer.displayName}")
        viewModel.onDecisionMade()
        callService?.rejectCall(peer)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (callService != null) unbindService(serviceConnection)
    }
}
