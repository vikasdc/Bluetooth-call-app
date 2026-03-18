package com.btcall.app.domain.repository

import com.btcall.app.domain.model.CallRecord
import kotlinx.coroutines.flow.Flow

interface CallHistoryRepository {
    fun getAllCallRecords(): Flow<List<CallRecord>>
    suspend fun insertCallRecord(record: CallRecord): Long
    suspend fun deleteAllCallRecords()
}
