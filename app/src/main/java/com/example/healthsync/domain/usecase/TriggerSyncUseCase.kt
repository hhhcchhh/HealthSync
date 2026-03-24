package com.example.healthsync.domain.usecase

import com.example.healthsync.data.sync.SyncCoordinator
import javax.inject.Inject

class TriggerSyncUseCase @Inject constructor(
    private val syncCoordinator: SyncCoordinator
) {
    suspend operator fun invoke() {
        syncCoordinator.triggerSync()
    }
}
