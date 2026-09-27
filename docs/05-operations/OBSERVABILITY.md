# Android Observability — Phase 2

This document describes the complete Android observability instrumentation layer:
what it captures, where it lives, how events flow from the device to the AI analysis
pipeline, and how to use it from ViewModels and UseCases.

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Event Model](#event-model)
4. [Components](#components)
5. [Usage Guide](#usage-guide)
6. [PII Filtering](#pii-filtering)
7. [Correlation IDs](#correlation-ids)
8. [Test Coverage](#test-coverage)
9. [AI Analysis Integration](#ai-analysis-integration)
10. [Production Considerations](#production-considerations)

---

## Overview

The observability layer captures structured events from the Android client and uploads
them to the backend `POST /api/v1/observability/events` endpoint (no auth required).
The backend persists these events and feeds them into the AI error analysis and anomaly
detection pipelines (Phases 10–12).

**What is captured automatically (no ViewModel code required):**

| Category | Captured by | Event types |
|---|---|---|
| Every HTTP request | `NetworkObservabilityInterceptor` | `http_success`, `http_error`, `network_error`, `network_timeout`, `api_latency` |
| App foreground/background | `AppLifecycleObserver` | `app_foreground`, `app_background` |
| Compose navigation | `ObservabilityNavTracker` | `screen_view` |
| Unhandled crashes | `CrashObservabilityHandler` | `crash_unhandled` |

**What requires a one-line call in the ViewModel/UseCase:**

| Category | How | Event type |
|---|---|---|
| Handled exceptions | `bus.captureHandled(exception, sessionManager)` | `crash_handled` |
| User-visible errors | `bus.captureUserError("message", sessionManager)` | `user_error` |
| Request tracing | `withTrace(sessionManager) { ... }` | Groups network events |

---

## Architecture

```
ViewModel / UseCase (optional: withTrace, captureHandled)
       │
       ▼
ObservabilityEventBus          (SharedFlow, extraBufferCapacity=64, DROP_OLDEST)
       │
       ▼
ObservabilityManager           (ArrayDeque ring buffer, max 500 events, Mutex)
       │
       │  drain() every 15 min (WorkManager)
       ▼
ObservabilityUploadWorker      (@HiltWorker, NetworkType.CONNECTED, retry on fail)
       │
       │  POST /api/v1/observability/events  (no JWT — uploads even when logged out)
       ▼
Backend PostgreSQL + ChromaDB  → AI error analysis + anomaly detection
```

**Key design choices:**

- `ObservabilityEventBus.emit()` uses `tryEmit()` — the OkHttp thread never suspends
- Events emitted before `ObservabilityManager.startCollecting()` is called are lost by design
  (they have no session context and would mislead the AI)
- `ObservabilityUploadWorker` uses a plain `OkHttpClient` (no auth interceptor)
  so events upload even when the user is logged out

---

## Event Model

```kotlin
@Serializable
data class ObservabilityEvent(
    val timestamp:  Long,                       // System.currentTimeMillis() (epoch ms UTC)
    val level:      EventLevel,                 // DEBUG | INFO | WARN | ERROR | CRITICAL
    val eventType:  String,                     // EventType.* constant
    val message:    String,                     // PII-filtered human-readable description
    val sessionId:  String,                     // UUID per app session (from SessionManager)
    val screen:     String?        = null,      // Active Compose route (set automatically)
    val requestId:  String?        = null,      // UUID per HTTP call (X-Request-ID header)
    val traceId:    String?        = null,      // UUID per user action (optional)
    val metadata:   Map<String, String> = emptyMap() // PII-filtered key-value context
)
```

### EventLevel

| Level | When to use |
|---|---|
| `DEBUG` | Verbose diagnostics. Not sent to the backend in production builds. |
| `INFO` | Routine operational events (screen opened, session started, HTTP 200). |
| `WARN` | Degraded but recovered (slow API call, HTTP 4xx, handled exception). |
| `ERROR` | User-visible failure (HTTP 5xx, network timeout, handled crash). |
| `CRITICAL` | Unhandled crash or data-loss event requiring immediate attention. |

### EventType Constants

```kotlin
object EventType {
    // Network (captured automatically by NetworkObservabilityInterceptor)
    const val HTTP_SUCCESS    = "http_success"
    const val HTTP_ERROR      = "http_error"       // 4xx or 5xx
    const val NETWORK_ERROR   = "network_error"    // connection failure
    const val NETWORK_TIMEOUT = "network_timeout"  // SocketTimeoutException
    const val API_LATENCY     = "api_latency"      // any call > 1 second

    // Crashes (captured automatically / call captureHandled() for handled ones)
    const val CRASH_UNHANDLED = "crash_unhandled"
    const val CRASH_HANDLED   = "crash_handled"

    // Lifecycle (captured automatically)
    const val APP_FOREGROUND  = "app_foreground"
    const val APP_BACKGROUND  = "app_background"
    const val SCREEN_VIEW     = "screen_view"

    // Session
    const val SESSION_START   = "session_start"
    const val SESSION_END     = "session_end"

    // User-visible errors (call captureUserError())
    const val USER_ERROR      = "user_error"
}
```

---

## Components

### `ObservabilityEvent.kt` — `core-common`

The shared data model. `@Serializable` for kotlinx.serialization.
`metadata` is `Map<String, String>` (never `Any`) — guaranteed JSON-safe.

### `ObservabilityEventBus.kt` — `core-common`

`SharedFlow`-based pub/sub bus. Call `bus.emit(event)` from any thread.
`tryEmit` never suspends — safe from OkHttp I/O threads.

### `ObservabilityManager.kt` — `core-common`

In-memory ring buffer (max 500 events). Call `drain()` to atomically return and clear.
Started by `ObservabilityModule.provideObservabilityManager()` — no manual wiring needed.

### `SessionManager.kt` — `core-common`

Generates correlation identifiers. Injected as `@Singleton`.

| Method | Returns | Scope |
|---|---|---|
| `sessionId` | `String` (stable) | Process lifetime |
| `beginTrace()` | New `traceId: String` | Until `endTrace()` |
| `endTrace()` | `Unit` | Resets `currentTraceId` to null |
| `currentTraceId` | `String?` | Read by interceptor per request |
| `newRequestId()` | New UUID | Per HTTP call |

### `PiiFilter.kt` — `core-common`

Applies regex-based redaction before events are created. Patterns:

| Pattern | Replaced with |
|---|---|
| Email addresses | `[email]` |
| Bearer tokens / JWTs | `Bearer [token]` |
| Authorization headers | `$scheme [redacted]` |
| Credit card numbers | `[card]` |
| IPv4 addresses | `[ip]` |
| Phone numbers | `[phone]` |

Always apply before constructing an `ObservabilityEvent`. Never skip.

### `ObservabilityExtensions.kt` — `core-common` *(new in Phase 2)*

Extension functions for use in ViewModels and UseCases:

```kotlin
// Emit a CRASH_HANDLED event for a caught exception
bus.captureHandled(throwable, sessionManager, screen = "ChatScreen")

// Emit a USER_ERROR event for an error shown to the user
bus.captureUserError("Upload failed. Please try again.", sessionManager)

// Wrap a block with automatic trace begin/end
withTrace(sessionManager) {
    repository.sendMessage(text)
}

// Same, but returns a value
val result = withTraceResult(sessionManager) {
    repository.loadConversation(id)
}
```

### `NetworkObservabilityInterceptor.kt` — `core-network`

OkHttp `Interceptor` installed in the `OkHttpClient` chain. Runs after `AuthInterceptor`
and before `HttpLoggingInterceptor`.

Per request it captures:
- HTTP method + path (no query parameters — may contain tokens)
- Response code and latency
- `X-Request-ID` header sent to the backend for log correlation
- `X-Trace-ID` header (when a trace is active)
- An additional `api_latency` event for calls exceeding 1 second

### `ObservabilityUploadWorker.kt` — `core-network`

`@HiltWorker` scheduled every 15 minutes via `WorkManager`.
Requires `NetworkType.CONNECTED`. Batches up to 200 events per POST.
Uses exponential back-off (30s initial) on network failure.
Scheduled in `AIAssistantApplication.onCreate()`:

```kotlin
scheduleObservabilityUpload(this)
```

### `CrashObservabilityHandler.kt` — `app`

`Thread.UncaughtExceptionHandler` that wraps Crashlytics. Registered in
`AIAssistantApplication.onCreate()` AFTER Firebase is initialised:

```kotlin
crashObservabilityHandler.register()
```

Chain: `CrashObservabilityHandler` → Crashlytics → system handler.

On crash: emits `CRASH_UNHANDLED` event, drains buffer (2 s timeout),
then forwards to Crashlytics. Never swallows a crash.

### `AppLifecycleObserver.kt` — `app`

`DefaultLifecycleObserver` attached to `ProcessLifecycleOwner`.
Emits `APP_FOREGROUND` / `APP_BACKGROUND` on true foreground transitions
(NOT on screen rotations).

### `ObservabilityNavTracker.kt` — `app`

Compose `NavController.OnDestinationChangedListener` via `@HiltViewModel`.
Emits `SCREEN_VIEW` on every navigation destination change.

---

## Usage Guide

### 1. Capturing handled exceptions

Call this in any `catch` block where you recover from an error:

```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val bus:            ObservabilityEventBus,
    private val sessionManager: SessionManager,
    private val repository:     ChatRepository,
) : ViewModel() {

    fun sendMessage(text: String) = viewModelScope.launch {
        runCatching { repository.sendMessage(text) }
            .onFailure { exception ->
                // Emit a CRASH_HANDLED event — the AI analysis pipeline
                // uses these to detect patterns before unhandled crashes.
                bus.captureHandled(
                    throwable      = exception,
                    sessionManager = sessionManager,
                    screen         = "ChatScreen",
                    additionalContext = mapOf("user_action" to "send_message"),
                )
                // Show error to user
                _uiState.update { it.copy(error = "Message failed. Please retry.") }
            }
    }
}
```

### 2. Tracing a user action

Wrap any multi-step operation that involves network calls:

```kotlin
fun handleSendButton(text: String) = viewModelScope.launch {
    // All network requests inside this block share the same traceId.
    // The AI can correlate every event from "tap Send" to "response rendered".
    withTrace(sessionManager) {
        val conversation = repository.getOrCreateConversation()
        repository.sendMessage(conversation.id, text)
    }
}
```

### 3. Capturing user-visible errors

```kotlin
fun onErrorShown(message: String) {
    bus.captureUserError(
        message        = message,  // already sanitized for display; no PII
        sessionManager = sessionManager,
        screen         = "SettingsScreen",
    )
}
```

### 4. Adding custom event types

For events not covered by the built-in helpers, emit directly:

```kotlin
bus.emit(
    ObservabilityEvent(
        timestamp  = System.currentTimeMillis(),
        level      = EventLevel.INFO,
        eventType  = "on_device_ai_loaded",     // custom type string
        message    = "On-device model ready",
        sessionId  = sessionManager.sessionId,
        traceId    = sessionManager.currentTraceId,
        metadata   = mapOf(
            "model_name"    to "gemma-2-2b",
            "load_time_ms"  to loadTimeMs.toString(),
        ),
    )
)
```

---

## PII Filtering

**All strings must be filtered before they enter an `ObservabilityEvent`.**

```kotlin
// ✅ Correct
val safeMessage = PiiFilter.filter(exception.message ?: "unknown")
val safeMetadata = PiiFilter.filterMap(mapOf("endpoint" to path))

// ❌ Wrong — never log raw exception messages, user input, or API responses
val event = ObservabilityEvent(message = exception.message!!, ...)
```

The `NetworkObservabilityInterceptor` and both extension functions (`captureHandled`,
`captureUserError`) already apply `PiiFilter` internally. You do not need to filter
manually when using them.

**What `PiiFilter` redacts:**

```
"Failed for user@example.com"      → "Failed for [email]"
"Bearer eyJhbGci...token..."        → "Bearer [token]"
"Connected to 192.168.1.100"       → "Connected to [ip]"
```

**What `PiiFilter` does NOT redact:**

- UUIDs (they identify resources, not people)
- HTTP paths (e.g. `/chat/message`)
- Numeric status codes and latencies

---

## Correlation IDs

### Request ID

Generated per HTTP call by `SessionManager.newRequestId()`. The interceptor:
1. Attaches it as `X-Request-ID` on the outgoing request
2. Stores it in the `ObservabilityEvent.requestId` field

The backend logs the same `X-Request-ID` on its side. When the AI analysis pipeline
sees a 500 error on the Android side, it can query the backend logs by request ID
to get the full server-side trace.

### Trace ID

Optional. Groups all events from a single user action. Call `sessionManager.beginTrace()`
at the start of an action and `sessionManager.endTrace()` at the end (or use `withTrace`).

The `NetworkObservabilityInterceptor` reads `SessionManager.currentTraceId` and:
1. Attaches it as `X-Trace-ID` on the outgoing request
2. Stores it in `ObservabilityEvent.traceId`

**Example trace timeline (single "Send Message" action):**

```
traceId = "abc-123"
  │
  ├── SCREEN_VIEW  (ChatScreen)               traceId=abc-123
  ├── HTTP_SUCCESS (POST /conversations)      traceId=abc-123, requestId=req-001
  ├── HTTP_SUCCESS (WS upgrade /ws/chat/...)  traceId=abc-123, requestId=req-002
  └── API_LATENCY  (3.2s streaming)           traceId=abc-123
```

The AI analysis pipeline can query `WHERE trace_id = 'abc-123'` to get all events
from that single action.

### Session ID

Stable for the process lifetime. A new UUID is assigned every time the app process
starts (not on foreground/background transitions). All events from a session share
the same `sessionId`, letting the AI reconstruct a complete picture of what happened
before an error.

---

## Test Coverage

Tests live in `core-common/src/test` (Kotest `DescribeSpec`) and
`app/src/test` (JUnit 4).

| Test file | Class under test | Tests |
|---|---|---|
| `PiiFilterTest.kt` | `PiiFilter` | 20 |
| `ObservabilityEventTest.kt` | `ObservabilityEvent`, `EventLevel`, `EventType` | 18 |
| `ObservabilityManagerTest.kt` | `ObservabilityManager` | 14 |
| `SessionManagerTest.kt` | `SessionManager` | 16 |
| `ObservabilityExtensionsTest.kt` | `captureHandled`, `captureUserError`, `withTrace`, `withTraceResult` | 18 |
| `CrashObservabilityHandlerTest.kt` | `CrashObservabilityHandler` | 12 |

Run all observability tests:

```bash
./gradlew :core-common:test --tests "com.aiassistant.core.common.observability.*"
./gradlew :app:test --tests "com.aiassistant.observability.*"
```

---

## AI Analysis Integration

Events uploaded by `ObservabilityUploadWorker` are stored in the backend's
`observability_events` PostgreSQL table and indexed in ChromaDB for semantic search.

The backend AI pipeline (Phase 10) uses them in three ways:

**1. Error analysis** (`POST /analysis/errors`)

The AI retrieves recent ERROR/CRITICAL events, correlates them by `sessionId` and
`traceId`, retrieves matching runbooks from ChromaDB, and produces a structured
`ErrorAnalysisResponse` including `facts_vs_inference`, `confidence`, and
`recommended_fix`.

**2. Anomaly detection** (Phase 11)

Error rate, latency, and crash rate are computed from `http_error` and `api_latency`
events. When a metric exceeds its threshold, an incident is created automatically.

**3. Root cause analysis** (`POST /incidents/{id}/rca`, Phase 12)

The RCA pipeline correlates all events in the evidence window (±30 min around the
incident) by `sessionId` and `traceId` to build a correlated timeline.

---

## Production Considerations

| Concern | Implementation |
|---|---|
| **Log level gate** | Production builds should only emit `WARN` and above. Check `BuildConfig.DEBUG` via the `@Named("isDebugBuild")` injection. |
| **Buffer persistence** | Current buffer is in-memory (lost on kill). Phase 8 will add Room persistence for high-importance events. |
| **Upload frequency** | 15-minute minimum (WorkManager minimum). Not configurable via Remote Config yet. |
| **Batch size cap** | Max 200 events per POST. A session with many rapid API calls will produce multiple batches. |
| **Metered networks** | `WorkManager` currently uses `NetworkType.CONNECTED` — includes metered. Change to `NetworkType.UNMETERED` if cellular data usage is a concern. |
| **No auth required** | The observability endpoint has no JWT requirement. Events upload even when the user is logged out or the refresh token has expired. |
| **Crashlytics integration** | `CrashObservabilityHandler` supplements Crashlytics — it prepends to the handler chain, never replaces it. Both systems receive every crash. |
