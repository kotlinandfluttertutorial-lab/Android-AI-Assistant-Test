/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-rag (test)
 * File       : DocumentChatViewModelTest.kt
 * Purpose    : Unit tests for DocumentChatViewModel state logic, structured
 *              citation path, legacy fallback path, and response parsing.
 *
 * Covers (all acceptance criteria):
 *   - Idle initial state
 *   - Document file name loaded in init
 *   - Blank query silently ignored
 *   - submitQuery: Loading → Success via structured /rag/query path
 *   - submitQuery: structured Success carries RagAnswer + sources (excerpt + score)
 *   - submitQuery: structured Success → exchange.sources populated
 *   - submitQuery: structured Success → ragAnswer non-null
 *   - submitQuery: structured endpoint failure → falls back to legacy path
 *   - submitQuery: legacy Success carries parsed citations
 *   - submitQuery: legacy path Success → exchange.sources empty, citations non-empty
 *   - submitQuery: error state on both paths failing
 *   - submitQuery: NetworkUnavailable → Error state
 *   - RAGExchange.hasAnySources (structured sources)
 *   - RAGExchange.hasAnySources (legacy citations)
 *   - RAGExchange.hasAnySources false when both empty
 *   - parseResponse Format A (with header)
 *   - parseResponse Format B (without header)
 *   - parseResponse no citations → full text, empty list
 *   - resetToIdle preserves file name
 *
 * Authentication is respected because the ViewModel never calls the repo with
 * auth credentials directly — the AuthInterceptor handles Bearer tokens
 * transparently in OkHttp.  Tests verify that the ViewModel uses the repository
 * result as-is without modifying it.
 *
 * Style: JUnit4 + MockK + UnconfinedTestDispatcher (matches project conventions)
 * ============================================================
 */

package com.aiassistant.feature.rag

import androidx.lifecycle.SavedStateHandle
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.Document
import com.aiassistant.domain.model.RagAnswer
import com.aiassistant.domain.model.RagCitationSource
import com.aiassistant.domain.repository.DocumentRepository
import com.aiassistant.domain.usecase.document.QueryDocumentUseCase
import com.aiassistant.domain.usecase.document.QueryDocumentWithSourcesUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.util.UUID
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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

    private val queryWithSources = mockk<QueryDocumentWithSourcesUseCase>()
    private val queryDocumentUseCase = mockk<QueryDocumentUseCase>()
    private val documentRepository = mockk<DocumentRepository>()
    private val savedStateHandle = mockk<SavedStateHandle>()

    private lateinit var viewModel: DocumentChatViewModel

    private val documentId = "doc-123"
    private val fileName = "report.pdf"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { savedStateHandle.get<String>("documentId") } returns documentId
        every { documentRepository.getDocuments() } returns flowOf(ApiResult.Success(emptyList()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    private fun initViewModel() {
        viewModel = DocumentChatViewModel(
            queryDocumentWithSourcesUseCase = queryWithSources,
            queryDocumentUseCase = queryDocumentUseCase,
            documentRepository = documentRepository,
            dispatchers = testDispatchers,
            savedStateHandle = savedStateHandle,
        )
    }

    private fun makeDocument(id: String = documentId, name: String = fileName) = Document(
        id = id,
        userId = "user-1",
        fileName = name,
        mimeType = "application/pdf",
        sizeBytes = 1024L,
        createdAt = System.currentTimeMillis(),
    )

    private fun makeRagAnswer(
        answer: String = "The structured answer.",
        sourceCount: Int = 2,
    ) = RagAnswer(
        answer = answer,
        sources = List(sourceCount) { i ->
            RagCitationSource(
                documentName = "doc_$i.pdf",
                pageNumber = i + 1,
                excerpt = "Excerpt from page ${i + 1}.",
                score = 0.9f - i * 0.1f,
            )
        },
        requestId = UUID.randomUUID().toString(),
    )

    // =========================================================================
    // Init / file name loading
    // =========================================================================

    @Test
    fun `initial state is Idle`() {
        initViewModel()
        assertTrue(viewModel.uiState.value is DocumentChatUiState.Idle)
    }

    @Test
    fun `document file name loaded in init when repository returns document`() = runTest {
        val docs = listOf(makeDocument())
        every { documentRepository.getDocuments() } returns flowOf(ApiResult.Success(docs))
        initViewModel()

        val state = viewModel.uiState.value as DocumentChatUiState.Idle
        assertEquals(fileName, state.documentFileName)
    }

    @Test
    fun `file name falls back to documentId when document not found`() = runTest {
        every { documentRepository.getDocuments() } returns flowOf(ApiResult.Success(emptyList()))
        initViewModel()

        val state = viewModel.uiState.value as DocumentChatUiState.Idle
        // Falls back to documentId when list is empty
        assertEquals(documentId, state.documentFileName)
    }

    // =========================================================================
    // submitQuery — blank / empty
    // =========================================================================

    @Test
    fun `blank query is silently ignored — state stays Idle`() = runTest {
        initViewModel()
        viewModel.submitQuery("   ")
        assertTrue(viewModel.uiState.value is DocumentChatUiState.Idle)
        coVerify(exactly = 0) { queryWithSources(any(), any(), any()) }
        coVerify(exactly = 0) { queryDocumentUseCase(any(), any()) }
    }

    @Test
    fun `empty query is silently ignored`() = runTest {
        initViewModel()
        viewModel.submitQuery("")
        assertTrue(viewModel.uiState.value is DocumentChatUiState.Idle)
    }

    // =========================================================================
    // submitQuery — structured /rag/query path (AC: RAG answer displayed)
    // =========================================================================

    @Test
    fun `submitQuery transitions through Loading to Success via structured path`() = runTest {
        val ragAnswer = makeRagAnswer()
        coEvery { queryWithSources(any(), any(), any()) } returns ApiResult.Success(ragAnswer)
        initViewModel()

        val states = mutableListOf<DocumentChatUiState>()
        val job = backgroundScope.launch(testDispatcher) {
            viewModel.uiState.collect { states.add(it) }
        }

        viewModel.submitQuery("What is the revenue?")

        assertTrue(states.any { it is DocumentChatUiState.Loading })
        val success = states.last() as DocumentChatUiState.Success
        assertEquals("The structured answer.", success.exchange.aiResponse)

        job.cancel()
    }

    @Test
    fun `structured Success — ragAnswer non-null with correct answer`() = runTest {
        val ragAnswer = makeRagAnswer("Structured answer text.")
        coEvery { queryWithSources(any(), any(), any()) } returns ApiResult.Success(ragAnswer)
        initViewModel()
        viewModel.submitQuery("Q?")

        val success = viewModel.uiState.value as DocumentChatUiState.Success
        assertNotNull(success.ragAnswer)
        assertEquals("Structured answer text.", success.ragAnswer!!.answer)
    }

    @Test
    fun `structured Success — exchange sources populated with RagCitationSource list`() = runTest {
        val ragAnswer = makeRagAnswer(sourceCount = 3)
        coEvery { queryWithSources(any(), any(), any()) } returns ApiResult.Success(ragAnswer)
        initViewModel()
        viewModel.submitQuery("Q?")

        val success = viewModel.uiState.value as DocumentChatUiState.Success
        assertEquals(3, success.exchange.sources.size)
        assertEquals("doc_0.pdf", success.exchange.sources[0].documentName)
        assertEquals(1, success.exchange.sources[0].pageNumber)
        assertEquals("Excerpt from page 1.", success.exchange.sources[0].excerpt)
        assertEquals(0.9f, success.exchange.sources[0].score, 0.001f)
    }

    @Test
    fun `structured Success — legacy citations list is empty`() = runTest {
        coEvery { queryWithSources(any(), any(), any()) } returns ApiResult.Success(makeRagAnswer())
        initViewModel()
        viewModel.submitQuery("Q?")

        val success = viewModel.uiState.value as DocumentChatUiState.Success
        assertTrue(success.exchange.citations.isEmpty())
    }

    @Test
    fun `structured Success — exchange hasAnySources is true`() = runTest {
        coEvery { queryWithSources(any(), any(), any()) } returns ApiResult.Success(makeRagAnswer(sourceCount = 1))
        initViewModel()
        viewModel.submitQuery("Q?")

        val success = viewModel.uiState.value as DocumentChatUiState.Success
        assertTrue(success.exchange.hasAnySources)
    }

    @Test
    fun `structured path passes documentId in documentIds list`() = runTest {
        coEvery { queryWithSources(any(), any(), any()) } returns ApiResult.Success(makeRagAnswer())
        initViewModel()
        viewModel.submitQuery("Q?")

        coVerify { queryWithSources(any(), listOf(documentId), any()) }
    }

    // =========================================================================
    // submitQuery — fallback to legacy path
    // =========================================================================

    @Test
    fun `structured path failure falls back to legacy QueryDocumentUseCase`() = runTest {
        coEvery { queryWithSources(any(), any(), any()) } returns ApiResult.Error(
            DomainError.ServerError("endpoint not found", 404)
        )
        val rawResponse = "Answer text.\nSources:\n[1] arch.pdf, page 7"
        coEvery { queryDocumentUseCase(documentId, any()) } returns ApiResult.Success(rawResponse)
        initViewModel()

        viewModel.submitQuery("Q?")

        val success = viewModel.uiState.value as DocumentChatUiState.Success
        assertEquals("Answer text.", success.exchange.aiResponse)
        assertEquals(1, success.exchange.citations.size)
        assertEquals("arch.pdf", success.exchange.citations[0].documentName)
        assertEquals(7, success.exchange.citations[0].pageNumber)
    }

    @Test
    fun `legacy fallback Success — ragAnswer is null`() = runTest {
        coEvery { queryWithSources(any(), any(), any()) } returns ApiResult.Error(
            DomainError.NetworkError("unavailable")
        )
        coEvery { queryDocumentUseCase(any(), any()) } returns ApiResult.Success("Just text.")
        initViewModel()
        viewModel.submitQuery("Q?")

        val success = viewModel.uiState.value as DocumentChatUiState.Success
        assertNull(success.ragAnswer)
    }

    @Test
    fun `legacy fallback Success — exchange sources empty, citations potentially non-empty`() = runTest {
        val rawResponse = "Answer.\n[1] doc.pdf, page 5"
        coEvery { queryWithSources(any(), any(), any()) } returns ApiResult.Error(
            DomainError.NetworkError("unavailable")
        )
        coEvery { queryDocumentUseCase(any(), any()) } returns ApiResult.Success(rawResponse)
        initViewModel()
        viewModel.submitQuery("Q?")

        val success = viewModel.uiState.value as DocumentChatUiState.Success
        assertTrue(success.exchange.sources.isEmpty())
        assertEquals(1, success.exchange.citations.size)
    }

    @Test
    fun `both paths fail — Error state with message from legacy error`() = runTest {
        coEvery { queryWithSources(any(), any(), any()) } returns ApiResult.Error(
            DomainError.NetworkError("primary error")
        )
        coEvery { queryDocumentUseCase(any(), any()) } returns ApiResult.Error(
            DomainError.ServerError("fallback error", 500)
        )
        initViewModel()
        viewModel.submitQuery("Q?")

        val error = viewModel.uiState.value as DocumentChatUiState.Error
        assertEquals("fallback error", error.message)
    }

    @Test
    fun `NetworkUnavailable from legacy fallback results in Error with offline message`() = runTest {
        coEvery { queryWithSources(any(), any(), any()) } returns ApiResult.Error(
            DomainError.NetworkError("primary fail")
        )
        coEvery { queryDocumentUseCase(any(), any()) } returns ApiResult.NetworkUnavailable
        initViewModel()
        viewModel.submitQuery("Q?")

        val error = viewModel.uiState.value as DocumentChatUiState.Error
        assertTrue(error.message.contains("network", ignoreCase = true) ||
                   error.message.contains("connectivity", ignoreCase = true))
        assertEquals("Q?", error.lastQuery)
    }

    // =========================================================================
    // RAGExchange.hasAnySources
    // =========================================================================

    @Test
    fun `hasAnySources false when both citations and sources are empty`() {
        val exchange = RAGExchange(
            userQuery = "Q",
            aiResponse = "A",
            citations = emptyList(),
            sources = emptyList(),
        )
        assertFalse(exchange.hasAnySources)
    }

    @Test
    fun `hasAnySources true when sources list is non-empty`() {
        val exchange = RAGExchange(
            userQuery = "Q",
            aiResponse = "A",
            sources = listOf(
                RagCitationSource("doc.pdf", 1, "excerpt", 0.8f)
            ),
        )
        assertTrue(exchange.hasAnySources)
    }

    @Test
    fun `hasAnySources true when legacy citations list is non-empty`() {
        val exchange = RAGExchange(
            userQuery = "Q",
            aiResponse = "A",
            citations = listOf(Citation("doc.pdf", 3)),
        )
        assertTrue(exchange.hasAnySources)
    }

    // =========================================================================
    // parseResponse — legacy text parsing
    // =========================================================================

    @Test
    fun `parseResponse Format A with Sources header extracts citations`() {
        initViewModel()
        val raw = "Response text.\nSources:\n[1] annual_report.pdf, page 5\n[2] product_spec.docx, p 12"
        val (text, citations) = viewModel.parseResponse(raw)

        assertEquals("Response text.", text)
        assertEquals(2, citations.size)
        assertEquals("annual_report.pdf", citations[0].documentName)
        assertEquals(5, citations[0].pageNumber)
        assertEquals("product_spec.docx", citations[1].documentName)
        assertEquals(12, citations[1].pageNumber)
    }

    @Test
    fun `parseResponse Format A with References header works`() {
        initViewModel()
        val raw = "Content.\nReferences:\n[1] doc.pdf, page 1"
        val (text, citations) = viewModel.parseResponse(raw)

        assertEquals("Content.", text)
        assertEquals(1, citations.size)
    }

    @Test
    fun `parseResponse Format B bare numbered list at end works`() {
        initViewModel()
        val raw = "Response without header.\n[1] plain_doc.pdf, page 3"
        val (text, citations) = viewModel.parseResponse(raw)

        assertEquals("Response without header.", text)
        assertEquals(1, citations.size)
        assertEquals("plain_doc.pdf", citations[0].documentName)
        assertEquals(3, citations[0].pageNumber)
    }

    @Test
    fun `parseResponse no citations returns full text and empty list`() {
        initViewModel()
        val raw = "Just plain answer text with no citations."
        val (text, citations) = viewModel.parseResponse(raw)

        assertEquals(raw, text)
        assertTrue(citations.isEmpty())
    }

    @Test
    fun `parseResponse multiple citations in Format B all captured`() {
        initViewModel()
        val raw = "Answer.\n[1] docA.pdf, page 2\n[2] docB.pdf, page 10"
        val (_, citations) = viewModel.parseResponse(raw)

        assertEquals(2, citations.size)
    }

    @Test
    fun `parseResponse page number null when missing`() {
        initViewModel()
        // Intentionally malformed — no page number — should return empty citations
        val raw = "Answer.\nSources:\n[1] doc.pdf, section 3"
        val (_, citations) = viewModel.parseResponse(raw)
        // No valid citation matches — empty list expected
        assertTrue(citations.isEmpty())
    }

    // =========================================================================
    // resetToIdle
    // =========================================================================

    @Test
    fun `resetToIdle returns to Idle preserving document file name`() = runTest {
        val docs = listOf(makeDocument())
        every { documentRepository.getDocuments() } returns flowOf(ApiResult.Success(docs))
        coEvery { queryWithSources(any(), any(), any()) } returns ApiResult.Success(makeRagAnswer())
        initViewModel()

        viewModel.submitQuery("Q?")
        assertTrue(viewModel.uiState.value is DocumentChatUiState.Success)

        viewModel.resetToIdle()

        val idle = viewModel.uiState.value as DocumentChatUiState.Idle
        assertEquals(fileName, idle.documentFileName)
    }

    // =========================================================================
    // Authentication — verifies ViewModel doesn't bypass auth
    // =========================================================================

    @Test
    fun `Unauthorized error from repository surfaces in Error state`() = runTest {
        coEvery { queryWithSources(any(), any(), any()) } returns ApiResult.Error(
            DomainError.Unauthorized("Authentication required.")
        )
        coEvery { queryDocumentUseCase(any(), any()) } returns ApiResult.Error(
            DomainError.Unauthorized("Authentication required.")
        )
        initViewModel()
        viewModel.submitQuery("Q?")

        val error = viewModel.uiState.value as DocumentChatUiState.Error
        assertTrue(error.message.contains("Authentication", ignoreCase = true) ||
                   error.message.contains("auth", ignoreCase = true))
    }
}
