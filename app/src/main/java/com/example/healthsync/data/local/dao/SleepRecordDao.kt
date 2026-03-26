package com.example.healthsync.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.healthsync.data.local.entity.SleepRecordEntity
import com.example.healthsync.data.local.entity.SyncState
import kotlinx.coroutines.flow.Flow

/**
 * 睡眠记录 DAO（Milestone 5，DESIGN §5.2/§7）。
 *
 * 除去重字段外，提供冲突处理支持：
 * - [getConflictsFlow]：查询所有冲突记录，用于 UI 提示用户解决
 * - [markConflict]：标记为冲突状态，并保存服务端快照
 * - [resolveConflict]：解决冲突后更新记录
 */
@Dao
interface SleepRecordDao {

    /**
     * upsert（插入或替换）睡眠记录。
     * onConflict=REPLACE 用于支持离线编辑已同步的记录（覆盖旧版本）。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SleepRecordEntity)

    /**
     * 查询所有睡眠记录，按开始时间倒序。
     */
    @Query("SELECT * FROM sleep_record ORDER BY startTime DESC")
    fun getAllFlow(): Flow<List<SleepRecordEntity>>

    /**
     * 查询最新的单条睡眠记录（按结束时间）。
     * 用于概览页显示"最近一次睡眠"。
     */
    @Query("SELECT * FROM sleep_record ORDER BY endTime DESC LIMIT 1")
    fun getLatestFlow(): Flow<SleepRecordEntity?>

    /**
     * 按 ID 查询单条睡眠记录（同步查询）。用于冲突解决时读取当前状态。
     */
    @Query("SELECT * FROM sleep_record WHERE id = :id")
    suspend fun getById(id: String): SleepRecordEntity?

    /**
     * 查询所有冲突的睡眠记录。UI 会订阅该 Flow，显示需要用户处理的冲突列表。
     */
    @Query("SELECT * FROM sleep_record WHERE syncState = 'CONFLICT'")
    fun getConflictsFlow(): Flow<List<SleepRecordEntity>>

    /**
     * 统计指定同步状态的记录数。用于可观测性。
     */
    @Query("SELECT COUNT(*) FROM sleep_record WHERE syncState IN (:states)")
    fun countByStatesFlow(states: List<SyncState>): Flow<Int>

    /**
     * 查询待同步的睡眠记录。条件同 HeartRateDao。
     * 注意 limit 默认为 10（比心率/步数更小），因为睡眠记录编辑较少。
     */
    @Query(
        "SELECT * FROM sleep_record WHERE syncState IN (:states) AND nextAttemptAt <= :now ORDER BY nextAttemptAt ASC LIMIT :limit"
    )
    suspend fun getPendingSync(
        states: List<SyncState> = listOf(SyncState.LOCAL_PENDING, SyncState.SYNC_FAILED),
        now: Long,
        limit: Int = 10
    ): List<SleepRecordEntity>

    /**
     * 事务式抢占任务。注意 ID 类型为 String（业务主键）。
     */
    @Transaction
    suspend fun claimForSync(ids: List<String>): Int {
        return markSyncing(ids, SyncState.LOCAL_PENDING, SyncState.SYNC_FAILED)
    }

    /**
     * 标记为同步中。
     */
    @Query(
        "UPDATE sleep_record SET syncState = 'SYNCING' WHERE id IN (:ids) AND syncState IN (:fromState1, :fromState2)"
    )
    suspend fun markSyncing(ids: List<String>, fromState1: SyncState, fromState2: SyncState): Int

    /**
     * 标记同步成功：状态转为 SYNCED，更新 remoteId 与 remoteVersion，清除错误信息。
     */
    @Query(
        "UPDATE sleep_record SET syncState = :state, remoteId = :remoteId, remoteVersion = :remoteVersion, lastError = NULL, attemptCount = 0 WHERE id = :id"
    )
    suspend fun markSynced(id: String, remoteId: String, remoteVersion: Int, state: SyncState = SyncState.SYNCED)

    /**
     * 标记同步失败。
     */
    @Query(
        "UPDATE sleep_record SET syncState = :state, attemptCount = :attemptCount, nextAttemptAt = :nextAttemptAt, lastError = :error WHERE id = :id"
    )
    suspend fun markFailed(id: String, state: SyncState, attemptCount: Int, nextAttemptAt: Long, error: String?)

    /**
     * 标记冲突：状态转为 CONFLICT，保存服务端快照（JSON 字符串），用于后续冲突解决。
     * 由 SyncEngine 在收到 HTTP 409 时调用（DESIGN §7.2）。
     */
    @Query(
        "UPDATE sleep_record SET syncState = 'CONFLICT', serverSnapshot = :snapshot WHERE id = :id"
    )
    suspend fun markConflict(id: String, snapshot: String)

    /**
     * 杀进程恢复。
     */
    @Query("UPDATE sleep_record SET syncState = 'LOCAL_PENDING' WHERE syncState = 'SYNCING'")
    suspend fun resetSyncingToLocal(): Int

    /**
     * 取消/中断兜底：将指定 id 且仍处于 SYNCING 的记录释放回 LOCAL_PENDING。
     * 用于防止 claim 后被取消导致记录长期卡在 SYNCING 而不再被扫描。
     */
    @Query("UPDATE sleep_record SET syncState = 'LOCAL_PENDING' WHERE id IN (:ids) AND syncState = 'SYNCING'")
    suspend fun releaseSyncingByIds(ids: List<String>): Int

    /**
     * 解决冲突：更新记录内容（startTime/endTime/quality）并回到 LOCAL_PENDING 或 SYNCED 状态，
     * 同时递增 localVersion、更新 baseRemoteVersion、清除 serverSnapshot。
     * 由 UseCase/UI 调用以完成冲突解决流程（DESIGN §7.2）。
     *
     * @param id 睡眠记录 ID
     * @param syncState 解决后的状态：LOCAL_PENDING（需再次同步）或 SYNCED（采用服务端版本）
     * @param startTime 新的开始时间
     * @param endTime 新的结束时间
     * @param quality 新的睡眠质量（字符串）
     * @param baseRemoteVersion 本次编辑基于的云端版本号
     * @param remoteVersion 当前云端版本号
     */
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
