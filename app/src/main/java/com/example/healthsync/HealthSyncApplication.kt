package com.example.healthsync

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.healthsync.data.sync.SyncCoordinator
import com.example.healthsync.data.sync.SyncWorkScheduler
import com.example.healthsync.di.ApplicationScope
import com.example.healthsync.domain.usecase.StartDataSourceUseCase
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 应用入口（Milestone 0/3/7，见 docs/SUMMARY.md）。
 *
 * 启动时完成：**recover**（SYNCING→LOCAL_PENDING，杀进程可恢复）、**模拟数据源采集**、**触发一轮同步**、
 * **WorkManager 周期兜底**（UniquePeriodicWork，15 分钟）。数据层以 Room 为事实来源，同步由 outbox 状态机驱动。
 */
@HiltAndroidApp
class HealthSyncApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncCoordinator: SyncCoordinator
    @Inject lateinit var syncWorkScheduler: SyncWorkScheduler
    @Inject lateinit var startDataSourceUseCase: StartDataSourceUseCase
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // 与设计一致：先恢复卡住的 SYNCING，再采集心率/步数事件，并立即推进同步；周期任务单独调度。
        appScope.launch {
            syncCoordinator.recover()
            startDataSourceUseCase()
            syncCoordinator.triggerSync()
        }

        syncWorkScheduler.schedulePeriodicSync()
    }
}
