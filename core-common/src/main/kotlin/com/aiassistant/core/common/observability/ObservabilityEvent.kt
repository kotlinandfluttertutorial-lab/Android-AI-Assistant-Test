/**
 * ObservabilityEvent.kt — core-common module
 *
 * Purpose: Standard event model for all observability data captured by the Android app.
 *          Every meaningful runtime event — network call, crash, screen view, lifecycle
 *          change — is represented as an [ObservabilityEvent] before being forwarded to
 *          the backend AI analysis pipeline.
 *
 * Architecture: core-common — pure Kotlin, zero Android/framework dependencies.
 *               This type is the single shared currency between the capture layer
 *               (interceptors, crash handlers) and the storage/upload layer (WorkManager).
 *
 * Design decisions:
 * - [metadata] is a Map<String, String> (not Any) so the entire event is safely
 *   JSON-serializable without reflection and never leaks complex objects.
 * - [traceId] groups all events belonging to one logical user action (e.g. "tap Send →
 *   POST /chat → streaming response"). When null, the event is stand-alone.
 * - [requestId] is unique per HTTP call — used to correlate the Android log entry with
 *   the backend log entry for the same request.
 * - [sessionId] groups all events within a single app launch-to-close session.
 * - PII is never stored raw in this model. The [PiiFilter] must be applied before
 *   constructing an [ObservabilityEvent].
 *
 * Phase 2 — Android Observability
 */

package com.aiassistant.core.common.observability

import kotlinx.serialization.Serializable

// ─── Event level ─────────────────────────────────────────────────────────────

/**
 * Severity / importance of an [ObservabilityEvent].
 *
 * Mirrors standard log-level semantics so backend consumers can filter by severity
 * without parsing the event body.
 */
enum class EventLevel {
    /** Verbose diagnostic output — not sent to the backend in production builds. */
    DEBUG,

    /** Routine operational events (screen opened, session started). */
    INFO,

    /** Degraded but recoverable state (slow API, retried request). */
    WARN,

    /** A user-visible failure occurred (API error, crash handled). */
    ERROR,

    /** A crash or data-loss event that requires immediate attention. */
    CRITICAL
}

// ─── Event type constants ─────────────────────────────────────────────────────

/**
 * Well-known values for [ObservabilityEvent.eventType].
 *
 * Using string constants (rather than an enum) keeps the model open for extension:
 * any module can define its own event types without modifying this file.
 */
object EventType {
    // Network
    const val NETWORK_ERROR = "network_error"
    const val NETWORK_TIMEOUT = "network_timeout"
    const val API_LATENCY = "api_latency"
    const val HTTP_ERROR = "http_error" // 4xx / 5xx responses
    const val HTTP_SUCCESS = "http_success"

    // Crashes & exceptions
    const val CRASH_UNHANDLED = "crash_unhandled"
    const val CRASH_HANDLED = "crash_handled"

    // Lifecycle
    const val APP_FOREGROUND = "app_foreground"
    const val APP_BACKGROUND = "app_background"
    const val SCREEN_VIEW = "screen_view"

    // Session
    const val SESSION_START = "session_start"
    const val SESSION_END = "session_end"

    // User-visible errors
    const val USER_ERROR = "user_error"
}

// ─── Core event model ─────────────────────────────────────────────────────────

/**
 * Immutable record describing a single observable event within the Android app.
 *
 * ### Required fields
 * - [timestamp] — epoch millis (UTC) when the event was captured.
 * - [level] — severity used for routing and alerting.
 * - [eventType] — machine-readable category; use constants from [EventType].
 * - [message] — human-readable description, PII-stripped.
 * - [sessionId] — groups events within one app session (set by [SessionManager]).
 *
 * ### Optional correlation fields
 * - [screen] — active Compose destination name at capture time.
 * - [requestId] — unique per HTTP call; links Android log ↔ backend log.
 * - [traceId] — groups related events across a single user flow.
 *
 * ### Extensible context
 * - [metadata] — arbitrary key-value pairs; all values are strings to guarantee
 *   safe serialization. No PII may appear here.
 *
 * Example:
 * ```kotlin
 * ObservabilityEvent(
 *     timestamp   = System.currentTimeMillis(),
 *     level       = EventLevel.ERROR,
 *     eventType   = EventType.HTTP_ERROR,
 *     message     = "POST /chat returned HTTP 500",
 *     screen      = "ChatScreen",
 *     requestId   = UUID.randomUUID().toString(),
 *     traceId     = currentTraceId,
 *     sessionId   = sessionManager.sessionId,
 *     metadata    = mapOf(
 *         "http_status" to "500",
 *         "endpoint"    to "/chat",
 *         "latency_ms"  to "320",
 *     ),
 * )
 * ```
 */
@Serializable
data class ObservabilityEvent(
    /** Epoch milliseconds (UTC) when the event was captured on-device. */
    val timestamp: Long,

    /** Severity level — used by the backend to filter and route events. */
    val level: EventLevel,

    /**
     * Machine-readable event category.
     * Use one of the [EventType] constants or a module-specific string.
     */
    val eventType: String,

    /**
     * Human-readable description of what happened.
     * Must be PII-free — apply [PiiFilter] before populating this field.
     */
    val message: String,

    /**
     * Compose navigation route / screen name that was active when the event fired.
     * Null for events not tied to a specific screen (e.g. background syncs).
     */
    val screen: String? = null,

    /**
     * Unique identifier for a single HTTP request.
     * Correlates this Android log entry with the corresponding backend log line.
     * Null for non-network events.
     */
    val requestId: String? = null,

    /**
     * Trace ID that groups all events belonging to one logical user action.
     * Example: one [traceId] spans "tap Send → POST /chat → stream chunks → render".
     * Null when no trace context has been established.
     */
    val traceId: String? = null,

    /**
     * Session ID assigned at app launch by [SessionManager].
     * Groups all events within a single foreground-to-background lifecycle.
     */
    val sessionId: String,

    /**
     * Arbitrary key-value metadata for this event.
     * All values must be plain strings — no nested objects, no PII.
     *
     * Common keys:
     * - `"http_status"`   — HTTP response code as a string, e.g. `"500"`
     * - `"endpoint"`      — API path, e.g. `"/chat"`
     * - `"latency_ms"`    — Request duration in milliseconds, e.g. `"320"`
     * - `"error_type"`    — DomainError subtype name, e.g. `"NetworkError"`
     * - `"retry_count"`   — Number of retries attempted, e.g. `"2"`
     */
    val metadata: Map<String, String> = emptyMap()
)
