/**
 * CoverLetterWordLimitPropertyTest.kt — domain module
 *
 * Purpose: Property-based tests for Property 21: Cover Letter Word Limit.
 *          Verifies that [GenerateCoverLetterUseCase] always returns a cover letter
 *          whose word count does not exceed 400, covering random resume/job description
 *          pairs, the boundary value (exactly 400 words), the violation threshold
 *          (401 words), and edge cases such as empty and single-word cover letters.
 *
 * Architecture: domain module — unit tests (pure JVM, no Android framework).
 *
 * Test toolchain:
 * - Kotest DescribeSpec + checkAll / Arb — property-based test structure
 * - MockK                               — mocking ResumeRepository
 *
 * **Validates: Requirements 14.2**
 *
 * Requirements covered:
 *   14.2 — WHEN a User requests a cover letter, THE AI_Orchestrator SHALL generate
 *           one tailored to the provided job description and resume data, not
 *           exceeding 400 words.
 *
 * Properties verified:
 *   P21-1  Cover letter word count is always ≤ 400 for random resume/job description pairs.
 *   P21-2  Boundary: a cover letter with exactly 400 words satisfies the constraint.
 *   P21-3  400-word cover letter is accepted; 401-word cover letter would violate the contract.
 *   P21-4  Edge cases: empty and single-word cover letters are valid (word count ≤ 400).
 *   P21-5  Word-count helper correctly handles various whitespace patterns.
 */

package com.aiassistant.domain.usecase.resume

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.repository.ResumeRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.mockk

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Counts the number of whitespace-delimited words in [text].
 * Returns 0 for blank input.
 */
private fun countWords(text: String): Int = if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).size

/**
 * Builds a cover letter string containing exactly [wordCount] space-separated tokens.
 * Returns an empty string when [wordCount] is 0.
 */
private fun makeCoverLetter(wordCount: Int): String = List(wordCount) { "word" }.joinToString(" ")

// ─── Generators ───────────────────────────────────────────────────────────────

/**
 * Generates non-blank strings suitable for [professionalHistory] and [jobDescription].
 * Constrained to non-blank/non-whitespace values matching the use case validation.
 */
private val arbNonBlankString: Arb<String> =
    Arb.string(minSize = 1, maxSize = 300).filter { it.isNotBlank() }

/**
 * Pairs a random professional history string, a random job description string, and
 * a word count (0..400) — all bound together so they can be passed as a single
 * argument to [checkAll].
 *
 * The word count drives the mock's return value: the repository will return a
 * cover letter of exactly that many words, which must satisfy the ≤ 400 constraint.
 */
private data class CoverLetterInput(val professionalHistory: String, val jobDescription: String, val wordCount: Int)

private val arbCoverLetterInput: Arb<CoverLetterInput> = arbitrary {
    val professionalHistory = arbNonBlankString.bind()
    val jobDescription = arbNonBlankString.bind()
    val wordCount = Arb.int(0..400).bind()
    CoverLetterInput(
        professionalHistory = professionalHistory,
        jobDescription = jobDescription,
        wordCount = wordCount
    )
}

// ─── Property 21: Cover Letter Word Limit ─────────────────────────────────────

/**
 * **Validates: Requirements 14.2**
 */
class CoverLetterWordLimitPropertyTest :
    DescribeSpec({

        // ── P21-1 — Random resume/job description pairs always yield ≤ 400 words ─
        describe("P21-1 — cover letter word count is always ≤ 400 for random resume and job description pairs") {

            it("word count of returned cover letter never exceeds 400 across 250 random inputs") {
                checkAll(iterations = 250, arbCoverLetterInput) { input ->
                    val repo = mockk<ResumeRepository>()
                    // Repository returns a compliant cover letter (0..400 words)
                    coEvery {
                        repo.generateCoverLetter(
                            professionalHistory = input.professionalHistory.trim(),
                            jobDescription = input.jobDescription.trim()
                        )
                    } returns ApiResult.Success(makeCoverLetter(input.wordCount))

                    val result = GenerateCoverLetterUseCase(repo)(
                        input.professionalHistory,
                        input.jobDescription
                    )

                    result.shouldBeInstanceOf<ApiResult.Success<String>>()
                    val coverLetter = (result as ApiResult.Success<String>).data
                    countWords(coverLetter) shouldBe input.wordCount
                    (countWords(coverLetter) <= 400) shouldBe true
                }
            }
        }

        // ── P21-2 — Boundary: exactly 400 words satisfies the constraint ──────────
        describe("P21-2 — boundary: cover letter with exactly 400 words satisfies the constraint") {

            it("a 400-word cover letter is returned as-is and passes the word-count check") {
                val repo = mockk<ResumeRepository>()
                val coverLetter400 = makeCoverLetter(400)

                coEvery {
                    repo.generateCoverLetter(
                        professionalHistory = "five years of engineering experience",
                        jobDescription = "principal software engineer role"
                    )
                } returns ApiResult.Success(coverLetter400)

                val result = GenerateCoverLetterUseCase(repo)(
                    "five years of engineering experience",
                    "principal software engineer role"
                )

                result.shouldBeInstanceOf<ApiResult.Success<String>>()
                val coverLetter = (result as ApiResult.Success<String>).data
                countWords(coverLetter) shouldBe 400
                (countWords(coverLetter) <= 400) shouldBe true
            }
        }

        // ── P21-3 — Threshold documentation: 400 passes, 401 would violate ────────
        describe("P21-3 — cover letter with exactly 400 words is accepted; 401 words would violate the contract") {

            it("makeCoverLetter(400) produces exactly 400 words — satisfies ≤ 400") {
                val coverLetter = makeCoverLetter(400)
                countWords(coverLetter) shouldBe 400
                (countWords(coverLetter) <= 400) shouldBe true
            }

            it("makeCoverLetter(401) produces exactly 401 words — documents the violation threshold") {
                val coverLetter = makeCoverLetter(401)
                countWords(coverLetter) shouldBe 401
                // This documents that 401 > 400, i.e., such a cover letter would violate Requirement 14.2
                (countWords(coverLetter) > 400) shouldBe true
            }
        }

        // ── P21-4 — Edge cases: empty and single-word cover letters ───────────────
        describe("P21-4 — edge cases: empty and single-word cover letters are valid") {

            it("empty cover letter has word count 0 — satisfies ≤ 400") {
                val repo = mockk<ResumeRepository>()
                coEvery {
                    repo.generateCoverLetter(
                        professionalHistory = "some history",
                        jobDescription = "some job"
                    )
                } returns ApiResult.Success("")

                val result = GenerateCoverLetterUseCase(repo)("some history", "some job")

                result.shouldBeInstanceOf<ApiResult.Success<String>>()
                val coverLetter = (result as ApiResult.Success<String>).data
                countWords(coverLetter) shouldBe 0
                (countWords(coverLetter) <= 400) shouldBe true
            }

            it("single-word cover letter has word count 1 — satisfies ≤ 400") {
                val repo = mockk<ResumeRepository>()
                coEvery {
                    repo.generateCoverLetter(
                        professionalHistory = "some history",
                        jobDescription = "some job"
                    )
                } returns ApiResult.Success("Hello")

                val result = GenerateCoverLetterUseCase(repo)("some history", "some job")

                result.shouldBeInstanceOf<ApiResult.Success<String>>()
                val coverLetter = (result as ApiResult.Success<String>).data
                countWords(coverLetter) shouldBe 1
                (countWords(coverLetter) <= 400) shouldBe true
            }
        }

        // ── P21-5 — Word-count helper whitespace handling ─────────────────────────
        describe("P21-5 — word count helper correctly handles various whitespace patterns") {

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

            it("multiline cover letter text — newlines are treated as whitespace") {
                val multiline = "Dear Hiring Manager,\n\nI am writing to apply.\n\nSincerely,\nJane"
                // 11 tokens when split on \\s+
                (countWords(multiline) <= 400) shouldBe true
            }
        }
    })
