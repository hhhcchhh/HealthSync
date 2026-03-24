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

        appScope.launch {
            syncCoordinator.recover()
            startDataSourceUseCase()
            syncCoordinator.triggerSync()
        }

        syncWorkScheduler.schedulePeriodicSync()
    }
}
