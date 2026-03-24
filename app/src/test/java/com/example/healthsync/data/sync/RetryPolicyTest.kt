package com.example.healthsync.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {

    private val policy = RetryPolicy()

    @Test
    fun `maxAttempts is 3`() {
        assertEquals(3, policy.maxAttempts)
    }

    // ── shouldRetry ──

    @Test
    fun `shouldRetry returns true when attemptCount less than maxAttempts`() {
        assertTrue(policy.shouldRetry(0))
        assertTrue(policy.shouldRetry(1))
        assertTrue(policy.shouldRetry(2))
    }

    @Test
    fun `shouldRetry returns false when attemptCount equals maxAttempts`() {
        assertFalse(policy.shouldRetry(3))
    }

    @Test
    fun `shouldRetry returns false when attemptCount exceeds maxAttempts`() {
        assertFalse(policy.shouldRetry(4))
        assertFalse(policy.shouldRetry(100))
    }

    // ── nextDelay exponential backoff ──

    @Test
    fun `first delay is approximately 2 seconds`() {
        val delay = policy.nextDelay(0)
        // baseDelay=2000, 2^0=1, so base=2000; jitter up to 10% = 200
        assertTrue("delay=$delay should be in [2000, 2200]", delay in 2000..2200)
    }

    @Test
    fun `second delay is approximately 4 seconds`() {
        val delay = policy.nextDelay(1)
        // baseDelay=2000, 2^1=2, so base=4000; jitter up to 10% = 400
        assertTrue("delay=$delay should be in [4000, 4400]", delay in 4000..4400)
    }

    @Test
    fun `third delay is approximately 8 seconds`() {
        val delay = policy.nextDelay(2)
        // baseDelay=2000, 2^2=4, so base=8000; jitter up to 10% = 800
        assertTrue("delay=$delay should be in [8000, 8800]", delay in 8000..8800)
    }

    @Test
    fun `delay is capped at maxDelayMs`() {
        val delay = policy.nextDelay(20)
        assertTrue("delay=$delay should not exceed 30000", delay <= 30_000)
    }

    @Test
    fun `jitter adds non-negative variation`() {
        repeat(100) {
            val delay = policy.nextDelay(0)
            assertTrue("delay=$delay should be >= 2000", delay >= 2000)
        }
    }

    // ── Integration: full failure sequence ──

    @Test
    fun `failure sequence - 3 failures lead to SYNC_FAILED`() {
        var attemptCount = 0

        // 1st failure
        attemptCount++
        assertTrue(policy.shouldRetry(attemptCount))
        val delay1 = policy.nextDelay(attemptCount - 1) // use old count for delay
        assertTrue(delay1 in 2000..2200)

        // 2nd failure
        attemptCount++
        assertTrue(policy.shouldRetry(attemptCount))
        val delay2 = policy.nextDelay(attemptCount - 1)
        assertTrue(delay2 in 4000..4400)

        // 3rd failure -> should not retry
        attemptCount++
        assertFalse(policy.shouldRetry(attemptCount))
    }
}
