package com.example.healthsync.domain.usecase

import com.example.healthsync.data.local.entity.HeartRateEntity
import com.example.healthsync.data.repository.HealthRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * 获取健康数据汇总 UseCase（Milestone 1/6）。
 *
 * 为 ViewModel 提供统一的数据查询入口，汇聚 Repository 的各个数据流。
 * UI 通过此 UseCase 获取展示所需的所有数据（最新心率、最近心率、待同步数等）。
 */
class GetHealthSummaryUseCase @Inject constructor(
    private val repository: HealthRepository
) {
    /** 获取指定时间戳之后的心率记录流（用于 5 分钟折线图）。 */
    fun getRecentHeartRates(sinceMs: Long): Flow<List<HeartRateEntity>> =
        repository.getRecentHeartRates(sinceMs)

    /** 获取最新的单条心率记录流（用于大字显示）。 */
    fun getLatestHeartRate(): Flow<HeartRateEntity?> =
        repository.getLatestHeartRate()

    /** 获取指定时间戳之后的步数总和流（用于今日步数展示）。 */
    fun getTodaySteps(todayStartMs: Long): Flow<Int> =
        repository.getTodaySteps(todayStartMs)

    /** 获取待同步的记录总数流（用于 badge 显示）。 */
    fun getPendingSyncCount(): Flow<Int> =
        repository.getPendingSyncCount()
}
