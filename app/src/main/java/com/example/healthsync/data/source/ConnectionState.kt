package com.example.healthsync.data.source

/**
 * 数据源连接状态枚举（Milestone 1/4，DESIGN §4.1）。
 *
 * - CONNECTED：已连接，可正常产生数据。
 * - DISCONNECTED：已断开。用于模拟蓝牙断连，此时停止产生数据。
 * - RECONNECTING：重连中。用于展示"正在重新连接..."的过渡状态。
 *
 * UI 可订阅此状态流，当非 CONNECTED 时显示警告提示（Milestone 4）。
 */
enum class ConnectionState {
    CONNECTED, DISCONNECTED, RECONNECTING
}
