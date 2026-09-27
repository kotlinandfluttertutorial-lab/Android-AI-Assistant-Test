/**
 * ObservabilityExtensions.kt — core-common module
 *
 * Purpose: Convenience extensions that make it easy to instrument ViewModels and
 *          UseCases with minimal boilerplate, completing the two genuine Phase 2 gaps:
 *
 *   Gap 1 — [captureHandled]: Emit a [EventType.CRASH_HANDLED] event whenever a
 *            caught exception is recovered from. Without this, the AI analysis
 *            pipeline only sees unhandled crashes; it cannot detect patterns in
 *            handled errors (e.g. a repository swallowing network timeouts).
 *
 *   Gap 2 — [withTrace] / [withTraceResult]: Wrap a suspend block with automatic
 *            [SessionManager.beginTrace] / [SessionManager.endTrace] calls so every
 *            network request inside the block is tagged with the same traceId.
 *            Without this the trace infrastructure in [SessionManager] is never used.
 *
 * Architecture: core-common — pure Kotlin, no Android or Hilt dependencies.
 *               Call these from ViewModels or UseCases.
 *
 * Usage in a ViewModel:
 * ```kotlin
 * class ChatViewModel @Inject constructor(
 *     private val bus: ObservabilityEventBus,
 *     private val sessionManager: SessionManager,
 *     private val repository: ChatRepository,
 * ) : ViewModel() {
 *
 *     fun sendMessage(text: String) = viewModelScope.launch {
 *         // All network calls inside this block share the same traceId.
 *         withTrace(sessionManager) {
 *             val result = repository.sendMessage(text)
 *             result.onFailure { error ->
 *                 bus.captureHandled(
 *                     throwable    = error,
 *                     screen       = "ChatScreen",
 *                     sessionManager = sessionManager,
 *                 )
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * AI Safety Principle 5: PiiFilter is applied to all exception messages before
 * they are stored in the event — exception messages can contain user-typed text.
 *
 * Phase 2 — Android Observability
 */

package com.aiassistant.core.common.observability

// ─── Handled exception capture ────────────────────────────────────────────────

/**
 * Emits a [EventType.CRASH_HANDLED] [ObservabilityEvent] for a caught [Throwable].
 *
 * Use this wherever an exception is caught and recovered from — repository catch
 * blocks, try/catch in a UseCase, etc. The AI analysis pipeline uses these events
 * to detect patterns in handled errors that precede unhandled crashes.
 *
 * The exception message is filtered through [PiiFilter] before storage.
 *
 * @receiver [ObservabilityEventBus] to emit the event on.
 * @param throwable        The caught exception.
 * @param screen           The active screen name, if known (e.g. "ChatScreen").
 * @param sessionManager   Source of [SessionManager.sessionId] and
 *                         [SessionManager.currentTraceId].
 * @param additionalContext Extra key-value context. All values are PII-filtered.
 *                          Do not include user input or PII here.
 */
fun ObservabilityEventBus.captureHandled(
    throwable: Throwable,
    sessionManager: SessionManager,
    screen: String? = null,
    additionalContext: Map<String, String> = emptyMap(),
) {
    val crashClass  = throwable.javaClass.name
    val rawMessage  = throwable.message ?: throwable.javaClass.simpleName
    val safeMessage = PiiFilter.filter(rawMessage)

    val metadata = PiiFilter.filterMap(
        buildMap {
            put("error_class",   crashClass)
            put("error_message", safeMessage)
            throwable.cause?.let { cause ->
                put("root_cause_class",   cause.javaClass.name)
                put("root_cause_message", PiiFilter.filter(cause.message ?: cause.javaClass.simpleName))
            }
            putAll(additionalContext)
        }
    )

    emit(
        ObservabilityEvent(
            timestamp  = System.currentTimeMillis(),
            level      = EventLevel.ERROR,
            eventType  = EventType.CRASH_HANDLED,
            message    = PiiFilter.filter("Handled exception: $crashClass — $safeMessage"),
            screen     = screen,
            traceId    = sessionManager.currentTraceId,
            sessionId  = sessionManager.sessionId,
            metadata   = metadata,
        )
    )
}

/**
 * Emits a [EventType.USER_ERROR] event for an error that was shown to the user.
 *
 * Use this in ViewModels when mapping a domain error to a UI error state — the AI
 * analysis pipeline needs to know which errors became visible, not just which were
 * thrown.
 *
 * @receiver [ObservabilityEventBus] to emit the event on.
 * @param message        User-facing error message (already PII-safe; do not include
 *                       raw exception messages here).
 * @param screen         Active screen name, if known.
 * @param sessionManager Source of correlation IDs.
 * @param metadata       Additional safe key-value context (PII-filtered).
 */
fun ObservabilityEventBus.captureUserError(
    message: String,
    sessionManager: SessionManager,
    screen: String? = null,
    metadata: Map<String, String> = emptyMap(),
) {
    emit(
        ObservabilityEvent(
            timestamp  = System.currentTimeMillis(),
            level      = EventLevel.WARN,
            eventType  = EventType.USER_ERROR,
            message    = PiiFilter.filter(message),
            screen     = screen,
            traceId    = sessionManager.currentTraceId,
            sessionId  = sessionManager.sessionId,
            metadata   = PiiFilter.filterMap(metadata),
        )
    )
}

// ─── Trace wrappers ───────────────────────────────────────────────────────────

/**
 * Wraps a suspend [block] with [SessionManager.beginTrace] / [SessionManager.endTrace],
 * guaranteeing that all [ObservabilityEvent] instances captured inside the block share
 * the same [SessionManager.currentTraceId].
 *
 * The trace is always ended in a `finally` block, even if [block] throws.
 *
 * Usage:
 * ```kotlin
 * viewModelScope.launch {
 *     withTrace(sessionManager) {
 *         repository.sendMessage(text)  // network interceptor picks up traceId
 *     }
 * }
 * ```
 *
 * @param sessionManager Controls the lifecycle of the trace ID.
 * @param block          Suspend lambda to execute within the trace context.
 */
suspend fun withTrace(
    sessionManager: SessionManager,
    block: suspend () -> Unit,
) {
    sessionManager.beginTrace()
    try {
        block()
    } finally {
        sessionManager.endTrace()
    }
}

/**
 * Like [withTrace] but returns the result of [block].
 *
 * Usage:
 * ```kotlin
 * val result = withTraceResult(sessionManager) {
 *     repository.loadConversation(id)
 * }
 * ```
 *
 * @param sessionManager Controls the lifecycle of the trace ID.
 * @param block          Suspend lambda whose return value is propagated.
 * @return The value returned by [block].
 */
suspend fun <T> withTraceResult(
    sessionManager: SessionManager,
    block: suspend () -> T,
): T {
    sessionManager.beginTrace()
    return try {
        block()
    } finally {
        sessionManager.endTrace()
    }
}
