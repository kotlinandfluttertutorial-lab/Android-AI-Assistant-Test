/**
 * SemanticSearchViewModelTest.kt
 *
 * Purpose: Unit tests for SemanticSearchViewModel state transitions, result grouping,
 *          empty state, and navigation deep-link trigger.
 *
 * Requirements: 21.1, 36.4, 36.8
 */
package com.aiassistant.feature.search

import app.cash.turbine.test
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.SemanticSearchResult
import com.aiassistant.domain.usecase.search.SemanticSearchUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SemanticSearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val semanticSearchUseCase: SemanticSearchUseCase = mockk()
    private lateinit var viewModel: SemanticSearchViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = SemanticSearchViewModel(semanticSearchUseCase)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun makeResult(
        sourceType: SemanticSearchResult.SourceType,
        sourceName: String,
        score: Float = 0.8f,
        deepLink: String = "aiassistant://${sourceType.name.lowercase()}/123"
    ) = SemanticSearchResult(
        sourceType = sourceType,
        sourceName = sourceName,
        excerpt = "Sample excerpt for $sourceName",
        relevanceScore = score,
        deepLinkUri = deepLink
    )

    // ─── Initial state ────────────────────────────────────────────────────────

    @Test
    fun `initial state is Idle`() = runTest {
        assertIs<SemanticSearchUiState.Idle>(viewModel.uiState.value)
    }

    // ─── Loading state ────────────────────────────────────────────────────────

    @Test
    fun `search transitions to Loading then Success`() = runTest {
        val results = listOf(
            makeResult(SemanticSearchResult.SourceType.NOTE, "My Note")
        )
        coEvery { semanticSearchUseCase(any()) } returns ApiResult.Success(results)

        viewModel.uiState.test {
            val initial = awaitItem() // Idle
            assertIs<SemanticSearchUiState.Idle>(initial)

            viewModel.search("test query")

            val loading = awaitItem()
            assertIs<SemanticSearchUiState.Loading>(loading)

            val success = awaitItem()
            assertIs<SemanticSearchUiState.Success>(success)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── Result grouping ──────────────────────────────────────────────────────

    @Test
    fun `results are grouped by source type with correct counts`() = runTest {
        val results = listOf(
            makeResult(SemanticSearchResult.SourceType.NOTE, "Note A", 0.9f),
            makeResult(SemanticSearchResult.SourceType.NOTE, "Note B", 0.75f),
            makeResult(SemanticSearchResult.SourceType.CONVERSATION, "Chat 1", 0.85f),
            makeResult(SemanticSearchResult.SourceType.DOCUMENT, "Doc X", 0.65f)
        )
        coEvery { semanticSearchUseCase(any()) } returns ApiResult.Success(results)

        viewModel.search("test query")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertIs<SemanticSearchUiState.Success>(state)

        // Verify group counts
        assertEquals(2, state.groupedResults[SemanticSearchResult.SourceType.NOTE]?.size)
        assertEquals(1, state.groupedResults[SemanticSearchResult.SourceType.CONVERSATION]?.size)
        assertEquals(1, state.groupedResults[SemanticSearchResult.SourceType.DOCUMENT]?.size)
        // Memory group should be absent (no memory results)
        assertTrue(state.groupedResults[SemanticSearchResult.SourceType.MEMORY] == null)
    }

    @Test
    fun `results within each group are sorted by relevance score descending`() = runTest {
        val results = listOf(
            makeResult(SemanticSearchResult.SourceType.NOTE, "Note Low", 0.6f),
            makeResult(SemanticSearchResult.SourceType.NOTE, "Note High", 0.95f),
            makeResult(SemanticSearchResult.SourceType.NOTE, "Note Mid", 0.75f)
        )
        coEvery { semanticSearchUseCase(any()) } returns ApiResult.Success(results)

        viewModel.search("test query")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as SemanticSearchUiState.Success
        val noteResults = state.groupedResults[SemanticSearchResult.SourceType.NOTE]!!

        assertEquals("Note High", noteResults[0].sourceName)
        assertEquals("Note Mid", noteResults[1].sourceName)
        assertEquals("Note Low", noteResults[2].sourceName)
    }

    @Test
    fun `all four source types can coexist in grouped results`() = runTest {
        val results = listOf(
            makeResult(SemanticSearchResult.SourceType.CONVERSATION, "Chat"),
            makeResult(SemanticSearchResult.SourceType.NOTE, "Note"),
            makeResult(SemanticSearchResult.SourceType.DOCUMENT, "Doc"),
            makeResult(SemanticSearchResult.SourceType.MEMORY, "Memory")
        )
        coEvery { semanticSearchUseCase(any()) } returns ApiResult.Success(results)

        viewModel.search("broad query")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as SemanticSearchUiState.Success
        assertEquals(4, state.groupedResults.keys.size)
    }

    // ─── Empty state ──────────────────────────────────────────────────────────

    @Test
    fun `empty results emits Empty state`() = runTest {
        coEvery { semanticSearchUseCase(any()) } returns ApiResult.Success(emptyList())

        viewModel.search("obscure query with no results")
        testDispatcher.scheduler.advanceUntilIdle()

        assertIs<SemanticSearchUiState.Empty>(viewModel.uiState.value)
    }

    @Test
    fun `Empty state does not emit Error`() = runTest {
        coEvery { semanticSearchUseCase(any()) } returns ApiResult.Success(emptyList())

        viewModel.search("nothing here")
        testDispatcher.scheduler.advanceUntilIdle()

        // Must be Empty, not Error
        val state = viewModel.uiState.value
        assertTrue(state is SemanticSearchUiState.Empty, "Expected Empty but got $state")
    }

    // ─── Error state ──────────────────────────────────────────────────────────

    @Test
    fun `network error emits Error state`() = runTest {
        coEvery { semanticSearchUseCase(any()) } returns ApiResult.NetworkUnavailable

        viewModel.search("query")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertIs<SemanticSearchUiState.Error>(state)
        assertTrue(state.message.contains("network", ignoreCase = true))
    }

    @Test
    fun `domain error emits Error state with message`() = runTest {
        coEvery { semanticSearchUseCase(any()) } returns ApiResult.Error(
            DomainError.ServerError(message = "Internal server error", httpStatusCode = 500)
        )

        viewModel.search("query")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertIs<SemanticSearchUiState.Error>(state)
        assertTrue(state.message.isNotBlank())
    }

    // ─── Navigation deep-link ─────────────────────────────────────────────────

    @Test
    fun `result deep link URI is preserved correctly`() = runTest {
        val expectedUri = "aiassistant://notes/abc-123"
        val results = listOf(
            SemanticSearchResult(
                sourceType = SemanticSearchResult.SourceType.NOTE,
                sourceName = "My Note",
                excerpt = "Sample excerpt",
                relevanceScore = 0.9f,
                deepLinkUri = expectedUri
            )
        )
        coEvery { semanticSearchUseCase(any()) } returns ApiResult.Success(results)

        viewModel.search("note content")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as SemanticSearchUiState.Success
        val noteResult = state.groupedResults[SemanticSearchResult.SourceType.NOTE]!!.first()
        assertEquals(expectedUri, noteResult.deepLinkUri)
    }

    // ─── Reset ────────────────────────────────────────────────────────────────

    @Test
    fun `reset transitions back to Idle`() = runTest {
        coEvery { semanticSearchUseCase(any()) } returns ApiResult.Success(emptyList())

        viewModel.search("query")
        testDispatcher.scheduler.advanceUntilIdle()
        assertIs<SemanticSearchUiState.Empty>(viewModel.uiState.value)

        viewModel.reset()
        assertIs<SemanticSearchUiState.Idle>(viewModel.uiState.value)
    }

    // ─── Blank query guard ────────────────────────────────────────────────────

    @Test
    fun `blank query is ignored and state remains Idle`() = runTest {
        viewModel.search("   ")
        testDispatcher.scheduler.advanceUntilIdle()

        // Should remain Idle — no use case call for blank queries
        assertIs<SemanticSearchUiState.Idle>(viewModel.uiState.value)
    }
}
