package com.example.healthsync.data.source

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedBluetoothSourceTest {

    @Test
    fun `dataEvents does not emit before start`() = runTest {
        val source = SimulatedBluetoothSource()
        val events = mutableListOf<HealthEvent>()

        val job = backgroundScope.launch {
            source.dataEvents.collect { events.add(it) }
        }
        // Ensure collector is actually running.
        testScheduler.runCurrent()

        testScheduler.advanceTimeBy(10_000L)
        testScheduler.runCurrent()

        assertTrue(events.isEmpty())
        job.cancel()
    }

    @Test
    fun `start sets CONNECTED and emits heart rate samples`() = runTest {
        val source = SimulatedBluetoothSource()
        val events = mutableListOf<HealthEvent>()

        val job = backgroundScope.launch {
            source.dataEvents.collect { events.add(it) }
        }
        // Ensure collector is actually running.
        testScheduler.runCurrent()

        // Start collecting first, then start the source (important: dataEvents is a cold flow).
        source.start()
        testScheduler.runCurrent()

        // First heart rate sample is emitted after HEART_RATE_INTERVAL_MS (2s).
        // There may be an initial 500ms idle delay before the loop observes running=true.
        testScheduler.advanceTimeBy(3_000L)
        testScheduler.runCurrent()

        assertEquals(ConnectionState.CONNECTED, source.connectionState.first())
        assertTrue(events.any { it is HealthEvent.HeartRateSample })

        job.cancel()
    }

    @Test
    fun `stop sets DISCONNECTED and stops emitting`() = runTest {
        val source = SimulatedBluetoothSource()
        val events = mutableListOf<HealthEvent>()

        val job = backgroundScope.launch {
            source.dataEvents.collect { events.add(it) }
        }
        // Ensure collector is actually running.
        testScheduler.runCurrent()

        source.start()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(3_000L)
        testScheduler.runCurrent()
        val sizeAfterFirst = events.size
        assertTrue(sizeAfterFirst > 0)

        source.stop()
        testScheduler.runCurrent()

        // Even if time advances, no new events should be emitted while DISCONNECTED.
        testScheduler.advanceTimeBy(10_000L)
        testScheduler.runCurrent()

        assertEquals(ConnectionState.DISCONNECTED, source.connectionState.first())
        assertEquals(sizeAfterFirst, events.size)

        job.cancel()
    }

    @Test
    fun `auto disconnect cycle transitions DISCONNECTED then RECONNECTING then CONNECTED`() = runTest {
        val source = SimulatedBluetoothSource()
        val states = mutableListOf<ConnectionState>()

        // Important: auto disconnect/reconnect is driven by the dataEvents loop,
        // so we must collect dataEvents to advance the internal timer.
        val eventsJob = backgroundScope.launch {
            source.dataEvents.collect { /* ignore */ }
        }

        val stateJob = backgroundScope.launch {
            source.connectionState.collect { states.add(it) }
        }

        source.start()
        testScheduler.runCurrent()

        // Auto cycle triggers after 60s of "connected running time".
        // Add a small buffer for the initial idle delay/tick alignment.
        testScheduler.advanceTimeBy(62_500L)
        testScheduler.runCurrent()

        // simulateDisconnect: DISCONNECTED -> (5s) RECONNECTING -> (5s) CONNECTED
        testScheduler.advanceTimeBy(5_000L)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(5_000L)
        testScheduler.runCurrent()

        val connectedIdx = states.indexOf(ConnectionState.CONNECTED)
        val disconnectedIdx = states.withIndex()
            .firstOrNull { it.index > connectedIdx && it.value == ConnectionState.DISCONNECTED }
            ?.index ?: -1
        val reconnectingIdx = states.withIndex()
            .firstOrNull { it.index > disconnectedIdx && it.value == ConnectionState.RECONNECTING }
            ?.index ?: -1
        val connectedAgainIdx = states.withIndex()
            .firstOrNull { it.index > reconnectingIdx && it.value == ConnectionState.CONNECTED }
            ?.index ?: -1

        assertTrue("Expected initial CONNECTED after start", connectedIdx >= 0)
        assertTrue("Expected DISCONNECTED after ~60s", disconnectedIdx > connectedIdx)
        assertTrue("Expected RECONNECTING after DISCONNECTED", reconnectingIdx > disconnectedIdx)
        assertTrue("Expected CONNECTED again after RECONNECTING", connectedAgainIdx > reconnectingIdx)

        eventsJob.cancel()
        stateJob.cancel()
    }
}
