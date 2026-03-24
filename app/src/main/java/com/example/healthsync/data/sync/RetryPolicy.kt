package com.example.healthsync.data.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class RetryPolicy @Inject constructor() {

    val maxAttempts: Int = 3
    private val baseDelayMs: Long = 2_000L
    private val maxDelayMs: Long = 30_000L

    fun nextDelay(attemptCount: Int): Long {
        val delay = baseDelayMs * (1L shl attemptCount)
        val jitter = (delay * 0.1 * Random.nextDouble()).toLong()
        return (delay + jitter).coerceAtMost(maxDelayMs)
    }

    fun shouldRetry(attemptCount: Int): Boolean = attemptCount < maxAttempts
}
