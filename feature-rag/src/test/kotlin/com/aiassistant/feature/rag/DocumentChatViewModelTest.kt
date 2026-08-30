/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-rag
 * File       : DocumentChatViewModelTest.kt
 * Purpose    : Unit tests for DocumentChatViewModel state logic and RAG response parsing.
 * Architecture: feature-rag — MVVM ViewModel Tests
 * Requirements: 4.6, 4.7
 * ============================================================
 */
package com.aiassistant.feature.rag

import androidx.lifecycle.SavedStateHandle
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.Document
import com.aiassistant.domain.repository.DocumentRepository
import com.aiassistant.domain.usecase.document.QueryDocumentUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
 * Unit tests for [DocumentChatViewModel] ensuring correct UI state transitions,
 * citation parsing, and document metadata loading.
 *
 * Uses MockK for dependency mocking and [UnconfinedTestDispatcher] for synchronous-like
 * coroutine execution in tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentChatViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
        override val default: CoroutineDispatcher = testDispatcher
        override val mainImmediate: CoroutineDispatcher = testDispatcher
        override val unconfined: CoroutineDispatcher = testDispatcher
    }

    private val queryDocumentUseCase = mockk<QueryDocumentUseCase>()
    private val documentRepository = mockk<DocumentRepository>()
    private val savedStateHandle = mockk<SavedStateHandle>()

    private lateinit var viewModel: DocumentChatViewModel

    private val documentId = "doc123"
    private val fileName = "test_document.pdf"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { savedStateHandle.get<String>("documentId") } returns documentId
        // Default behavior for init block
        every { documentRepository.getDocuments() } returns flowOf(ApiResult.Success(emptyList()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun initViewModel() {
        viewModel = DocumentChatViewModel(
            queryDocumentUseCase = queryDocumentUseCase,
            documentRepository = documentRepository,
            dispatchers = testDispatchers,
            savedStateHandle = savedStateHandle
        )
    }

    private fun makeDocument(id: String, name: String) = Document(
        id = id,
        userId = "user1",
        fileName = name,
        mimeType = "application/pdf",
        sizeBytes = 1024L,
        createdAt = System.currentTimeMillis()
    )

    @Test
    fun `loadDocumentFileName in init updates state with resolved name`() = runTest {
        val documents = listOf(makeDocument(documentId, fileName))
        every { documentRepository.getDocuments() } returns flowOf(ApiResult.Success(documents))

        initViewModel()

        val state = viewModel.uiState.value
        assertTrue(state is DocumentChatUiState.Idle)
        assertEquals(fileName, (state as DocumentChatUiState.Idle).documentFileName)
    }

    @Test
    fun `submitQuery transitions through Loading to Success with parsed citations`() = runTest {
        val query = "What is the revenue?"
        val doc = makeDocument(documentId, fileName)
        val rawResponse = """
            The revenue is $10M.
            Sources:
            [1] annual_report.pdf, page 5
        """.trimIndent()

        // Mocking repo for init
        every {
            documentRepository.getDocuments()
        } returns flowOf(ApiResult.Success(listOf(doc)))
        initViewModel()

        coEvery { queryDocumentUseCase(documentId, query) } returns ApiResult.Success(rawResponse)

        val states = mutableListOf<DocumentChatUiState>()
        val job = backgroundScope.launch(testDispatcher) {
            viewModel.uiState.collect {
                states.add(it)
            }
        }

        viewModel.submitQuery(query)

        // Verify sequence: Idle -> Loading -> Success
        // states[0] is Idle (initial)
        // states[1] is Loading
        // states[2] is Success
        assertTrue(states.any { it is DocumentChatUiState.Loading })
        val finalState = states.last() as DocumentChatUiState.Success
        assertEquals("The revenue is $10M.", finalState.exchange.aiResponse)
        assertEquals(1, finalState.exchange.citations.size)
        assertEquals("annual_report.pdf", finalState.exchange.citations[0].documentName)
        assertEquals(5, finalState.exchange.citations[0].pageNumber)
        assertEquals(fileName, finalState.documentFileName)

        job.cancel()
    }

    @Test
    fun `submitQuery error transitions to Error state`() = runTest {
        val query = "Failure query"
        initViewModel()

        coEvery { queryDocumentUseCase(documentId, query) } returns ApiResult.Error(
            DomainError.NetworkError("Network error")
        )

        viewModel.submitQuery(query)

        val state = viewModel.uiState.value as DocumentChatUiState.Error
        assertEquals("Network error", state.message)
        assertEquals(query, state.lastQuery)
    }

    @Test
    fun `parseResponse Format A (with Sources header) works correctly`() {
        initViewModel()
        val raw = "Response text\nSources:\n[1] doc_a.pdf, page 10\n[2] doc_b.pdf, p 20"

        val (text, citations) = viewModel.parseResponse(raw)

        assertEquals("Response text", text)
        assertEquals(2, citations.size)
        assertEquals("doc_a.pdf", citations[0].documentName)
        assertEquals(10, citations[0].pageNumber)
        assertEquals("doc_b.pdf", citations[1].documentName)
        assertEquals(20, citations[1].pageNumber)
    }

    @Test
    fun `parseResponse Format B (numbered list without header) works correctly`() {
        initViewModel()
        val raw = "Response text without header\n[1] doc_c.pdf, page 5"

        val (text, citations) = viewModel.parseResponse(raw)

        assertEquals("Response text without header", text)
        assertEquals(1, citations.size)
        assertEquals("doc_c.pdf", citations[0].documentName)
        assertEquals(5, citations[0].pageNumber)
    }

    @Test
    fun `parseResponse Format C (no citations) returns empty list`() {
        initViewModel()
        val raw = "Just plain text response."

        val (text, citations) = viewModel.parseResponse(raw)

        assertEquals(raw, text)
        assertTrue(citations.isEmpty())
    }

    @Test
    fun `resetToIdle clears exchange but preserves file name`() = runTest {
        val doc = makeDocument(documentId, fileName)
        every {
            documentRepository.getDocuments()
        } returns flowOf(ApiResult.Success(listOf(doc)))
        initViewModel()

        viewModel.submitQuery("Query")
        viewModel.resetToIdle()

        val state = viewModel.uiState.value as DocumentChatUiState.Idle
        assertEquals(fileName, state.documentFileName)
    }
}
