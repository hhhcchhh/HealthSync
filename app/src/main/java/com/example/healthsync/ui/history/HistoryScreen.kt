package com.example.healthsync.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.healthsync.data.local.entity.SyncState
import com.example.healthsync.data.local.entity.SleepQuality
import com.example.healthsync.data.sync.ConflictResolution
import com.example.healthsync.data.remote.ServerSleepData
import com.example.healthsync.ui.components.SleepRecordEditorDialog
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 健康数据历史时间线页面（Milestone 6，DESIGN §10.3）。
 *
 * 三类数据的映射与分组由 [HistoryViewModel] 完成，Screen 只负责渲染
 * [HistoryUiState.groupedItems]。使用 [LazyColumn] + stickyHeader + contentType
 * 呈现时间线列表，支持下拉刷新触发同步。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var editSleep by remember {
        mutableStateOf<EditSleepRequest?>(null)
    }
    var resolveConflict by remember {
        mutableStateOf<ResolveConflictRequest?>(null)
    }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = { viewModel.onRefresh() },
        modifier = Modifier.fillMaxSize()
    ) {
        if (state.groupedItems.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "暂无历史数据",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "下拉刷新以同步数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                state.groupedItems.forEach { (date, dayItems) ->
                    stickyHeader(key = date, contentType = "header") {
                        Text(
                            text = date,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        )
                    }
                    items(
                        items = dayItems,
                        key = { it.id },
                        contentType = { it.type }
                    ) { item ->
                        val onClick: (() -> Unit)? = if (item.type == "sleep" && item.sleepRecordId != null) {
                            {
                                if (item.syncState == SyncState.CONFLICT) {
                                    resolveConflict = ResolveConflictRequest(
                                        recordId = item.sleepRecordId,
                                        localStart = item.sleepStartTime ?: 0L,
                                        localEnd = item.sleepEndTime ?: 0L,
                                        localQuality = item.sleepQuality?.name ?: "",
                                        serverSnapshot = item.sleepServerSnapshot
                                    )
                                } else {
                                    editSleep = EditSleepRequest(
                                        recordId = item.sleepRecordId,
                                        startTimeMs = item.sleepStartTime ?: 0L,
                                        endTimeMs = item.sleepEndTime ?: 0L,
                                        quality = item.sleepQuality
                                    )
                                }
                            }
                        } else {
                            null
                        }

                        HistoryItemCard(
                            type = item.type,
                            label = item.label,
                            detail = item.detail,
                            syncState = item.syncState,
                            onClick = onClick
                        )
                    }
                }
            }
        }
    }

    if (editSleep != null) {
        val req = editSleep!!
        SleepRecordEditorDialog(
            onDismiss = { editSleep = null },
            onSave = { startMs, endMs, quality ->
                viewModel.editSleepRecord(req.recordId, startMs, endMs, quality)
                editSleep = null
            },
            title = "编辑睡眠记录",
            initialStartTimeMs = req.startTimeMs,
            initialEndTimeMs = req.endTimeMs,
            initialQuality = req.quality ?: SleepQuality.GOOD
        )
    }

    if (resolveConflict != null) {
        val req = resolveConflict!!
        ConflictResolutionDialog(
            recordId = req.recordId,
            localStart = req.localStart,
            localEnd = req.localEnd,
            localQuality = req.localQuality,
            serverSnapshot = req.serverSnapshot,
            onDismiss = { resolveConflict = null },
            onSelect = { resolution ->
                viewModel.resolveSleepConflict(req.recordId, resolution)
                resolveConflict = null
            }
        )
    }
}

private data class EditSleepRequest(
    val recordId: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val quality: SleepQuality?
)

private data class ResolveConflictRequest(
    val recordId: String,
    val localStart: Long,
    val localEnd: Long,
    val localQuality: String,
    val serverSnapshot: String?
)

/**
 * 单条历史记录卡片。左侧为类型图标，中间为主标签+详情，
 * 若记录处于 CONFLICT 或 SYNC_FAILED 状态则右侧显示对应提示。
 */
@Composable
private fun HistoryItemCard(
    type: String,
    label: String,
    detail: String,
    syncState: SyncState,
    onClick: (() -> Unit)? = null
) {
    val typeIcon = when (type) {
        "heart_rate" -> "\u2764\uFE0F"
        "step_count" -> "\uD83D\uDEB6"
        "sleep" -> "\uD83D\uDCA4"
        else -> ""
    }

    val (statusText, statusColor) = when (syncState) {
        SyncState.LOCAL_PENDING -> "本地待同步" to Color(0xFF616161)
        SyncState.SYNCING -> "同步中" to Color(0xFFFFA726)
        SyncState.SYNCED -> "已同步" to Color(0xFF43A047)
        SyncState.SYNC_FAILED -> "同步失败" to Color(0xFFE53935)
        SyncState.CONFLICT -> "冲突" to Color(0xFF7B1FA2)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = typeIcon, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(text = detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 每条记录都展示同步状态，满足“可见性”验收要求
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ConflictResolutionDialog(
    recordId: String,
    localStart: Long,
    localEnd: Long,
    localQuality: String,
    serverSnapshot: String?,
    onDismiss: () -> Unit,
    onSelect: (ConflictResolution) -> Unit
) {
    val df = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val gson = remember { Gson() }
    val serverData = remember(serverSnapshot) {
        if (serverSnapshot.isNullOrBlank()) {
            null
        } else {
            runCatching { gson.fromJson(serverSnapshot, ServerSleepData::class.java) }.getOrNull()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "解决冲突") },
        text = {
            Column {
                Text(
                    text = "记录：$recordId",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(text = "本地版本", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(text = "${df.format(Date(localStart))} → ${df.format(Date(localEnd))}")
                Text(text = "质量：$localQuality")

                Spacer(Modifier.height(12.dp))

                Text(text = "服务端快照", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                when {
                    serverData != null -> {
                        Text(text = "${df.format(Date(serverData.startTime))} → ${df.format(Date(serverData.endTime))}")
                        Text(text = "质量：${serverData.quality}")
                        Text(
                            text = "版本：v${serverData.remoteVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    serverSnapshot.isNullOrBlank() -> {
                        Text(text = "（无快照数据）", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        // 降级展示：解析失败时展示 JSON 片段，避免 UI 崩溃
                        val raw = if (serverSnapshot.length > 240) serverSnapshot.take(240) + "…" else serverSnapshot
                        Text(
                            text = raw,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onSelect(ConflictResolution.KEEP_LOCAL) }) { Text("保留本地") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { onSelect(ConflictResolution.KEEP_REMOTE) }) { Text("采用云端") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { onSelect(ConflictResolution.MERGE) }) { Text("合并") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
