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
 *
 * 设计要点：
 * - 时间戳使用 Double/Float 精度保留，避免精度丢失导致 X 坐标崩溃
 * - points 按时间戳排序，计算窗口范围（最近 5 分钟）
 * - 坐标映射：timestamp → X 轴，BPM → Y 轴（倒序，上高下低）
 */
@Composable
fun HeartRateChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier
) {
    val lineColor = Color(0xFFE53935)           // 心率曲线颜色：红
    val abnormalHighZone = Color(0x30E53935)    // 高血压区间背景：半透明红
    val abnormalLowZone = Color(0x301E88E5)     // 低血压区间背景：半透明蓝
    val gridColor = Color(0x20000000)           // 网格线：浅灰

    Canvas(modifier = modifier.fillMaxWidth().height(200.dp)) {
        val minBpm = 40f
        val maxBpm = 160f
        val bpmRange = maxBpm - minBpm

        val highThreshold = 100f  // 偏高阈值
        val lowThreshold = 60f    // 偏低阈值

        // 绘制异常区间背景
        val highY = size.height * (1f - (highThreshold - minBpm) / bpmRange)
        val lowY = size.height * (1f - (lowThreshold - minBpm) / bpmRange)

        // 高 BPM 区间（>100）
        drawRect(
            color = abnormalHighZone,
            topLeft = Offset(0f, 0f),
            size = androidx.compose.ui.geometry.Size(size.width, highY)
        )
        // 低 BPM 区间（<60）
        drawRect(
            color = abnormalLowZone,
            topLeft = Offset(0f, lowY),
            size = androidx.compose.ui.geometry.Size(size.width, size.height - lowY)
        )

        // 绘制网格线（60/80/100/120 BPM）
        listOf(60f, 80f, 100f, 120f).forEach { bpm ->
            val y = size.height * (1f - (bpm - minBpm) / bpmRange)
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }

        if (points.size < 2) return@Canvas

        // 数据准备：按时间戳排序，计算 5 分钟时间窗口
        val sortedPoints = points.sortedBy { it.timestamp }
        val windowEndMs = sortedPoints.last().timestamp
        val windowStartMs = windowEndMs - FIVE_MINUTES_MS
        val timeRangeMs = FIVE_MINUTES_MS.toFloat().coerceAtLeast(1f)

        // 绘制心率曲线（Path）
        val path = Path()
        sortedPoints.forEachIndexed { index, point ->
            // 映射时间戳到 X 坐标
            val x =
                (((point.timestamp - windowStartMs).toFloat() / timeRangeMs) * size.width)
                    .coerceIn(0f, size.width)
            // 映射 BPM 到 Y 坐标（倒序：BPM 越高 Y 越小）
            val y =
                (size.height * (1f - (point.bpm.toFloat() - minBpm) / bpmRange))
                    .coerceIn(0f, size.height)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(path, lineColor, style = Stroke(width = 3f))

        // 绘制最新数据点的标记圆点
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
