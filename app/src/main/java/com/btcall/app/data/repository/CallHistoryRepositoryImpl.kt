package com.btcall.app.data.repository

import com.btcall.app.data.room.CallRecordDao
import com.btcall.app.domain.model.CallRecord
import com.btcall.app.domain.repository.CallHistoryRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallHistoryRepositoryImpl @Inject constructor(
    private val dao: CallRecordDao
) : CallHistoryRepository {
    override fun getAllCallRecords(): Flow<List<CallRecord>> = dao.getAllRecords()
    override suspend fun insertCallRecord(record: CallRecord): Long = dao.insert(record)
    override suspend fun deleteAllCallRecords() = dao.deleteAll()
}
