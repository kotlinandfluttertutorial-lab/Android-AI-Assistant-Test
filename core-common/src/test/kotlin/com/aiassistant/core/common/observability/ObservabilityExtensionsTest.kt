/**
 * ObservabilityExtensionsTest.kt — core-common module unit tests
 *
 * Tests for [ObservabilityExtensions]:
 *   - [captureHandled] — emits CRASH_HANDLED event with correct fields
 *   - [captureUserError] — emits USER_ERROR event with correct fields
 *   - PII filtering is applied inside both helpers
 *   - [withTrace] — beginTrace / endTrace lifecycle
 *   - [withTraceResult] — returns block value; endTrace called in finally
 *
 * Phase 2 — Android Observability
 */

package com.aiassistant.core.common.observability

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ObservabilityExtensionsTest : DescribeSpec({

    fun makeSession() = SessionManager()
    fun makeBus()     = ObservabilityEventBus()

    // ── captureHandled ────────────────────────────────────────────────────────

    describe("ObservabilityEventBus.captureHandled") {

        it("emits a CRASH_HANDLED event") {
            runTest {
                val bus     = makeBus()
                val session = makeSession()

                val deferred = kotlinx.coroutines.async {
                    bus.events.first()
                }

                bus.captureHandled(
                    throwable      = RuntimeException("connection refused"),
                    sessionManager = session,
                )

                val event = deferred.await()
                event.eventType shouldBe EventType.CRASH_HANDLED
            }
        }

        it("sets level to ERROR") {
            runTest {
                val bus     = makeBus()
                val session = makeSession()
                val deferred = kotlinx.coroutines.async { bus.events.first() }

                bus.captureHandled(
                    throwable      = IllegalStateException("boom"),
                    sessionManager = session,
                )

                deferred.await().level shouldBe EventLevel.ERROR
            }
        }

        it("uses the sessionId from SessionManager") {
            runTest {
                val bus     = makeBus()
                val session = makeSession()
                val deferred = kotlinx.coroutines.async { bus.events.first() }

                bus.captureHandled(
                    throwable      = Exception("err"),
                    sessionManager = session,
                )

                deferred.await().sessionId shouldBe session.sessionId
            }
        }

        it("includes the active traceId when a trace is in progress") {
            runTest {
                val bus     = makeBus()
                val session = makeSession()
                val traceId = session.beginTrace()
                val deferred = kotlinx.coroutines.async { bus.events.first() }

                bus.captureHandled(
                    throwable      = Exception("err"),
                    sessionManager = session,
                )

                deferred.await().traceId shouldBe traceId
            }
        }

        it("traceId is null when no trace is active") {
            runTest {
                val bus     = makeBus()
                val session = makeSession()
                val deferred = kotlinx.coroutines.async { bus.events.first() }

                bus.captureHandled(
                    throwable      = Exception("err"),
                    sessionManager = session,
                )

                deferred.await().traceId.shouldBeNull()
            }
        }

        it("sets the screen field when provided") {
            runTest {
                val bus     = makeBus()
                val session = makeSession()
                val deferred = kotlinx.coroutines.async { bus.events.first() }

                bus.captureHandled(
                    throwable      = Exception("err"),
                    sessionManager = session,
                    screen         = "ChatScreen",
                )

                deferred.await().screen shouldBe "ChatScreen"
            }
        }

        it("PII in the exception message is stripped from the event message") {
            runTest {
                val bus     = makeBus()
                val session = makeSession()
                val deferred = kotlinx.coroutines.async { bus.events.first() }

                // Exception message contains an email address — must be redacted
                bus.captureHandled(
                    throwable      = RuntimeException("Failed for user@example.com"),
                    sessionManager = session,
                )

                val event = deferred.await()
                event.message shouldNotContain "user@example.com"
                event.message shouldContain "[email]"
            }
        }

        it("PII in additionalContext values is stripped") {
            runTest {
                val bus     = makeBus()
                val session = makeSession()
                val deferred = kotlinx.coroutines.async { bus.events.first() }

                bus.captureHandled(
                    throwable         = RuntimeException("err"),
                    sessionManager    = session,
                    additionalContext = mapOf("user_info" to "contact: alice@corp.com"),
                )

                val event = deferred.await()
                event.metadata["user_info"] shouldNotContain "alice@corp.com"
                event.metadata["user_info"] shouldContain "[email]"
            }
        }

        it("metadata contains error_class") {
            runTest {
                val bus     = makeBus()
                val session = makeSession()
                val deferred = kotlinx.coroutines.async { bus.events.first() }

                bus.captureHandled(
                    throwable      = IllegalArgumentException("bad arg"),
                    sessionManager = session,
                )

                val event = deferred.await()
                event.metadata["error_class"].shouldNotBeNull()
                event.metadata["error_class"] shouldContain "IllegalArgumentException"
            }
        }
    }

    // ── captureUserError ──────────────────────────────────────────────────────

    describe("ObservabilityEventBus.captureUserError") {

        it("emits a USER_ERROR event") {
            runTest {
                val bus     = makeBus()
                val session = makeSession()
                val deferred = kotlinx.coroutines.async { bus.events.first() }

                bus.captureUserError(
                    message        = "Something went wrong. Please try again.",
                    sessionManager = session,
                )

                deferred.await().eventType shouldBe EventType.USER_ERROR
            }
        }

        it("sets level to WARN") {
            runTest {
                val bus     = makeBus()
                val session = makeSession()
                val deferred = kotlinx.coroutines.async { bus.events.first() }

                bus.captureUserError(
                    message        = "Upload failed",
                    sessionManager = session,
                )

                deferred.await().level shouldBe EventLevel.WARN
            }
        }

        it("PII in the user error message is stripped") {
            runTest {
                val bus     = makeBus()
                val session = makeSession()
                val deferred = kotlinx.coroutines.async { bus.events.first() }

                bus.captureUserError(
                    message        = "Could not send to bob@test.com",
                    sessionManager = session,
                )

                val event = deferred.await()
                event.message shouldNotContain "bob@test.com"
            }
        }
    }

    // ── withTrace ─────────────────────────────────────────────────────────────

    describe("withTrace") {

        it("currentTraceId is non-null inside the block") {
            runTest {
                val session = makeSession()
                var idInsideBlock: String? = null

                withTrace(session) {
                    idInsideBlock = session.currentTraceId
                }

                idInsideBlock.shouldNotBeNull()
            }
        }

        it("currentTraceId is null after the block completes") {
            runTest {
                val session = makeSession()
                withTrace(session) { /* nothing */ }
                session.currentTraceId.shouldBeNull()
            }
        }

        it("endTrace is called even when the block throws") {
            runTest {
                val session = makeSession()
                try {
                    withTrace(session) {
                        throw RuntimeException("simulated error")
                    }
                } catch (_: RuntimeException) {
                    // expected
                }
                session.currentTraceId.shouldBeNull()
            }
        }

        it("nested withTrace calls use the inner traceId") {
            runTest {
                val session = makeSession()
                var outerId: String? = null
                var innerId: String? = null

                withTrace(session) {
                    outerId = session.currentTraceId
                    withTrace(session) {
                        innerId = session.currentTraceId
                    }
                    // After inner trace ends, currentTraceId is null (endTrace was called)
                }

                outerId.shouldNotBeNull()
                innerId.shouldNotBeNull()
                // Inner and outer are separate UUIDs
                (outerId == innerId) shouldBe false
            }
        }
    }

    // ── withTraceResult ────────────────────────────────────────────────────────

    describe("withTraceResult") {

        it("returns the value produced by the block") {
            runTest {
                val session = makeSession()
                val result  = withTraceResult(session) { 42 }
                result shouldBe 42
            }
        }

        it("returns a string value correctly") {
            runTest {
                val session = makeSession()
                val result  = withTraceResult(session) { "hello" }
                result shouldBe "hello"
            }
        }

        it("currentTraceId is null after the block") {
            runTest {
                val session = makeSession()
                withTraceResult(session) { "done" }
                session.currentTraceId.shouldBeNull()
            }
        }

        it("endTrace is called even when block throws") {
            runTest {
                val session = makeSession()
                try {
                    withTraceResult(session) {
                        throw IllegalStateException("fail")
                    }
                } catch (_: IllegalStateException) { /* expected */ }
                session.currentTraceId.shouldBeNull()
            }
        }
    }
})
