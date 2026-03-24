package com.example.healthsync.data.sync

import com.example.healthsync.data.local.entity.HeartRateEntity
import com.example.healthsync.data.local.entity.SyncState
import com.example.healthsync.data.remote.MockCloudApi
import com.example.healthsync.testutil.FakeHeartRateDao
import com.example.healthsync.testutil.FakeStepCountDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncCoordinatorTest {

    private lateinit var heartRateDao: FakeHeartRateDao
    private lateinit var stepCountDao: FakeStepCountDao
    private lateinit var cloudApi: MockCloudApi
    private lateinit var syncEngine: SyncEngine
    private lateinit var coordinator: SyncCoordinator

    @Before
    fun setup() {
        heartRateDao = FakeHeartRateDao()
        stepCountDao = FakeStepCountDao()
        cloudApi = MockCloudApi().apply {
            failureRate = 0.0
            minDelayMs = 0
            maxDelayMs = 0
        }
        syncEngine = SyncEngine(heartRateDao, stepCountDao, cloudApi, RetryPolicy())
        coordinator = SyncCoordinator(syncEngine)
    }

    @Test
    fun `triggerSync processes pending records`() = runTest {
        heartRateDao.store[1L] = makeHeartRate(1L, SyncState.LOCAL_PENDING)

        coordinator.triggerSync()

        assertEquals(SyncState.SYNCED, heartRateDao.store[1L]?.syncState)
    }

    @Test
    fun `recover resets SYNCING records before sync`() = runTest {
        heartRateDao.store[1L] = makeHeartRate(1L, SyncState.SYNCING, attemptCount = 1)

        coordinator.recover()

        assertEquals(SyncState.LOCAL_PENDING, heartRateDao.store[1L]?.syncState)
        assertEquals(1, heartRateDao.store[1L]?.attemptCount)
    }

    @Test
    fun `concurrent triggerSync calls do not duplicate processing`() = runTest {
        heartRateDao.store[1L] = makeHeartRate(1L, SyncState.LOCAL_PENDING)

        val jobs = (1..5).map {
            async { coordinator.triggerSync() }
        }
        jobs.awaitAll()

        assertEquals(SyncState.SYNCED, heartRateDao.store[1L]?.syncState)
    }

    private fun makeHeartRate(
        id: Long,
        syncState: SyncState,
        attemptCount: Int = 0
    ) = HeartRateEntity(
        id = id,
        timestamp = System.currentTimeMillis(),
        bpm = 72,
        sourceId = "test",
        eventId = "evt-$id",
        syncState = syncState,
        attemptCount = attemptCount,
        nextAttemptAt = 0,
        lastError = null,
        remoteId = null
    )
}
