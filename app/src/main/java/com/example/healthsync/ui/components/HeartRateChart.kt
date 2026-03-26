package com.example.healthsync.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.healthsync.ui.heartrate.ChartPoint

private const val FIVE_MINUTES_MS = 5 * 60 * 1000L

/**
 * 心率折线图组件（Milestone 1/6，DESIGN §10.1）。
 *
 * 自定义 Canvas 绘制最近 5 分钟的心率数据，无依赖第三方图表库。
 * 显示内容：
 * - BPM 范围：40-160
 * - 异常区间背景：>100 红色区间 + <60 蓝色区间
 * - 网格线：60/80/100/120 BPM
 * - 心率曲线：红色折线连接数据点
 * - 最新点标记：红色圆点
 * - 新数据到达时使用 Animatable 做平滑位移（DESIGN §10.1）
 */
@Composable
fun HeartRateChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier
) {
    val lineColor = Color(0xFFE53935)
    val abnormalHighZone = Color(0x30E53935)
    val abnormalLowZone = Color(0x301E88E5)
    val gridColor = Color(0x20000000)

    // Reference time anchor to keep relative values within Float precision
    val referenceTime = remember { System.currentTimeMillis() }
    val latestRelativeMs = remember(points) {
        ((points.maxOfOrNull { it.timestamp } ?: referenceTime) - referenceTime).toFloat()
    }

    // Smooth window panning: animate the relative window-end position (DESIGN §10.1)
    val animatedRelativeEnd by animateFloatAsState(
        targetValue = latestRelativeMs,
        animationSpec = tween(durationMillis = 300),
        label = "chart_pan"
    )

    Canvas(modifier = modifier.fillMaxWidth().height(200.dp)) {
        val minBpm = 40f
        val maxBpm = 160f
        val bpmRange = maxBpm - minBpm

        val highThreshold = 100f
        val lowThreshold = 60f

        val highY = size.height * (1f - (highThreshold - minBpm) / bpmRange)
        val lowY = size.height * (1f - (lowThreshold - minBpm) / bpmRange)

        drawRect(
            color = abnormalHighZone,
            topLeft = Offset(0f, 0f),
            size = Size(size.width, highY)
        )
        drawRect(
            color = abnormalLowZone,
            topLeft = Offset(0f, lowY),
            size = Size(size.width, size.height - lowY)
        )

        listOf(60f, 80f, 100f, 120f).forEach { bpm ->
            val y = size.height * (1f - (bpm - minBpm) / bpmRange)
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }

        if (points.size < 2) return@Canvas

        val sortedPoints = points.sortedBy { it.timestamp }

        // Use animated window end for smooth panning when new data arrives
        val windowEndMs = referenceTime + animatedRelativeEnd.toLong()
        val windowStartMs = windowEndMs - FIVE_MINUTES_MS
        val timeRangeMs = FIVE_MINUTES_MS.toFloat().coerceAtLeast(1f)

        val path = Path()
        sortedPoints.forEachIndexed { index, point ->
            val x =
                (((point.timestamp - windowStartMs).toFloat() / timeRangeMs) * size.width)
                    .coerceIn(0f, size.width)
            val y =
                (size.height * (1f - (point.bpm.toFloat() - minBpm) / bpmRange))
                    .coerceIn(0f, size.height)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(path, lineColor, style = Stroke(width = 3f))

        val last = sortedPoints.last()
        val lastX =
            (((last.timestamp - windowStartMs).toFloat() / timeRangeMs) * size.width)
                .coerceIn(0f, size.width)
        val lastY =
            (size.height * (1f - (last.bpm.toFloat() - minBpm) / bpmRange))
                .coerceIn(0f, size.height)
        drawCircle(lineColor, radius = 6f, center = Offset(lastX, lastY))
    }
}
