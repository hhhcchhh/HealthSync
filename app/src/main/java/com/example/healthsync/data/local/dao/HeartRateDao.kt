package com.example.healthsync.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.healthsync.data.local.entity.HeartRateEntity
import com.example.healthsync.data.local.entity.SyncState
import kotlinx.coroutines.flow.Flow

/**
 * 心率 DAO（Data Access Object，Milestone 1/2/3，DESIGN §5.2）。
 * 提供 Room 操作接口：读路径（Flow 响应式查询）+ 写路径（同步状态转换）。
 */
@Dao
interface HeartRateDao {

    /**
     * 插入心率记录（onConflict=IGNORE 用于去重：如果 eventId 已存在则忽略）。
     * 由 Repository 调用，将 [HealthEvent.HeartRateSample] 转换为 Entity 并落库。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: HeartRateEntity): Long

    /**
     * 查询指定时间戳之后的所有心率记录，返回响应式 Flow。
     * UI/ViewModel 订阅该 Flow 以获得最新数据（实时心率展示，Milestone 1）。
     */
    @Query("SELECT * FROM heart_rate WHERE timestamp >= :since ORDER BY timestamp DESC")
    fun getRecentFlow(since: Long): Flow<List<HeartRateEntity>>

    /**
     * 查询最新的单条心率记录。用于大字显示当前 BPM（Milestone 6）。
     */
    @Query("SELECT * FROM heart_rate ORDER BY timestamp DESC LIMIT 1")
    fun getLatestFlow(): Flow<HeartRateEntity?>

    /**
     * 统计指定同步状态的记录数。用于可观测性：UI 展示"X 条待同步"（Milestone 2）。
     */
    @Query("SELECT COUNT(*) FROM heart_rate WHERE syncState IN (:states)")
    fun countByStatesFlow(states: List<SyncState>): Flow<Int>

    /**
     * 查询待同步的记录。条件：
     * - syncState 为 LOCAL_PENDING 或 SYNC_FAILED
     * - nextAttemptAt <= now（不违反指数退避计划）
     *
     * 由 SyncEngine 周期扫描调用（Milestone 2）。
     * @param states 待同步的状态列表
     * @param now 当前时间戳
     * @param limit 单次批量大小（默认 50）
     */
    @Query(
        "SELECT * FROM heart_rate WHERE syncState IN (:states) AND nextAttemptAt <= :now ORDER BY nextAttemptAt ASC LIMIT :limit"
    )
    suspend fun getPendingSync(
        states: List<SyncState> = listOf(SyncState.LOCAL_PENDING, SyncState.SYNC_FAILED),
        now: Long,
        limit: Int = 50
    ): List<HeartRateEntity>

    /**
     * 事务式抢占任务：原子地将 LOCAL_PENDING/SYNC_FAILED 的记录标记为 SYNCING。
     * 防止多个同步任务（WorkManager + 前台循环）重复处理同一批记录（Milestone 3）。
     * DESIGN §6.2 第 2 步说明：抢占必须在事务内完成。
     */
    @Transaction
    suspend fun claimForSync(ids: List<Long>): Int {
        return markSyncing(ids, SyncState.LOCAL_PENDING, SyncState.SYNC_FAILED)
    }

    /**
     * 实际执行状态转换（从 LOCAL_PENDING/SYNC_FAILED → SYNCING）。
     * 返回成功更新的行数。
     */
    @Query(
        "UPDATE heart_rate SET syncState = 'SYNCING' WHERE id IN (:ids) AND syncState IN (:fromState1, :fromState2)"
    )
    suspend fun markSyncing(ids: List<Long>, fromState1: SyncState, fromState2: SyncState): Int

    /**
     * 标记同步成功：状态转为 SYNCED，写入 remoteId，清除错误信息与重试计数。
     * 由 SyncEngine 调用（DESIGN §6.2 第 4 步）。
     */
    @Query(
        "UPDATE heart_rate SET syncState = :state, remoteId = :remoteId, lastError = NULL, attemptCount = 0 WHERE id = :id"
    )
    suspend fun markSynced(id: Long, remoteId: String, state: SyncState = SyncState.SYNCED)

    /**
     * 标记同步失败：更新状态、递增 attemptCount、计算下次重试时间、记录错误。
     * 由 SyncEngine 在网络错误时调用（DESIGN §6.2 第 6-7 步）。
     *
     * @param state LOCAL_PENDING（仍有重试机会）或 SYNC_FAILED（已达上限）
     * @param attemptCount 新的失败次数
     * @param nextAttemptAt 下次允许重试的时间戳（按指数退避计算）
     * @param error 错误信息（供调试）
     */
    @Query(
        "UPDATE heart_rate SET syncState = :state, attemptCount = :attemptCount, nextAttemptAt = :nextAttemptAt, lastError = :error WHERE id = :id"
    )
    suspend fun markFailed(id: Long, state: SyncState, attemptCount: Int, nextAttemptAt: Long, error: String?)

    /**
     * 杀进程恢复：将所有 SYNCING 的记录重置为 LOCAL_PENDING，保留 attemptCount。
     * Application.onCreate 中由 SyncCoordinator.recover() 调用（Milestone 3，DESIGN §6.4）。
     * 返回重置的行数。
     */
    @Query("UPDATE heart_rate SET syncState = 'LOCAL_PENDING' WHERE syncState = 'SYNCING'")
    suspend fun resetSyncingToLocal(): Int

    /**
     * 查询所有心率记录。用于历史页展示（Milestone 6）。
     */
    @Query("SELECT * FROM heart_rate ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<HeartRateEntity>>
}
