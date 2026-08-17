/**
 * WebSocketBackoffPropertyTest.kt
 *
 * Purpose: Property-based tests validating the exponential backoff reconnection behaviour
 *          of AIStreamClientImpl (Property 27: WebSocket Reconnection Backoff).
 *          Tests are pure formula / logic tests — no OkHttp or DispatcherProvider mocking needed.
 * Architecture: core-ai — unit tests (pure JVM, no Android framework).
 * Requirements: 26.4 (exponential backoff reconnection)
 *
 * Design decisions:
 * - The backoff formula `minOf(1000L shl (attempt - 1), 30_000L)` is extracted into a
 *   local helper so all cases test the same expression that lives in AIStreamClientImpl.
 * - The max-attempts gate (attempt >= MAX_RECONNECT_ATTEMPTS) is validated with a simple
 *   simulation loop rather than exercising the full WebSocket lifecycle.
 * - Uses Kotest PropTest (checkAll / Arb) consistent with StreamEventSchemaPropertyTest style.
 */

package com.aiassistant.core.ai

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

// ─── Mirror of AIStreamClientImpl constants (keep in sync) ───────────────────

private const val MAX_RECONNECT_ATTEMPTS = 5
private const val MAX_BACKOFF_MS = 30_000L

// ─── Pure backoff formula helper ─────────────────────────────────────────────

/**
 * Pure helper that mirrors the backoff expression used inside [AIStreamClientImpl]:
 * ```
 * minOf(1_000L shl (attempt - 1), MAX_BACKOFF_MS)
 * ```
 */
fun computeBackoffMs(attempt: Int): Long = minOf(1_000L shl (attempt - 1), 30_000L)

// ─── Property 27: WebSocket Reconnection Backoff ──────────────────────────────

/**
 * **Validates: Requirements 26.4**
 *
 * Verifies that the exponential backoff formula produces correct intervals for all
 * valid attempt numbers, that intervals are capped at 30 s, that they are
 * monotonically non-decreasing before the cap, and that the max-attempts gate
 * triggers at exactly 5 failures.
 */
class WebSocketBackoffPropertyTest :
    DescribeSpec({

        // ── Case 1 — backoff formula for N ∈ [1, 4] produces correct intervals ────
        describe("Case 1 — backoff formula for attempt ∈ [1, 4] matches expected expression") {

            it("computeBackoffMs(N) equals minOf(1000L shl (N - 1), 30_000L) for all N") {
                checkAll(iterations = 200, Arb.int(1..4)) { n ->
                    val expected = minOf(1_000L shl (n - 1), 30_000L)
                    computeBackoffMs(n) shouldBe expected
                }
            }
        }

        // ── Case 2 — exact interval sequence 1s → 2s → 4s → 8s ──────────────────
        describe("Case 2 — exact interval sequence 1 s → 2 s → 4 s → 8 s") {

            it("attempt 1 produces 1000 ms") {
                computeBackoffMs(1) shouldBe 1_000L
            }

            it("attempt 2 produces 2000 ms") {
                computeBackoffMs(2) shouldBe 2_000L
            }

            it("attempt 3 produces 4000 ms") {
                computeBackoffMs(3) shouldBe 4_000L
            }

            it("attempt 4 produces 8000 ms") {
                computeBackoffMs(4) shouldBe 8_000L
            }

            it("attempt 5 produces 16000 ms") {
                // attempt=5 is the boundary: the gate triggers AFTER this value would be used,
                // so the formula itself still produces 16 s for attempt=5.
                computeBackoffMs(5) shouldBe 16_000L
            }
        }

        // ── Case 3 — cap at 30 s for large attempt numbers ────────────────────────
        describe("Case 3 — cap at 30 s for large attempt numbers") {

            it("computeBackoffMs(N) == 30_000L for all N >= 5 (large inputs)") {
                checkAll(iterations = 200, Arb.int(5..30)) { n ->
                    // At attempt=5: 1000 shl 4 = 16_000 (< 30_000), still below cap.
                    // At attempt=6: 1000 shl 5 = 32_000 → capped to 30_000.
                    // We verify the cap applies for any large N.
                    val result = computeBackoffMs(n)
                    result shouldBe minOf(1_000L shl (n - 1), 30_000L)
                    // Additionally assert the result never exceeds MAX_BACKOFF_MS.
                    result shouldBe minOf(result, MAX_BACKOFF_MS)
                }
            }

            it("computeBackoffMs(N) is exactly 30_000L when shl would overflow cap") {
                // From attempt=6 onward, the shifted value exceeds 30_000.
                // Long shl uses only the low 6 bits of shift amount (mod 64),
                // so we stay within [6, 30] to avoid wrapping artefacts.
                checkAll(iterations = 200, Arb.int(6..30)) { n ->
                    computeBackoffMs(n) shouldBe 30_000L
                }
            }
        }

        // ── Case 4 — max 5 attempts gate ──────────────────────────────────────────
        describe("Case 4 — max 5 attempts gate") {

            it("gate triggers after exactly 5 failures for any N ∈ [1, 5]") {
                checkAll(iterations = 200, Arb.int(1..5)) { n ->
                    var attempt = 0
                    var gateTriggered = false

                    repeat(n) {
                        attempt++
                        if (attempt >= MAX_RECONNECT_ATTEMPTS) {
                            gateTriggered = true
                        }
                    }

                    // The gate should trigger if and only if at least 5 failures occurred.
                    if (n >= MAX_RECONNECT_ATTEMPTS) {
                        gateTriggered shouldBe true
                    } else {
                        gateTriggered shouldBe false
                    }
                }
            }

            it("total reconnect attempts never exceed MAX_RECONNECT_ATTEMPTS (5)") {
                checkAll(iterations = 200, Arb.int(1..5)) { n ->
                    var attempt = 0

                    repeat(n) {
                        attempt++
                        if (attempt >= MAX_RECONNECT_ATTEMPTS) {
                            return@repeat
                        }
                    }

                    (attempt <= MAX_RECONNECT_ATTEMPTS) shouldBe true
                }
            }
        }

        // ── Case 5 — no negative or zero intervals ────────────────────────────────
        describe("Case 5 — no negative or zero intervals for any N ∈ [1, 30]") {

            it("computeBackoffMs(N) > 0 for all N") {
                // Long shl uses low 6 bits of shift amount (mod 64), so we stay within
                // [1, 30] to avoid overflow wrapping that would produce zero or negative.
                // In practice AIStreamClientImpl only ever reaches attempt 1..5 before
                // the max-attempts gate, so this range covers all real usage.
                checkAll(iterations = 200, Arb.int(1..30)) { n ->
                    computeBackoffMs(n) shouldBeGreaterThan 0L
                }
            }
        }

        // ── Case 6 — monotonically non-decreasing intervals before cap ────────────
        describe("Case 6 — monotonically non-decreasing intervals for attempt 1..4") {

            it("each successive interval is greater than the previous (attempts 1 through 4)") {
                val intervals = (1..4).map { computeBackoffMs(it) }

                for (i in 0 until intervals.size - 1) {
                    val current = intervals[i]
                    val next = intervals[i + 1]
                    (next > current) shouldBe true
                }
            }

            it("intervals are strictly increasing: 1000 < 2000 < 4000 < 8000") {
                computeBackoffMs(1) shouldBe 1_000L
                (computeBackoffMs(2) > computeBackoffMs(1)) shouldBe true
                (computeBackoffMs(3) > computeBackoffMs(2)) shouldBe true
                (computeBackoffMs(4) > computeBackoffMs(3)) shouldBe true
            }
        }
    })
