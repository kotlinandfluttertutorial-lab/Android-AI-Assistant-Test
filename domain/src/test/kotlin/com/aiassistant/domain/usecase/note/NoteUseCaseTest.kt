/**
 * NoteUseCaseTest.kt — domain module unit tests
 *
 * Tests for note use cases:
 *   - [SaveNoteUseCase]      — validates title not blank; delegates to repository
 *   - [SummarizeNoteUseCase] — pure delegation; no validation
 *   - [RewriteNoteUseCase]   — pure delegation; no validation
 *
 * Requirements: 21.1
 * Related requirements: 13.1, 13.2, 13.3, 13.4
 *
 * Test framework: Kotest (DescribeSpec, JUnit 5 runner) + MockK for NoteRepository mocking
 */

package com.aiassistant.domain.usecase.note

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.Note
import com.aiassistant.domain.model.SyncStatus
import com.aiassistant.domain.repository.NoteRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll

// ─── Shared test fixtures ──────────────────────────────────────────────────────

private val SAMPLE_NOTE = Note(
    id = "note-001",
    userId = "user-456",
    title = "My Test Note",
    content = "Some note content in **Markdown**.",
    tags = listOf("work", "research"),
    syncStatus = SyncStatus.PENDING,
    createdAt = 1_000_000L,
    updatedAt = 2_000_000L
)

private fun noteWithTitle(title: String) = SAMPLE_NOTE.copy(title = title)

// ─── SaveNoteUseCase ──────────────────────────────────────────────────────────

class SaveNoteUseCaseTest :
    DescribeSpec({

        val noteRepository = mockk<NoteRepository>()
        val saveNoteUseCase = SaveNoteUseCase(noteRepository)

        beforeEach {
            clearMocks(noteRepository)
        }

        afterEach {
            unmockkAll()
        }

        describe("SaveNoteUseCase") {

            describe("successful save") {

                it("returns Success with Note when title is valid") {
                    coEvery { noteRepository.saveNote(SAMPLE_NOTE) } returns ApiResult.Success(SAMPLE_NOTE)

                    val result = saveNoteUseCase(SAMPLE_NOTE)

                    result.shouldBeInstanceOf<ApiResult.Success<Note>>()
                    (result as ApiResult.Success<Note>).data shouldBe SAMPLE_NOTE
                }

                it("delegates to repository exactly once with the provided note") {
                    coEvery { noteRepository.saveNote(SAMPLE_NOTE) } returns ApiResult.Success(SAMPLE_NOTE)

                    saveNoteUseCase(SAMPLE_NOTE)

                    coVerify(exactly = 1) { noteRepository.saveNote(SAMPLE_NOTE) }
                }
            }

            describe("title validation") {

                it("returns ValidationError when note title is blank") {
                    val blankTitleNote = noteWithTitle("")

                    val result = saveNoteUseCase(blankTitleNote)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error
                    error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("returns ValidationError when note title is only whitespace") {
                    val result = saveNoteUseCase(noteWithTitle("   "))

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("ValidationError contains 'title' in fields map") {
                    val result = saveNoteUseCase(noteWithTitle(""))

                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey SaveNoteUseCase.FIELD_TITLE
                }

                it("does NOT call repository when title is blank") {
                    saveNoteUseCase(noteWithTitle(""))

                    coVerify(exactly = 0) { noteRepository.saveNote(any()) }
                }
            }

            describe("error propagation") {

                it("propagates NetworkUnavailable from repository") {
                    coEvery { noteRepository.saveNote(any()) } returns ApiResult.NetworkUnavailable

                    val result = saveNoteUseCase(SAMPLE_NOTE)

                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }

                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { noteRepository.saveNote(any()) } returns ApiResult.Error(error)

                    val result = saveNoteUseCase(SAMPLE_NOTE)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── SummarizeNoteUseCase ─────────────────────────────────────────────────────

class SummarizeNoteUseCaseTest :
    DescribeSpec({

        val noteRepository = mockk<NoteRepository>()
        val summarizeNoteUseCase = SummarizeNoteUseCase(noteRepository)

        beforeEach {
            clearMocks(noteRepository)
        }

        afterEach {
            unmockkAll()
        }

        describe("SummarizeNoteUseCase") {

            describe("successful summarization") {

                it("returns Success with summary text when repository succeeds") {
                    coEvery { noteRepository.summarizeNote("note-001") } returns
                        ApiResult.Success("A concise summary of the note.")

                    val result = summarizeNoteUseCase("note-001")

                    result.shouldBeInstanceOf<ApiResult.Success<String>>()
                    (result as ApiResult.Success<String>).data shouldBe "A concise summary of the note."
                }

                it("delegates to repository exactly once with the given noteId") {
                    coEvery { noteRepository.summarizeNote("note-001") } returns
                        ApiResult.Success("Summary")

                    summarizeNoteUseCase("note-001")

                    coVerify(exactly = 1) { noteRepository.summarizeNote("note-001") }
                }
            }

            describe("error propagation") {

                it("propagates NetworkUnavailable from repository") {
                    coEvery { noteRepository.summarizeNote(any()) } returns ApiResult.NetworkUnavailable

                    val result = summarizeNoteUseCase("note-001")

                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }

                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { noteRepository.summarizeNote(any()) } returns ApiResult.Error(error)

                    val result = summarizeNoteUseCase("note-001")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── RewriteNoteUseCase ───────────────────────────────────────────────────────

class RewriteNoteUseCaseTest :
    DescribeSpec({

        val noteRepository = mockk<NoteRepository>()
        val rewriteNoteUseCase = RewriteNoteUseCase(noteRepository)

        beforeEach {
            clearMocks(noteRepository)
        }

        afterEach {
            unmockkAll()
        }

        describe("RewriteNoteUseCase") {

            describe("successful rewrite") {

                it("returns Success with rewritten content when repository succeeds") {
                    coEvery { noteRepository.rewriteNote("note-001") } returns
                        ApiResult.Success("Rewritten note content in professional style.")

                    val result = rewriteNoteUseCase("note-001")

                    result.shouldBeInstanceOf<ApiResult.Success<String>>()
                    (result as ApiResult.Success<String>).data shouldBe
                        "Rewritten note content in professional style."
                }

                it("delegates to repository exactly once with the given noteId") {
                    coEvery { noteRepository.rewriteNote("note-001") } returns
                        ApiResult.Success("Rewritten content")

                    rewriteNoteUseCase("note-001")

                    coVerify(exactly = 1) { noteRepository.rewriteNote("note-001") }
                }
            }

            describe("error propagation") {

                it("propagates NetworkUnavailable from repository") {
                    coEvery { noteRepository.rewriteNote(any()) } returns ApiResult.NetworkUnavailable

                    val result = rewriteNoteUseCase("note-001")

                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }

                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { noteRepository.rewriteNote(any()) } returns ApiResult.Error(error)

                    val result = rewriteNoteUseCase("note-001")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })
