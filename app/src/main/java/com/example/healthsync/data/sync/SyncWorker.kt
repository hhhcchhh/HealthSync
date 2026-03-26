package com.example.healthsync.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * WorkManager Worker（Milestone 3，DESIGN §6.1）。
 *
 * 触发方式：由 WorkManager 根据 UniquePeriodicWork（15 分钟）或手动排程调用。
 * 职责：
 * - 恢复卡在 SYNCING 的记录（recover）
 * - 触发一轮同步（triggerSync）
 * 作为兜底机制，确保即使 App 被杀后，后台仍能继续同步。
 *
 * @HiltWorker 注解使得 WorkManager 能注入依赖，无需手动 Factory。
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncCoordinator: SyncCoordinator,
    private val logger: SyncLogger
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SyncWorker"
    }

    override suspend fun doWork(): Result {
        logger.i(TAG, "WorkManager sync triggered")
        return try {
            syncCoordinator.recover()
            syncCoordinator.triggerSync()
            Result.success()
        } catch (e: CancellationException) {
            // 避免吞掉协程取消：WorkManager 停止/取消任务时应直接向上抛出
            logger.w(TAG, "Sync worker cancelled", e)
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Sync worker failed", e)
            Result.retry()
        }
    }
}
