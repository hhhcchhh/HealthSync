package com.example.healthsync.data.source

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 手动输入数据源（Milestone 5，DESIGN §4.3）。
 *
 * 用于用户手动录入/编辑睡眠记录。与模拟蓝牙不同，此源：
 * - connectionState 始终为 CONNECTED（手动输入不依赖外部连接）
 * - dataEvents 通过外部调用 submitSleepRecord 主动发出（不产生自主事件）
 * - 支持离线编辑"已同步"的睡眠记录，以触发冲突链路验证
 *
 * 由 UI/UseCase 调用 submitSleepRecord 发出 SleepRecord 事件，
 * Repository 订阅并落库。
 */
@Singleton
class ManualInputSource @Inject constructor() : HealthDataSource {

    companion object {
        const val SOURCE_ID = "manual_input"
    }

    /** 睡眠记录发出流（主动触发）。 */
    private val _dataEvents = MutableSharedFlow<HealthEvent>(extraBufferCapacity = 16)
    override val dataEvents: Flow<HealthEvent> = _dataEvents.asSharedFlow()

    /** 连接状态始终为 CONNECTED。 */
    private val _connectionState = MutableStateFlow(ConnectionState.CONNECTED)
    override val connectionState: Flow<ConnectionState> = _connectionState.asStateFlow()

    override suspend fun start() { /* 无需启动，手动输入始终可用 */ }
    override suspend fun stop() { /* 无需停止 */ }

    /**
     * 提交睡眠记录事件。由 UI/UseCase 调用以发出睡眠数据。
     * @param record 睡眠记录事件
     */
    suspend fun submitSleepRecord(record: HealthEvent.SleepRecord) {
        _dataEvents.emit(record)
    }
}
