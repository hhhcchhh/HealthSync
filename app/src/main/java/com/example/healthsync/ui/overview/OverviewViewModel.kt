package com.example.healthsync.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.healthsync.data.local.entity.SleepRecordEntity
import com.example.healthsync.data.sync.SyncCoordinator
import com.example.healthsync.domain.usecase.GetHealthSummaryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import javax.inject.Inject

/**
 * 今日概览页面的 UI 状态。
 *
 * @property currentBpm       最新心率值，null 表示暂无数据
 * @property todaySteps       今日累计步数
 * @property latestSleep      最近一条睡眠记录，null 表示暂无
 * @property pendingSyncCount 待同步记录数
 * @property conflictCount    未解决冲突数
 * @property isSyncing        是否正在同步中
 */
data class OverviewUiState(
    val currentBpm: Int? = null,
    val todaySteps: Int = 0,
    val latestSleep: SleepRecordEntity? = null,
    val pendingSyncCount: Int = 0,
    val conflictCount: Int = 0,
    val isSyncing: Boolean = false
)

/**
 * 今日概览 ViewModel（Milestone 6，DESIGN §10.2）。
 *
 * 组合最新心率、今日步数、最近睡眠、待同步数量和同步状态五路 Flow，
 * 输出单一 [OverviewUiState] 供 [OverviewScreen] 消费。
 */
@HiltViewModel
class OverviewViewModel @Inject constructor(
    getHealthSummaryUseCase: GetHealthSummaryUseCase,
    syncCoordinator: SyncCoordinator
) : ViewModel() {

    /** 今日 0 点的毫秒时间戳，每次读取时实时计算，用于查询今日步数。 */
    private val todayStartMs: Long
        get() {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return cal.timeInMillis
        }

    private val syncCountsFlow = combine(
        getHealthSummaryUseCase.getPendingSyncCount(),
        getHealthSummaryUseCase.getConflictCount()
    ) { pending, conflict -> Pair(pending, conflict) }

    /** 由多路 Flow combine 而来的页面状态，订阅者至少保活 5 秒以避免配置变更时反复重订阅。 */
    val uiState: StateFlow<OverviewUiState> = combine(
        getHealthSummaryUseCase.getLatestHeartRate(),
        getHealthSummaryUseCase.getTodaySteps(todayStartMs),
        getHealthSummaryUseCase.getLatestSleepRecord(),
        syncCountsFlow,
        syncCoordinator.isSyncing
    ) { latestHr, steps, sleep, (pending, conflict), syncing ->
        OverviewUiState(
            currentBpm = latestHr?.bpm,
            todaySteps = steps,
            latestSleep = sleep,
            pendingSyncCount = pending,
            conflictCount = conflict,
            isSyncing = syncing
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        OverviewUiState()
    )
}
