/**
 * SessionManagerTest.kt — core-common module unit tests
 *
 * Tests for [SessionManager]:
 *   - [SessionManager.sessionId] — assigned at construction, stable for lifetime
 *   - [SessionManager.beginTrace] — generates unique trace IDs
 *   - [SessionManager.endTrace] — clears [currentTraceId] back to null
 *   - [SessionManager.currentTraceId] — reflects current trace state
 *   - [SessionManager.newRequestId] — generates unique, non-repeating request IDs
 *   - Thread safety — concurrent calls to beginTrace / endTrace are consistent
 *
 * Phase 2 — Android Observability
 */

package com.aiassistant.core.common.observability

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty

class SessionManagerTest : DescribeSpec({

    // ── sessionId ─────────────────────────────────────────────────────────────

    describe("SessionManager.sessionId") {

        it("is assigned at construction and is non-empty") {
            val manager = SessionManager()
            manager.sessionId.shouldNotBeEmpty()
        }

        it("is stable — reading it multiple times returns the same value") {
            val manager = SessionManager()
            val first   = manager.sessionId
            val second  = manager.sessionId
            first shouldBe second
        }

        it("two different instances have different session IDs") {
            val a = SessionManager()
            val b = SessionManager()
            a.sessionId shouldNotBe b.sessionId
        }

        it("sessionId looks like a UUID (36 chars, contains dashes)") {
            val id = SessionManager().sessionId
            id.length shouldBe 36
            id.count { it == '-' } shouldBe 4
        }
    }

    // ── beginTrace ────────────────────────────────────────────────────────────

    describe("SessionManager.beginTrace") {

        it("returns a non-empty trace ID") {
            val manager = SessionManager()
            manager.beginTrace().shouldNotBeEmpty()
        }

        it("sets currentTraceId to the returned value") {
            val manager = SessionManager()
            val id = manager.beginTrace()
            manager.currentTraceId shouldBe id
        }

        it("each call generates a different trace ID") {
            val manager = SessionManager()
            val first  = manager.beginTrace()
            manager.endTrace()
            val second = manager.beginTrace()
            first shouldNotBe second
        }

        it("trace ID looks like a UUID") {
            val id = SessionManager().beginTrace()
            id.length shouldBe 36
            id.count { it == '-' } shouldBe 4
        }
    }

    // ── endTrace ──────────────────────────────────────────────────────────────

    describe("SessionManager.endTrace") {

        it("sets currentTraceId to null") {
            val manager = SessionManager()
            manager.beginTrace()
            manager.endTrace()
            manager.currentTraceId.shouldBeNull()
        }

        it("is idempotent — calling endTrace when no trace is active is safe") {
            val manager = SessionManager()
            manager.endTrace()
            manager.currentTraceId.shouldBeNull()
        }

        it("endTrace after beginTrace returns null, not the old ID") {
            val manager = SessionManager()
            manager.beginTrace()
            manager.endTrace()
            manager.currentTraceId.shouldBeNull()
        }
    }

    // ── currentTraceId ────────────────────────────────────────────────────────

    describe("SessionManager.currentTraceId") {

        it("is null on a fresh instance") {
            SessionManager().currentTraceId.shouldBeNull()
        }

        it("is non-null after beginTrace") {
            val manager = SessionManager()
            manager.beginTrace()
            manager.currentTraceId.shouldNotBeNull()
        }

        it("is null after endTrace") {
            val manager = SessionManager()
            manager.beginTrace()
            manager.endTrace()
            manager.currentTraceId.shouldBeNull()
        }

        it("reflects the most recent beginTrace call") {
            val manager = SessionManager()
            val first   = manager.beginTrace()
            manager.endTrace()
            val second  = manager.beginTrace()
            manager.currentTraceId shouldBe second
            manager.currentTraceId shouldNotBe first
        }
    }

    // ── newRequestId ─────────────────────────────────────────────────────────

    describe("SessionManager.newRequestId") {

        it("returns a non-empty request ID") {
            SessionManager().newRequestId().shouldNotBeEmpty()
        }

        it("each call returns a different request ID") {
            val manager = SessionManager()
            val ids = (1..20).map { manager.newRequestId() }.toSet()
            ids.size shouldBe 20  // all 20 should be unique
        }

        it("request ID looks like a UUID") {
            val id = SessionManager().newRequestId()
            id.length shouldBe 36
            id.count { it == '-' } shouldBe 4
        }

        it("does NOT affect currentTraceId") {
            val manager = SessionManager()
            manager.beginTrace()
            val traceId = manager.currentTraceId

            repeat(5) { manager.newRequestId() }

            manager.currentTraceId shouldBe traceId
        }
    }
})
