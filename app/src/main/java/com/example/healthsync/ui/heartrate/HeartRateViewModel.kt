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

data class HeartRateUiState(
    val currentBpm: Int? = null,
    val isAbnormal: Boolean = false,
    val chartPoints: List<ChartPoint> = emptyList(),
    val pendingSyncCount: Int = 0,
    val isSyncing: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val isRefreshing: Boolean = false
)

data class ChartPoint(
    val timestamp: Long,
    val bpm: Int
)

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

    @OptIn(ExperimentalCoroutinesApi::class)
    private val recentHeartRatesFlow = getHealthSummaryUseCase
        .getLatestHeartRate()
        .flatMapLatest { latest ->
            val anchorMs = latest?.timestamp ?: System.currentTimeMillis()
            getHealthSummaryUseCase.getRecentHeartRates(anchorMs - FIVE_MINUTES_MS)
        }

    private val dataFlows = combine(
        recentHeartRatesFlow,
        getHealthSummaryUseCase.getLatestHeartRate(),
        getHealthSummaryUseCase.getPendingSyncCount()
    ) { recent, latest, pendingCount ->
        Triple(recent, latest, pendingCount)
    }

    private val statusFlows = combine(
        syncCoordinator.isSyncing,
        simulatedSource.connectionState,
        _isRefreshing
    ) { syncing, conn, refreshing ->
        Triple(syncing, conn, refreshing)
    }

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

    fun startForegroundSync() {
        syncCoordinator.startForegroundLoop(appScope)
    }

    fun stopForegroundSync() {
        syncCoordinator.stopForegroundLoop()
    }
}
