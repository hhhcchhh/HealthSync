package com.example.healthsync.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.healthsync.ui.components.SleepRecordEditorDialog
import com.example.healthsync.ui.components.StepRingProgress
import com.example.healthsync.ui.components.SyncStatusBadge
import java.util.concurrent.TimeUnit

/**
 * 今日概览页面（Milestone 6，DESIGN §10.2）。
 *
 * 以垂直滚动的卡片形式展示：
 * - 当前心率（含高/低心率颜色预警）
 * - 今日步数环形进度条 [StepRingProgress]
 * - 最近一次睡眠时长与质量
 * - 同步状态徽章 [SyncStatusBadge]
 */
@Composable
fun OverviewScreen(
    viewModel: OverviewViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSleepEditor by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "今日概览",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(24.dp))

        // Heart rate card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "当前心率",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = state.currentBpm?.toString() ?: "--",
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                state.currentBpm == null -> Color.Gray
                                state.currentBpm!! > 100 -> Color(0xFFE53935)
                                state.currentBpm!! < 60 -> Color(0xFF1E88E5)
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                        Text(
                            text = " BPM",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Step ring
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "今日步数",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(16.dp))
                StepRingProgress(steps = state.todaySteps)
            }
        }

        Spacer(Modifier.height(16.dp))

        // Latest sleep card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "最近睡眠",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                if (state.latestSleep != null) {
                    val sleep = state.latestSleep!!
                    val durationMs = sleep.endTime - sleep.startTime
                    val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
                    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
                    Text(
                        text = "${hours}h ${minutes}m",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "质量: ${sleep.quality.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "暂无睡眠记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    FilledTonalButton(onClick = { showSleepEditor = true }) {
                        Text("新增睡眠记录")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Sync status card
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
                    pendingCount = state.pendingSyncCount,
                    isSyncing = state.isSyncing,
                    conflictCount = state.conflictCount
                )
            }
        }
    }

    if (showSleepEditor) {
        SleepRecordEditorDialog(
            onDismiss = { showSleepEditor = false },
            onSave = { startMs, endMs, quality ->
                viewModel.createSleepRecord(startMs, endMs, quality)
                showSleepEditor = false
            }
        )
    }
}
