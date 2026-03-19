package com.btcall.app.data.bluetooth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * Starts [BluetoothCallService] automatically after the device boots or the
 * app is updated, so incoming calls can be received without opening the app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Timber.d("BootReceiver: starting BluetoothCallService after ${intent.action}")
                val serviceIntent = Intent(context, BluetoothCallService::class.java)
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to start BluetoothCallService from boot")
                }
            }
        }
    }
}
