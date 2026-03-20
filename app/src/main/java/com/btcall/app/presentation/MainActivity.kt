package com.btcall.app.presentation

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.btcall.app.R
import com.btcall.app.data.bluetooth.BluetoothCallService
import com.btcall.app.databinding.ActivityMainBinding
import com.btcall.app.domain.model.CallState
import com.btcall.app.presentation.activecall.ActiveCallFragment
import com.btcall.app.presentation.nearby.NearbyDevicesFragment
import com.btcall.app.presentation.nearby.NearbyDevicesViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Single-activity host. Hosts fragments via Navigation Component.
 * Also owns the [BluetoothCallService] binding and passes it to fragments.
 *
 * Permission handling is centralised here:
 * - Android 12+: BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE
 * - All: RECORD_AUDIO, POST_NOTIFICATIONS (Android 13+)
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val nearbyVm: NearbyDevicesViewModel by viewModels()

    var callService: BluetoothCallService? = null
        private set
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localBinder = binder as BluetoothCallService.LocalBinder
            callService = localBinder.getService()
            serviceBound = true
            Timber.d("BluetoothCallService bound")
            observeCallState()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            callService = null
            serviceBound = false
        }
    }

    // ── Permission Request ─────────────────────────────────────────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startAndBindService()
        } else {
            Toast.makeText(
                this,
                "Bluetooth and microphone permissions are required for BTCall",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        nearbyVm.checkBluetooth()
        if (result.resultCode != RESULT_OK) {
            Toast.makeText(this, "Bluetooth is required for BTCall", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!hasRequiredPermissions()) {
            permissionLauncher.launch(requiredPermissions())
        } else {
            startAndBindService()
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, NearbyDevicesFragment())
                .commit()
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasRequiredPermissions() && !serviceBound) {
            bindToService()
        }
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    // ── Service Management ────────────────────────────────────────────────

    private fun startAndBindService() {
        val intent = Intent(this, BluetoothCallService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindToService()
    }

    private fun bindToService() {
        val intent = Intent(this, BluetoothCallService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // ── Call State Observer ────────────────────────────────────────────────

    private fun observeCallState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                callService?.callState?.collect { state ->
                    handleCallStateNavigation(state)
                }
            }
        }
    }

    private fun handleCallStateNavigation(state: CallState) {
        when (state) {
            is CallState.Calling, is CallState.Connected -> {
                // Show in-call screen for both outgoing (Calling) and connected states
                val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
                if (current !is ActiveCallFragment) {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, ActiveCallFragment())
                        .addToBackStack("active_call")
                        .commit()
                }
            }
            is CallState.Ended, is CallState.Idle -> {
                // Pop back to nearby devices
                supportFragmentManager.popBackStack("active_call",
                    androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
            }
            else -> {}
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
        } else {
            permissions += Manifest.permission.BLUETOOTH
            permissions += Manifest.permission.BLUETOOTH_ADMIN
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }

        return permissions.toTypedArray()
    }

    private fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }

    fun requestEnableBluetooth() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        enableBtLauncher.launch(intent)
    }
}
