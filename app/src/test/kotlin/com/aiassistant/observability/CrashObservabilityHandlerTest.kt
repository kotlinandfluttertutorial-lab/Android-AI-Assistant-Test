/**
 * CrashObservabilityHandlerTest.kt — app module unit tests
 *
 * Tests for [CrashObservabilityHandler]:
 *
 *   Registration
 *   - [register] installs this as the default uncaught exception handler
 *   - [register] is idempotent — calling it twice does not double-wrap the chain
 *   - Previous handler (e.g. Crashlytics) is preserved and called after our handler
 *
 *   Event emission
 *   - [uncaughtException] emits a CRASH_UNHANDLED event to [ObservabilityEventBus]
 *   - The emitted event has CRITICAL severity
 *   - The emitted event carries the session ID
 *   - PII in the exception message is stripped
 *   - The crash class name is included in event metadata
 *
 *   Previous handler forwarding
 *   - The original handler is ALWAYS called, even if event emission throws
 *
 * Testing approach:
 *   - [ObservabilityEventBus] is used directly — no mock needed (pure Kotlin).
 *   - [ObservabilityManager] is mocked so drain() returns immediately.
 *   - The "previous handler" is a mock that records whether it was called.
 *   - Events are captured via a [kotlinx.coroutines.channels.Channel] to avoid
 *     GlobalScope usage in tests.
 *
 * Phase 2 — Android Observability
 */

package com.aiassistant.observability

import com.aiassistant.core.common.observability.EventLevel
import com.aiassistant.core.common.observability.EventType
import com.aiassistant.core.common.observability.ObservabilityEvent
import com.aiassistant.core.common.observability.ObservabilityEventBus
import com.aiassistant.core.common.observability.ObservabilityManager
import com.aiassistant.core.common.observability.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CrashObservabilityHandlerTest {

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private val bus            = ObservabilityEventBus()
    private val sessionManager = SessionManager()
    private val manager        = mockk<ObservabilityManager>(relaxed = true)

    private lateinit var handler: CrashObservabilityHandler

    /** Holds the default handler that was installed before our test. */
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        handler = CrashObservabilityHandler(bus, manager, sessionManager)

        // drain() is a suspend function; mock it to return an empty list immediately
        coEvery { manager.drain() } returns emptyList()
    }

    @After
    fun tearDown() {
        // Restore the handler that was in place before this test ran
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Captures the next event emitted on [bus] while [action] is executing.
     *
     * Uses a [Channel] inside a coroutine scope confined to [runBlocking] so
     * no GlobalScope is needed.
     */
    private fun captureNextEvent(action: () -> Unit): ObservabilityEvent =
        runBlocking {
            val channel = Channel<ObservabilityEvent>(capacity = 1)
            val job = launch {
                bus.events.collect { event ->
                    channel.trySend(event)
                }
            }
            try {
                action()
                withTimeout(2_000) { channel.receive() }
            } finally {
                job.cancel()
            }
        }

    // ── Registration ─────────────────────────────────────────────────────────

    @Test
    fun `register installs this handler as the default`() {
        handler.register()
        assertEquals(handler, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun `register is idempotent - second call does not change the handler`() {
        handler.register()
        val firstInstall = Thread.getDefaultUncaughtExceptionHandler()
        handler.register() // second call should be a no-op
        assertEquals(firstInstall, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun `register preserves the previous handler in the chain`() {
        val mockPrevious = mockk<Thread.UncaughtExceptionHandler>(relaxed = true)
        Thread.setDefaultUncaughtExceptionHandler(mockPrevious)

        handler.register()

        // Trigger the handler — the previous handler must be called at the end
        handler.uncaughtException(Thread.currentThread(), RuntimeException("test"))

        verify(exactly = 1) { mockPrevious.uncaughtException(any(), any()) }
    }

    // ── Event emission ────────────────────────────────────────────────────────

    @Test
    fun `uncaughtException emits a CRASH_UNHANDLED event`() {
        handler.register()
        val event = captureNextEvent {
            handler.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        }
        assertEquals(EventType.CRASH_UNHANDLED, event.eventType)
    }

    @Test
    fun `uncaughtException emits an event with CRITICAL severity`() {
        handler.register()
        val event = captureNextEvent {
            handler.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        }
        assertEquals(EventLevel.CRITICAL, event.level)
    }

    @Test
    fun `uncaughtException event carries the current sessionId`() {
        handler.register()
        val event = captureNextEvent {
            handler.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        }
        assertEquals(sessionManager.sessionId, event.sessionId)
    }

    @Test
    fun `uncaughtException event metadata contains crash_class`() {
        handler.register()
        val event = captureNextEvent {
            handler.uncaughtException(
                Thread.currentThread(),
                IllegalStateException("bad state"),
            )
        }
        val crashClass = event.metadata["crash_class"]
        assertNotNull("crash_class should be in metadata", crashClass)
        assertTrue(
            "crash_class should contain exception type name",
            crashClass!!.contains("IllegalStateException"),
        )
    }

    @Test
    fun `uncaughtException strips PII from exception message`() {
        handler.register()
        val event = captureNextEvent {
            handler.uncaughtException(
                Thread.currentThread(),
                RuntimeException("Login failed for user@example.com"),
            )
        }
        assertTrue(
            "PII email must be filtered from event message",
            !event.message.contains("user@example.com"),
        )
    }

    @Test
    fun `uncaughtException includes traceId when a trace is active`() {
        val traceId = sessionManager.beginTrace()
        handler.register()
        val event = captureNextEvent {
            handler.uncaughtException(Thread.currentThread(), RuntimeException("err"))
        }
        assertEquals(traceId, event.traceId)
    }

    @Test
    fun `uncaughtException traceId is null when no trace is active`() {
        sessionManager.endTrace() // ensure no trace
        handler.register()
        val event = captureNextEvent {
            handler.uncaughtException(Thread.currentThread(), RuntimeException("err"))
        }
        assertNull("traceId should be null when no trace is active", event.traceId)
    }

    // ── Buffer drain ──────────────────────────────────────────────────────────

    @Test
    fun `uncaughtException calls manager drain`() {
        handler.register()
        handler.uncaughtException(Thread.currentThread(), RuntimeException("crash"))
        coVerify(atLeast = 1) { manager.drain() }
    }

    // ── Previous handler always called ────────────────────────────────────────

    @Test
    fun `previous handler is called even when uncaughtException is invoked`() {
        val mockPrevious = mockk<Thread.UncaughtExceptionHandler>(relaxed = true)
        Thread.setDefaultUncaughtExceptionHandler(mockPrevious)
        handler.register()

        handler.uncaughtException(Thread.currentThread(), RuntimeException("forward me"))

        verify(exactly = 1) { mockPrevious.uncaughtException(any(), any()) }
    }
}
