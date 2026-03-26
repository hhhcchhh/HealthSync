package com.example.healthsync.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.healthsync.data.local.entity.StepCountEntity
import com.example.healthsync.data.local.entity.SyncState
import kotlinx.coroutines.flow.Flow

/**
 * 步数 DAO（Milestone 1/2/3）。
 * 接口与 [HeartRateDao] 类似，但 getTotalStepsSinceFlow 用聚合而非列表（便于 UI 显示总步数）。
 */
@Dao
interface StepCountDao {

    /**
     * 插入步数记录。onConflict=IGNORE 用于去重。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: StepCountEntity): Long

    /**
     * 查询指定时间戳之后的所有步数记录。
     */
    @Query("SELECT * FROM step_count WHERE timestamp >= :since ORDER BY timestamp DESC")
    fun getRecentFlow(since: Long): Flow<List<StepCountEntity>>

    /**
     * 查询指定时间戳之后的步数总和。便于 UI 显示"今日步数"。
     * 使用 SQL 聚合而非获取列表后求和，提高查询性能。
     */
    @Query("SELECT COALESCE(SUM(steps), 0) FROM step_count WHERE timestamp >= :since")
    fun getTotalStepsSinceFlow(since: Long): Flow<Int>

    /**
     * 统计待同步的记录数。
     */
    @Query("SELECT COUNT(*) FROM step_count WHERE syncState IN (:states)")
    fun countByStatesFlow(states: List<SyncState>): Flow<Int>

    /**
     * 查询待同步的记录。条件与 HeartRateDao 相同。
     */
    @Query(
        "SELECT * FROM step_count WHERE syncState IN (:states) AND nextAttemptAt <= :now ORDER BY nextAttemptAt ASC LIMIT :limit"
    )
    suspend fun getPendingSync(
        states: List<SyncState> = listOf(SyncState.LOCAL_PENDING, SyncState.SYNC_FAILED),
        now: Long,
        limit: Int = 50
    ): List<StepCountEntity>

    /**
     * 事务式抢占任务（同 HeartRateDao）。
     */
    @Transaction
    suspend fun claimForSync(ids: List<Long>): Int {
        return markSyncing(ids, SyncState.LOCAL_PENDING, SyncState.SYNC_FAILED)
    }

    /**
     * 标记为同步中。
     */
    @Query(
        "UPDATE step_count SET syncState = 'SYNCING' WHERE id IN (:ids) AND syncState IN (:fromState1, :fromState2)"
    )
    suspend fun markSyncing(ids: List<Long>, fromState1: SyncState, fromState2: SyncState): Int

    /**
     * 标记同步成功。
     */
    @Query(
        "UPDATE step_count SET syncState = :state, remoteId = :remoteId, lastError = NULL, attemptCount = 0 WHERE id = :id"
    )
    suspend fun markSynced(id: Long, remoteId: String, state: SyncState = SyncState.SYNCED)

    /**
     * 标记同步失败。
     */
    @Query(
        "UPDATE step_count SET syncState = :state, attemptCount = :attemptCount, nextAttemptAt = :nextAttemptAt, lastError = :error WHERE id = :id"
    )
    suspend fun markFailed(id: Long, state: SyncState, attemptCount: Int, nextAttemptAt: Long, error: String?)

    /**
     * 杀进程恢复。
     */
    @Query("UPDATE step_count SET syncState = 'LOCAL_PENDING' WHERE syncState = 'SYNCING'")
    suspend fun resetSyncingToLocal(): Int

    /**
     * 取消/中断兜底：将指定 id 且仍处于 SYNCING 的记录释放回 LOCAL_PENDING。
     * 用于防止 claim 后被取消导致记录长期卡在 SYNCING 而不再被扫描。
     */
    @Query("UPDATE step_count SET syncState = 'LOCAL_PENDING' WHERE id IN (:ids) AND syncState = 'SYNCING'")
    suspend fun releaseSyncingByIds(ids: List<Long>): Int

    /**
     * 查询所有步数记录。
     */
    @Query("SELECT * FROM step_count ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<StepCountEntity>>
}
