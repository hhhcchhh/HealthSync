package com.example.healthsync.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 步数数据本地模型（Milestone 1，DESIGN §5.2）。
 *
 * 与 [HeartRateEntity] 类似，步数也是 append-only 事件，仅记录增量步数而非累计值。
 * 包含同步状态字段与去重字段（eventId），便于幂等上传与重复记录处理。
 */
@Entity(
    tableName = "step_count",
    indices = [Index(value = ["eventId"], unique = true)]
)
data class StepCountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    /** 本次增加的步数（增量而非累计）。 */
    val steps: Int,
    val sourceId: String,
    /** 客户端生成的唯一事件标识，用于去重。 */
    val eventId: String,
    val syncState: SyncState = SyncState.LOCAL_PENDING,
    val attemptCount: Int = 0,
    val nextAttemptAt: Long = 0L,
    val lastError: String? = null,
    val remoteId: String? = null
)
