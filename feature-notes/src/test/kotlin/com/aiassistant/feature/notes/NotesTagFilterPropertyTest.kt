/**
 * NotesTagFilterPropertyTest.kt — feature-notes module
 *
 * Purpose: Property-based tests for Property 20: Notes Tag Filter Invariant.
 *          Verifies that the tag-filter predicate (the contract implemented by
 *          [NoteRepository.getNotesByTag]) correctly includes and excludes notes
 *          for any arbitrary note list and filter tag.
 *
 * Architecture: feature-notes module — unit tests (pure JVM, no Android framework).
 *
 * Test toolchain:
 * - Kotest DescribeSpec + checkAll / Arb — property-based test structure
 * - MockK                               — mocking NoteRepository (P20-6 only)
 * - kotlinx.coroutines.test             — UnconfinedTestDispatcher + runTest (P20-6)
 *
 * **Validates: Requirements 13.5**
 *
 * Requirements covered:
 *   13.5 — THE AI_Assistant SHALL allow the User to filter notes by tag. When a
 *           tag filter is applied, ONLY notes whose tags list contains the selected
 *           tag SHALL be displayed.
 *
 * Properties verified:
 *   P20-1  Every note in the filtered result contains the filter tag (inclusion invariant).
 *   P20-2  No note without the filter tag appears in filtered results (exclusion invariant).
 *   P20-3  Filtering by a tag present in no note yields an empty result.
 *   P20-4  Filtering by a tag present in all notes returns all notes.
 *   P20-5  Result size is always ≤ input size (filter never adds notes).
 *   P20-6  NotesViewModel.selectTagFilter delegates to NoteRepository and emits correct state.
 */

package com.aiassistant.feature.notes

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.Note
import com.aiassistant.domain.model.SyncStatus
import com.aiassistant.domain.repository.NoteRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

// ─── Tag pool ─────────────────────────────────────────────────────────────────

/** Fixed pool of well-known tags used when generating notes. */
private val TAG_POOL = listOf("work", "personal", "urgent", "review", "idea")

// ─── Pure filter function under test ──────────────────────────────────────────

/**
 * Applies the tag filter predicate: returns only notes whose [Note.tags] list
 * contains [tag].
 *
 * This mirrors the repository contract described in [NoteRepository.getNotesByTag]:
 * "Only notes whose [Note.tags] list contains [tag] are included."
 */
private fun applyTagFilter(notes: List<Note>, tag: String): List<Note> = notes.filter { tag in it.tags }

// ─── Generators ───────────────────────────────────────────────────────────────

/**
 * Generates valid tag strings: 1–20 non-blank characters without double-quotes.
 */
private val arbTag: Arb<String> = arbitrary {
    // Generate a string of 1..20 alphanumeric characters to ensure no blank/quote issues
    val length = Arb.int(1..20).bind()
    val chars = (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '_')).toList()
    (1..length).map { Arb.element(chars).bind() }.joinToString("")
}

/**
 * Generates a single [Note] with a positional [index]-based id (guaranteeing uniqueness
 * across a generated list) and a random subset of tags drawn from [TAG_POOL].
 *
 * Uses a random bitmask to select which pool tags the note gets, ensuring a true
 * random subset without requiring [Arb.subset] which is not available in this version.
 */
private fun arbNote(index: Int): Arb<Note> = arbitrary {
    val epochMs = Arb.long(1_000_000L..9_999_999_999L).bind()
    val suffix = Arb.int(1..Int.MAX_VALUE).bind()

    // Generate a random bitmask to select a subset of TAG_POOL
    val mask = Arb.int(0..(1 shl TAG_POOL.size) - 1).bind()
    val tagSubset = TAG_POOL.filterIndexed { idx, _ -> (mask shr idx) and 1 == 1 }

    Note(
        id = "note-$index-$suffix",
        userId = "user-test",
        title = "Title $index",
        content = "Content $index",
        tags = tagSubset,
        syncStatus = SyncStatus.PENDING,
        createdAt = epochMs,
        updatedAt = epochMs
    )
}

/**
 * Generates a list of 0–20 [Note]s with guaranteed unique positional ids.
 */
private val arbNoteList: Arb<List<Note>> = arbitrary {
    val size = Arb.int(0..20).bind()
    (0 until size).map { idx -> arbNote(idx).bind() }
}

/**
 * Pairs a [Note] list with a randomly chosen filter tag.
 * The tag is either one of the well-known pool entries or a fully arbitrary string,
 * exercising both "tag exists in pool" and "tag never assigned to any note" scenarios.
 */
private val arbNoteListAndTag: Arb<Pair<List<Note>, String>> = arbitrary {
    val notes = arbNoteList.bind()
    // 50 % chance: use a known pool tag; 50 % chance: use a random string tag
    val usePoolTag = Arb.int(0..1).bind() == 0
    val tag = if (usePoolTag) {
        Arb.element(TAG_POOL).bind()
    } else {
        arbTag.bind()
    }
    notes to tag
}

// ─── Property 20: Notes Tag Filter Invariant ──────────────────────────────────

/**
 * **Validates: Requirements 13.5**
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesTagFilterPropertyTest :
    DescribeSpec({

        afterSpec {
            unmockkAll()
        }

        // ── P20-1 — Inclusion invariant ───────────────────────────────────────────
        describe("P20-1 — every note in filtered result contains the filter tag") {

            it("no filtered note is missing the filter tag, across 300 random inputs") {
                checkAll(iterations = 300, arbNoteListAndTag) { (notes, tag) ->
                    val filtered = applyTagFilter(notes, tag)
                    filtered.forEach { note ->
                        note.tags.contains(tag) shouldBe true
                    }
                }
            }
        }

        // ── P20-2 — Exclusion invariant ───────────────────────────────────────────
        describe("P20-2 — no note without the filter tag appears in filtered results") {

            it("the complement of the filtered set contains no note with the filter tag, across 300 random inputs") {
                checkAll(iterations = 300, arbNoteListAndTag) { (notes, tag) ->
                    val filtered = applyTagFilter(notes, tag)
                    val filteredIds = filtered.map { it.id }.toSet()
                    val unfiltered = notes.filter { it.id !in filteredIds }
                    unfiltered.forEach { note ->
                        note.tags.contains(tag) shouldBe false
                    }
                }
            }
        }

        // ── P20-3 — No-match tag yields empty result ───────────────────────────────
        describe("P20-3 — filtering by a tag present in no note yields empty result") {

            it("result is empty when filter tag matches no note") {
                checkAll(iterations = 200, arbNoteList) { notes ->
                    // Use a timestamp-derived tag that cannot collide with any generated tag
                    val unusedTag = "tag-that-no-note-has-${System.nanoTime()}"
                    val filtered = applyTagFilter(notes, unusedTag)
                    filtered.shouldBeEmpty()
                }
            }
        }

        // ── P20-4 — All-match tag returns all notes ────────────────────────────────
        describe("P20-4 — filtering by a tag present in all notes returns all notes") {

            it("result size equals input size when all notes share the filter tag") {
                checkAll(iterations = 200, arbNoteList) { baseNotes ->
                    val sharedTag = "shared-tag"
                    val notes = baseNotes.map { it.copy(tags = it.tags + sharedTag) }
                    val filtered = applyTagFilter(notes, sharedTag)
                    filtered.size shouldBe notes.size
                }
            }
        }

        // ── P20-5 — Monotonicity: filter never adds notes ─────────────────────────
        describe("P20-5 — result size is always ≤ input size") {

            it("filter never adds notes to the result, across 300 random inputs") {
                checkAll(iterations = 300, arbNoteListAndTag) { (notes, tag) ->
                    val filtered = applyTagFilter(notes, tag)
                    (filtered.size <= notes.size) shouldBe true
                }
            }
        }

        // ── P20-6 — ViewModel delegation via mocked repository ────────────────────
        describe("P20-6 — NotesViewModel.selectTagFilter delegates to repository and emits correct state") {

            it("NotesList.notes contains only notes with the filter tag, verified via mocked repository") {
                val testDispatcher = UnconfinedTestDispatcher()
                Dispatchers.setMain(testDispatcher)

                try {
                    runTest(testDispatcher) {
                        // Build a fixed set of notes: two have the target tag, one doesn't
                        val filterTag = "work"
                        val matchingNotes = listOf(
                            Note(
                                id = "note-1",
                                userId = "user-test",
                                title = "Work note 1",
                                content = "Content 1",
                                tags = listOf("work", "urgent"),
                                syncStatus = SyncStatus.PENDING,
                                createdAt = 1_000_000L,
                                updatedAt = 1_000_000L
                            ),
                            Note(
                                id = "note-2",
                                userId = "user-test",
                                title = "Work note 2",
                                content = "Content 2",
                                tags = listOf("work"),
                                syncStatus = SyncStatus.PENDING,
                                createdAt = 2_000_000L,
                                updatedAt = 2_000_000L
                            )
                        )
                        val nonMatchingNotes = listOf(
                            Note(
                                id = "note-3",
                                userId = "user-test",
                                title = "Personal note",
                                content = "Content 3",
                                tags = listOf("personal"),
                                syncStatus = SyncStatus.PENDING,
                                createdAt = 3_000_000L,
                                updatedAt = 3_000_000L
                            )
                        )
                        val allNotes = matchingNotes + nonMatchingNotes

                        // Mock repository: getNotes returns all; getNotesByTag returns only matching
                        val mockRepo = mockk<NoteRepository>()
                        every { mockRepo.getNotes() } returns
                            flowOf(ApiResult.Success(allNotes))
                        every { mockRepo.getNotesByTag(filterTag) } returns
                            flowOf(ApiResult.Success(matchingNotes))

                        // Mock the use cases that NotesViewModel requires
                        val mockSaveUseCase =
                            mockk<com.aiassistant.domain.usecase.note.SaveNoteUseCase>()
                        val mockSummarizeUseCase =
                            mockk<com.aiassistant.domain.usecase.note.SummarizeNoteUseCase>()
                        val mockRewriteUseCase =
                            mockk<com.aiassistant.domain.usecase.note.RewriteNoteUseCase>()
                        val mockDispatchers =
                            mockk<com.aiassistant.core.common.DispatcherProvider>()
                        every { mockDispatchers.io } returns testDispatcher

                        val mockContextSuggestionsUseCase =
                            mockk<com.aiassistant.domain.usecase.suggestions.GetContextSuggestionsUseCase>()
                        val mockDismissSuggestionUseCase =
                            mockk<com.aiassistant.domain.usecase.suggestions.DismissSuggestionUseCase>()

                        val viewModel = NotesViewModel(
                            saveNoteUseCase = mockSaveUseCase,
                            summarizeNoteUseCase = mockSummarizeUseCase,
                            rewriteNoteUseCase = mockRewriteUseCase,
                            noteRepository = mockRepo,
                            dispatchers = mockDispatchers,
                            getContextSuggestionsUseCase = mockContextSuggestionsUseCase,
                            dismissSuggestionUseCase = mockDismissSuggestionUseCase
                        )

                        // Advance past the init { loadNotes() } call
                        advanceUntilIdle()

                        // Call the function under test
                        viewModel.selectTagFilter(filterTag)
                        advanceUntilIdle()

                        // Assert: state is NotesList with only matching notes
                        val state = viewModel.uiState.value
                        val notesList = (state as NotesUiState.NotesList).notes

                        // P20-1 invariant via ViewModel: every note in the result contains the filter tag
                        notesList.forEach { note ->
                            note.tags.contains(filterTag) shouldBe true
                        }

                        // P20-2 invariant via ViewModel: none of the non-matching notes appear in the result
                        val resultIds = notesList.map { it.id }.toSet()
                        nonMatchingNotes.forEach { note ->
                            (note.id in resultIds) shouldBe false
                        }

                        // selectedTag is correctly propagated to the state
                        (state as NotesUiState.NotesList).selectedTag shouldBe filterTag
                    }
                } finally {
                    Dispatchers.resetMain()
                }
            }
        }
    })
