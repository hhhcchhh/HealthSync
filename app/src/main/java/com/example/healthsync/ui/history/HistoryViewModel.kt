package com.example.healthsync.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.healthsync.data.local.entity.SyncState
import com.example.healthsync.domain.usecase.GetHealthSummaryUseCase
import com.example.healthsync.domain.usecase.TriggerSyncUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * 历史时间线中的统一条目，由 ViewModel 将三类 Entity 映射而来。
 */
data class TimelineItem(
    val id: String,
    val timestamp: Long,
    val type: String,
    val label: String,
    val detail: String,
    val syncState: SyncState
)

/**
 * 历史页面的 UI 状态：按日期分组的时间线条目 + 下拉刷新状态。
 */
data class HistoryUiState(
    val groupedItems: Map<String, List<TimelineItem>> = emptyMap(),
    val isRefreshing: Boolean = false
)

/**
 * 历史页面 ViewModel（Milestone 6，DESIGN §10.3）。
 *
 * 通过 [GetHealthSummaryUseCase] 订阅心率、步数、睡眠三类 Flow，
 * 在 ViewModel 层完成"Entity → [TimelineItem]"映射与按日分组，
 * 输出 [HistoryUiState] 供 Screen 直接渲染。
 * 下拉刷新时调用 [TriggerSyncUseCase] 执行一次同步。
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val getHealthSummaryUseCase: GetHealthSummaryUseCase,
    private val triggerSyncUseCase: TriggerSyncUseCase
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** 由多个数据源 Flow combine 而成的 UI 状态，订阅者至少保活 5 秒。 */
    val uiState: StateFlow<HistoryUiState> = combine(
        getHealthSummaryUseCase.getAllHeartRates(),
        getHealthSummaryUseCase.getAllStepCounts(),
        getHealthSummaryUseCase.getAllSleepRecords(),
        _isRefreshing
    ) { hr, sc, sl, refreshing ->
        val items = buildList {
            hr.forEach { entity ->
                add(TimelineItem(
                    id = "hr-${entity.id}",
                    timestamp = entity.timestamp,
                    type = "heart_rate",
                    label = "${entity.bpm} BPM",
                    detail = timeFormat.format(Date(entity.timestamp)),
                    syncState = entity.syncState
                ))
            }
            sc.forEach { entity ->
                add(TimelineItem(
                    id = "sc-${entity.id}",
                    timestamp = entity.timestamp,
                    type = "step_count",
                    label = "+${entity.steps} 步",
                    detail = timeFormat.format(Date(entity.timestamp)),
                    syncState = entity.syncState
                ))
            }
            sl.forEach { entity ->
                val durationMs = entity.endTime - entity.startTime
                val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
                val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
                add(TimelineItem(
                    id = "sl-${entity.id}",
                    timestamp = entity.startTime,
                    type = "sleep",
                    label = "睡眠 ${hours}h ${minutes}m",
                    detail = "${entity.quality.name} | ${timeFormat.format(Date(entity.startTime))}",
                    syncState = entity.syncState
                ))
            }
        }.sortedByDescending { it.timestamp }

        val grouped = items.groupBy { dateFormat.format(Date(it.timestamp)) }

        HistoryUiState(
            groupedItems = grouped,
            isRefreshing = refreshing
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        HistoryUiState()
    )

    /** 下拉刷新回调：触发一次同步，并在同步完成后关闭刷新指示器。 */
    fun onRefresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                triggerSyncUseCase()
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
