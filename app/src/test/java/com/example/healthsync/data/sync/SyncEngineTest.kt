package com.example.healthsync.data.sync

import com.example.healthsync.data.local.entity.HeartRateEntity
import com.example.healthsync.data.local.entity.SleepQuality
import com.example.healthsync.data.local.entity.SleepRecordEntity
import com.example.healthsync.data.local.entity.SyncState
import com.example.healthsync.data.remote.HeartRateUploadItem
import com.example.healthsync.data.remote.MockCloudApi
import com.example.healthsync.data.remote.UploadResult
import com.example.healthsync.testutil.FakeHeartRateDao
import com.example.healthsync.testutil.FakeSleepRecordDao
import com.example.healthsync.testutil.FakeStepCountDao
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyncEngineTest {

    private lateinit var heartRateDao: FakeHeartRateDao
    private lateinit var stepCountDao: FakeStepCountDao
    private lateinit var sleepRecordDao: FakeSleepRecordDao
    private lateinit var cloudApi: MockCloudApi
    private lateinit var retryPolicy: RetryPolicy
    private lateinit var conflictResolver: ConflictResolver
    private lateinit var syncEngine: SyncEngine

    @Before
    fun setup() {
        heartRateDao = FakeHeartRateDao()
        stepCountDao = FakeStepCountDao()
        sleepRecordDao = FakeSleepRecordDao()
        cloudApi = MockCloudApi().apply {
            failureRate = 0.0
            minDelayMs = 0
            maxDelayMs = 0
        }
        retryPolicy = RetryPolicy()
        conflictResolver = ConflictResolver(sleepRecordDao, Gson())
        syncEngine = SyncEngine(
            heartRateDao, stepCountDao, sleepRecordDao,
            cloudApi, retryPolicy, conflictResolver, NoopSyncLogger
        )
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

    @Test
    fun `cancellation during upload releases SYNCING records back to LOCAL_PENDING`() = runTest {
        val uploadEntered = CompletableDeferred<Unit>()
        cloudApi = object : MockCloudApi() {
            override suspend fun uploadHeartRates(items: List<HeartRateUploadItem>): List<UploadResult> {
                uploadEntered.complete(Unit)
                // Hang until cancelled to simulate an in-flight network call.
                return suspendCancellableCoroutine { /* cancelled */ }
            }
        }.apply {
            failureRate = 0.0
            minDelayMs = 0
            maxDelayMs = 0
        }
        syncEngine = SyncEngine(
            heartRateDao, stepCountDao, sleepRecordDao,
            cloudApi, retryPolicy, conflictResolver, NoopSyncLogger
        )

        heartRateDao.store[1L] = makeHeartRate(1L, SyncState.LOCAL_PENDING)

        val job: Job = launch {
            syncEngine.syncOnce()
        }

        uploadEntered.await()
        job.cancel()
        job.join()

        // Should not remain stuck in SYNCING after cancellation.
        assertEquals(SyncState.LOCAL_PENDING, heartRateDao.store[1L]!!.syncState)
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

    // ── Sleep record sync: normal flow ──

    @Test
    fun `syncOnce transitions sleep record from LOCAL_PENDING to SYNCED with remoteVersion`() = runTest {
        val record = makeSleepRecord("sleep-1", SyncState.LOCAL_PENDING)
        sleepRecordDao.store[record.id] = record

        val didWork = syncEngine.syncOnce()

        assertTrue(didWork)
        val synced = sleepRecordDao.store["sleep-1"]!!
        assertEquals(SyncState.SYNCED, synced.syncState)
        assertEquals("sleep-1", synced.remoteId)
        assertEquals(1, synced.remoteVersion)
    }

    // ── Sleep record sync: 409 conflict ──

    @Test
    fun `sleep record conflict sets CONFLICT state with server snapshot`() = runTest {
        cloudApi.forceConflict = true

        // Pre-populate server store so conflict can trigger
        cloudApi.failureRate = 0.0
        val initial = makeSleepRecord("sleep-c", SyncState.LOCAL_PENDING)
        sleepRecordDao.store[initial.id] = initial

        // First sync succeeds (no server record yet, forceConflict only triggers when existing)
        syncEngine.syncOnce()
        assertEquals(SyncState.SYNCED, sleepRecordDao.store["sleep-c"]!!.syncState)

        // Now edit and re-sync → conflict
        sleepRecordDao.store["sleep-c"] = sleepRecordDao.store["sleep-c"]!!.copy(
            syncState = SyncState.LOCAL_PENDING,
            startTime = 5000L,
            endTime = 15000L
        )

        syncEngine.syncOnce()

        val conflicted = sleepRecordDao.store["sleep-c"]!!
        assertEquals(SyncState.CONFLICT, conflicted.syncState)
        assertNotNull(conflicted.serverSnapshot)
    }

    // ── Sleep record sync: failure retry → SYNC_FAILED ──

    @Test
    fun `sleep record failure retries until SYNC_FAILED`() = runTest {
        cloudApi.failureRate = 1.0

        val record = makeSleepRecord("sleep-f", SyncState.LOCAL_PENDING, attemptCount = 0)
        sleepRecordDao.store[record.id] = record

        // 1st failure
        syncEngine.syncOnce()
        assertEquals(1, sleepRecordDao.store["sleep-f"]!!.attemptCount)
        assertEquals(SyncState.LOCAL_PENDING, sleepRecordDao.store["sleep-f"]!!.syncState)

        sleepRecordDao.store["sleep-f"] = sleepRecordDao.store["sleep-f"]!!.copy(nextAttemptAt = 0)

        // 2nd failure
        syncEngine.syncOnce()
        assertEquals(2, sleepRecordDao.store["sleep-f"]!!.attemptCount)

        sleepRecordDao.store["sleep-f"] = sleepRecordDao.store["sleep-f"]!!.copy(nextAttemptAt = 0)

        // 3rd failure → SYNC_FAILED
        syncEngine.syncOnce()
        assertEquals(3, sleepRecordDao.store["sleep-f"]!!.attemptCount)
        assertEquals(SyncState.SYNC_FAILED, sleepRecordDao.store["sleep-f"]!!.syncState)
    }

    // ── Recovery includes sleep records ──

    @Test
    fun `recover resets SYNCING sleep records to LOCAL_PENDING`() = runTest {
        val record = makeSleepRecord("sleep-r", SyncState.SYNCING, attemptCount = 1)
        sleepRecordDao.store[record.id] = record

        syncEngine.recover()

        assertEquals(SyncState.LOCAL_PENDING, sleepRecordDao.store["sleep-r"]?.syncState)
        assertEquals(1, sleepRecordDao.store["sleep-r"]?.attemptCount)
    }

    // ── Helpers ──

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

    private fun makeSleepRecord(
        id: String,
        syncState: SyncState,
        attemptCount: Int = 0,
        nextAttemptAt: Long = 0L
    ) = SleepRecordEntity(
        id = id,
        startTime = 1000L,
        endTime = 9000L,
        quality = SleepQuality.GOOD,
        sourceId = "test",
        syncState = syncState,
        attemptCount = attemptCount,
        nextAttemptAt = nextAttemptAt
    )
}
