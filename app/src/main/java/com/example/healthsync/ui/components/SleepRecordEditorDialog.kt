package com.example.healthsync.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.healthsync.data.local.entity.SleepQuality
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 新增睡眠记录的最小输入对话框。
 *
 * 设计取舍：
 * - 不引入复杂的日期/时间选择器，使用 "HH:mm" 文本输入以降低实现与测试成本
 * - 默认日期为“今天”；若结束时间早于开始时间，则视为跨天（结束日期 +1）
 */
@Composable
fun SleepRecordEditorDialog(
    onDismiss: () -> Unit,
    onSave: (startTimeMs: Long, endTimeMs: Long, quality: SleepQuality) -> Unit
) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("H:mm") }

    var startTimeText by rememberSaveable { mutableStateOf("23:00") }
    var endTimeText by rememberSaveable { mutableStateOf("07:00") }
    var selectedQuality by rememberSaveable { mutableStateOf(SleepQuality.GOOD) }

    var qualityExpanded by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    fun parseLocalTime(text: String): LocalTime? {
        return try {
            LocalTime.parse(text.trim(), timeFormatter)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun validateAndBuildEpoch(): Pair<Long, Long>? {
        val start = parseLocalTime(startTimeText) ?: run {
            errorText = "开始时间格式不正确，请输入 HH:mm（例如 23:00）"
            return null
        }
        val end = parseLocalTime(endTimeText) ?: run {
            errorText = "结束时间格式不正确，请输入 HH:mm（例如 07:00）"
            return null
        }

        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()

        val startDateTime = today.atTime(start)
        val endDate = if (end <= start) today.plusDays(1) else today
        val endDateTime = endDate.atTime(end)

        val startMs = startDateTime.atZone(zone).toInstant().toEpochMilli()
        val endMs = endDateTime.atZone(zone).toInstant().toEpochMilli()

        if (endMs <= startMs) {
            errorText = "结束时间必须晚于开始时间"
            return null
        }

        errorText = null
        return startMs to endMs
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "新增睡眠记录") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = startTimeText,
                    onValueChange = { startTimeText = it },
                    label = { Text("开始时间（HH:mm）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None
                    )
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = endTimeText,
                    onValueChange = { endTimeText = it },
                    label = { Text("结束时间（HH:mm）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None
                    )
                )
                Spacer(Modifier.height(12.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = selectedQuality.name,
                        onValueChange = { /* readOnly */ },
                        readOnly = true,
                        label = { Text("睡眠质量") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { qualityExpanded = true },
                        trailingIcon = { Text("▼") }
                    )
                    DropdownMenu(
                        expanded = qualityExpanded,
                        onDismissRequest = { qualityExpanded = false }
                    ) {
                        SleepQuality.entries.forEach { q ->
                            DropdownMenuItem(
                                text = { Text(q.name) },
                                onClick = {
                                    selectedQuality = q
                                    qualityExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "提示：默认日期为今天；若结束时间早于开始时间，会按跨天处理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (errorText != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = errorText!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val times = validateAndBuildEpoch() ?: return@TextButton
                    onSave(times.first, times.second, selectedQuality)
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

