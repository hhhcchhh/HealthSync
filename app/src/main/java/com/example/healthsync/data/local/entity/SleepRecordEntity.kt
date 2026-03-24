package com.example.healthsync.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sleep_record")
data class SleepRecordEntity(
    @PrimaryKey val id: String,
    val startTime: Long,
    val endTime: Long,
    val quality: SleepQuality,
    val sourceId: String,
    val syncState: SyncState = SyncState.LOCAL_PENDING,
    val attemptCount: Int = 0,
    val nextAttemptAt: Long = 0L,
    val lastError: String? = null,
    val remoteId: String? = null,
    val remoteVersion: Int = 0,
    val baseRemoteVersion: Int = 0,
    val localVersion: Int = 1,
    val serverSnapshot: String? = null
)
