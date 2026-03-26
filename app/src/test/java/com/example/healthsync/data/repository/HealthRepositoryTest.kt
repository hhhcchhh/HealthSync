package com.example.healthsync.data.repository

import com.example.healthsync.data.local.entity.SleepQuality
import com.example.healthsync.data.local.entity.SleepRecordEntity
import com.example.healthsync.data.local.entity.SyncState
import com.example.healthsync.data.source.ConnectionState
import com.example.healthsync.data.source.HealthDataSource
import com.example.healthsync.data.source.HealthEvent
import com.example.healthsync.testutil.FakeHeartRateDao
import com.example.healthsync.testutil.FakeSleepRecordDao
import com.example.healthsync.testutil.FakeStepCountDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HealthRepositoryTest {

    private lateinit var heartRateDao: FakeHeartRateDao
    private lateinit var stepCountDao: FakeStepCountDao
    private lateinit var sleepRecordDao: FakeSleepRecordDao
    private lateinit var repository: HealthRepository
    private lateinit var testScope: TestScope

    @Before
    fun setup() {
        heartRateDao = FakeHeartRateDao()
        stepCountDao = FakeStepCountDao()
        sleepRecordDao = FakeSleepRecordDao()
        repository = HealthRepository(heartRateDao, stepCountDao, sleepRecordDao)
        testScope = TestScope(UnconfinedTestDispatcher())
    }

    @Test
    fun `collectFrom inserts HeartRateSample as LOCAL_PENDING entity`() = runTest {
        val source = FakeDataSource()
        repository.collectFrom(source, testScope)

        source.emit(
            HealthEvent.HeartRateSample(
                timestamp = 1000L,
                bpm = 72,
                sourceId = "test"
            )
        )

        testScope.testScheduler.advanceUntilIdle()

        assertEquals(1, heartRateDao.store.size)
        val entity = heartRateDao.store.values.first()
        assertEquals(72, entity.bpm)
        assertEquals(SyncState.LOCAL_PENDING, entity.syncState)
        assertEquals(0, entity.attemptCount)
    }

    @Test
    fun `collectFrom inserts StepCountIncrement as LOCAL_PENDING entity`() = runTest {
        val source = FakeDataSource()
        repository.collectFrom(source, testScope)

        source.emit(
            HealthEvent.StepCountIncrement(
                timestamp = 1000L,
                steps = 15,
                sourceId = "test"
            )
        )

        testScope.testScheduler.advanceUntilIdle()

        assertEquals(1, stepCountDao.store.size)
        val entity = stepCountDao.store.values.first()
        assertEquals(15, entity.steps)
        assertEquals(SyncState.LOCAL_PENDING, entity.syncState)
    }

    @Test
    fun `collectFrom inserts SleepRecord as LOCAL_PENDING entity`() = runTest {
        val source = FakeDataSource()
        repository.collectFrom(source, testScope)

        source.emit(
            HealthEvent.SleepRecord(
                id = "sleep-test-1",
                startTime = 1000L,
                endTime = 9000L,
                quality = SleepQuality.GOOD,
                sourceId = "test"
            )
        )

        testScope.testScheduler.advanceUntilIdle()

        assertEquals(1, sleepRecordDao.store.size)
        val entity = sleepRecordDao.store["sleep-test-1"]!!
        assertEquals(1000L, entity.startTime)
        assertEquals(9000L, entity.endTime)
        assertEquals(SleepQuality.GOOD, entity.quality)
        assertEquals(SyncState.LOCAL_PENDING, entity.syncState)
        assertEquals(0, entity.attemptCount)
    }

    @Test
    fun `collectFrom updates existing SleepRecord preserving remote version fields`() = runTest {
        val source = FakeDataSource()
        repository.collectFrom(source, testScope)

        sleepRecordDao.store["sleep-edit-1"] = SleepRecordEntity(
            id = "sleep-edit-1",
            startTime = 1_000L,
            endTime = 9_000L,
            quality = SleepQuality.FAIR,
            sourceId = "manual_input",
            syncState = SyncState.SYNCED,
            attemptCount = 2,
            nextAttemptAt = 12_345L,
            lastError = "old error",
            remoteId = "remote-1",
            remoteVersion = 7,
            baseRemoteVersion = 7,
            localVersion = 3,
            serverSnapshot = """{"quality":"GOOD"}"""
        )

        source.emit(
            HealthEvent.SleepRecord(
                id = "sleep-edit-1",
                startTime = 2_000L,
                endTime = 10_000L,
                quality = SleepQuality.GOOD,
                sourceId = "manual_input"
            )
        )
        testScope.testScheduler.advanceUntilIdle()

        val updated = sleepRecordDao.store["sleep-edit-1"]!!
        assertEquals(2_000L, updated.startTime)
        assertEquals(10_000L, updated.endTime)
        assertEquals(SleepQuality.GOOD, updated.quality)
        assertEquals(SyncState.LOCAL_PENDING, updated.syncState)
        assertEquals(0, updated.attemptCount)
        assertEquals(0L, updated.nextAttemptAt)
        assertNull(updated.lastError)
        assertNull(updated.serverSnapshot)
        assertEquals("remote-1", updated.remoteId)
        assertEquals(7, updated.remoteVersion)
        assertEquals(7, updated.baseRemoteVersion)
        assertEquals(4, updated.localVersion)
    }

    @Test
    fun `concurrent writes from two sources do not lose data`() = runTest {
        val source1 = FakeDataSource()
        val source2 = FakeDataSource()
        repository.collectFrom(source1, testScope)
        repository.collectFrom(source2, testScope)

        val jobs = (1..50).map { i ->
            async {
                val src = if (i % 2 == 0) source1 else source2
                src.emit(
                    HealthEvent.HeartRateSample(
                        timestamp = i.toLong(),
                        bpm = 60 + i,
                        sourceId = "source-${i % 2}"
                    )
                )
            }
        }
        jobs.awaitAll()
        testScope.testScheduler.advanceUntilIdle()

        assertEquals(50, heartRateDao.store.size)
    }

    private class FakeDataSource : HealthDataSource {
        private val _events = MutableSharedFlow<HealthEvent>(extraBufferCapacity = 64)
        override val dataEvents: Flow<HealthEvent> = _events
        override val connectionState: Flow<ConnectionState> = MutableStateFlow(ConnectionState.CONNECTED)
        override suspend fun start() {}
        override suspend fun stop() {}
        suspend fun emit(event: HealthEvent) = _events.emit(event)
    }
}
