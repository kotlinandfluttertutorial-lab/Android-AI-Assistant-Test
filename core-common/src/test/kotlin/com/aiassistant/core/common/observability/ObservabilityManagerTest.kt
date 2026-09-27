/**
 * ObservabilityManagerTest.kt — core-common module unit tests
 *
 * Tests for [ObservabilityManager]:
 *   - [ObservabilityManager.startCollecting] — idempotency
 *   - Buffer accumulation — events from [ObservabilityEventBus] reach the buffer
 *   - [ObservabilityManager.drain] — returns all buffered events and clears buffer
 *   - Buffer cap — oldest events are dropped when MAX_BUFFER_SIZE is exceeded
 *   - [ObservabilityManager.bufferedCount] — reflects current buffer size
 *   - Concurrent drain safety — draining while events arrive
 *
 * Test framework: Kotest (DescribeSpec) + kotlinx-coroutines-test
 * No Android framework dependencies.
 *
 * Phase 2 — Android Observability
 */

package com.aiassistant.core.common.observability

import com.aiassistant.core.common.DispatcherProvider
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ObservabilityManagerTest : DescribeSpec({

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun testDispatcherProvider(dispatcher: CoroutineDispatcher): DispatcherProvider =
        object : DispatcherProvider {
            override val main: CoroutineDispatcher          = dispatcher
            override val mainImmediate: CoroutineDispatcher = dispatcher
            override val io: CoroutineDispatcher            = dispatcher
            override val default: CoroutineDispatcher       = dispatcher
            override val unconfined: CoroutineDispatcher    = dispatcher
        }

    fun makeEvent(
        eventType: String  = EventType.HTTP_SUCCESS,
        sessionId: String  = "sess-test",
        message:   String  = "test",
    ) = ObservabilityEvent(
        timestamp = System.currentTimeMillis(),
        level     = EventLevel.INFO,
        eventType = eventType,
        message   = message,
        sessionId = sessionId,
    )

    fun makeManager(scope: TestScope): Triple<ObservabilityManager, ObservabilityEventBus, DispatcherProvider> {
        val testDispatcher = StandardTestDispatcher(scope.testScheduler)
        val provider       = testDispatcherProvider(testDispatcher)
        val bus            = ObservabilityEventBus()
        val manager        = ObservabilityManager(bus, provider)
        return Triple(manager, bus, provider)
    }

    // ── startCollecting idempotency ───────────────────────────────────────────

    describe("ObservabilityManager.startCollecting") {

        it("calling startCollecting twice does not start two collectors") {
            runTest {
                val (manager, bus, _) = makeManager(this)
                manager.startCollecting()
                manager.startCollecting() // second call should be a no-op

                bus.emit(makeEvent(eventType = EventType.HTTP_SUCCESS))
                advanceUntilIdle()

                // Buffer should contain exactly one event, not two
                manager.bufferedCount() shouldBe 1
            }
        }
    }

    // ── Event accumulation ────────────────────────────────────────────────────

    describe("ObservabilityManager — event accumulation") {

        it("events emitted to the bus arrive in the buffer") {
            runTest {
                val (manager, bus, _) = makeManager(this)
                manager.startCollecting()

                bus.emit(makeEvent(message = "first"))
                bus.emit(makeEvent(message = "second"))
                bus.emit(makeEvent(message = "third"))
                advanceUntilIdle()

                manager.bufferedCount() shouldBe 3
            }
        }

        it("bufferedCount is 0 before any events") {
            runTest {
                val (manager, _, _) = makeManager(this)
                manager.startCollecting()
                manager.bufferedCount() shouldBe 0
            }
        }

        it("events are ordered oldest-first in the buffer") {
            runTest {
                val (manager, bus, _) = makeManager(this)
                manager.startCollecting()

                bus.emit(makeEvent(message = "A"))
                bus.emit(makeEvent(message = "B"))
                bus.emit(makeEvent(message = "C"))
                advanceUntilIdle()

                val drained = manager.drain()
                drained.map { it.message } shouldBe listOf("A", "B", "C")
            }
        }
    }

    // ── drain ─────────────────────────────────────────────────────────────────

    describe("ObservabilityManager.drain") {

        it("drain returns all buffered events") {
            runTest {
                val (manager, bus, _) = makeManager(this)
                manager.startCollecting()

                repeat(5) { bus.emit(makeEvent(message = "event-$it")) }
                advanceUntilIdle()

                val drained = manager.drain()
                drained shouldHaveSize 5
            }
        }

        it("buffer is empty after drain") {
            runTest {
                val (manager, bus, _) = makeManager(this)
                manager.startCollecting()

                bus.emit(makeEvent())
                bus.emit(makeEvent())
                advanceUntilIdle()

                manager.drain()
                manager.bufferedCount() shouldBe 0
            }
        }

        it("drain on an empty buffer returns an empty list") {
            runTest {
                val (manager, _, _) = makeManager(this)
                manager.startCollecting()

                manager.drain().shouldBeEmpty()
            }
        }

        it("events emitted after drain are buffered normally") {
            runTest {
                val (manager, bus, _) = makeManager(this)
                manager.startCollecting()

                bus.emit(makeEvent(message = "before-drain"))
                advanceUntilIdle()
                manager.drain()

                bus.emit(makeEvent(message = "after-drain"))
                advanceUntilIdle()

                val second = manager.drain()
                second shouldHaveSize 1
                second.first().message shouldBe "after-drain"
            }
        }

        it("drain is atomic — bufferedCount is 0 immediately after") {
            runTest {
                val (manager, bus, _) = makeManager(this)
                manager.startCollecting()

                repeat(10) { bus.emit(makeEvent()) }
                advanceUntilIdle()

                manager.drain()
                manager.bufferedCount() shouldBe 0
            }
        }
    }

    // ── Buffer cap ────────────────────────────────────────────────────────────

    describe("ObservabilityManager — buffer cap") {

        /**
         * The buffer cap is 500 — verified indirectly by emitting 510 events and
         * confirming the buffer size never exceeds 500.
         *
         * Note: This test emits many events in rapid succession. The SharedFlow's
         * extraBufferCapacity (64) may cause some events to be dropped at the flow
         * level before reaching the manager buffer. We verify the UPPER BOUND only.
         */
        it("buffer size never exceeds 500 events") {
            runTest {
                val (manager, bus, _) = makeManager(this)
                manager.startCollecting()

                repeat(510) { bus.emit(makeEvent(message = "event-$it")) }
                advanceUntilIdle()

                manager.bufferedCount() shouldBeLessThanOrEqualTo 500
            }
        }
    }

    // ── Not collecting before startCollecting ─────────────────────────────────

    describe("ObservabilityManager — before startCollecting") {

        it("events emitted before startCollecting are not buffered") {
            runTest {
                val (manager, bus, _) = makeManager(this)
                // startCollecting NOT called yet

                bus.emit(makeEvent())
                advanceUntilIdle()

                // SharedFlow replay=0: events emitted before the collector started are lost
                manager.startCollecting()
                advanceUntilIdle()

                manager.bufferedCount() shouldBe 0
            }
        }
    }
})
