package com.example.healthsync.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 睡眠记录本地模型（Milestone 5，DESIGN §5.2）。
 *
 * 与 [HeartRateEntity]/[StepCountEntity] 不同，睡眠记录是**可编辑的业务对象**（带业务主键 UUID），
 * 需要完整的冲突检测与解决字段支持。
 *
 * 字段分类：
 * - **业务字段**：id(UUID), startTime, endTime, quality
 * - **同步状态字段**：syncState, attemptCount, nextAttemptAt, lastError, remoteId
 * - **版本控制字段**（用于冲突检测，DESIGN §7.1）：
 *   - remoteVersion: 上次同步时的云端版本号
 *   - baseRemoteVersion: 本次编辑基于的云端版本号（用于乐观锁）
 *   - localVersion: 本地编辑版本号（每次编辑 +1）
 * - **冲突字段**（DESIGN §7.2）：
 *   - serverSnapshot: 冲突时存储的服务端数据 JSON 快照
 */
@Entity(tableName = "sleep_record")
data class SleepRecordEntity(
    @PrimaryKey val id: String,
    val startTime: Long,
    val endTime: Long,
    val quality: SleepQuality,
    val sourceId: String,
    val syncState: SyncState = SyncState.LOCAL_PENDING,
    val attemptCount: Int = 0,
    val nextAttemptAt: Long = 0L,
    val lastError: String? = null,
    val remoteId: String? = null,
    /** 上次同步成功时的云端版本号。 */
    val remoteVersion: Int = 0,
    /** 编辑时看到的云端版本号。上传时用于冲突检测（乐观锁）。 */
    val baseRemoteVersion: Int = 0,
    /** 本地编辑版本号。每次编辑时递增，用于区分不同轮次编辑。 */
    val localVersion: Int = 1,
    /** 冲突时存储的服务端数据 JSON 快照，供后续冲突解决使用（DESIGN §7.2）。 */
    val serverSnapshot: String? = null
)
