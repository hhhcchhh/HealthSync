package com.example.healthsync.data.source

import com.example.healthsync.data.local.entity.SleepQuality

sealed class HealthEvent {
    data class HeartRateSample(
        val timestamp: Long,
        val bpm: Int,
        val sourceId: String
    ) : HealthEvent()

    data class StepCountIncrement(
        val timestamp: Long,
        val steps: Int,
        val sourceId: String
    ) : HealthEvent()

    data class SleepRecord(
        val id: String,
        val startTime: Long,
        val endTime: Long,
        val quality: SleepQuality,
        val sourceId: String
    ) : HealthEvent()
}
