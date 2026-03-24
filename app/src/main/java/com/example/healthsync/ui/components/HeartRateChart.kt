package com.example.healthsync.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.healthsync.ui.heartrate.ChartPoint

@Composable
fun HeartRateChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier
) {
    val lineColor = Color(0xFFE53935)
    val abnormalHighZone = Color(0x30E53935)
    val abnormalLowZone = Color(0x301E88E5)
    val gridColor = Color(0x20000000)

    Canvas(modifier = modifier.fillMaxWidth().height(200.dp)) {
        val minBpm = 40f
        val maxBpm = 160f
        val bpmRange = maxBpm - minBpm

        val highThreshold = 100f
        val lowThreshold = 60f

        // Abnormal zones
        val highY = size.height * (1f - (highThreshold - minBpm) / bpmRange)
        val lowY = size.height * (1f - (lowThreshold - minBpm) / bpmRange)

        drawRect(
            color = abnormalHighZone,
            topLeft = Offset(0f, 0f),
            size = androidx.compose.ui.geometry.Size(size.width, highY)
        )
        drawRect(
            color = abnormalLowZone,
            topLeft = Offset(0f, lowY),
            size = androidx.compose.ui.geometry.Size(size.width, size.height - lowY)
        )

        // Grid lines at 60, 80, 100, 120
        listOf(60f, 80f, 100f, 120f).forEach { bpm ->
            val y = size.height * (1f - (bpm - minBpm) / bpmRange)
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }

        if (points.size < 2) return@Canvas

        val minTime = points.first().timestamp.toFloat()
        val maxTime = points.last().timestamp.toFloat()
        val timeRange = (maxTime - minTime).coerceAtLeast(1f)

        val path = Path()
        points.forEachIndexed { index, point ->
            val x = ((point.timestamp - minTime) / timeRange) * size.width
            val y = size.height * (1f - (point.bpm.toFloat() - minBpm) / bpmRange)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(path, lineColor, style = Stroke(width = 3f))

        // Latest point marker
        val last = points.last()
        val lastX = size.width
        val lastY = size.height * (1f - (last.bpm.toFloat() - minBpm) / bpmRange)
        drawCircle(lineColor, radius = 6f, center = Offset(lastX, lastY))
    }
}
