package com.example.healthsync.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager 周期同步任务调度器（Milestone 3，DESIGN §6.1）。
 *
 * 使用 WorkManager 的 UniquePeriodicWork 排程一个 15 分钟周期的同步任务，
 * 作为前台循环的兜底机制，确保即使 App 被杀也能继续同步。
 *
 * 约束条件：需要网络连接（NetworkType.CONNECTED）。
 */
@Singleton
class SyncWorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val UNIQUE_WORK_NAME = "healthsync_periodic_sync"
        private const val REPEAT_INTERVAL_MINUTES = 15L
    }

    /**
     * 调度周期同步任务。
     * 使用 enqueueUniquePeriodicWork + KEEP 策略避免重复排程同一任务。
     */
    fun schedulePeriodicSync() {
        // 添加网络约束：仅当设备联网时执行
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // 创建周期工作请求
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            REPEAT_INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        // 使用 enqueueUniquePeriodicWork 排程
        // KEEP 策略：若该名称的周期任务已存在，保留现有任务（避免重复排程）
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
