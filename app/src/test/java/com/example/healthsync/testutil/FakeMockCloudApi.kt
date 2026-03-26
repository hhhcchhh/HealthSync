package com.example.healthsync.testutil

import com.example.healthsync.data.remote.ApiConflictException
import com.example.healthsync.data.remote.ApiException
import com.example.healthsync.data.remote.ConflictResponse
import com.example.healthsync.data.remote.HeartRateUploadItem
import com.example.healthsync.data.remote.MockCloudApi
import com.example.healthsync.data.remote.ServerSleepData
import com.example.healthsync.data.remote.SleepRecordUploadRequest
import com.example.healthsync.data.remote.StepCountUploadItem
import com.example.healthsync.data.remote.UploadResult

/**
 * Fully configurable fake for [MockCloudApi] (DESIGN §12.2).
 *
 * Unlike the real [MockCloudApi] which uses random delays and failure rates,
 * this fake provides deterministic behavior for unit tests:
 * - [shouldFail]: next call throws [ApiException]
 * - [conflictResponse]: next sleep upload throws [ApiConflictException]
 * - No actual delay (instant execution)
 * - Tracks call counts for verification
 */
class FakeMockCloudApi : MockCloudApi() {

    var shouldFail: Boolean = false
    var conflictResponse: ConflictResponse? = null

    var heartRateUploadCount: Int = 0
        private set
    var stepCountUploadCount: Int = 0
        private set
    var sleepUploadCount: Int = 0
        private set
    var sleepGetCount: Int = 0
        private set

    val uploadedHeartRates = mutableListOf<HeartRateUploadItem>()
    val uploadedStepCounts = mutableListOf<StepCountUploadItem>()
    val uploadedSleepRecords = mutableListOf<SleepRecordUploadRequest>()

    init {
        failureRate = 0.0
        minDelayMs = 0
        maxDelayMs = 0
    }

    override suspend fun uploadHeartRates(items: List<HeartRateUploadItem>): List<UploadResult> {
        heartRateUploadCount++
        if (shouldFail) throw ApiException("Fake network error", 500)
        uploadedHeartRates.addAll(items)
        return items.map { UploadResult(remoteId = "hr-${it.eventId}") }
    }

    override suspend fun uploadStepCounts(items: List<StepCountUploadItem>): List<UploadResult> {
        stepCountUploadCount++
        if (shouldFail) throw ApiException("Fake network error", 500)
        uploadedStepCounts.addAll(items)
        return items.map { UploadResult(remoteId = "sc-${it.eventId}") }
    }

    override suspend fun uploadSleepRecord(request: SleepRecordUploadRequest): UploadResult {
        sleepUploadCount++
        if (shouldFail) throw ApiException("Fake network error", 500)
        conflictResponse?.let { throw ApiConflictException(it) }
        uploadedSleepRecords.add(request)
        return UploadResult(remoteId = request.id, remoteVersion = request.baseRemoteVersion + 1)
    }

    override suspend fun getSleepRecord(id: String): ServerSleepData? {
        sleepGetCount++
        if (shouldFail) throw ApiException("Fake network error", 500)
        return null
    }

    fun reset() {
        shouldFail = false
        conflictResponse = null
        heartRateUploadCount = 0
        stepCountUploadCount = 0
        sleepUploadCount = 0
        sleepGetCount = 0
        uploadedHeartRates.clear()
        uploadedStepCounts.clear()
        uploadedSleepRecords.clear()
    }
}
