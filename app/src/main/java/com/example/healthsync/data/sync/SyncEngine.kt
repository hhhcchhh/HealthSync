package com.example.healthsync.data.sync

import com.example.healthsync.data.local.dao.HeartRateDao
import com.example.healthsync.data.local.dao.StepCountDao
import com.example.healthsync.data.local.entity.SyncState
import com.example.healthsync.data.remote.HeartRateUploadItem
import com.example.healthsync.data.remote.MockCloudApi
import com.example.healthsync.data.remote.StepCountUploadItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同步引擎（Milestone 2/3/7，DESIGN §6）：扫描 outbox → **事务抢占** SYNCING → 调用 [MockCloudApi] → 回写状态或重试计划。
 *
 * - 待同步条件：`syncState in (LOCAL_PENDING, SYNC_FAILED)` 且 `nextAttemptAt <= now`
 * - 失败口径：`attemptCount` 为已失败次数；满 3 次进入 SYNC_FAILED（DESIGN §6.6）
 * - [recover]：启动时将 SYNCING 重置为 LOCAL_PENDING，避免杀进程后永远卡住（保留 attemptCount）
 */
@Singleton
class SyncEngine @Inject constructor(
    private val heartRateDao: HeartRateDao,
    private val stepCountDao: StepCountDao,
    private val cloudApi: MockCloudApi,
    private val retryPolicy: RetryPolicy,
    private val logger: SyncLogger
) {
    companion object {
        private const val TAG = "SyncEngine"
    }

    /**
     * 执行一轮同步：心率批次与步数批次各至多处理一批；任一批有工作则返回 true，供前台循环继续调度。
     */
    suspend fun syncOnce(): Boolean {
        val now = System.currentTimeMillis()
        var didWork = false

        if (syncHeartRates(now)) didWork = true
        if (syncStepCounts(now)) didWork = true

        return didWork
    }

    /**
     * 杀进程/崩溃恢复：全部 SYNCING → LOCAL_PENDING，**不**清零 attemptCount（DESIGN §6.4）。
     */
    suspend fun recover() {
        val hrReset = heartRateDao.resetSyncingToLocal()
        val scReset = stepCountDao.resetSyncingToLocal()
        logger.i(TAG, "recover: reset $hrReset heart-rate, $scReset step-count records")
    }

    /**
     * @return the earliest nextAttemptAt among pending records, or null if none.
     */
    suspend fun getEarliestPendingTime(): Long? {
        val now = System.currentTimeMillis()
        val states = listOf(SyncState.LOCAL_PENDING, SyncState.SYNC_FAILED)

        val hrPending = heartRateDao.getPendingSync(states, Long.MAX_VALUE, 1)
        val scPending = stepCountDao.getPendingSync(states, Long.MAX_VALUE, 1)

        val times = listOfNotNull(
            hrPending.firstOrNull()?.nextAttemptAt,
            scPending.firstOrNull()?.nextAttemptAt
        )
        return times.minOrNull()
    }

    private suspend fun syncHeartRates(now: Long): Boolean {
        val pending = heartRateDao.getPendingSync(
            states = listOf(SyncState.LOCAL_PENDING, SyncState.SYNC_FAILED),
            now = now
        )
        if (pending.isEmpty()) return false

        val ids = pending.map { it.id }
        val claimed = heartRateDao.claimForSync(ids)
        if (claimed == 0) return false

        val uploadItems = pending.map {
            HeartRateUploadItem(
                eventId = it.eventId,
                timestamp = it.timestamp,
                bpm = it.bpm,
                sourceId = it.sourceId
            )
        }

        try {
            val results = cloudApi.uploadHeartRates(uploadItems)
            pending.forEachIndexed { index, record ->
                val result = results.getOrNull(index)
                if (result != null) {
                    heartRateDao.markSynced(record.id, result.remoteId)
                }
            }
            logger.d(TAG, "syncHeartRates: ${pending.size} records synced")
        } catch (e: Exception) {
            logger.w(TAG, "syncHeartRates failed: ${e.message}", e)
            val errorMsg = e.message ?: "Unknown error"
            pending.forEach { record ->
                handleHeartRateFailure(record.id, record.attemptCount, errorMsg)
            }
        }
        return true
    }

    private suspend fun syncStepCounts(now: Long): Boolean {
        val pending = stepCountDao.getPendingSync(
            states = listOf(SyncState.LOCAL_PENDING, SyncState.SYNC_FAILED),
            now = now
        )
        if (pending.isEmpty()) return false

        val ids = pending.map { it.id }
        val claimed = stepCountDao.claimForSync(ids)
        if (claimed == 0) return false

        val uploadItems = pending.map {
            StepCountUploadItem(
                eventId = it.eventId,
                timestamp = it.timestamp,
                steps = it.steps,
                sourceId = it.sourceId
            )
        }

        try {
            val results = cloudApi.uploadStepCounts(uploadItems)
            pending.forEachIndexed { index, record ->
                val result = results.getOrNull(index)
                if (result != null) {
                    stepCountDao.markSynced(record.id, result.remoteId)
                }
            }
            logger.d(TAG, "syncStepCounts: ${pending.size} records synced")
        } catch (e: Exception) {
            logger.w(TAG, "syncStepCounts failed: ${e.message}", e)
            val errorMsg = e.message ?: "Unknown error"
            pending.forEach { record ->
                handleStepCountFailure(record.id, record.attemptCount, errorMsg)
            }
        }
        return true
    }

    private suspend fun handleHeartRateFailure(id: Long, currentAttemptCount: Int, error: String) {
        val newAttemptCount = currentAttemptCount + 1
        if (!retryPolicy.shouldRetry(newAttemptCount)) {
            heartRateDao.markFailed(
                id = id,
                state = SyncState.SYNC_FAILED,
                attemptCount = newAttemptCount,
                nextAttemptAt = 0,
                error = error
            )
        } else {
            val delay = retryPolicy.nextDelay(currentAttemptCount)
            heartRateDao.markFailed(
                id = id,
                state = SyncState.LOCAL_PENDING,
                attemptCount = newAttemptCount,
                nextAttemptAt = System.currentTimeMillis() + delay,
                error = error
            )
        }
    }

    private suspend fun handleStepCountFailure(id: Long, currentAttemptCount: Int, error: String) {
        val newAttemptCount = currentAttemptCount + 1
        if (!retryPolicy.shouldRetry(newAttemptCount)) {
            stepCountDao.markFailed(
                id = id,
                state = SyncState.SYNC_FAILED,
                attemptCount = newAttemptCount,
                nextAttemptAt = 0,
                error = error
            )
        } else {
            val delay = retryPolicy.nextDelay(currentAttemptCount)
            stepCountDao.markFailed(
                id = id,
                state = SyncState.LOCAL_PENDING,
                attemptCount = newAttemptCount,
                nextAttemptAt = System.currentTimeMillis() + delay,
                error = error
            )
        }
    }
}
