package com.btcall.app.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent call history record stored in Room database.
 */
@Entity(tableName = "call_records")
data class CallRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val remotePeerId: String,
    val remotePeerName: String,
    val direction: CallDirection,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val durationSeconds: Long,
    val endReason: EndReason
)

enum class CallDirection { OUTGOING, INCOMING }
