package com.example.healthsync.data.remote

import com.example.healthsync.data.local.entity.SleepQuality
import kotlinx.coroutines.delay
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

data class HeartRateUploadItem(
    val eventId: String,
    val timestamp: Long,
    val bpm: Int,
    val sourceId: String
)

data class StepCountUploadItem(
    val eventId: String,
    val timestamp: Long,
    val steps: Int,
    val sourceId: String
)

data class SleepRecordUploadRequest(
    val id: String,
    val startTime: Long,
    val endTime: Long,
    val quality: String,
    val baseRemoteVersion: Int
)

data class UploadResult(
    val remoteId: String,
    val remoteVersion: Int = 0
)

data class ConflictResponse(
    val currentRemoteVersion: Int,
    val serverData: ServerSleepData
)

data class ServerSleepData(
    val startTime: Long,
    val endTime: Long,
    val quality: String,
    val remoteVersion: Int
)

class ApiException(message: String, val code: Int) : Exception(message)

@Singleton
class MockCloudApi @Inject constructor() {

    private val sleepStore = ConcurrentHashMap<String, ServerSleepRecord>()
    private val heartRateEventIds = ConcurrentHashMap.newKeySet<String>()
    private val stepCountEventIds = ConcurrentHashMap.newKeySet<String>()

    var failureRate: Double = 0.1
    var minDelayMs: Long = 200
    var maxDelayMs: Long = 1000
    var forceConflict: Boolean = false

    private data class ServerSleepRecord(
        val id: String,
        val startTime: Long,
        val endTime: Long,
        val quality: String,
        val remoteVersion: Int
    )

    suspend fun uploadHeartRates(items: List<HeartRateUploadItem>): List<UploadResult> {
        simulateNetworkDelay()
        maybeThrowNetworkError()

        return items.map { item ->
            heartRateEventIds.add(item.eventId)
            UploadResult(remoteId = "hr-${item.eventId}")
        }
    }

    suspend fun uploadStepCounts(items: List<StepCountUploadItem>): List<UploadResult> {
        simulateNetworkDelay()
        maybeThrowNetworkError()

        return items.map { item ->
            stepCountEventIds.add(item.eventId)
            UploadResult(remoteId = "sc-${item.eventId}")
        }
    }

    suspend fun uploadSleepRecord(request: SleepRecordUploadRequest): UploadResult {
        simulateNetworkDelay()
        maybeThrowNetworkError()

        val existing = sleepStore[request.id]

        if (forceConflict && existing != null) {
            val conflictData = ServerSleepData(
                startTime = existing.startTime,
                endTime = existing.endTime,
                quality = existing.quality,
                remoteVersion = existing.remoteVersion
            )
            throw ApiConflictException(
                ConflictResponse(
                    currentRemoteVersion = existing.remoteVersion,
                    serverData = conflictData
                )
            )
        }

        if (existing != null && request.baseRemoteVersion < existing.remoteVersion) {
            val conflictData = ServerSleepData(
                startTime = existing.startTime,
                endTime = existing.endTime,
                quality = existing.quality,
                remoteVersion = existing.remoteVersion
            )
            throw ApiConflictException(
                ConflictResponse(
                    currentRemoteVersion = existing.remoteVersion,
                    serverData = conflictData
                )
            )
        }

        val newVersion = (existing?.remoteVersion ?: 0) + 1
        sleepStore[request.id] = ServerSleepRecord(
            id = request.id,
            startTime = request.startTime,
            endTime = request.endTime,
            quality = request.quality,
            remoteVersion = newVersion
        )

        return UploadResult(remoteId = request.id, remoteVersion = newVersion)
    }

    private suspend fun simulateNetworkDelay() {
        val delayMs = Random.nextLong(minDelayMs, maxDelayMs + 1)
        delay(delayMs)
    }

    private fun maybeThrowNetworkError() {
        if (Random.nextDouble() < failureRate) {
            throw ApiException("Simulated network error", 500)
        }
    }
}

class ApiConflictException(val conflict: ConflictResponse) :
    Exception("VERSION_CONFLICT: server version ${conflict.currentRemoteVersion}")
