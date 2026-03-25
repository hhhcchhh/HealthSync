package com.example.healthsync.domain.usecase

import com.example.healthsync.data.sync.SyncCoordinator
import javax.inject.Inject

/**
 * 触发同步 UseCase（Milestone 3/6）。
 *
 * 由 UI/ViewModel（下拉刷新）或其他调用者使用，触发一轮立即同步。
 * 委托给 SyncCoordinator，由其负责并发控制与前后台分界管理。
 */
class TriggerSyncUseCase @Inject constructor(
    private val syncCoordinator: SyncCoordinator
) {
    suspend operator fun invoke() {
        syncCoordinator.triggerSync()
    }
}
