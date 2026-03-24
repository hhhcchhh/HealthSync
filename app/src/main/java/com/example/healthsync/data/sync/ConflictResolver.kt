package com.example.healthsync.data.sync

import com.example.healthsync.data.local.dao.SleepRecordDao
import com.example.healthsync.data.local.entity.SleepQuality
import com.example.healthsync.data.local.entity.SleepRecordEntity
import com.example.healthsync.data.local.entity.SyncState
import com.example.healthsync.data.remote.ConflictResponse
import com.google.gson.Gson
import javax.inject.Inject
import javax.inject.Singleton

enum class ConflictResolution {
    KEEP_LOCAL, KEEP_REMOTE, MERGE
}

@Singleton
class ConflictResolver @Inject constructor(
    private val sleepRecordDao: SleepRecordDao,
    private val gson: Gson
) {

    suspend fun handleConflict(recordId: String, conflict: ConflictResponse) {
        val snapshot = gson.toJson(conflict.serverData)
        sleepRecordDao.markConflict(recordId, snapshot)
    }

    suspend fun resolveConflict(recordId: String, resolution: ConflictResolution) {
        val record = sleepRecordDao.getById(recordId) ?: return
        val serverData = record.serverSnapshot?.let {
            gson.fromJson(it, com.example.healthsync.data.remote.ServerSleepData::class.java)
        }

        when (resolution) {
            ConflictResolution.KEEP_LOCAL -> {
                val serverVersion = serverData?.remoteVersion ?: record.remoteVersion
                sleepRecordDao.resolveConflict(
                    id = recordId,
                    syncState = SyncState.LOCAL_PENDING,
                    startTime = record.startTime,
                    endTime = record.endTime,
                    quality = record.quality.name,
                    baseRemoteVersion = serverVersion,
                    remoteVersion = serverVersion
                )
            }

            ConflictResolution.KEEP_REMOTE -> {
                if (serverData != null) {
                    sleepRecordDao.resolveConflict(
                        id = recordId,
                        syncState = SyncState.SYNCED,
                        startTime = serverData.startTime,
                        endTime = serverData.endTime,
                        quality = serverData.quality,
                        baseRemoteVersion = serverData.remoteVersion,
                        remoteVersion = serverData.remoteVersion
                    )
                }
            }

            ConflictResolution.MERGE -> {
                if (serverData != null) {
                    val mergedStartTime = minOf(record.startTime, serverData.startTime)
                    val mergedEndTime = maxOf(record.endTime, serverData.endTime)
                    val mergedQuality = record.quality.name

                    sleepRecordDao.resolveConflict(
                        id = recordId,
                        syncState = SyncState.LOCAL_PENDING,
                        startTime = mergedStartTime,
                        endTime = mergedEndTime,
                        quality = mergedQuality,
                        baseRemoteVersion = serverData.remoteVersion,
                        remoteVersion = serverData.remoteVersion
                    )
                }
            }
        }
    }
}
