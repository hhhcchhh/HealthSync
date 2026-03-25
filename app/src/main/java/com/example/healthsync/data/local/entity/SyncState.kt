package com.example.healthsync.data.local.entity

/**
 * 同步状态机枚举（Milestone 2，DESIGN §5.1）。
 *
 * 每条数据在生命周期中按如下流转：
 * - LOCAL_PENDING：本地新增/修改，待同步。outbox 语义。
 * - SYNCING：同步引擎已抢占处理中（防重复上传）。
 * - SYNCED：与云端一致，同步完成。
 * - SYNC_FAILED：超过最大重试次数（3 次），停止自动重试。用户可手动重试。
 * - CONFLICT：检测到版本冲突（仅限可编辑数据如睡眠记录，Milestone 5）。
 *              保留本地和服务端两份数据，等待用户手动解决。
 */
enum class SyncState {
    /** 本地新增/修改，尚未与云端同步的数据。 */
    LOCAL_PENDING,
    /** 同步引擎正在处理中，避免多个任务重复上传同一条记录。 */
    SYNCING,
    /** 已与云端一致。 */
    SYNCED,
    /** 超过最大重试次数，停止自动重试。 */
    SYNC_FAILED,
    /** 版本冲突（本地修改与云端修改并发，需要解决）。 */
    CONFLICT
}
