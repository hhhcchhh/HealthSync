package com.example.healthsync.domain.usecase

import com.example.healthsync.data.local.entity.SleepQuality
import com.example.healthsync.data.repository.HealthRepository
import com.example.healthsync.data.source.HealthEvent
import com.example.healthsync.data.source.ManualInputSource
import java.util.UUID
import javax.inject.Inject

/**
 * 保存睡眠记录 UseCase（Milestone 5，DESIGN §7）。
 *
 * 通过 [ManualInputSource] 发出睡眠事件，统一走"数据源 -> Repository"主链路（DESIGN §4.3）。
 * UseCase 仅负责输入校验与组装事件，不直接操作 DAO。
 *
 * 支持两种场景：
 * 1. **新建**：生成 UUID 主键，syncState = LOCAL_PENDING
 * 2. **编辑已存在记录**：以同一 id 发出事件，具体版本字段更新由 Repository 的 sleep 编辑逻辑统一处理
 */
class SaveSleepRecordUseCase @Inject constructor(
    private val repository: HealthRepository,
    private val manualInputSource: ManualInputSource
) {
    /**
     * 创建新的睡眠记录。
     * @return the generated record ID
     */
    suspend fun createNew(
        startTime: Long,
        endTime: Long,
        quality: SleepQuality,
        sourceId: String = "manual_input"
    ): String {
        val id = UUID.randomUUID().toString()
        // 手动录入通过 ManualInputSource 发事件，统一走“数据源 -> Repository”主链路。
        manualInputSource.submitSleepRecord(
            HealthEvent.SleepRecord(
                id = id,
                startTime = startTime,
                endTime = endTime,
                quality = quality,
                sourceId = sourceId
            )
        )
        return id
    }

    /**
     * 编辑已存在的睡眠记录（通常是已同步的记录）。
     * 将状态回退到 LOCAL_PENDING 并递增 localVersion，
     * 设置 baseRemoteVersion 为当前 remoteVersion 以便冲突检测。
     */
    suspend fun edit(
        id: String,
        startTime: Long,
        endTime: Long,
        quality: SleepQuality
    ) {
        val existing = repository.getSleepRecordById(id) ?: return
        manualInputSource.submitSleepRecord(
            HealthEvent.SleepRecord(
                id = existing.id,
                startTime = startTime,
                endTime = endTime,
                quality = quality,
                sourceId = existing.sourceId
            )
        )
    }
}
