package com.example.healthsync.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 心率数据本地模型（Milestone 1，DESIGN §5.2）。
 *
 * 作为 Room 中的事实来源，包含：
 * - 业务字段：timestamp（采样时间），bpm（心率值），sourceId（数据来源）
 * - 同步状态字段：syncState, attemptCount, nextAttemptAt, lastError, remoteId
 * - 去重字段：eventId（客户端生成的唯一标识，配合 Room 唯一索引与云端去重，DESIGN §6.5）
 *
 * 心率样本是 append-only 事件，不存在编辑场景，因此无需 localVersion/baseRemoteVersion/serverSnapshot。
 */
@Entity(
    tableName = "heart_rate",
    indices = [Index(value = ["eventId"], unique = true)]
)
data class HeartRateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val bpm: Int,
    val sourceId: String,
    /** 客户端生成的唯一事件标识（UUID），用于幂等去重（DESIGN §6.5）。 */
    val eventId: String,
    /** 同步状态：LOCAL_PENDING → SYNCING → SYNCED（或 SYNC_FAILED） */
    val syncState: SyncState = SyncState.LOCAL_PENDING,
    /** 已失败次数（从 0 开始）；满 3 次进入 SYNC_FAILED（DESIGN §6.6）。 */
    val attemptCount: Int = 0,
    /** 下次允许重试的时间戳（毫秒）。未来同步时跳过 nextAttemptAt > now 的记录。 */
    val nextAttemptAt: Long = 0L,
    /** 最近一次同步失败的错误信息（用于调试）。成功同步后清除。 */
    val lastError: String? = null,
    /** 云端分配的远程 ID（同步成功后回写）。 */
    val remoteId: String? = null
)
