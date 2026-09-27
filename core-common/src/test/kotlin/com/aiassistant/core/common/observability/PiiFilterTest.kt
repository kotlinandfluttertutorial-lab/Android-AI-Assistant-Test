/**
 * PiiFilterTest.kt — core-common module unit tests
 *
 * Tests for [PiiFilter]:
 *   - [PiiFilter.filter]         — redacts PII patterns in a string
 *   - [PiiFilter.filterMap]      — applies filter to every value in a Map
 *   - [PiiFilter.containsPii]    — returns true when PII is detected
 *
 * Design:
 * - Tests are organised by PII category (email, phone, token, card, ip) so that
 *   adding a new category is a one-step change: add the category's describe block.
 * - Each describe block covers: positive match, negative match (no false positive),
 *   and edge cases.
 * - No Android dependencies — pure JVM tests.
 *
 * Phase 2 — Android Observability
 */

package com.aiassistant.core.common.observability

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class PiiFilterTest : DescribeSpec({

    // ── Email ─────────────────────────────────────────────────────────────────

    describe("PiiFilter.filter — email") {

        it("replaces a plain email address with [email]") {
            val result = PiiFilter.filter("User is alice@example.com in the log")
            result shouldContain "[email]"
            result shouldNotContain "alice@example.com"
        }

        it("replaces multiple email addresses in a single string") {
            val result = PiiFilter.filter("Sent from bob@test.org to carol@corp.io")
            result shouldNotContain "bob@test.org"
            result shouldNotContain "carol@corp.io"
            result.count { it == '[' } shouldBe 2 // two [email] replacements
        }

        it("replaces email with plus-tag addressing") {
            val result = PiiFilter.filter("Delivered to user+tag@sub.domain.org")
            result shouldNotContain "user+tag@sub.domain.org"
            result shouldContain "[email]"
        }

        it("does NOT corrupt a plain string with no email") {
            val input = "Network error: connection refused on port 443"
            PiiFilter.filter(input) shouldBe input
        }
    }

    // ── Bearer token / JWT ────────────────────────────────────────────────────

    describe("PiiFilter.filter — bearer token") {

        it("redacts a Bearer token in an Authorization header value") {
            val result = PiiFilter.filter(
                "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.signature"
            )
            result shouldNotContain "eyJhbGci"
            result shouldContain "Bearer [token]"
        }

        it("redacts a Bearer token regardless of case") {
            val result = PiiFilter.filter("bearer ABCDEFGHIJ1234567890abcdefghij1234567890")
            result shouldNotContain "ABCDEFGHIJ"
            result shouldContain "Bearer [token]"
        }

        it("does NOT corrupt a short non-token word after 'bearer' (< 20 chars)") {
            // The regex requires >= 20 chars after 'bearer' — too short to be a token.
            val input = "Error: bearer short"
            // Short value should not be matched by the >=20 char token regex
            PiiFilter.containsPii(input).shouldBeFalse()
        }

        it("redacts Authorization header assignment syntax") {
            val result = PiiFilter.filter("Authorization: Basic dXNlcjpwYXNzd29yZA==")
            result shouldNotContain "dXNlcjpwYXNzd29yZA=="
        }
    }

    // ── IPv4 ──────────────────────────────────────────────────────────────────

    describe("PiiFilter.filter — IPv4 address") {

        it("replaces an IPv4 address with [ip]") {
            val result = PiiFilter.filter("Connection to 192.168.1.100 failed")
            result shouldNotContain "192.168.1.100"
            result shouldContain "[ip]"
        }

        it("replaces a public IP address") {
            val result = PiiFilter.filter("Remote host: 203.0.113.42")
            result shouldNotContain "203.0.113.42"
            result shouldContain "[ip]"
        }

        it("does NOT corrupt a plain string with no IP") {
            val input = "HTTP 200 OK — request completed"
            PiiFilter.filter(input) shouldBe input
        }
    }

    // ── filterMap ─────────────────────────────────────────────────────────────

    describe("PiiFilter.filterMap") {

        it("applies filter to every value, leaving keys unchanged") {
            val input = mapOf(
                "endpoint"     to "/api/v1/chat",
                "user_email"   to "alice@example.com",
                "status_code"  to "200",
            )
            val result = PiiFilter.filterMap(input)

            result["endpoint"]    shouldBe "/api/v1/chat"  // no PII
            result["user_email"]  shouldBe "[email]"
            result["status_code"] shouldBe "200"           // no PII
        }

        it("returns an empty map when given an empty map") {
            PiiFilter.filterMap(emptyMap()) shouldBe emptyMap()
        }

        it("does NOT modify a map whose values contain no PII") {
            val input = mapOf(
                "latency_ms"  to "145",
                "http_status" to "500",
                "endpoint"    to "/chat/message",
            )
            PiiFilter.filterMap(input) shouldBe input
        }

        it("filters IP addresses in map values") {
            val input = mapOf("remote_host" to "10.0.2.2")
            val result = PiiFilter.filterMap(input)
            result["remote_host"] shouldBe "[ip]"
        }
    }

    // ── containsPii ───────────────────────────────────────────────────────────

    describe("PiiFilter.containsPii") {

        it("returns true when an email is present") {
            PiiFilter.containsPii("Error for user@company.com").shouldBeTrue()
        }

        it("returns true when a Bearer token is present") {
            PiiFilter.containsPii(
                "bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.data.signature"
            ).shouldBeTrue()
        }

        it("returns false for clean operational messages") {
            PiiFilter.containsPii("POST /api/v1/chat → HTTP 200 (145ms)").shouldBeFalse()
        }

        it("returns false for empty string") {
            PiiFilter.containsPii("").shouldBeFalse()
        }

        it("returns true for an IPv4 address") {
            PiiFilter.containsPii("Remote: 192.168.0.1").shouldBeTrue()
        }
    }

    // ── idempotency ───────────────────────────────────────────────────────────

    describe("PiiFilter.filter — idempotency") {

        it("filtering an already-filtered string produces no further changes") {
            val raw      = "auth=Bearer eyJhbGciOiJIUzI1NiJ9.test.sig"
            val once     = PiiFilter.filter(raw)
            val twice    = PiiFilter.filter(once)
            twice shouldBe once
        }
    }

    // ── safety for edge inputs ─────────────────────────────────────────────────

    describe("PiiFilter.filter — edge inputs") {

        it("handles an empty string without throwing") {
            PiiFilter.filter("") shouldBe ""
        }

        it("handles a string with only whitespace") {
            PiiFilter.filter("   ") shouldBe "   "
        }

        it("handles a very long string without throwing") {
            val long = "a".repeat(100_000)
            PiiFilter.filter(long) shouldBe long
        }
    }
})
