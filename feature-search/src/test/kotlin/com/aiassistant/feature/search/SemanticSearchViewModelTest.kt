package com.aiassistant.feature.search

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.SemanticSearchResult
import com.aiassistant.domain.usecase.search.SemanticSearchUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SemanticSearchViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SemanticSearchViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val semanticSearchUseCase: SemanticSearchUseCase = mockk()
    private lateinit var viewModel: SemanticSearchViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = SemanticSearchViewModel(semanticSearchUseCase)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `search with valid query updates state to Success with grouped results`() = runTest {
        // Arrange
        val query = "test query"
        val results = listOf(
            SemanticSearchResult(
                sourceType = SemanticSearchResult.SourceType.NOTE,
                sourceName = "Note 1",
                excerpt = "Excerpt 1",
                relevanceScore = 0.9f,
                deepLinkUri = "uri1"
            ),
            SemanticSearchResult(
                sourceType = SemanticSearchResult.SourceType.CONVERSATION,
                sourceName = "Conv 1",
                excerpt = "Excerpt 2",
                relevanceScore = 0.8f,
                deepLinkUri = "uri2"
            ),
            SemanticSearchResult(
                sourceType = SemanticSearchResult.SourceType.NOTE,
                sourceName = "Note 2",
                excerpt = "Excerpt 3",
                relevanceScore = 0.95f,
                deepLinkUri = "uri3"
            )
        )
        coEvery { semanticSearchUseCase(query) } returns ApiResult.Success(results)

        val states = mutableListOf<SemanticSearchUiState>()
        val job = launch(testDispatcher) {
            viewModel.uiState.collect { states.add(it) }
        }

        // Act
        viewModel.search(query)

        // Assert
        assertEquals(SemanticSearchUiState.Idle, states[0])
        assertEquals(SemanticSearchUiState.Loading, states[1])
        assertTrue(states[2] is SemanticSearchUiState.Success)

        val successState = states[2] as SemanticSearchUiState.Success

        // Verify grouping
        assertEquals(2, successState.groupedResults.size)
        assertTrue(successState.groupedResults.containsKey(SemanticSearchResult.SourceType.NOTE))
        assertTrue(successState.groupedResults.containsKey(SemanticSearchResult.SourceType.CONVERSATION))

        // Verify sorting within groups (Note 2 should be first because 0.95 > 0.9)
        val noteResults = successState.groupedResults[SemanticSearchResult.SourceType.NOTE]!!
        assertEquals(0.95f, noteResults[0].relevanceScore)
        assertEquals(0.9f, noteResults[1].relevanceScore)

        job.cancel()
    }

    @Test
    fun `search with empty results updates state to Empty`() = runTest {
        // Arrange
        val query = "empty query"
        coEvery { semanticSearchUseCase(query) } returns ApiResult.Success(emptyList())

        // Act
        viewModel.search(query)

        // Assert
        assertEquals(SemanticSearchUiState.Empty, viewModel.uiState.value)
    }

    @Test
    fun `search with error updates state to Error`() = runTest {
        // Arrange
        val query = "error query"
        val errorMessage = "Search failed"
        coEvery { semanticSearchUseCase(query) } returns ApiResult.Error(DomainError.ServerError(message = errorMessage))

        // Act
        viewModel.search(query)

        // Assert
        val state = viewModel.uiState.value
        assertTrue(state is SemanticSearchUiState.Error)
        assertEquals(errorMessage, (state as SemanticSearchUiState.Error).message)
    }

    @Test
    fun `search with network unavailable updates state to Error`() = runTest {
        // Arrange
        val query = "network query"
        coEvery { semanticSearchUseCase(query) } returns ApiResult.NetworkUnavailable

        // Act
        viewModel.search(query)

        // Assert
        val state = viewModel.uiState.value
        assertTrue(state is SemanticSearchUiState.Error)
        assertEquals("No network connection. Semantic search requires internet access.", (state as SemanticSearchUiState.Error).message)
    }

    @Test
    fun `reset updates state to Idle`() = runTest {
        // Arrange - set to something else first
        coEvery { semanticSearchUseCase(any()) } returns ApiResult.Success(emptyList())
        viewModel.search("some query")

        // Act
        viewModel.reset()

        // Assert
        assertEquals(SemanticSearchUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `search with blank query does not update state`() = runTest {
        // Act
        viewModel.search("   ")

        // Assert
        assertEquals(SemanticSearchUiState.Idle, viewModel.uiState.value)
    }
}
