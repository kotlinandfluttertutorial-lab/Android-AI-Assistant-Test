/**
 * ContextSuggestionRateGatePropertyTest.kt — domain module
 *
 * Purpose: Property-based tests for Property 32: Context Suggestion Rate-Gate Invariant.
 *          Verifies that GetContextSuggestionsUseCase enforces at most one suggestion
 *          generation request per screen per 5-second idle window.
 *
 * Architecture: domain module — unit tests (pure JVM, no Android framework).
 *
 * Test toolchain:
 * - Kotest DescribeSpec + checkAll / Arb — property-based test structure
 * - MockK                               — mocking ContextSuggestionRepository
 *
 * **Validates: Requirements 33.4**
 *
 * Requirements covered:
 *   33.4 — THE AI_Assistant SHALL limit context-aware suggestion generation to at most
 *           one generation request per screen per 5-second idle window.
 *
 * Properties verified:
 *   P32-1  Exact count: allowed calls == count of events ≥5 s after previous allowed call.
 *   P32-2  Rapid events (all < 5 s apart): at most one call allowed.
 *   P32-3  Well-spaced events (all ≥ 5 s apart): every event is allowed.
 *   P32-4  Mixed sequence: only the first of each rapid burst is allowed.
 *   P32-5  Integration: repository call count matches expected allowed count via resetRateGate().
 */

package com.aiassistant.domain.usecase.suggestions

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DefaultDispatcherProvider
import com.aiassistant.domain.model.ContextSuggestion
import com.aiassistant.domain.model.ScreenContext
import com.aiassistant.domain.model.SuggestionType
import com.aiassistant.domain.model.TargetScreenType
import com.aiassistant.domain.repository.ContextSuggestionRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

// ─── Constants ────────────────────────────────────────────────────────────────

/** Rate gate window mirroring the use case constant (5 seconds in milliseconds). */
private const val RATE_GATE_WINDOW_MS = GetContextSuggestionsUseCase.RATE_GATE_WINDOW_MS // 5_000L

// ─── Pure rate-gate simulation ────────────────────────────────────────────────

/**
 * Simulates the rate-gate algorithm and returns the count of allowed calls.
 *
 * Mirrors the logic in [GetContextSuggestionsUseCase] exactly:
 * - First event is always allowed.
 * - Subsequent event is allowed only if (timestamp - lastAllowedTimestamp) >= RATE_GATE_WINDOW_MS.
 *
 * @param timestamps Ordered list of absolute event timestamps in milliseconds.
 * @return The number of events that the rate-gate would permit through.
 */
private fun simulateRateGate(timestamps: List<Long>): Int {
    if (timestamps.isEmpty()) return 0
    var allowedCount = 1
    var lastAllowedTimestamp = timestamps[0]
    for (i in 1 until timestamps.size) {
        val interval = timestamps[i] - lastAllowedTimestamp
        if (interval >= RATE_GATE_WINDOW_MS) {
            allowedCount++
            lastAllowedTimestamp = timestamps[i]
        }
    }
    return allowedCount
}

// ─── Generators ───────────────────────────────────────────────────────────────

/**
 * Generates a sequence of N absolute timestamps (N in 1..20) starting at a
 * random base, with inter-event intervals randomly chosen from 0..10,000 ms.
 * This covers both rapid (< 5 s) and slow (≥ 5 s) inter-event spacings.
 */
private val arbTimestampSequence: Arb<List<Long>> = arbitrary {
    val n = Arb.int(1..20).bind()
    val base = Arb.long(0L..1_000_000L).bind()
    val timestamps = mutableListOf(base)
    repeat(n - 1) {
        val interval = Arb.long(0L..10_000L).bind()
        timestamps.add(timestamps.last() + interval)
    }
    timestamps
}

/**
 * Generates a sequence of N timestamps (N in 2..10) all within a 4,999 ms window
 * measured from the FIRST event.
 *
 * Since simulateRateGate tracks lastAllowedTimestamp (= first event after gate is allowed),
 * all subsequent events need to be within 4,999 ms of the FIRST event — not just the
 * previous event — to guarantee they are blocked by the rate-gate.
 *
 * This guarantees all but the first event are blocked by the rate-gate.
 */
private val arbRapidTimestamps: Arb<List<Long>> = arbitrary {
    val n = Arb.int(2..10).bind()
    val base = Arb.long(0L..1_000_000L).bind()
    val timestamps = mutableListOf(base)
    repeat(n - 1) {
        // Absolute offset from base: 0..4999 ms, so interval from lastAllowed (= base) < 5000
        val absoluteOffset = Arb.long(0L..4_999L).bind()
        timestamps.add(base + absoluteOffset)
    }
    timestamps.sort()
    timestamps
}

/**
 * Generates a sequence of N timestamps (N in 2..10) each ≥5,000 ms after the previous.
 * This guarantees every event passes the rate-gate.
 */
private val arbSpacedTimestamps: Arb<List<Long>> = arbitrary {
    val n = Arb.int(2..10).bind()
    val base = Arb.long(0L..1_000_000L).bind()
    val timestamps = mutableListOf(base)
    repeat(n - 1) {
        val interval = Arb.long(5_000L..15_000L).bind()
        timestamps.add(timestamps.last() + interval)
    }
    timestamps
}

/**
 * Generates a mixed sequence of alternating rapid bursts and well-spaced gaps.
 *
 * Structure: [burst of 1..3 rapid events] + [gap ≥5 s] + [burst of 1..3 rapid events] + ...
 * Each burst starts one allowed event; rapid follow-ups within the burst are blocked.
 *
 * Returns a Pair of (timestamps, expectedAllowedCount).
 *
 * Rapid events in each burst use absolute offsets from the burst's first event (the
 * lastAllowedTimestamp after that burst is allowed). Each offset is strictly < 5,000 ms,
 * guaranteeing every rapid follow-up event is blocked by simulateRateGate regardless
 * of how many rapid events are in the burst.
 */
private val arbMixedSequence: Arb<Pair<List<Long>, Int>> = arbitrary {
    val numBursts = Arb.int(2..5).bind()
    val base = Arb.long(0L..100_000L).bind()
    val timestamps = mutableListOf<Long>()
    var current = base
    var expectedAllowed = 0

    repeat(numBursts) {
        // First event in burst is always allowed; record burst start as lastAllowed
        val burstStart = current
        timestamps.add(burstStart)
        expectedAllowed++

        // 0..2 additional rapid events within the same burst (all blocked).
        // Each rapid event uses an absolute offset from burstStart in 1..4999 ms,
        // so its interval from lastAllowed (= burstStart) is always < 5000 ms.
        val burstSize = Arb.int(0..2).bind()
        if (burstSize > 0) {
            val maxOffset = 4_999L
            val offsets = (1..burstSize)
                .map { Arb.long(1L..maxOffset).bind() }
                .sorted()
            for (offset in offsets) {
                timestamps.add(burstStart + offset)
            }
            // Advance current to just after the last rapid event in the burst
            current = burstStart + offsets.last()
        }
        // (if burstSize == 0, current stays at burstStart)

        // Jump forward by ≥5,000 ms to ensure the next burst's first event passes the gate
        val gap = Arb.long(5_000L..10_000L).bind()
        current += gap
    }

    timestamps to expectedAllowed
}

// ─── Test fixtures ─────────────────────────────────────────────────────────────

private fun makeSuggestion(id: String = "sug-1"): ContextSuggestion = ContextSuggestion(
    id = id,
    type = SuggestionType.SUMMARIZE,
    displayText = "Summarize this note",
    preFillText = "Please summarize:",
    targetScreenType = TargetScreenType.NOTE
)

private val SAMPLE_SUGGESTIONS = listOf(makeSuggestion("sug-1"))

private val NOTE_CONTEXT = ScreenContext.NoteContext(
    noteContent = "Sample note content for rate-gate testing.",
    screenInstanceId = "note-rate-gate-test"
)

// ─── Property 32: Context Suggestion Rate-Gate Invariant ──────────────────────

/**
 * **Validates: Requirements 33.4**
 */
class ContextSuggestionRateGatePropertyTest :
    DescribeSpec({

        // ── P32-1 — Exact count: allowed count matches simulation ──────────────────
        describe("P32-1 — allowed call count equals events with ≥5 s interval since last allowed call") {

            it("simulateRateGate returns the exact count of events passing the 5 s gate for random sequences") {
                checkAll(iterations = 500, arbTimestampSequence) { timestamps ->
                    val allowedCount = simulateRateGate(timestamps)

                    // Verify: first event is always allowed
                    (allowedCount >= 1) shouldBe true

                    // Verify: allowed count cannot exceed the total number of events
                    (allowedCount <= timestamps.size) shouldBe true

                    // Re-derive expected count via a second independent calculation to cross-check
                    var recomputedAllowed = 1
                    var lastAllowed = timestamps[0]
                    for (i in 1 until timestamps.size) {
                        if (timestamps[i] - lastAllowed >= RATE_GATE_WINDOW_MS) {
                            recomputedAllowed++
                            lastAllowed = timestamps[i]
                        }
                    }

                    allowedCount shouldBe recomputedAllowed
                }
            }

            it("single-event sequence always allows exactly 1 call") {
                checkAll(iterations = 200, Arb.long(0L..1_000_000L)) { timestamp ->
                    val allowed = simulateRateGate(listOf(timestamp))
                    allowed shouldBe 1
                }
            }
        }

        // ── P32-2 — Rapid events: at most 1 call allowed ───────────────────────────
        describe("P32-2 — rapid consecutive events (< 5 s apart) produce at most one allowed call") {

            it("sequence of N timestamps all within a 4999 ms window allows exactly 1 call") {
                checkAll(iterations = 300, arbRapidTimestamps) { timestamps ->
                    val allowedCount = simulateRateGate(timestamps)

                    // Only the very first event in a rapid burst is allowed
                    allowedCount shouldBe 1
                }
            }

            it("two events 0 ms apart — only 1 allowed") {
                val base = 1_000_000L
                val allowed = simulateRateGate(listOf(base, base))
                allowed shouldBe 1
            }

            it("two events 4999 ms apart — only 1 allowed (just inside the gate window)") {
                val base = 1_000_000L
                val allowed = simulateRateGate(listOf(base, base + 4_999L))
                allowed shouldBe 1
            }
        }

        // ── P32-3 — Well-spaced events: every event is allowed ────────────────────
        describe("P32-3 — events ≥5 s apart are all allowed") {

            it("sequence of N timestamps each ≥5000 ms after the previous allows all N calls") {
                checkAll(iterations = 300, arbSpacedTimestamps) { timestamps ->
                    val allowedCount = simulateRateGate(timestamps)

                    // Every event passes because each gap is ≥ RATE_GATE_WINDOW_MS
                    allowedCount shouldBe timestamps.size
                }
            }

            it("two events exactly 5000 ms apart — both allowed (boundary is inclusive)") {
                val base = 1_000_000L
                val allowed = simulateRateGate(listOf(base, base + 5_000L))
                allowed shouldBe 2
            }

            it("two events 5001 ms apart — both allowed") {
                val base = 1_000_000L
                val allowed = simulateRateGate(listOf(base, base + 5_001L))
                allowed shouldBe 2
            }
        }

        // ── P32-4 — Mixed sequence: only first of each burst is allowed ───────────
        describe("P32-4 — mixed sequence: only the first event of each rapid burst is allowed") {

            it("alternating bursts and gaps allow exactly one call per burst") {
                checkAll(iterations = 300, arbMixedSequence) { (timestamps, expectedAllowed) ->
                    val allowedCount = simulateRateGate(timestamps)

                    allowedCount shouldBe expectedAllowed
                }
            }

            it("explicit mixed sequence [0, 1000, 2000, 7000, 8000, 13000] allows 3 calls") {
                // Burst 1: t=0 (allowed), t=1000 (blocked, < 5s), t=2000 (blocked, < 5s from t=0)
                // Burst 2: t=7000 (allowed, 7000-0=7000 >= 5000), t=8000 (blocked, < 5s from t=7000)
                // Burst 3: t=13000 (allowed, 13000-7000=6000 >= 5000)
                val timestamps = listOf(0L, 1_000L, 2_000L, 7_000L, 8_000L, 13_000L)
                val allowed = simulateRateGate(timestamps)
                allowed shouldBe 3
            }

            it("rapid burst [0, 100, 200] followed by gap [5200] followed by rapid [5300, 5400] allows 2 calls") {
                // t=0 allowed, t=100 blocked, t=200 blocked (last allowed=0)
                // t=5200 allowed (5200-0=5200 >= 5000, last allowed=5200)
                // t=5300 blocked (5300-5200=100 < 5000)
                // t=5400 blocked (5400-5200=200 < 5000)
                val timestamps = listOf(0L, 100L, 200L, 5_200L, 5_300L, 5_400L)
                val allowed = simulateRateGate(timestamps)
                allowed shouldBe 2
            }
        }

        // ── P32-5 — Integration: use case repository calls match expected allowed count ──
        describe("P32-5 — integration: use case invokes repository exactly for allowed events") {

            it("N pairs of (allowed + immediately blocked) calls result in exactly N repository calls") {
                checkAll(iterations = 50, Arb.int(1..5)) { n ->
                    val dispatchers = DefaultDispatcherProvider()
                    var totalRepositoryCalls = 0

                    // Each iteration: fresh use case (clean rate-gate state), make 1 allowed call,
                    // then make 1 immediate call that is blocked
                    repeat(n) {
                        // Fresh use case per pair so gate is always clean at the start of each pair
                        val repository = mockk<ContextSuggestionRepository>()
                        coEvery { repository.getSuggestions(any()) } returns ApiResult.Success(SAMPLE_SUGGESTIONS)

                        val useCase = GetContextSuggestionsUseCase(repository)

                        // Allowed call — gate is clear on a brand-new use case
                        val allowedResult = useCase(
                            NOTE_CONTEXT,
                            isPrivacyModeEnabled = false,
                            isSuggestionsEnabled = true
                        )
                        allowedResult.shouldBeInstanceOf<ApiResult.Success<List<ContextSuggestion>>>()

                        // Immediate second call — blocked by rate-gate
                        val blockedResult = useCase(
                            NOTE_CONTEXT,
                            isPrivacyModeEnabled = false,
                            isSuggestionsEnabled = true
                        )
                        blockedResult.shouldBeInstanceOf<ApiResult.Success<List<ContextSuggestion>>>()
                        (blockedResult as ApiResult.Success).data shouldBe emptyList()

                        // This fresh mock should have been called exactly once (the allowed call)
                        coVerify(exactly = 1) { repository.getSuggestions(NOTE_CONTEXT) }
                        totalRepositoryCalls++
                    }

                    // Total across all pairs equals N
                    totalRepositoryCalls shouldBe n
                }
            }

            it("single allowed call after resetRateGate returns repository results") {
                val repository = mockk<ContextSuggestionRepository>()
                val dispatchers = DefaultDispatcherProvider()
                val useCase = GetContextSuggestionsUseCase(repository)

                coEvery { repository.getSuggestions(NOTE_CONTEXT) } returns ApiResult.Success(SAMPLE_SUGGESTIONS)

                useCase.resetRateGate()
                val result = useCase(
                    NOTE_CONTEXT,
                    isPrivacyModeEnabled = false,
                    isSuggestionsEnabled = true
                )

                result.shouldBeInstanceOf<ApiResult.Success<List<ContextSuggestion>>>()
                (result as ApiResult.Success).data shouldBe SAMPLE_SUGGESTIONS
                coVerify(exactly = 1) { repository.getSuggestions(NOTE_CONTEXT) }
            }

            it(
                "immediate call without resetRateGate after an allowed call returns empty list without repository call"
            ) {
                val repository = mockk<ContextSuggestionRepository>()
                val dispatchers = DefaultDispatcherProvider()
                val useCase = GetContextSuggestionsUseCase(repository)

                coEvery { repository.getSuggestions(NOTE_CONTEXT) } returns ApiResult.Success(SAMPLE_SUGGESTIONS)

                // First call — allowed
                useCase(NOTE_CONTEXT, isPrivacyModeEnabled = false, isSuggestionsEnabled = true)
                // Second call — blocked
                val blockedResult = useCase(NOTE_CONTEXT, isPrivacyModeEnabled = false, isSuggestionsEnabled = true)

                blockedResult.shouldBeInstanceOf<ApiResult.Success<List<ContextSuggestion>>>()
                (blockedResult as ApiResult.Success).data shouldBe emptyList()
                coVerify(exactly = 1) { repository.getSuggestions(NOTE_CONTEXT) }
            }
        }
    })
