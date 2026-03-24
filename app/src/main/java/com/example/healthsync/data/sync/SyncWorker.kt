package com.example.healthsync.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncCoordinator: SyncCoordinator
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SyncWorker"
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "WorkManager sync triggered")
        return try {
            syncCoordinator.recover()
            syncCoordinator.triggerSync()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync worker failed", e)
            Result.retry()
        }
    }
}
