package com.example.healthsync.domain.usecase

import com.example.healthsync.data.sync.ConflictResolution
import com.example.healthsync.data.sync.ConflictResolver
import javax.inject.Inject

/**
 * 解决冲突 UseCase（Milestone 5，DESIGN §7.2）。
 *
 * 封装 [ConflictResolver.resolveConflict] 为 UseCase，供 UI/ViewModel 调用。
 */
class ResolveConflictUseCase @Inject constructor(
    private val conflictResolver: ConflictResolver
) {
    suspend operator fun invoke(recordId: String, resolution: ConflictResolution) {
        conflictResolver.resolveConflict(recordId, resolution)
    }
}
