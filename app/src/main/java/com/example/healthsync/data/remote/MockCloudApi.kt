package com.example.healthsync.data.remote

import com.example.healthsync.data.local.entity.SleepQuality
import kotlinx.coroutines.delay
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 心率上传项。
 * @param eventId 客户端生成的唯一标识，用于云端去重（DESIGN §6.5）。
 * @param timestamp 采样时间戳（毫秒）。
 * @param bpm 心率值。
 * @param sourceId 数据来源标识。
 */
data class HeartRateUploadItem(
    val eventId: String,
    val timestamp: Long,
    val bpm: Int,
    val sourceId: String
)

/**
 * 步数上传项。
 * @param eventId 客户端生成的唯一标识，用于云端去重（DESIGN §6.5）。
 * @param timestamp 采样时间戳（毫秒）。
 * @param steps 步数增量。
 * @param sourceId 数据来源标识。
 */
data class StepCountUploadItem(
    val eventId: String,
    val timestamp: Long,
    val steps: Int,
    val sourceId: String
)

/**
 * 睡眠记录上传请求（Milestone 5）。
 * @param id 业务主键（UUID）。
 * @param startTime 睡眠开始时间戳。
 * @param endTime 睡眠结束时间戳。
 * @param quality 睡眠质量（字符串形式）。
 * @param baseRemoteVersion 编辑时的云端版本号，用于冲突检测（乐观锁，DESIGN §7.1）。
 */
data class SleepRecordUploadRequest(
    val id: String,
    val startTime: Long,
    val endTime: Long,
    val quality: String,
    val baseRemoteVersion: Int
)

/**
 * 上传结果。
 * @param remoteId 云端分配的记录 ID（或返回客户端 ID）。
 * @param remoteVersion 云端当前版本号（用于后续冲突检测）。
 */
data class UploadResult(
    val remoteId: String,
    val remoteVersion: Int = 0
)

/**
 * 冲突响应（HTTP 409）。
 * 当客户端上传时，检测到 baseRemoteVersion < 当前云端版本时返回。
 * 包含云端最新数据，供客户端处理冲突（DESIGN §7.1-§7.2）。
 */
data class ConflictResponse(
    val currentRemoteVersion: Int,
    val serverData: ServerSleepData
)

/**
 * 服务端睡眠记录快照（用于冲突响应）。
 */
data class ServerSleepData(
    val startTime: Long,
    val endTime: Long,
    val quality: String,
    val remoteVersion: Int
)

/**
 * API 异常（网络/服务器错误）。
 */
class ApiException(message: String, val code: Int) : Exception(message)

/**
 * Mock 云端 API 实现（Milestone 2/5，DESIGN §8）。
 *
 * 使用内存 Map 模拟服务端存储。支持：
 * - 可配置的网络延迟（200-1000ms）与失败率
 * - 心率/步数的幂等去重（按 eventId）
 * - 睡眠记录的冲突检测（基于 baseRemoteVersion vs remoteVersion）
 *
 * **不代表真实后端行为**，仅用于验证同步流程与重试逻辑。
 */
@Singleton
class MockCloudApi @Inject constructor() {

    /** 睡眠记录存储（ID → 服务端数据）。 */
    private val sleepStore = ConcurrentHashMap<String, ServerSleepRecord>()
    /** 心率 eventId 去重集合。 */
    private val heartRateEventIds = ConcurrentHashMap.newKeySet<String>()
    /** 步数 eventId 去重集合。 */
    private val stepCountEventIds = ConcurrentHashMap.newKeySet<String>()

    /** 可配置的网络失败率（0.0-1.0）。默认 10%。 */
    var failureRate: Double = 0.1
    /** 网络延迟下限（毫秒）。 */
    var minDelayMs: Long = 200
    /** 网络延迟上限（毫秒）。 */
    var maxDelayMs: Long = 1000
    /** 强制冲突标志（用于测试，Milestone 7）。 */
    var forceConflict: Boolean = false

    private data class ServerSleepRecord(
        val id: String,
        val startTime: Long,
        val endTime: Long,
        val quality: String,
        val remoteVersion: Int
    )

    /**
     * 批量上传心率数据。
     * 模拟网络延迟，可能失败（simulateNetworkDelay + maybeThrowNetworkError）。
     * 成功时将 eventId 加入去重集合，避免重复插入（DESIGN §6.5）。
     *
     * @param items 心率上传项列表
     * @return 上传结果列表（与输入一一对应）
     * @throws ApiException 网络错误
     */
    suspend fun uploadHeartRates(items: List<HeartRateUploadItem>): List<UploadResult> {
        simulateNetworkDelay()
        maybeThrowNetworkError()

        return items.map { item ->
            heartRateEventIds.add(item.eventId)
            UploadResult(remoteId = "hr-${item.eventId}")
        }
    }

    /**
     * 批量上传步数数据。
     * @param items 步数上传项列表
     * @return 上传结果列表
     * @throws ApiException 网络错误
     */
    suspend fun uploadStepCounts(items: List<StepCountUploadItem>): List<UploadResult> {
        simulateNetworkDelay()
        maybeThrowNetworkError()

        return items.map { item ->
            stepCountEventIds.add(item.eventId)
            UploadResult(remoteId = "sc-${item.eventId}")
        }
    }

    /**
     * 上传/更新单条睡眠记录（PUT 语义，Milestone 5）。
     *
     * 冲突检测（DESIGN §7.1）：
     * - 若 request.baseRemoteVersion < 当前服务端版本，返回 409 冲突
     * - 客户端收到 409 后将记录置为 CONFLICT，并保存 serverSnapshot（DESIGN §7.2）
     *
     * @param request 睡眠记录上传请求，包含 baseRemoteVersion（编辑时的云端版本）
     * @return 上传结果，含新的 remoteVersion
     * @throws ApiConflictException 版本冲突（HTTP 409）
     * @throws ApiException 网络错误
     */
    suspend fun uploadSleepRecord(request: SleepRecordUploadRequest): UploadResult {
        simulateNetworkDelay()
        maybeThrowNetworkError()

        val existing = sleepStore[request.id]

        // 强制冲突模式（用于测试）
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

        // 版本冲突检测：baseRemoteVersion 小于当前版本
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

        // 成功：新增或覆盖，版本号 +1
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

    /**
     * 模拟网络延迟（200-1000ms 随机）。
     */
    private suspend fun simulateNetworkDelay() {
        val delayMs = Random.nextLong(minDelayMs, maxDelayMs + 1)
        delay(delayMs)
    }

    /**
     * 模拟随机网络错误。按 failureRate 概率抛出 ApiException。
     */
    private fun maybeThrowNetworkError() {
        if (Random.nextDouble() < failureRate) {
            throw ApiException("Simulated network error", 500)
        }
    }
}

/**
 * 版本冲突异常（HTTP 409）。
 * 由 MockCloudApi 在检测到版本冲突时抛出，包含服务端最新数据。
 */
class ApiConflictException(val conflict: ConflictResponse) :
    Exception("VERSION_CONFLICT: server version ${conflict.currentRemoteVersion}")
