package com.example.healthsync.data.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 指数退避重试策略（Milestone 2/7，DESIGN §6.3）。
 *
 * 定义同步失败后的重试延迟计算规则。按失败次数采用 2^n 指数退避：
 * - 第 1 次失败后等待 ~2s
 * - 第 2 次失败后等待 ~4s
 * - 第 3 次失败后：达到上限，SyncEngine 将记录置为 SYNC_FAILED
 *
 * 加入 10% jitter 避免"惊群"（多条记录同时重试）。
 */
@Singleton
class RetryPolicy @Inject constructor() {

    /** 最大重试次数。当 attemptCount >= maxAttempts 时，停止自动重试。 */
    val maxAttempts: Int = 3
    /** 基础延迟时间（毫秒）。用于计算 2^n 的底数。 */
    private val baseDelayMs: Long = 2_000L
    /** 最大延迟上限（毫秒）。计算结果超过该值时取上限，避免无限等待。 */
    private val maxDelayMs: Long = 30_000L

    /**
     * 根据失败次数计算下次重试的延迟时间。
     *
     * @param attemptCount 已发生的失败次数（从 0 开始）
     * @return 建议的延迟毫秒数
     *
     * 举例（DESIGN §6.6）：
     * - attemptCount=0（第 1 次失败）→ ~2s
     * - attemptCount=1（第 2 次失败）→ ~4s
     * - attemptCount=2（第 3 次失败）→ ~8s，但超过 8s 后还应继续，此时由 SyncEngine 置为 SYNC_FAILED
     */
    fun nextDelay(attemptCount: Int): Long {
        // 指数退避：2^attemptCount * baseDelayMs
        val delay = baseDelayMs * (1L shl attemptCount)
        // 加入 10% jitter，打散同时重试的请求，避免"惊群"
        val jitter = (delay * 0.1 * Random.nextDouble()).toLong()
        return (delay + jitter).coerceAtMost(maxDelayMs)
    }

    /**
     * 判断是否应该继续重试。
     *
     * @param attemptCount 已发生的失败次数
     * @return 若 attemptCount < maxAttempts 则返回 true（可继续重试），否则返回 false（达到上限）
     *
     * 示例：当 attemptCount 达到 3 时返回 false，SyncEngine 将记录置为 SYNC_FAILED。
     */
    fun shouldRetry(attemptCount: Int): Boolean = attemptCount < maxAttempts
}
