package com.example.healthsync.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.NumberFormat

/**
 * 步数环形进度条组件（Milestone 6，DESIGN §10.2）。
 *
 * 用 Canvas drawArc 绘制背景轨道与前景进度弧，中间居中显示当前步数和目标值。
 * 进度值会被 clamp 到 [0, 1] 区间，超过目标时展示满环。
 *
 * @param steps     当前累计步数
 * @param goal      每日目标步数，默认 10,000
 * @param size      环形控件整体尺寸（宽高一致）
 * @param strokeWidth 环形轨道与进度弧的线宽
 * @param modifier  外部传入的 Modifier
 */
@Composable
fun StepRingProgress(
    steps: Int,
    goal: Int = 10_000,
    size: Dp = 180.dp,
    strokeWidth: Dp = 12.dp,
    modifier: Modifier = Modifier
) {
    val progress = (steps.toFloat() / goal).coerceIn(0f, 1f)
    val trackColor = Color(0xFFE0E0E0)
    val progressColor = Color(0xFF43A047)

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke
            )
            drawArc(
                color = progressColor,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = stroke
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = NumberFormat.getInstance().format(steps),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "/ ${NumberFormat.getInstance().format(goal)} 步",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
