package com.btcall.app.di

import android.content.Context
import androidx.room.Room
import com.btcall.app.data.audio.AudioCaptureEngine
import com.btcall.app.data.audio.AudioPlaybackEngine
import com.btcall.app.data.audio.JitterBuffer
import com.btcall.app.data.bluetooth.BleAdvertiser
import com.btcall.app.data.bluetooth.BleScanner
import com.btcall.app.data.bluetooth.DeviceIdProvider
import com.btcall.app.data.bluetooth.GattServer
import com.btcall.app.data.wifi.WifiDirectManager
import com.btcall.app.data.repository.AudioRepositoryImpl
import com.btcall.app.data.repository.BluetoothRepositoryImpl
import com.btcall.app.data.repository.CallHistoryRepositoryImpl
import com.btcall.app.data.room.BTCallDatabase
import com.btcall.app.data.room.CallRecordDao
import com.btcall.app.domain.repository.AudioRepository
import com.btcall.app.domain.repository.BluetoothRepository
import com.btcall.app.domain.repository.CallHistoryRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BTCallDatabase {
        return Room.databaseBuilder(
            context,
            BTCallDatabase::class.java,
            "btcall_db"
        ).build()
    }

    @Provides
    fun provideCallRecordDao(db: BTCallDatabase): CallRecordDao = db.callRecordDao()
}

@Module
@InstallIn(SingletonComponent::class)
object AudioModule {

    @Provides
    @Singleton
    fun provideJitterBuffer(): JitterBuffer = JitterBuffer()

    @Provides
    @Singleton
    fun provideAudioCaptureEngine(): AudioCaptureEngine = AudioCaptureEngine()

    @Provides
    @Singleton
    fun provideAudioPlaybackEngine(jitterBuffer: JitterBuffer): AudioPlaybackEngine =
        AudioPlaybackEngine(jitterBuffer)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindBluetoothRepository(impl: BluetoothRepositoryImpl): BluetoothRepository

    @Binds
    @Singleton
    abstract fun bindAudioRepository(impl: AudioRepositoryImpl): AudioRepository

    @Binds
    @Singleton
    abstract fun bindCallHistoryRepository(impl: CallHistoryRepositoryImpl): CallHistoryRepository
}
