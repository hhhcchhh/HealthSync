package com.example.healthsync.data.sync

import com.example.healthsync.data.local.entity.HeartRateEntity
import com.example.healthsync.data.local.entity.StepCountEntity
import com.example.healthsync.data.local.entity.SyncState
import com.example.healthsync.data.remote.ApiException
import com.example.healthsync.data.remote.HeartRateUploadItem
import com.example.healthsync.data.remote.MockCloudApi
import com.example.healthsync.data.remote.StepCountUploadItem
import com.example.healthsync.data.remote.UploadResult
import com.example.healthsync.testutil.FakeHeartRateDao
import com.example.healthsync.testutil.FakeStepCountDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyncEngineTest {

    private lateinit var heartRateDao: FakeHeartRateDao
    private lateinit var stepCountDao: FakeStepCountDao
    private lateinit var cloudApi: MockCloudApi
    private lateinit var retryPolicy: RetryPolicy
    private lateinit var syncEngine: SyncEngine

    @Before
    fun setup() {
        heartRateDao = FakeHeartRateDao()
        stepCountDao = FakeStepCountDao()
        cloudApi = MockCloudApi().apply {
            failureRate = 0.0
            minDelayMs = 0
            maxDelayMs = 0
        }
        retryPolicy = RetryPolicy()
        syncEngine = SyncEngine(heartRateDao, stepCountDao, cloudApi, retryPolicy)
    }

    // ── Normal flow: LOCAL_PENDING → SYNCING → SYNCED ──

    @Test
    fun `syncOnce transitions heart rate from LOCAL_PENDING to SYNCED`() = runTest {
        heartRateDao.store[1L] = makeHeartRate(1L, SyncState.LOCAL_PENDING)

        val didWork = syncEngine.syncOnce()

        assertTrue(didWork)
        assertEquals(SyncState.SYNCED, heartRateDao.store[1L]?.syncState)
        assertEquals("hr-evt-1", heartRateDao.store[1L]?.remoteId)
    }

    @Test
    fun `syncOnce returns false when no pending records`() = runTest {
        val didWork = syncEngine.syncOnce()
        assertFalse(didWork)
    }

    @Test
    fun `syncOnce processes multiple heart rate records`() = runTest {
        heartRateDao.store[1L] = makeHeartRate(1L, SyncState.LOCAL_PENDING)
        heartRateDao.store[2L] = makeHeartRate(2L, SyncState.LOCAL_PENDING)
        heartRateDao.store[3L] = makeHeartRate(3L, SyncState.LOCAL_PENDING)

        syncEngine.syncOnce()

        heartRateDao.store.values.forEach { entity ->
            assertEquals(SyncState.SYNCED, entity.syncState)
        }
    }

    // ── Failure flow: retry then SYNC_FAILED ──

    @Test
    fun `first failure increments attemptCount and sets LOCAL_PENDING`() = runTest {
        cloudApi.failureRate = 1.0

        heartRateDao.store[1L] = makeHeartRate(1L, SyncState.LOCAL_PENDING, attemptCount = 0)

        syncEngine.syncOnce()

        val record = heartRateDao.store[1L]!!
        assertEquals(SyncState.LOCAL_PENDING, record.syncState)
        assertEquals(1, record.attemptCount)
        assertTrue(record.nextAttemptAt > 0)
        assertTrue(record.lastError != null)
    }

    @Test
    fun `third failure sets SYNC_FAILED`() = runTest {
        cloudApi.failureRate = 1.0

        heartRateDao.store[1L] = makeHeartRate(1L, SyncState.LOCAL_PENDING, attemptCount = 2)

        syncEngine.syncOnce()

        val record = heartRateDao.store[1L]!!
        assertEquals(SyncState.SYNC_FAILED, record.syncState)
        assertEquals(3, record.attemptCount)
    }

    @Test
    fun `full failure sequence LOCAL_PENDING to SYNC_FAILED after 3 failures`() = runTest {
        cloudApi.failureRate = 1.0

        heartRateDao.store[1L] = makeHeartRate(1L, SyncState.LOCAL_PENDING, attemptCount = 0)

        // 1st failure
        syncEngine.syncOnce()
        assertEquals(1, heartRateDao.store[1L]!!.attemptCount)
        assertEquals(SyncState.LOCAL_PENDING, heartRateDao.store[1L]!!.syncState)

        // Fast forward nextAttemptAt
        heartRateDao.store[1L] = heartRateDao.store[1L]!!.copy(nextAttemptAt = 0)

        // 2nd failure
        syncEngine.syncOnce()
        assertEquals(2, heartRateDao.store[1L]!!.attemptCount)
        assertEquals(SyncState.LOCAL_PENDING, heartRateDao.store[1L]!!.syncState)

        heartRateDao.store[1L] = heartRateDao.store[1L]!!.copy(nextAttemptAt = 0)

        // 3rd failure → SYNC_FAILED
        syncEngine.syncOnce()
        assertEquals(3, heartRateDao.store[1L]!!.attemptCount)
        assertEquals(SyncState.SYNC_FAILED, heartRateDao.store[1L]!!.syncState)
    }

    // ── Recovery: SYNCING → LOCAL_PENDING ──

    @Test
    fun `recover resets SYNCING records to LOCAL_PENDING`() = runTest {
        heartRateDao.store[1L] = makeHeartRate(1L, SyncState.SYNCING, attemptCount = 1)
        heartRateDao.store[2L] = makeHeartRate(2L, SyncState.SYNCED)

        syncEngine.recover()

        assertEquals(SyncState.LOCAL_PENDING, heartRateDao.store[1L]?.syncState)
        assertEquals(1, heartRateDao.store[1L]?.attemptCount) // preserved
        assertEquals(SyncState.SYNCED, heartRateDao.store[2L]?.syncState) // unchanged
    }

    @Test
    fun `recover handles empty database`() = runTest {
        syncEngine.recover()
        // no exception
    }

    // ── Skip records whose nextAttemptAt has not arrived ──

    @Test
    fun `syncOnce skips records whose nextAttemptAt is in the future`() = runTest {
        heartRateDao.store[1L] = makeHeartRate(
            1L, SyncState.LOCAL_PENDING,
            nextAttemptAt = System.currentTimeMillis() + 60_000
        )

        val didWork = syncEngine.syncOnce()

        assertFalse(didWork)
        assertEquals(SyncState.LOCAL_PENDING, heartRateDao.store[1L]?.syncState)
    }

    // ── Helper ──

    private fun makeHeartRate(
        id: Long,
        syncState: SyncState,
        attemptCount: Int = 0,
        nextAttemptAt: Long = 0L
    ) = HeartRateEntity(
        id = id,
        timestamp = System.currentTimeMillis(),
        bpm = 72,
        sourceId = "test",
        eventId = "evt-$id",
        syncState = syncState,
        attemptCount = attemptCount,
        nextAttemptAt = nextAttemptAt,
        lastError = null,
        remoteId = null
    )
}
