package com.example.healthsync.domain.usecase

import com.example.healthsync.data.local.entity.HeartRateEntity
import com.example.healthsync.data.local.entity.SleepRecordEntity
import com.example.healthsync.data.local.entity.StepCountEntity
import com.example.healthsync.data.repository.HealthRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * 获取健康数据汇总 UseCase（Milestone 1/5/6）。
 *
 * 为 ViewModel 提供统一的数据查询入口，汇聚 Repository 的各个数据流。
 * UI 通过此 UseCase 获取展示所需的所有数据（心率、步数、睡眠、同步状态等）。
 */
class GetHealthSummaryUseCase @Inject constructor(
    private val repository: HealthRepository
) {
    fun getRecentHeartRates(sinceMs: Long): Flow<List<HeartRateEntity>> =
        repository.getRecentHeartRates(sinceMs)

    fun getLatestHeartRate(): Flow<HeartRateEntity?> =
        repository.getLatestHeartRate()

    fun getTodaySteps(todayStartMs: Long): Flow<Int> =
        repository.getTodaySteps(todayStartMs)

    fun getPendingSyncCount(): Flow<Int> =
        repository.getPendingSyncCount()

    fun getAllHeartRates(): Flow<List<HeartRateEntity>> =
        repository.getAllHeartRates()

    fun getAllStepCounts(): Flow<List<StepCountEntity>> =
        repository.getAllStepCounts()

    fun getLatestSleepRecord(): Flow<SleepRecordEntity?> =
        repository.getLatestSleepRecord()

    fun getAllSleepRecords(): Flow<List<SleepRecordEntity>> =
        repository.getAllSleepRecords()

    fun getConflicts(): Flow<List<SleepRecordEntity>> =
        repository.getConflicts()

    fun getConflictCount(): Flow<Int> =
        repository.getConflictCount()
}
