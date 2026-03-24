package com.example.healthsync.data.source

import kotlinx.coroutines.flow.Flow

interface HealthDataSource {
    val dataEvents: Flow<HealthEvent>
    val connectionState: Flow<ConnectionState>
    suspend fun start()
    suspend fun stop()
}
