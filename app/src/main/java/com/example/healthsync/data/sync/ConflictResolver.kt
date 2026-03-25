package com.example.healthsync.data.sync

import com.example.healthsync.data.local.dao.SleepRecordDao
import com.example.healthsync.data.local.entity.SleepQuality
import com.example.healthsync.data.local.entity.SleepRecordEntity
import com.example.healthsync.data.local.entity.SyncState
import com.example.healthsync.data.remote.ConflictResponse
import com.google.gson.Gson
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 冲突解决策略枚举（Milestone 5，DESIGN §7.2）。
 *
 * 当睡眠记录发生版本冲突时（HTTP 409）的解决方式：
 * - KEEP_LOCAL：保留本地版本，状态回到 LOCAL_PENDING 重新同步
 * - KEEP_REMOTE：采用服务端版本，状态直达 SYNCED
 * - MERGE：合并本地与服务端（简单策略：时间区间取并集，质量取本地）
 */
enum class ConflictResolution {
    KEEP_LOCAL, KEEP_REMOTE, MERGE
}

/**
 * 冲突解决器（Milestone 5，DESIGN §7）。
 *
 * 职责：
 * 1. 处理冲突：当 SyncEngine 检测到 409 冲突时，记录冲突状态并保存服务端快照
 * 2. 解决冲突：根据用户选择的策略（保留本地/保留远端/合并）更新记录
 *
 * DESIGN §7.2-§7.4 中详细讨论了冲突处理策略选择。本实现选择"保留双方并标记冲突"，
 * 不丢数据且可审计，虽然实现复杂但更符合离线优先系统的需求。
 */
@Singleton
class ConflictResolver @Inject constructor(
    private val sleepRecordDao: SleepRecordDao,
    private val gson: Gson
) {

    /**
     * 处理冲突：标记记录为 CONFLICT 状态并保存服务端快照。
     * 由 SyncEngine 在收到 HTTP 409 时调用（DESIGN §7.2）。
     *
     * @param recordId 睡眠记录 ID
     * @param conflict 冲突响应（包含服务端数据）
     */
    suspend fun handleConflict(recordId: String, conflict: ConflictResponse) {
        val snapshot = gson.toJson(conflict.serverData)
        sleepRecordDao.markConflict(recordId, snapshot)
    }

    /**
     * 解决冲突：根据用户选择的策略更新睡眠记录。
     *
     * @param recordId 睡眠记录 ID
     * @param resolution 解决策略（KEEP_LOCAL/KEEP_REMOTE/MERGE）
     */
    suspend fun resolveConflict(recordId: String, resolution: ConflictResolution) {
        val record = sleepRecordDao.getById(recordId) ?: return
        val serverData = record.serverSnapshot?.let {
            gson.fromJson(it, com.example.healthsync.data.remote.ServerSleepData::class.java)
        }

        when (resolution) {
            ConflictResolution.KEEP_LOCAL -> {
                // 保留本地版本：状态回到 LOCAL_PENDING，需要重新同步
                // baseRemoteVersion 使用服务端版本，以便下次同步时能重新检测冲突
                val serverVersion = serverData?.remoteVersion ?: record.remoteVersion
                sleepRecordDao.resolveConflict(
                    id = recordId,
                    syncState = SyncState.LOCAL_PENDING,
                    startTime = record.startTime,
                    endTime = record.endTime,
                    quality = record.quality.name,
                    baseRemoteVersion = serverVersion,
                    remoteVersion = serverVersion
                )
            }

            ConflictResolution.KEEP_REMOTE -> {
                // 采用服务端版本：使用服务端数据，状态直达 SYNCED
                if (serverData != null) {
                    sleepRecordDao.resolveConflict(
                        id = recordId,
                        syncState = SyncState.SYNCED,
                        startTime = serverData.startTime,
                        endTime = serverData.endTime,
                        quality = serverData.quality,
                        baseRemoteVersion = serverData.remoteVersion,
                        remoteVersion = serverData.remoteVersion
                    )
                }
            }

            ConflictResolution.MERGE -> {
                // 合并策略：时间区间取并集（开始时间取 min，结束时间取 max），
                // 质量保持本地（假设本地质量评分更新、更可信）
                // DESIGN §7.4 中讨论了其他合并策略的可能性
                if (serverData != null) {
                    val mergedStartTime = minOf(record.startTime, serverData.startTime)
                    val mergedEndTime = maxOf(record.endTime, serverData.endTime)
                    val mergedQuality = record.quality.name

                    sleepRecordDao.resolveConflict(
                        id = recordId,
                        syncState = SyncState.LOCAL_PENDING,
                        startTime = mergedStartTime,
                        endTime = mergedEndTime,
                        quality = mergedQuality,
                        baseRemoteVersion = serverData.remoteVersion,
                        remoteVersion = serverData.remoteVersion
                    )
                }
            }
        }
    }
}
