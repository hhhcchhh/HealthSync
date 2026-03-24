package com.example.healthsync.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.healthsync.data.local.entity.HeartRateEntity
import com.example.healthsync.data.local.entity.SyncState
import kotlinx.coroutines.flow.Flow

@Dao
interface HeartRateDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: HeartRateEntity): Long

    @Query("SELECT * FROM heart_rate WHERE timestamp >= :since ORDER BY timestamp DESC")
    fun getRecentFlow(since: Long): Flow<List<HeartRateEntity>>

    @Query("SELECT * FROM heart_rate ORDER BY timestamp DESC LIMIT 1")
    fun getLatestFlow(): Flow<HeartRateEntity?>

    @Query("SELECT COUNT(*) FROM heart_rate WHERE syncState IN (:states)")
    fun countByStatesFlow(states: List<SyncState>): Flow<Int>

    @Query(
        "SELECT * FROM heart_rate WHERE syncState IN (:states) AND nextAttemptAt <= :now ORDER BY nextAttemptAt ASC LIMIT :limit"
    )
    suspend fun getPendingSync(
        states: List<SyncState> = listOf(SyncState.LOCAL_PENDING, SyncState.SYNC_FAILED),
        now: Long,
        limit: Int = 50
    ): List<HeartRateEntity>

    @Transaction
    suspend fun claimForSync(ids: List<Long>): Int {
        return markSyncing(ids, SyncState.LOCAL_PENDING, SyncState.SYNC_FAILED)
    }

    @Query(
        "UPDATE heart_rate SET syncState = 'SYNCING' WHERE id IN (:ids) AND syncState IN (:fromState1, :fromState2)"
    )
    suspend fun markSyncing(ids: List<Long>, fromState1: SyncState, fromState2: SyncState): Int

    @Query(
        "UPDATE heart_rate SET syncState = :state, remoteId = :remoteId, lastError = NULL, attemptCount = 0 WHERE id = :id"
    )
    suspend fun markSynced(id: Long, remoteId: String, state: SyncState = SyncState.SYNCED)

    @Query(
        "UPDATE heart_rate SET syncState = :state, attemptCount = :attemptCount, nextAttemptAt = :nextAttemptAt, lastError = :error WHERE id = :id"
    )
    suspend fun markFailed(id: Long, state: SyncState, attemptCount: Int, nextAttemptAt: Long, error: String?)

    @Query("UPDATE heart_rate SET syncState = 'LOCAL_PENDING' WHERE syncState = 'SYNCING'")
    suspend fun resetSyncingToLocal(): Int

    @Query("SELECT * FROM heart_rate ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<HeartRateEntity>>
}
