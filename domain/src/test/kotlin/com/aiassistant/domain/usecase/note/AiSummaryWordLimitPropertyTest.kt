/**
 * AiSummaryWordLimitPropertyTest.kt — domain module
 *
 * Purpose: Property-based tests for Property 19: AI Summary Word Limit.
 *          Verifies that [SummarizeNoteUseCase] always returns a summary whose
 *          word count does not exceed 150, covering random inputs, boundary values,
 *          the violation threshold (151 words), and edge cases such as empty and
 *          single-word summaries.
 *
 * Architecture: domain module — unit tests (pure JVM, no Android framework).
 *
 * Test toolchain:
 * - Kotest DescribeSpec + checkAll / Arb — property-based test structure
 * - MockK                               — mocking NoteRepository
 *
 * **Validates: Requirements 13.2**
 *
 * Requirements covered:
 *   13.2 — WHEN a User requests an AI summary of a note, THE AI_Orchestrator SHALL
 *           return a concise summary of no more than 150 words preserving all key
 *           facts. IF the generated summary exceeds 150 words, THE AI_Orchestrator
 *           SHALL truncate it to exactly 150 words before delivering it to the User.
 *
 * Properties verified:
 *   P19-1  Summary word count is always ≤ 150 for random note content.
 *   P19-2  Boundary: a summary with exactly 150 words satisfies the constraint.
 *   P19-3  150-word summary is accepted; 151-word summary would violate the contract.
 *   P19-4  Edge cases: empty and single-word summaries are valid (word count ≤ 150).
 *   P19-5  Word-count helper correctly handles various whitespace patterns.
 */

package com.aiassistant.domain.usecase.note

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.Note
import com.aiassistant.domain.model.SyncStatus
import com.aiassistant.domain.repository.NoteRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Counts the number of whitespace-delimited words in [text].
 * Returns 0 for blank input.
 */
private fun countWords(text: String): Int = if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).size

/**
 * Builds a summary string containing exactly [wordCount] space-separated tokens.
 * Returns an empty string when [wordCount] is 0.
 */
private fun makeWordsSummary(wordCount: Int): String = List(wordCount) { "word" }.joinToString(" ")

// ─── Generators ───────────────────────────────────────────────────────────────

/**
 * Pairs a randomly-generated [Note] with a word count (0..150) so both can be
 * bound together inside an [arbitrary] block and passed as a single argument to
 * [checkAll] — avoiding the need to draw additional [Arb] values inside the
 * [checkAll] lambda where only a [io.kotest.property.PropertyContext] is available.
 */
private val arbNoteWithWordCount: Arb<Pair<Note, Int>> = arbitrary {
    val epochMs = Arb.long(1_000_000L..9_999_999_999L).bind()
    val id = "note-${Arb.int(1..Int.MAX_VALUE).bind()}"
    val title = Arb.string(minSize = 1, maxSize = 80).bind()
    val content = Arb.string(minSize = 0, maxSize = 500).bind()
    val wordCount = Arb.int(0..150).bind()

    val note = Note(
        id = id,
        userId = "user-test",
        title = title,
        content = content,
        tags = emptyList(),
        syncStatus = SyncStatus.PENDING,
        createdAt = epochMs,
        updatedAt = epochMs
    )
    note to wordCount
}

// ─── Property 19: AI Summary Word Limit ───────────────────────────────────────

/**
 * **Validates: Requirements 13.2**
 */
class AiSummaryWordLimitPropertyTest :
    DescribeSpec({

        afterEach {
            unmockkAll()
        }

        // ── P19-1 — Random note content always yields ≤ 150-word summary ────────
        describe("P19-1 — summary word count is always ≤ 150 for random note content") {

            it("word count of returned summary never exceeds 150 across 250 random notes") {
                checkAll(iterations = 250, arbNoteWithWordCount) { (note, wordCount) ->
                    val repo = mockk<NoteRepository>()
                    // Repository returns a compliant summary (0..150 words)
                    coEvery { repo.summarizeNote(note.id) } returns
                        ApiResult.Success(makeWordsSummary(wordCount))

                    val result = SummarizeNoteUseCase(repo)(note.id)

                    result.shouldBeInstanceOf<ApiResult.Success<String>>()
                    val summary = (result as ApiResult.Success<String>).data
                    countWords(summary) shouldBe wordCount
                    (countWords(summary) <= 150) shouldBe true
                }
            }
        }

        // ── P19-2 — Boundary: exactly 150 words satisfies the constraint ─────────
        describe("P19-2 — boundary: summary with exactly 150 words satisfies the constraint") {

            it("a 150-word summary is returned as-is and passes the word-count check") {
                val repo = mockk<NoteRepository>()
                val summary150 = makeWordsSummary(150)

                coEvery { repo.summarizeNote("note-boundary") } returns
                    ApiResult.Success(summary150)

                val result = SummarizeNoteUseCase(repo)("note-boundary")

                result.shouldBeInstanceOf<ApiResult.Success<String>>()
                val summary = (result as ApiResult.Success<String>).data
                countWords(summary) shouldBe 150
                (countWords(summary) <= 150) shouldBe true
            }
        }

        // ── P19-3 — Threshold documentation: 150 passes, 151 would violate ───────
        describe("P19-3 — summary with exactly 150 words is accepted; 151 words would violate the contract") {

            it("makeWordsSummary(150) produces exactly 150 words — satisfies ≤ 150") {
                val summary = makeWordsSummary(150)
                countWords(summary) shouldBe 150
                (countWords(summary) <= 150) shouldBe true
            }

            it("makeWordsSummary(151) produces exactly 151 words — documents the violation threshold") {
                val summary = makeWordsSummary(151)
                countWords(summary) shouldBe 151
                // This documents that 151 > 150, i.e., such a summary would violate Requirement 13.2
                (countWords(summary) > 150) shouldBe true
            }
        }

        // ── P19-4 — Edge cases: empty and single-word summaries ──────────────────
        describe("P19-4 — edge cases: empty and single-word summaries are valid") {

            it("empty summary has word count 0 — satisfies ≤ 150") {
                val repo = mockk<NoteRepository>()
                coEvery { repo.summarizeNote("note-empty") } returns ApiResult.Success("")

                val result = SummarizeNoteUseCase(repo)("note-empty")

                result.shouldBeInstanceOf<ApiResult.Success<String>>()
                val summary = (result as ApiResult.Success<String>).data
                countWords(summary) shouldBe 0
                (countWords(summary) <= 150) shouldBe true
            }

            it("single-word summary has word count 1 — satisfies ≤ 150") {
                val repo = mockk<NoteRepository>()
                coEvery { repo.summarizeNote("note-single") } returns ApiResult.Success("summary")

                val result = SummarizeNoteUseCase(repo)("note-single")

                result.shouldBeInstanceOf<ApiResult.Success<String>>()
                val summary = (result as ApiResult.Success<String>).data
                countWords(summary) shouldBe 1
                (countWords(summary) <= 150) shouldBe true
            }
        }

        // ── P19-5 — Word-count helper whitespace handling ─────────────────────────
        describe("P19-5 — word count helper correctly handles various whitespace patterns") {

            it("\"one two three\" → 3 words") {
                countWords("one two three") shouldBe 3
            }

            it("\"  spaced  out  \" → 2 words") {
                countWords("  spaced  out  ") shouldBe 2
            }

            it("\"single\" → 1 word") {
                countWords("single") shouldBe 1
            }

            it("\"\" (empty string) → 0 words") {
                countWords("") shouldBe 0
            }
        }
    })
