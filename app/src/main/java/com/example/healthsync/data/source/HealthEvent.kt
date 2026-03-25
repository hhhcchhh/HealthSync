package com.example.healthsync.data.source

import com.example.healthsync.data.local.entity.SleepQuality

/**
 * 统一的健康事件模型（Milestone 1，DESIGN §4.2）。
 * 所有数据源产生的数据都包装为 [HealthEvent] 的某个子类型，
 * 确保不同数据源的接口统一，便于 Repository 统一处理。
 */
sealed class HealthEvent {
    /**
     * 心率样本事件（append-only，不可编辑）。
     * 每个样本是独立的采样点，无需业务 id，由 Room 的 autoGenerate 主键标识。
     *
     * @param timestamp 采样时间戳（毫秒）。统一使用 epoch millis 便于跨层传递与排序。
     * @param bpm 心率值（beats per minute）。范围校验可在数据源或 UseCase 侧完成。
     * @param sourceId 数据来源标识，用于区分模拟蓝牙/真实蓝牙/Health Connect/手动输入等。
     */
    data class HeartRateSample(
        val timestamp: Long,
        val bpm: Int,
        val sourceId: String
    ) : HealthEvent()

    /**
     * 步数增量事件（append-only，不可编辑）。
     * 记录的是本次增加的步数增量，而非累计值，便于离线合并与追溯。
     *
     * @param timestamp 记录时间戳（毫秒）。
     * @param steps 本次增加的步数增量。
     * @param sourceId 数据来源标识。
     */
    data class StepCountIncrement(
        val timestamp: Long,
        val steps: Int,
        val sourceId: String
    ) : HealthEvent()

    /**
     * 睡眠记录事件（可编辑的业务对象，Milestone 5）。
     * 与心率/步数不同，睡眠记录需要在离线状态下修改，涉及冲突检测与解决。
     * 因此在事件模型中显式携带业务主键 [id]（UUID），避免与云端/其他设备冲突。
     *
     * @param id 业务主键（UUID），离线创建时即生成，保证唯一性且不与云端冲突。
     * @param startTime 睡眠开始时间戳（毫秒）。
     * @param endTime 睡眠结束时间戳（毫秒）。要求 endTime > startTime。
     * @param quality 睡眠质量枚举（POOR/FAIR/GOOD/EXCELLENT），用于展示与冲突合并。
     * @param sourceId 数据来源标识，通常为 ManualInput（手动录入）。
     */
    data class SleepRecord(
        val id: String,
        val startTime: Long,
        val endTime: Long,
        val quality: SleepQuality,
        val sourceId: String
    ) : HealthEvent()
}
