package com.example.healthsync.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "heart_rate",
    indices = [Index(value = ["eventId"], unique = true)]
)
data class HeartRateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val bpm: Int,
    val sourceId: String,
    val eventId: String,
    val syncState: SyncState = SyncState.LOCAL_PENDING,
    val attemptCount: Int = 0,
    val nextAttemptAt: Long = 0L,
    val lastError: String? = null,
    val remoteId: String? = null
)
