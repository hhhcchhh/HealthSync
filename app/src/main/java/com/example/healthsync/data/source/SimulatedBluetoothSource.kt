package com.example.healthsync.data.source

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

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

    override val dataEvents: Flow<HealthEvent> = flow {
        var heartRateCounter = 0L
        var stepCounter = 0L
        var totalElapsed = 0L

        while (running) {
            if (_connectionState.value != ConnectionState.CONNECTED) {
                delay(500)
                continue
            }

            delay(HEART_RATE_INTERVAL_MS)
            totalElapsed += HEART_RATE_INTERVAL_MS
            heartRateCounter += HEART_RATE_INTERVAL_MS

            val bpm = Random.nextInt(55, 125)
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

            if (disconnectEnabled && totalElapsed >= DISCONNECT_AFTER_MS) {
                simulateDisconnect()
                totalElapsed = 0
            }
        }
    }

    override suspend fun start() {
        if (running) return
        running = true
        _connectionState.value = ConnectionState.CONNECTED
    }

    override suspend fun stop() {
        running = false
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private suspend fun simulateDisconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
        delay(DISCONNECT_DURATION_MS / 2)
        _connectionState.value = ConnectionState.RECONNECTING
        delay(DISCONNECT_DURATION_MS / 2)
        _connectionState.value = ConnectionState.CONNECTED
    }
}
