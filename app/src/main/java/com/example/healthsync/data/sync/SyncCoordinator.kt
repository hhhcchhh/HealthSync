package com.example.healthsync.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同步触发与并发治理（Milestone 3，DESIGN §6.1）：
 *
 * - **Mutex**：进程内单实例，避免 WorkManager / 下拉刷新 / 前台循环并行重复跑同一批上传
 * - **前台循环**：`syncOnce` 直到无活可干，再按最近 `nextAttemptAt` delay（上限 30s），使 2s/4s/8s 退避不被粗粒度轮询吃掉
 * - [triggerSync]：手动刷新与 Worker 共用入口
 */
@Singleton
class SyncCoordinator @Inject constructor(
    private val syncEngine: SyncEngine,
    private val logger: SyncLogger
) {
    companion object {
        private const val TAG = "SyncCoordinator"
        private const val MAX_IDLE_WAIT_MS = 30_000L
    }

    private val mutex = Mutex()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing = _isSyncing.asStateFlow()

    private var foregroundJob: Job? = null

    /**
     * Starts the foreground continuous push loop.
     * Repeatedly runs syncOnce() until no work remains, then aligns
     * to the nearest nextAttemptAt (capped at MAX_IDLE_WAIT_MS).
     */
    fun startForegroundLoop(scope: CoroutineScope) {
        if (foregroundJob?.isActive == true) {
            logger.d(TAG, "foreground loop already running, skip")
            return
        }
        foregroundJob = scope.launch {
            logger.i(TAG, "foreground sync loop started")
            while (true) {
                val didWork = runSyncPass()
                if (!didWork) {
                    val nextTime = syncEngine.getEarliestPendingTime()
                    val now = System.currentTimeMillis()
                    val waitMs = if (nextTime != null && nextTime > now) {
                        (nextTime - now).coerceAtMost(MAX_IDLE_WAIT_MS)
                    } else {
                        MAX_IDLE_WAIT_MS
                    }
                    delay(waitMs)
                }
            }
        }
    }

    fun stopForegroundLoop() {
        foregroundJob?.cancel()
        foregroundJob = null
        logger.i(TAG, "foreground sync loop stopped")
    }

    /**
     * Triggers one immediate sync pass (used by manual refresh / WorkManager).
     * If a sync is already running, this call waits for the mutex instead
     * of starting a parallel pass.
     */
    suspend fun triggerSync() {
        runSyncPass()
    }

    suspend fun recover() {
        mutex.withLock {
            syncEngine.recover()
        }
    }

    private suspend fun runSyncPass(): Boolean {
        if (!mutex.tryLock()) {
            logger.d(TAG, "sync already in progress, skipping")
            return false
        }
        return try {
            _isSyncing.value = true
            syncEngine.syncOnce()
        } finally {
            _isSyncing.value = false
            mutex.unlock()
        }
    }
}
