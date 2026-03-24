package com.example.healthsync.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.healthsync.data.local.entity.SleepRecordEntity
import com.example.healthsync.data.local.entity.SyncState
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SleepRecordEntity)

    @Query("SELECT * FROM sleep_record ORDER BY startTime DESC")
    fun getAllFlow(): Flow<List<SleepRecordEntity>>

    @Query("SELECT * FROM sleep_record ORDER BY endTime DESC LIMIT 1")
    fun getLatestFlow(): Flow<SleepRecordEntity?>

    @Query("SELECT * FROM sleep_record WHERE id = :id")
    suspend fun getById(id: String): SleepRecordEntity?

    @Query("SELECT * FROM sleep_record WHERE syncState = 'CONFLICT'")
    fun getConflictsFlow(): Flow<List<SleepRecordEntity>>

    @Query("SELECT COUNT(*) FROM sleep_record WHERE syncState IN (:states)")
    fun countByStatesFlow(states: List<SyncState>): Flow<Int>

    @Query(
        "SELECT * FROM sleep_record WHERE syncState IN (:states) AND nextAttemptAt <= :now ORDER BY nextAttemptAt ASC LIMIT :limit"
    )
    suspend fun getPendingSync(
        states: List<SyncState> = listOf(SyncState.LOCAL_PENDING, SyncState.SYNC_FAILED),
        now: Long,
        limit: Int = 10
    ): List<SleepRecordEntity>

    @Transaction
    suspend fun claimForSync(ids: List<String>): Int {
        return markSyncing(ids, SyncState.LOCAL_PENDING, SyncState.SYNC_FAILED)
    }

    @Query(
        "UPDATE sleep_record SET syncState = 'SYNCING' WHERE id IN (:ids) AND syncState IN (:fromState1, :fromState2)"
    )
    suspend fun markSyncing(ids: List<String>, fromState1: SyncState, fromState2: SyncState): Int

    @Query(
        "UPDATE sleep_record SET syncState = :state, remoteId = :remoteId, remoteVersion = :remoteVersion, lastError = NULL, attemptCount = 0 WHERE id = :id"
    )
    suspend fun markSynced(id: String, remoteId: String, remoteVersion: Int, state: SyncState = SyncState.SYNCED)

    @Query(
        "UPDATE sleep_record SET syncState = :state, attemptCount = :attemptCount, nextAttemptAt = :nextAttemptAt, lastError = :error WHERE id = :id"
    )
    suspend fun markFailed(id: String, state: SyncState, attemptCount: Int, nextAttemptAt: Long, error: String?)

    @Query(
        "UPDATE sleep_record SET syncState = 'CONFLICT', serverSnapshot = :snapshot WHERE id = :id"
    )
    suspend fun markConflict(id: String, snapshot: String)

    @Query("UPDATE sleep_record SET syncState = 'LOCAL_PENDING' WHERE syncState = 'SYNCING'")
    suspend fun resetSyncingToLocal(): Int

    @Query(
        "UPDATE sleep_record SET syncState = :syncState, startTime = :startTime, endTime = :endTime, quality = :quality, baseRemoteVersion = :baseRemoteVersion, remoteVersion = :remoteVersion, localVersion = localVersion + 1, serverSnapshot = NULL, attemptCount = 0, nextAttemptAt = 0, lastError = NULL WHERE id = :id"
    )
    suspend fun resolveConflict(
        id: String,
        syncState: SyncState,
        startTime: Long,
        endTime: Long,
        quality: String,
        baseRemoteVersion: Int,
        remoteVersion: Int
    )
}
