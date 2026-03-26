package com.example.healthsync.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.healthsync.data.local.entity.SyncState

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
                        HistoryItemCard(item.type, item.label, item.detail, item.syncState)
                    }
                }
            }
        }
    }
}

/**
 * 单条历史记录卡片。左侧为类型图标，中间为主标签+详情，
 * 若记录处于 CONFLICT 或 SYNC_FAILED 状态则右侧显示对应提示。
 */
@Composable
private fun HistoryItemCard(type: String, label: String, detail: String, syncState: SyncState) {
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
        modifier = Modifier.fillMaxWidth(),
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
