package com.example.healthsync.ui.heartrate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.healthsync.data.source.ConnectionState
import com.example.healthsync.data.source.SimulatedBluetoothSource
import com.example.healthsync.data.sync.SyncCoordinator
import com.example.healthsync.di.ApplicationScope
import com.example.healthsync.domain.usecase.GetHealthSummaryUseCase
import com.example.healthsync.domain.usecase.TriggerSyncUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 心率 UI 状态（Milestone 1/6）。
 * 聚合所有 UI 所需的数据源，由 Repository Flow + ViewModel combine 生成。
 */
data class HeartRateUiState(
    /** 当前最新的心率值（BPM）。 */
    val currentBpm: Int? = null,
    /** 是否异常（> 100 或 < 60）。 */
    val isAbnormal: Boolean = false,
    /** 最近 5 分钟的心率数据点（用于折线图绘制）。 */
    val chartPoints: List<ChartPoint> = emptyList(),
    /** 待同步的记录总数（用于 badge 显示）。 */
    val pendingSyncCount: Int = 0,
    /** 是否正在同步。 */
    val isSyncing: Boolean = false,
    /** 蓝牙连接状态（CONNECTED/DISCONNECTED/RECONNECTING）。 */
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    /** 下拉刷新状态。 */
    val isRefreshing: Boolean = false
)

/**
 * 折线图数据点。
 */
data class ChartPoint(
    val timestamp: Long,
    val bpm: Int
)

/**
 * 心率 ViewModel（Milestone 1/6，DESIGN §10.1）。
 *
 * 职责：
 * 1. 聚合多个 Flow（最近心率 + 最新值 + 待同步数 + 同步状态 + 连接状态）为 UiState
 * 2. 响应 UI 交互：下拉刷新（onRefresh）
 * 3. 管理前台同步循环：startForegroundSync / stopForegroundSync（Milestone 3）
 *
 * 使用 flatMapLatest 实现依赖绑定：每当最新心率更新时，自动重新查询最近 5 分钟的数据。
 */
@HiltViewModel
class HeartRateViewModel @Inject constructor(
    private val getHealthSummaryUseCase: GetHealthSummaryUseCase,
    private val triggerSyncUseCase: TriggerSyncUseCase,
    private val simulatedSource: SimulatedBluetoothSource,
    private val syncCoordinator: SyncCoordinator,
    @param:ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    companion object {
        private const val FIVE_MINUTES_MS = 5 * 60 * 1000L
    }

    private val _isRefreshing = MutableStateFlow(false)

    /**
     * 最近 5 分钟心率数据流。
     * 当最新心率更新时，自动重新查询 5 分钟窗口内的数据（以最新时间为锚点）。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val recentHeartRatesFlow = getHealthSummaryUseCase
        .getLatestHeartRate()
        .flatMapLatest { latest ->
            val anchorMs = latest?.timestamp ?: System.currentTimeMillis()
            getHealthSummaryUseCase.getRecentHeartRates(anchorMs - FIVE_MINUTES_MS)
        }

    /**
     * 数据流聚合：最近 5 分钟记录 + 最新单条记录 + 待同步数。
     */
    private val dataFlows = combine(
        recentHeartRatesFlow,
        getHealthSummaryUseCase.getLatestHeartRate(),
        getHealthSummaryUseCase.getPendingSyncCount()
    ) { recent, latest, pendingCount ->
        Triple(recent, latest, pendingCount)
    }

    /**
     * 状态流聚合：同步状态 + 连接状态 + 刷新状态。
     */
    private val statusFlows = combine(
        syncCoordinator.isSyncing,
        simulatedSource.connectionState,
        _isRefreshing
    ) { syncing, conn, refreshing ->
        Triple(syncing, conn, refreshing)
    }

    /**
     * 最终 UI 状态流：combine 数据流与状态流，生成 HeartRateUiState。
     * 使用 stateIn 转换为 StateFlow，支持 MVVM 最佳实践（ViewModel 暴露 StateFlow 而非 Flow）。
     */
    val uiState: StateFlow<HeartRateUiState> = combine(
        dataFlows, statusFlows
    ) { (records, latest, pendingCount), (syncing, connState, refreshing) ->
        val bpm = latest?.bpm

        HeartRateUiState(
            currentBpm = bpm,
            isAbnormal = bpm != null && (bpm > 100 || bpm < 60),
            chartPoints = records
                .sortedBy { it.timestamp }
                .map { ChartPoint(it.timestamp, it.bpm) },
            pendingSyncCount = pendingCount,
            isSyncing = syncing,
            connectionState = connState,
            isRefreshing = refreshing
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        HeartRateUiState()
    )

    /**
     * 下拉刷新回调：触发一次同步，刷新状态由 SyncCoordinator.isSyncing 自动更新。
     */
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

    /**
     * 启动前台同步循环。当 Screen 进入前台时调用（Milestone 3）。
     * 循环会持续扫描→上传→回写，对齐 nextAttemptAt 以实现精确的指数退避。
     */
    fun startForegroundSync() {
        syncCoordinator.startForegroundLoop(appScope)
    }

    /**
     * 停止前台同步循环。当 Screen 进入后台/销毁时调用。
     */
    fun stopForegroundSync() {
        syncCoordinator.stopForegroundLoop()
    }
}
