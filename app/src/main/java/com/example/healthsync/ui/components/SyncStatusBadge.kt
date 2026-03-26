package com.example.healthsync.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 同步状态 Badge 组件（Milestone 2/6，DESIGN §10.2）。
 *
 * 显示当前同步状态与冲突数量：
 * - 绿色对号"已同步"：待同步数为 0 且无冲突
 * - 红色"X条待同步"：有待同步的记录
 * - 橙色"同步中..."：正在执行同步
 * - 紫色"X条冲突"：有未解决的冲突记录（追加在待同步信息之后）
 */
@Composable
fun SyncStatusBadge(
    pendingCount: Int,
    isSyncing: Boolean,
    conflictCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val color by animateColorAsState(
        targetValue = when {
            isSyncing -> Color(0xFFFFA726)
            pendingCount > 0 -> Color(0xFFE53935)
            else -> Color(0xFF43A047)
        },
        label = "sync_color"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!isSyncing && pendingCount == 0 && conflictCount == 0) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "已同步",
                tint = Color(0xFF43A047),
                modifier = Modifier.width(16.dp)
            )
        } else {
            Text(
                text = "\u25CF",
                color = color,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = when {
                isSyncing -> "同步中..."
                pendingCount > 0 -> "${pendingCount}条待同步"
                else -> "已同步"
            },
            style = MaterialTheme.typography.bodySmall,
            color = color
        )

        if (conflictCount > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "\u25CF",
                color = Color(0xFF7B1FA2),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "${conflictCount}条冲突",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF7B1FA2)
            )
        }
    }
}
