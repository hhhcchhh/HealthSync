package com.example.healthsync.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * 同步状态 Badge 组件（Milestone 2/6）。
 *
 * 显示当前同步状态：
 * - 绿色"已同步"：待同步数为 0
 * - 红色"X条待同步"：有待同步的记录
 * - 橙色"同步中..."：正在执行同步
 *
 * 用于 TopAppBar 与卡片中显示，确保用户对数据同步状态的可观测性（DESIGN §6.2）。
 */
@Composable
fun SyncStatusBadge(
    pendingCount: Int,
    isSyncing: Boolean,
    modifier: Modifier = Modifier
) {
    // 根据状态动画切换颜色
    val color by animateColorAsState(
        targetValue = when {
            isSyncing -> Color(0xFFFFA726)      // 橙色：同步中
            pendingCount > 0 -> Color(0xFFE53935) // 红色：待同步
            else -> Color(0xFF43A047)             // 绿色：已同步
        },
        label = "sync_color"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 状态指示圆点
        Text(
            text = "\u25CF",  // 实心圆点
            color = color,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.width(4.dp))
        // 状态文本
        Text(
            text = when {
                isSyncing -> "同步中..."
                pendingCount > 0 -> "${pendingCount}条待同步"
                else -> "已同步"
            },
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
    }
}
