package com.btcall.app.data.room

import androidx.room.Database
import androidx.room.RoomDatabase
import com.btcall.app.domain.model.CallRecord

@Database(
    entities = [CallRecord::class],
    version = 1,
    exportSchema = false
)
abstract class BTCallDatabase : RoomDatabase() {
    abstract fun callRecordDao(): CallRecordDao
}
