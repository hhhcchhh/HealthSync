package com.example.healthsync.data.source

import kotlinx.coroutines.flow.Flow

/**
 * 统一的数据源抽象接口（Milestone 1，DESIGN §4.1）。
 *
 * 所有数据源（蓝牙、手动输入、Health Connect 等）均实现该接口，
 * 使得 Repository 能统一订阅事件并写入 Room，无需关注具体数据来源。
 * 数据源的扩展性在于新增实现类 + DI 注册，无需修改核心 sync/repository 代码。
 */
interface HealthDataSource {
    /**
     * 数据事件流：心率/步数/睡眠等所有数据事件均通过此流发送给 Repository 写入库。
     * Repository 订阅并映射为对应 Entity，实现离线优先（先落库后同步）。
     */
    val dataEvents: Flow<HealthEvent>

    /**
     * 连接状态流：CONNECTED/DISCONNECTED/RECONNECTING。
     * 用于 UI 展示设备连接状态，以及同步/采集策略判断（如断连时暂停产生数据）。
     */
    val connectionState: Flow<ConnectionState>

    /**
     * 启动数据采集。实现应幂等，重复调用不抛异常，便于恢复与重试场景。
     */
    suspend fun start()

    /**
     * 停止数据采集。实现应幂等。
     */
    suspend fun stop()
}
