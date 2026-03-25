package com.example.healthsync.data.local.entity

/**
 * 睡眠质量枚举（Milestone 5，DESIGN §4.2）。
 *
 * 用于展示与统计，以及冲突合并时的选择依据。
 */
enum class SleepQuality {
    POOR,       // 睡眠质量差
    FAIR,       // 一般
    GOOD,       // 良好
    EXCELLENT   // 优秀
}
