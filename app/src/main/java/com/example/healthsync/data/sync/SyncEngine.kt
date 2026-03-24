package com.example.healthsync.data.sync

import android.util.Log
import com.example.healthsync.data.local.dao.HeartRateDao
import com.example.healthsync.data.local.dao.StepCountDao
import com.example.healthsync.data.local.entity.SyncState
import com.example.healthsync.data.remote.HeartRateUploadItem
import com.example.healthsync.data.remote.MockCloudApi
import com.example.healthsync.data.remote.StepCountUploadItem
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncEngine @Inject constructor(
    private val heartRateDao: HeartRateDao,
    private val stepCountDao: StepCountDao,
    private val cloudApi: MockCloudApi,
    private val retryPolicy: RetryPolicy
) {
    companion object {
        private const val TAG = "SyncEngine"
    }

    /**
     * Runs one sync pass: scan pending → claim → upload → write back.
     * @return true if any records were processed (caller may loop again).
     */
    suspend fun syncOnce(): Boolean {
        val now = System.currentTimeMillis()
        var didWork = false

        if (syncHeartRates(now)) didWork = true
        if (syncStepCounts(now)) didWork = true

        return didWork
    }

    /**
     * Resets all SYNCING records to LOCAL_PENDING (preserving attemptCount).
     * Called on app startup to recover from interrupted syncs.
     */
    suspend fun recover() {
        val hrReset = heartRateDao.resetSyncingToLocal()
        val scReset = stepCountDao.resetSyncingToLocal()
        Log.i(TAG, "recover: reset $hrReset heart-rate, $scReset step-count records")
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
            Log.d(TAG, "syncHeartRates: ${pending.size} records synced")
        } catch (e: Exception) {
            Log.w(TAG, "syncHeartRates failed: ${e.message}")
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
            Log.d(TAG, "syncStepCounts: ${pending.size} records synced")
        } catch (e: Exception) {
            Log.w(TAG, "syncStepCounts failed: ${e.message}")
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
