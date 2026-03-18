# BTCall ProGuard Rules

# Keep domain models (used by Room and serialisation)
-keep class com.btcall.app.domain.model.** { *; }

# Keep Bluetooth service (referenced from Manifest)
-keep class com.btcall.app.data.bluetooth.BluetoothCallService { *; }

# Keep Hilt generated code
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *

# Kotlin Coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }

# Timber
-dontwarn org.jetbrains.annotations.**
