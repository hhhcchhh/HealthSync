package com.example.healthsync.ui.heartrate

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.healthsync.data.source.ConnectionState
import com.example.healthsync.ui.components.HeartRateChart
import com.example.healthsync.ui.components.SyncStatusBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeartRateScreen(
    viewModel: HeartRateViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    DisposableEffect(Unit) {
        viewModel.startForegroundSync()
        onDispose { viewModel.stopForegroundSync() }
    }

    LaunchedEffect(state.connectionState) {
        if (state.connectionState == ConnectionState.DISCONNECTED) {
            snackbarHostState.showSnackbar("设备已断开连接")
        } else if (state.connectionState == ConnectionState.RECONNECTING) {
            snackbarHostState.showSnackbar("正在重新连接...")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HealthSync") },
                actions = {
                    SyncStatusBadge(
                        pendingCount = state.pendingSyncCount,
                        isSyncing = state.isSyncing,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.onRefresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ConnectionBanner(state.connectionState)

                Spacer(Modifier.height(24.dp))

                HeartRateDisplay(
                    bpm = state.currentBpm,
                    isAbnormal = state.isAbnormal
                )

                Spacer(Modifier.height(24.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "最近5分钟心率",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        HeartRateChart(
                            points = state.chartPoints,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                SyncSummaryCard(
                    pendingCount = state.pendingSyncCount,
                    isSyncing = state.isSyncing
                )
            }
        }
    }
}

@Composable
private fun HeartRateDisplay(bpm: Int?, isAbnormal: Boolean) {
    val bpmColor by animateColorAsState(
        targetValue = when {
            bpm == null -> Color.Gray
            bpm > 100 -> Color(0xFFE53935)
            bpm < 60 -> Color(0xFF1E88E5)
            else -> MaterialTheme.colorScheme.onSurface
        },
        label = "bpm_color"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = bpm?.toString() ?: "--",
            fontSize = 72.sp,
            fontWeight = FontWeight.Bold,
            color = bpmColor
        )
        Text(
            text = "BPM",
            style = MaterialTheme.typography.titleMedium,
            color = bpmColor
        )
        if (isAbnormal && bpm != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (bpm > 100) "⚠ 心率偏高" else "⚠ 心率偏低",
                color = bpmColor,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ConnectionBanner(connectionState: ConnectionState) {
    if (connectionState != ConnectionState.CONNECTED) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when (connectionState) {
                    ConnectionState.DISCONNECTED -> Color(0xFFFFEBEE)
                    ConnectionState.RECONNECTING -> Color(0xFFFFF3E0)
                    else -> MaterialTheme.colorScheme.surface
                }
            )
        ) {
            Text(
                text = when (connectionState) {
                    ConnectionState.DISCONNECTED -> "设备已断开，显示历史数据"
                    ConnectionState.RECONNECTING -> "正在重新连接..."
                    else -> ""
                },
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = when (connectionState) {
                    ConnectionState.DISCONNECTED -> Color(0xFFE53935)
                    ConnectionState.RECONNECTING -> Color(0xFFFFA726)
                    else -> Color.Unspecified
                }
            )
        }
    }
}

@Composable
private fun SyncSummaryCard(pendingCount: Int, isSyncing: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "同步状态",
                style = MaterialTheme.typography.titleMedium
            )
            SyncStatusBadge(
                pendingCount = pendingCount,
                isSyncing = isSyncing
            )
        }
    }
}
