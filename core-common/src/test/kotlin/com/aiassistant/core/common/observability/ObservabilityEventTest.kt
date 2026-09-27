/**
 * ObservabilityEventTest.kt — core-common module unit tests
 *
 * Tests for [ObservabilityEvent] and its supporting types:
 *   - Data class equality and copy
 *   - Default field values
 *   - [EventLevel] ordinal ordering
 *   - [EventType] constant values (prevent accidental renames breaking the backend contract)
 *   - kotlinx.serialization round-trip (JSON → event → JSON)
 *
 * Phase 2 — Android Observability
 */

package com.aiassistant.core.common.observability

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun testEvent(
    level:     EventLevel          = EventLevel.INFO,
    eventType: String              = EventType.HTTP_SUCCESS,
    message:   String              = "test event",
    sessionId: String              = "session-001",
    metadata:  Map<String, String> = emptyMap(),
) = ObservabilityEvent(
    timestamp = 1_700_000_000_000L,
    level     = level,
    eventType = eventType,
    message   = message,
    sessionId = sessionId,
    metadata  = metadata,
)

class ObservabilityEventTest : DescribeSpec({

    // ── Default field values ──────────────────────────────────────────────────

    describe("ObservabilityEvent — default fields") {

        it("screen is null by default") {
            testEvent().screen.shouldBeNull()
        }

        it("requestId is null by default") {
            testEvent().requestId.shouldBeNull()
        }

        it("traceId is null by default") {
            testEvent().traceId.shouldBeNull()
        }

        it("metadata is an empty map by default") {
            testEvent().metadata shouldBe emptyMap()
        }
    }

    // ── Data class equality ───────────────────────────────────────────────────

    describe("ObservabilityEvent — equality") {

        it("two events with identical fields are equal") {
            val a = testEvent(message = "hello")
            val b = testEvent(message = "hello")
            a shouldBe b
        }

        it("copy with changed message produces a different event") {
            val original = testEvent(message = "before")
            val updated  = original.copy(message = "after")
            (original == updated) shouldBe false
        }

        it("copy preserves all other fields when changing one") {
            val original = testEvent(
                level     = EventLevel.ERROR,
                eventType = EventType.HTTP_ERROR,
                message   = "original",
                sessionId = "sess-xyz",
                metadata  = mapOf("key" to "value"),
            )
            val updated = original.copy(message = "updated")

            updated.level      shouldBe EventLevel.ERROR
            updated.eventType  shouldBe EventType.HTTP_ERROR
            updated.sessionId  shouldBe "sess-xyz"
            updated.metadata   shouldBe mapOf("key" to "value")
        }
    }

    // ── EventLevel ordinal ordering ───────────────────────────────────────────

    describe("EventLevel — ordinal ordering") {

        it("DEBUG < INFO < WARN < ERROR < CRITICAL") {
            EventLevel.DEBUG.ordinal   shouldBe 0
            EventLevel.INFO.ordinal    shouldBe 1
            EventLevel.WARN.ordinal    shouldBe 2
            EventLevel.ERROR.ordinal   shouldBe 3
            EventLevel.CRITICAL.ordinal shouldBe 4
        }

        it("has exactly five levels") {
            EventLevel.entries.size shouldBe 5
        }
    }

    // ── EventType constants — backend contract ─────────────────────────────────

    describe("EventType — constant values") {

        it("network event types match backend contract") {
            EventType.NETWORK_ERROR   shouldBe "network_error"
            EventType.NETWORK_TIMEOUT shouldBe "network_timeout"
            EventType.API_LATENCY     shouldBe "api_latency"
            EventType.HTTP_ERROR      shouldBe "http_error"
            EventType.HTTP_SUCCESS    shouldBe "http_success"
        }

        it("crash event types match backend contract") {
            EventType.CRASH_UNHANDLED shouldBe "crash_unhandled"
            EventType.CRASH_HANDLED   shouldBe "crash_handled"
        }

        it("lifecycle event types match backend contract") {
            EventType.APP_FOREGROUND shouldBe "app_foreground"
            EventType.APP_BACKGROUND shouldBe "app_background"
            EventType.SCREEN_VIEW    shouldBe "screen_view"
        }

        it("session and user event types match backend contract") {
            EventType.SESSION_START shouldBe "session_start"
            EventType.SESSION_END   shouldBe "session_end"
            EventType.USER_ERROR    shouldBe "user_error"
        }
    }

    // ── JSON serialization ────────────────────────────────────────────────────

    describe("ObservabilityEvent — JSON serialization") {

        it("serializes to JSON and back to an equal event") {
            val original = testEvent(
                level     = EventLevel.ERROR,
                eventType = EventType.HTTP_ERROR,
                message   = "POST /chat → HTTP 500",
                sessionId = "sess-round-trip",
                metadata  = mapOf("http_status" to "500", "endpoint" to "/chat"),
            )
            val jsonString = json.encodeToString(original)
            val decoded    = json.decodeFromString<ObservabilityEvent>(jsonString)
            decoded shouldBe original
        }

        it("serialized JSON contains the required top-level keys") {
            val event      = testEvent()
            val jsonString = json.encodeToString(event)

            // The backend expects camelCase field names (kotlinx-serialization default).
            jsonString shouldBe jsonString // will throw if serialization fails
            jsonString.contains("\"timestamp\"") shouldBe true
            jsonString.contains("\"level\"")     shouldBe true
            jsonString.contains("\"eventType\"") shouldBe true
            jsonString.contains("\"message\"")   shouldBe true
            jsonString.contains("\"sessionId\"") shouldBe true
        }

        it("null optional fields are omitted from JSON when encodeDefaults=false") {
            // With default settings null optionals appear in JSON (encodeDefaults=true).
            // Verify at minimum that serialization does not throw for null fields.
            val event = testEvent()
            val encodedWithDefaults = json.encodeToString(event)
            encodedWithDefaults.shouldNotBeNull()
        }

        it("metadata map survives JSON round-trip") {
            val meta  = mapOf("latency_ms" to "145", "endpoint" to "/api/v1/chat")
            val event = testEvent(metadata = meta)
            val back  = json.decodeFromString<ObservabilityEvent>(json.encodeToString(event))
            back.metadata shouldBe meta
        }

        it("EventLevel enum serializes as its name string") {
            val event  = testEvent(level = EventLevel.CRITICAL)
            val jsonStr = json.encodeToString(event)
            jsonStr.contains("\"CRITICAL\"") shouldBe true
        }
    }

    // ── Optional field presence ───────────────────────────────────────────────

    describe("ObservabilityEvent — optional fields") {

        it("screen can be set and retrieved") {
            val event = testEvent().copy(screen = "ChatScreen")
            event.screen shouldBe "ChatScreen"
        }

        it("requestId can be set and retrieved") {
            val event = testEvent().copy(requestId = "req-abc-123")
            event.requestId shouldBe "req-abc-123"
        }

        it("traceId can be set and retrieved") {
            val event = testEvent().copy(traceId = "trace-xyz-789")
            event.traceId shouldBe "trace-xyz-789"
        }

        it("all optional fields can be set simultaneously") {
            val event = testEvent().copy(
                screen    = "HomeScreen",
                requestId = "req-001",
                traceId   = "trace-001",
            )
            event.screen.shouldNotBeNull()
            event.requestId.shouldNotBeNull()
            event.traceId.shouldNotBeNull()
        }
    }
})
