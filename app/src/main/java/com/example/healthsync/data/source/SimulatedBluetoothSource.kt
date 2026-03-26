package com.example.healthsync.data.source

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 模拟蓝牙数据源实现（Milestone 1/4，DESIGN §4.3）。
 *
 * 每 2 秒发出一个心率采样（60-120 bpm）、每 30 秒发出一个步数增量（1-20 步）。
 * 支持模拟断连/重连，用于测试 UI 降级与重连逻辑（Milestone 4）。
 */
@Singleton
class SimulatedBluetoothSource @Inject constructor() : HealthDataSource {

    companion object {
        const val SOURCE_ID = "simulated_bluetooth"
        private const val HEART_RATE_INTERVAL_MS = 2_000L
        private const val STEP_COUNT_INTERVAL_MS = 30_000L
        private const val DISCONNECT_AFTER_MS = 60_000L
        private const val DISCONNECT_DURATION_MS = 10_000L
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: Flow<ConnectionState> = _connectionState.asStateFlow()

    @Volatile
    private var running = false

    private var disconnectEnabled = true

    /**
     * 数据事件流实现：每 2s 发一个心率，每 30s 发一个步数增量。
     * 当 connectionState 非 CONNECTED 时，暂停产生数据（模拟蓝牙断连行为）。
     * 每 60s 自动触发一次模拟断连/重连周期，以验证 UI 降级与恢复能力。
     */
    override val dataEvents: Flow<HealthEvent> = flow {
        var heartRateCounter = 0L
        var stepCounter = 0L
        var totalElapsed = 0L

        // 关键点：dataEvents 必须“常驻”，不能因 running=false 就结束；
        // 否则在先 collectFrom() 后 start() 的启动顺序下，会订阅即结束，导致心率不再产生。
        while (currentCoroutineContext().isActive) {
            if (!running || _connectionState.value != ConnectionState.CONNECTED) {
                delay(500)
                continue
            }

            delay(HEART_RATE_INTERVAL_MS)
            totalElapsed += HEART_RATE_INTERVAL_MS
            heartRateCounter += HEART_RATE_INTERVAL_MS

            val bpm = Random.nextInt(60, 121)
            emit(
                HealthEvent.HeartRateSample(
                    timestamp = System.currentTimeMillis(),
                    bpm = bpm,
                    sourceId = SOURCE_ID
                )
            )

            stepCounter += HEART_RATE_INTERVAL_MS
            if (stepCounter >= STEP_COUNT_INTERVAL_MS) {
                stepCounter = 0
                val steps = Random.nextInt(1, 21)
                emit(
                    HealthEvent.StepCountIncrement(
                        timestamp = System.currentTimeMillis(),
                        steps = steps,
                        sourceId = SOURCE_ID
                    )
                )
            }

            // 每 60s 自动模拟一次断连/重连周期，用于验证异常场景（Milestone 4）
            if (disconnectEnabled && totalElapsed >= DISCONNECT_AFTER_MS) {
                simulateDisconnect()
                totalElapsed = 0
            }
        }
    }

    /**
     * 启动数据采集。幂等实现，重复调用不抛异常。
     */
    override suspend fun start() {
        if (running) return
        running = true
        _connectionState.value = ConnectionState.CONNECTED
    }

    /**
     * 停止数据采集。幂等实现。
     */
    override suspend fun stop() {
        running = false
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * 模拟蓝牙断连：切换为 DISCONNECTED，延迟后切为 RECONNECTING，再延迟后恢复 CONNECTED。
     * 用于验证 UI 在设备断连时的降级展示与自动恢复能力（Milestone 4）。
     */
    private suspend fun simulateDisconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
        delay(DISCONNECT_DURATION_MS / 2)
        _connectionState.value = ConnectionState.RECONNECTING
        delay(DISCONNECT_DURATION_MS / 2)
        _connectionState.value = ConnectionState.CONNECTED
    }
}
