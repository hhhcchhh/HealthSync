package com.example.healthsync.data.source

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ManualInputSource @Inject constructor() : HealthDataSource {

    companion object {
        const val SOURCE_ID = "manual_input"
    }

    private val _dataEvents = MutableSharedFlow<HealthEvent>(extraBufferCapacity = 16)
    override val dataEvents: Flow<HealthEvent> = _dataEvents.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.CONNECTED)
    override val connectionState: Flow<ConnectionState> = _connectionState.asStateFlow()

    override suspend fun start() { /* always connected */ }
    override suspend fun stop() { /* no-op */ }

    suspend fun submitSleepRecord(record: HealthEvent.SleepRecord) {
        _dataEvents.emit(record)
    }
}
