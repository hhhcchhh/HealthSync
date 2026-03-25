package com.example.healthsync.domain.usecase

import com.example.healthsync.data.repository.HealthRepository
import com.example.healthsync.data.source.SimulatedBluetoothSource
import com.example.healthsync.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 启动数据源 UseCase（Milestone 1，DESIGN §2.1）。
 *
 * 职责：
 * 1. 订阅模拟蓝牙数据源的事件流，注册到 Repository（建立数据流管道）
 * 2. 启动数据源采集（调用 start）
 *
 * 执行流程：
 * - Application.onCreate 中调用 invoke
 * - Repository.collectFrom 在应用级 CoroutineScope 中建立事件流订阅，
 *   所有产生的 HealthEvent 都通过此管道写入 Room
 * - SimulatedBluetoothSource.start 开始定时产生模拟数据
 */
@Singleton
class StartDataSourceUseCase @Inject constructor(
    private val simulatedSource: SimulatedBluetoothSource,
    private val repository: HealthRepository,
    @ApplicationScope private val appScope: CoroutineScope
) {
    /**
     * 启动数据采集管道。
     * 必须在 suspend 上下文中调用（Application.onCreate 中通过 appScope.launch）。
     */
    suspend operator fun invoke() {
        // 在应用级 Scope 中建立从数据源 → Repository 的事件流订阅
        repository.collectFrom(simulatedSource, appScope)
        // 启动数据源（开始产生心率/步数事件）
        simulatedSource.start()
    }

    /**
     * 停止数据采集。应在 App 退出/pause 时调用。
     */
    suspend fun stop() {
        simulatedSource.stop()
    }
}
