package com.btcall.app.data.bluetooth

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides a stable UUID for this device that persists across app restarts.
 *
 * On Android 10+, the Bluetooth MAC address is randomized, so we generate
 * and store our own stable UUID in SharedPreferences.
 */
@Singleton
class DeviceIdProvider @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("btcall_prefs", Context.MODE_PRIVATE)

    private val _deviceId: String by lazy {
        prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
            newId
        }
    }

    fun getDeviceId(): String = _deviceId

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
    }
}
