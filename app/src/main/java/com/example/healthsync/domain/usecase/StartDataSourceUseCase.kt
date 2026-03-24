package com.example.healthsync.domain.usecase

import com.example.healthsync.data.repository.HealthRepository
import com.example.healthsync.data.source.SimulatedBluetoothSource
import com.example.healthsync.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StartDataSourceUseCase @Inject constructor(
    private val simulatedSource: SimulatedBluetoothSource,
    private val repository: HealthRepository,
    @ApplicationScope private val appScope: CoroutineScope
) {
    suspend operator fun invoke() {
        repository.collectFrom(simulatedSource, appScope)
        simulatedSource.start()
    }

    suspend fun stop() {
        simulatedSource.stop()
    }
}
