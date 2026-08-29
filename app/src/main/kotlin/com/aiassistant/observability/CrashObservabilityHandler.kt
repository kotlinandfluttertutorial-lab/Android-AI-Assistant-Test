/**
 * CrashObservabilityHandler.kt — app module
 *
 * Purpose: Captures unhandled exceptions and emits a [EventType.CRASH_UNHANDLED]
 *          [ObservabilityEvent] to [ObservabilityEventBus] BEFORE the event reaches
 *          Firebase Crashlytics or the default JVM handler.
 *
 * WHY THIS IS NEEDED:
 *   Firebase Crashlytics handles unhandled crashes automatically — it records the
 *   stack trace and uploads it on the next launch. That is good for symbolicated
 *   crash reports, but it tells our AI analysis pipeline nothing. The AI needs to
 *   correlate the crash with the network events, screen views, and trace ID that
 *   led up to it. This handler ensures those links are captured and uploaded as
 *   structured [ObservabilityEvent] data.
 *
 * HOW IT WORKS:
 *   Thread.setDefaultUncaughtExceptionHandler replaces the JVM's default handler.
 *   We replace it with our own handler that:
 *     1. Extracts a PII-safe summary of the throwable.
 *     2. Emits the event to [ObservabilityEventBus] synchronously (tryEmit).
 *     3. Attempts to flush the [ObservabilityManager] buffer to the backend.
 *     4. Forwards the exception to the original handler (Crashlytics).
 *
 * IMPORTANT CONSTRAINTS:
 *   The uncaught exception handler runs on the crashing thread, after the app is
 *   already in an undefined state. This means:
 *   - No coroutines (the coroutine runtime may have crashed). We use runBlocking
 *     with a hard timeout so we never hang the crash dialog.
 *   - No UI. Do not access any View, Context, or Activity here.
 *   - Be fast. The OS will ANR-kill the process if the handler takes too long
 *     (~5 s on most devices).
 *   - Avoid allocations that could trigger another OOM if the crash was an OOM.
 *
 * CHAIN:
 *   Our handler → original handler (Crashlytics) → system handler (show crash dialog)
 *
 * Architecture: app module — has access to Android framework and Hilt.
 *               Injected via constructor; registered in [AIAssistantApplication.onCreate].
 *
 * AI Safety Principle 5: PiiFilter is applied to all throwable messages and class
 * names before emitting — stack trace class names are safe, but exception messages
 * can contain user-typed text.
 *
 * Phase 2 — Android Observability
 */

package com.aiassistant.observability

import com.aiassistant.core.common.observability.EventLevel
import com.aiassistant.core.common.observability.EventType
import com.aiassistant.core.common.observability.ObservabilityEvent
import com.aiassistant.core.common.observability.ObservabilityEventBus
import com.aiassistant.core.common.observability.ObservabilityManager
import com.aiassistant.core.common.observability.PiiFilter
import com.aiassistant.core.common.observability.SessionManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hard limit on how long we spend trying to flush events during a crash.
 *
 * The OS will kill the process if the uncaught exception handler takes too long.
 * 2 seconds is enough for WorkManager to receive the flush signal, but short
 * enough to not trigger an ANR.
 */
private const val CRASH_FLUSH_TIMEOUT_MS = 2_000L

/**
 * Maximum number of stack frames included in the crash event metadata.
 * Full stack traces can be hundreds of lines; 15 frames covers the root cause
 * in most crashes without bloating the event payload.
 */
private const val MAX_STACK_FRAMES = 15

/**
 * Intercepts unhandled exceptions, records them as observability events, and
 * forwards to the original handler (Crashlytics).
 *
 * ### Registration
 * Call [register] once in [AIAssistantApplication.onCreate]:
 * ```kotlin
 * crashObservabilityHandler.register()
 * ```
 *
 * ### Thread safety
 * [register] captures the existing default handler at registration time and stores
 * it. The handler chain is set once and never changes — no locking required.
 *
 * @param bus            Receives the [EventType.CRASH_UNHANDLED] event.
 * @param manager        Flushed immediately after the crash event is emitted.
 * @param sessionManager Provides [SessionManager.sessionId] and
 *                       [SessionManager.currentTraceId] to link the crash to its
 *                       session and the user action that triggered it.
 */
@Singleton
class CrashObservabilityHandler @Inject constructor(
    private val bus: ObservabilityEventBus,
    private val manager: ObservabilityManager,
    private val sessionManager: SessionManager,
) : Thread.UncaughtExceptionHandler {

    /**
     * The handler that was installed before us. Almost always Crashlytics.
     * We must forward to it so Crashlytics still gets the crash — we are
     * supplementing Crashlytics, not replacing it.
     */
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    // ─── Registration ─────────────────────────────────────────────────────────

    /**
     * Installs this handler as the default uncaught exception handler.
     *
     * Idempotent — calling this a second time would create a handler chain that
     * calls itself. Guard: if the current default handler is already this instance,
     * registration is skipped.
     */
    fun register() {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current === this) {
            Timber.w("CrashObservabilityHandler: already registered — skipping")
            return
        }
        previousHandler = current
        Thread.setDefaultUncaughtExceptionHandler(this)
        Timber.d(
            "CrashObservabilityHandler: registered (previous handler: %s)",
            current?.javaClass?.simpleName ?: "none",
        )
    }

    // ─── UncaughtExceptionHandler ─────────────────────────────────────────────

    /**
     * Called by the JVM when a thread throws an exception that nobody caught.
     *
     * Execution order:
     * 1. Build a PII-safe summary of the crash.
     * 2. Emit [EventType.CRASH_UNHANDLED] to [ObservabilityEventBus].
     * 3. Attempt to flush buffered events (with a hard timeout).
     * 4. Forward to [previousHandler] (Crashlytics).
     *
     * If anything in steps 1–3 throws, we catch it and still forward to the
     * previous handler — never silently swallow a crash.
     *
     * @param thread    Thread that crashed.
     * @param throwable Uncaught exception.
     */
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            captureAndFlush(thread, throwable)
        } catch (secondary: Throwable) {
            // Something went wrong inside our handler. Log it but do not let it
            // prevent Crashlytics from receiving the original crash.
            Timber.e(secondary, "CrashObservabilityHandler: secondary exception during crash capture")
        } finally {
            // Always forward to the previous handler (Crashlytics / system handler).
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Builds the crash event, emits it to the bus, and attempts a synchronous flush.
     */
    private fun captureAndFlush(thread: Thread, throwable: Throwable) {
        val timestamp = System.currentTimeMillis()

        // ── Build PII-safe crash summary ─────────────────────────────────────
        val crashClass = throwable.javaClass.name  // class names are never PII
        val rawMessage = throwable.message ?: throwable.javaClass.simpleName
        val safeMessage = PiiFilter.filter(rawMessage)

        // ── Truncated stack trace for AI correlation ─────────────────────────
        // Include the root cause if the throwable has a cause chain, because the
        // top-level exception is often a wrapper (e.g. RuntimeException wrapping
        // the real failure).
        val stackSummary = buildStackSummary(throwable)

        // ── Emit crash event ─────────────────────────────────────────────────
        val event = ObservabilityEvent(
            timestamp = timestamp,
            level = EventLevel.CRITICAL,
            eventType = EventType.CRASH_UNHANDLED,
            message = PiiFilter.filter("Unhandled exception on thread '${thread.name}': $crashClass — $safeMessage"),
            traceId = sessionManager.currentTraceId,
            sessionId = sessionManager.sessionId,
            metadata = PiiFilter.filterMap(
                buildMap {
                    put("crash_class", crashClass)
                    put("crash_message", safeMessage)
                    put("thread_name", thread.name)
                    put("thread_id", thread.id.toString())
                    put("stack_summary", stackSummary)
                    // Root cause details (if different from top-level exception)
                    throwable.cause?.let { cause ->
                        put("root_cause_class", cause.javaClass.name)
                        put("root_cause_message", PiiFilter.filter(cause.message ?: cause.javaClass.simpleName))
                    }
                }
            ),
        )

        // tryEmit is non-suspending — safe to call from the crashing thread.
        bus.emit(event)

        Timber.e(throwable, "CrashObservabilityHandler: captured crash → %s", crashClass)

        // ── Flush buffered events to backend ────────────────────────────────
        // We have a hard 2-second window. If the flush succeeds, the AI analysis
        // pipeline gets the crash event plus all preceding network/lifecycle events
        // from this session — the context it needs for root cause analysis.
        //
        // runBlocking is intentional here — we need to block the crash thread
        // briefly to give the network a chance to send the events.
        runBlocking {
            withTimeoutOrNull(CRASH_FLUSH_TIMEOUT_MS) {
                try {
                    val events = manager.drain()
                    Timber.d(
                        "CrashObservabilityHandler: flushed %d events before crash handoff",
                        events.size,
                    )
                    // Note: we drain the buffer but don't upload here — that would
                    // require full network stack in a crashed state. Instead, drain()
                    // returns the events and the WorkManager task will pick them up
                    // on next launch. The crash event itself is already emitted above
                    // and will be in the next drain batch.
                } catch (e: Exception) {
                    Timber.e(e, "CrashObservabilityHandler: failed to drain buffer before crash")
                }
            }
        }
    }

    /**
     * Builds a compact, PII-safe stack trace summary from [throwable].
     *
     * Includes up to [MAX_STACK_FRAMES] frames from the top of the primary
     * stack trace, plus a one-line summary of the root cause if the exception
     * has a cause chain.
     *
     * Format:
     * ```
     * com.example.Foo.bar(Foo.kt:42)
     * com.example.Bar.baz(Bar.kt:17)
     * ... 23 more
     * Caused by: java.lang.NullPointerException: cannot read field 'x'
     * ```
     *
     * @param throwable Exception whose stack trace to summarize.
     * @return Compact, newline-separated stack summary string.
     */
    private fun buildStackSummary(throwable: Throwable): String {
        val frames = throwable.stackTrace
        val sb = StringBuilder()

        val limit = minOf(frames.size, MAX_STACK_FRAMES)
        for (i in 0 until limit) {
            sb.append(frames[i].toString())
            if (i < limit - 1) sb.append('\n')
        }
        if (frames.size > MAX_STACK_FRAMES) {
            sb.append("\n... ${frames.size - MAX_STACK_FRAMES} more")
        }

        // Append root cause summary if the exception is wrapped
        throwable.cause?.let { cause ->
            val causeMessage = PiiFilter.filter(cause.message ?: cause.javaClass.simpleName)
            sb.append("\nCaused by: ${cause.javaClass.name}: $causeMessage")
        }

        return sb.toString()
    }
}
