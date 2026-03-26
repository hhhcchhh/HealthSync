package com.example.healthsync.testutil

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * Unified test dispatcher / scope factory (DESIGN §12.2).
 *
 * Provides a single [TestCoroutineScheduler] shared across
 * [testDispatcher] and [testScope] so that `advanceTimeBy` / `advanceUntilIdle`
 * controls all coroutines launched in either context.
 *
 * Usage:
 * ```
 * private val testDispatchers = TestDispatchers()
 * private val scope = testDispatchers.testScope
 * // in test body:
 * scope.advanceUntilIdle()
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestDispatchers {
    val scheduler = TestCoroutineScheduler()
    val testDispatcher: TestDispatcher = StandardTestDispatcher(scheduler)
    val testScope = TestScope(testDispatcher)
}
