package com.example.healthsync.data.repository

import com.example.healthsync.data.local.dao.HeartRateDao
import com.example.healthsync.data.local.dao.StepCountDao
import com.example.healthsync.data.local.entity.HeartRateEntity
import com.example.healthsync.data.local.entity.StepCountEntity
import com.example.healthsync.data.local.entity.SyncState
import com.example.healthsync.data.source.HealthDataSource
import com.example.healthsync.data.source.HealthEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthRepository @Inject constructor(
    private val heartRateDao: HeartRateDao,
    private val stepCountDao: StepCountDao
) {
    /**
     * Subscribes to a data source's event flow and persists events to Room.
     * Should be called in an application-scoped coroutine.
     */
    fun collectFrom(source: HealthDataSource, scope: CoroutineScope) {
        source.dataEvents
            .onEach { event -> handleEvent(event) }
            .launchIn(scope)
    }

    private suspend fun handleEvent(event: HealthEvent) {
        when (event) {
            is HealthEvent.HeartRateSample -> {
                heartRateDao.insert(
                    HeartRateEntity(
                        timestamp = event.timestamp,
                        bpm = event.bpm,
                        sourceId = event.sourceId,
                        eventId = UUID.randomUUID().toString(),
                        syncState = SyncState.LOCAL_PENDING,
                        attemptCount = 0,
                        nextAttemptAt = 0L
                    )
                )
            }
            is HealthEvent.StepCountIncrement -> {
                stepCountDao.insert(
                    StepCountEntity(
                        timestamp = event.timestamp,
                        steps = event.steps,
                        sourceId = event.sourceId,
                        eventId = UUID.randomUUID().toString(),
                        syncState = SyncState.LOCAL_PENDING,
                        attemptCount = 0,
                        nextAttemptAt = 0L
                    )
                )
            }
            is HealthEvent.SleepRecord -> {
                // Handled via ManualInputSource → SaveSleepRecordUseCase (Milestone 5)
            }
        }
    }

    // ── Query Flows ──

    fun getRecentHeartRates(sinceMs: Long): Flow<List<HeartRateEntity>> =
        heartRateDao.getRecentFlow(sinceMs)

    fun getLatestHeartRate(): Flow<HeartRateEntity?> =
        heartRateDao.getLatestFlow()

    fun getTodaySteps(todayStartMs: Long): Flow<Int> =
        stepCountDao.getTotalStepsSinceFlow(todayStartMs)

    fun getAllHeartRates(): Flow<List<HeartRateEntity>> =
        heartRateDao.getAllFlow()

    // ── Sync Status ──

    private val pendingStates = listOf(
        SyncState.LOCAL_PENDING,
        SyncState.SYNCING,
        SyncState.SYNC_FAILED
    )

    fun getPendingSyncCount(): Flow<Int> {
        val hrCount = heartRateDao.countByStatesFlow(pendingStates)
        val scCount = stepCountDao.countByStatesFlow(pendingStates)
        return combine(hrCount, scCount) { hr, sc -> hr + sc }
    }
}
