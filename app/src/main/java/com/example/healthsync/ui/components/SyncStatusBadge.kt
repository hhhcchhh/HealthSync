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

@Composable
fun SyncStatusBadge(
    pendingCount: Int,
    isSyncing: Boolean,
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
        Text(
            text = "\u25CF",
            color = color,
            style = MaterialTheme.typography.bodySmall
        )
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
    }
}
