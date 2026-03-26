package com.example.healthsync.data.repository

import com.example.healthsync.data.local.dao.HeartRateDao
import com.example.healthsync.data.local.dao.SleepRecordDao
import com.example.healthsync.data.local.dao.StepCountDao
import com.example.healthsync.data.local.entity.HeartRateEntity
import com.example.healthsync.data.local.entity.SleepRecordEntity
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

/**
 * 数据层统一写入口（DESIGN §2.1、§5.3）：订阅 [HealthDataSource] 的 [HealthEvent]，映射为 Entity 写入 Room。
 *
 * **离线优先**：先落库（LOCAL_PENDING），再由 [com.example.healthsync.data.sync.SyncEngine] 扫描上传。
 * 心率/步数使用客户端 [java.util.UUID] 作为 **eventId**，配合 Room 唯一索引与云端去重（DESIGN §6.5）。
 */
@Singleton
class HealthRepository @Inject constructor(
    private val heartRateDao: HeartRateDao,
    private val stepCountDao: StepCountDao,
    private val sleepRecordDao: SleepRecordDao
) {
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
                val existing = sleepRecordDao.getById(event.id)
                if (existing == null) {
                    sleepRecordDao.upsert(
                        SleepRecordEntity(
                            id = event.id,
                            startTime = event.startTime,
                            endTime = event.endTime,
                            quality = event.quality,
                            sourceId = event.sourceId,
                            syncState = SyncState.LOCAL_PENDING,
                            attemptCount = 0,
                            nextAttemptAt = 0L
                        )
                    )
                } else {
                    // 同 ID 事件视为“离线编辑”：保留远端版本信息，回到待同步并清理旧错误/冲突痕迹。
                    sleepRecordDao.upsert(
                        existing.copy(
                            startTime = event.startTime,
                            endTime = event.endTime,
                            quality = event.quality,
                            sourceId = event.sourceId,
                            syncState = SyncState.LOCAL_PENDING,
                            attemptCount = 0,
                            nextAttemptAt = 0L,
                            lastError = null,
                            baseRemoteVersion = existing.remoteVersion,
                            localVersion = existing.localVersion + 1,
                            serverSnapshot = null
                        )
                    )
                }
            }
        }
    }

    // ── 写路径：UseCase 通过 Repository 统一写入（DESIGN §2.1、§5.3）──

    suspend fun saveSleepRecord(entity: SleepRecordEntity) {
        sleepRecordDao.upsert(entity)
    }

    suspend fun getSleepRecordById(id: String): SleepRecordEntity? =
        sleepRecordDao.getById(id)

    // ── 读路径：UI / UseCase 订阅 Room Flow（事实来源）──

    fun getRecentHeartRates(sinceMs: Long): Flow<List<HeartRateEntity>> =
        heartRateDao.getRecentFlow(sinceMs)

    fun getLatestHeartRate(): Flow<HeartRateEntity?> =
        heartRateDao.getLatestFlow()

    fun getTodaySteps(todayStartMs: Long): Flow<Int> =
        stepCountDao.getTotalStepsSinceFlow(todayStartMs)

    fun getAllHeartRates(): Flow<List<HeartRateEntity>> =
        heartRateDao.getAllFlow()

    fun getLatestSleepRecord(): Flow<SleepRecordEntity?> =
        sleepRecordDao.getLatestFlow()

    fun getAllSleepRecords(): Flow<List<SleepRecordEntity>> =
        sleepRecordDao.getAllFlow()

    fun getConflicts(): Flow<List<SleepRecordEntity>> =
        sleepRecordDao.getConflictsFlow()

    fun getAllStepCounts(): Flow<List<StepCountEntity>> =
        stepCountDao.getAllFlow()

    // ── 可观测性：待同步条数（LOCAL_PENDING / SYNCING / SYNC_FAILED，Milestone 2）──

    private val pendingStates = listOf(
        SyncState.LOCAL_PENDING,
        SyncState.SYNCING,
        SyncState.SYNC_FAILED
    )

    fun getPendingSyncCount(): Flow<Int> {
        val hrCount = heartRateDao.countByStatesFlow(pendingStates)
        val scCount = stepCountDao.countByStatesFlow(pendingStates)
        val slCount = sleepRecordDao.countByStatesFlow(pendingStates)
        return combine(hrCount, scCount, slCount) { hr, sc, sl -> hr + sc + sl }
    }

    fun getConflictCount(): Flow<Int> =
        sleepRecordDao.countByStatesFlow(listOf(SyncState.CONFLICT))
}
