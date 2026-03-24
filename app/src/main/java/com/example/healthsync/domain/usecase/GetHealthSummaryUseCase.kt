package com.example.healthsync.domain.usecase

import com.example.healthsync.data.local.entity.HeartRateEntity
import com.example.healthsync.data.repository.HealthRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetHealthSummaryUseCase @Inject constructor(
    private val repository: HealthRepository
) {
    fun getRecentHeartRates(sinceMs: Long): Flow<List<HeartRateEntity>> =
        repository.getRecentHeartRates(sinceMs)

    fun getLatestHeartRate(): Flow<HeartRateEntity?> =
        repository.getLatestHeartRate()

    fun getTodaySteps(todayStartMs: Long): Flow<Int> =
        repository.getTodaySteps(todayStartMs)

    fun getPendingSyncCount(): Flow<Int> =
        repository.getPendingSyncCount()
}
