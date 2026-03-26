package com.example.healthsync.data.sync

import com.example.healthsync.data.local.dao.HeartRateDao
import com.example.healthsync.data.local.dao.SleepRecordDao
import com.example.healthsync.data.local.dao.StepCountDao
import com.example.healthsync.data.local.entity.SyncState
import com.example.healthsync.data.remote.ApiConflictException
import com.example.healthsync.data.remote.HeartRateUploadItem
import com.example.healthsync.data.remote.MockCloudApi
import com.example.healthsync.data.remote.SleepRecordUploadRequest
import com.example.healthsync.data.remote.StepCountUploadItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同步引擎（Milestone 2/3/5/7，DESIGN §6）：扫描 outbox → **事务抢占** SYNCING → 调用 [MockCloudApi] → 回写状态或重试计划。
 *
 * - 待同步条件：`syncState in (LOCAL_PENDING, SYNC_FAILED)` 且 `nextAttemptAt <= now`
 * - 失败口径：`attemptCount` 为已失败次数；满 3 次进入 SYNC_FAILED（DESIGN §6.6）
 * - 睡眠记录额外支持 409 冲突检测，委托 [ConflictResolver] 处理（DESIGN §7）
 * - [recover]：启动时将 SYNCING 重置为 LOCAL_PENDING，避免杀进程后永远卡住（保留 attemptCount）
 */
@Singleton
class SyncEngine @Inject constructor(
    private val heartRateDao: HeartRateDao,
    private val stepCountDao: StepCountDao,
    private val sleepRecordDao: SleepRecordDao,
    private val cloudApi: MockCloudApi,
    private val retryPolicy: RetryPolicy,
    private val conflictResolver: ConflictResolver,
    private val logger: SyncLogger
) {
    companion object {
        private const val TAG = "SyncEngine"
    }

    suspend fun syncOnce(): Boolean {
        val now = System.currentTimeMillis()
        var didWork = false

        if (syncHeartRates(now)) didWork = true
        if (syncStepCounts(now)) didWork = true
        if (syncSleepRecords(now)) didWork = true

        return didWork
    }

    suspend fun recover() {
        val hrReset = heartRateDao.resetSyncingToLocal()
        val scReset = stepCountDao.resetSyncingToLocal()
        val slReset = sleepRecordDao.resetSyncingToLocal()
        logger.i(TAG, "recover: reset $hrReset heart-rate, $scReset step-count, $slReset sleep records")
    }

    suspend fun getEarliestPendingTime(): Long? {
        val states = listOf(SyncState.LOCAL_PENDING, SyncState.SYNC_FAILED)

        val hrPending = heartRateDao.getPendingSync(states, Long.MAX_VALUE, 1)
        val scPending = stepCountDao.getPendingSync(states, Long.MAX_VALUE, 1)
        val slPending = sleepRecordDao.getPendingSync(states, Long.MAX_VALUE, 1)

        val times = listOfNotNull(
            hrPending.firstOrNull()?.nextAttemptAt,
            scPending.firstOrNull()?.nextAttemptAt,
            slPending.firstOrNull()?.nextAttemptAt
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

    private suspend fun syncSleepRecords(now: Long): Boolean {
        val pending = sleepRecordDao.getPendingSync(
            states = listOf(SyncState.LOCAL_PENDING, SyncState.SYNC_FAILED),
            now = now
        )
        if (pending.isEmpty()) return false

        val ids = pending.map { it.id }
        val claimed = sleepRecordDao.claimForSync(ids)
        if (claimed == 0) return false

        for (record in pending) {
            try {
                val request = SleepRecordUploadRequest(
                    id = record.id,
                    startTime = record.startTime,
                    endTime = record.endTime,
                    quality = record.quality.name,
                    baseRemoteVersion = record.baseRemoteVersion
                )
                val result = cloudApi.uploadSleepRecord(request)
                sleepRecordDao.markSynced(record.id, result.remoteId, result.remoteVersion)
                logger.d(TAG, "syncSleepRecords: record ${record.id} synced (v${result.remoteVersion})")
            } catch (e: ApiConflictException) {
                logger.w(TAG, "syncSleepRecords: conflict on ${record.id}", e)
                conflictResolver.handleConflict(record.id, e.conflict)
            } catch (e: Exception) {
                logger.w(TAG, "syncSleepRecords failed for ${record.id}: ${e.message}", e)
                handleSleepRecordFailure(record.id, record.attemptCount, e.message ?: "Unknown error")
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

    private suspend fun handleSleepRecordFailure(id: String, currentAttemptCount: Int, error: String) {
        val newAttemptCount = currentAttemptCount + 1
        if (!retryPolicy.shouldRetry(newAttemptCount)) {
            sleepRecordDao.markFailed(
                id = id,
                state = SyncState.SYNC_FAILED,
                attemptCount = newAttemptCount,
                nextAttemptAt = 0,
                error = error
            )
        } else {
            val delay = retryPolicy.nextDelay(currentAttemptCount)
            sleepRecordDao.markFailed(
                id = id,
                state = SyncState.LOCAL_PENDING,
                attemptCount = newAttemptCount,
                nextAttemptAt = System.currentTimeMillis() + delay,
                error = error
            )
        }
    }
}
