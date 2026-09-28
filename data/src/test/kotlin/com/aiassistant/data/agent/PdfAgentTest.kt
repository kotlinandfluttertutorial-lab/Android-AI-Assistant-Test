/*
 * Data layer unit tests for PdfAgent — upload, query, summarize, PDF→RAG.
 */
package com.aiassistant.data.agent

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.agent.AgentEvent
import com.aiassistant.domain.agent.AgentExecution
import com.aiassistant.domain.agent.AgentRequest
import com.aiassistant.domain.agent.AgentStatus
import com.aiassistant.domain.model.Document
import com.aiassistant.domain.model.IngestionStatus
import com.aiassistant.domain.repository.DocumentRepository
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class PdfAgentTest {

    private lateinit var documentRepository: DocumentRepository
    private lateinit var agent: PdfAgent

    @Before fun setUp() { documentRepository = mockk(relaxed = true); agent = PdfAgent(documentRepository) }

    private fun doc(id: String = "doc-1", status: IngestionStatus = IngestionStatus.READY) = Document(
        id = id, userId = "u1", fileName = "test.pdf", mimeType = "application/pdf",
        sizeBytes = 1024, ingestionStatus = status, createdAt = 0, errorMessage = null)

    private fun req(
        action: String = "query",
        input: String = "What is this about?",
        documentId: String? = "doc-1",
        fileUri: String? = null,
        fileName: String? = null,
    ) = AgentRequest(
        userId = "u1", input = input,
        metadata = buildMap {
            put(PdfAgent.METADATA_PDF_ACTION, action)
            documentId?.let { put(PdfAgent.METADATA_DOCUMENT_ID, it) }
            fileUri?.let { put(PdfAgent.METADATA_FILE_URI, it) }
            fileName?.let { put(PdfAgent.METADATA_FILE_NAME, it) }
        },
    )

    private fun exec(r: AgentRequest = req()) = AgentExecution(request = r, agentName = PdfAgent.NAME)

    // ── Metadata / capabilities ───────────────────────────────────────────────

    @Test fun `agent name is pdf`() { agent.name shouldBe "pdf" }
    @Test fun `declares DOCUMENT_RETRIEVAL`() {
        (com.aiassistant.domain.agent.AgentCapability.DOCUMENT_RETRIEVAL in agent.capabilities).shouldBeTrue()
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    @Test fun `query emits Started, RetrievalCompleted, Token, Completed`() = runTest {
        coEvery { documentRepository.queryDocument("doc-1", any()) } returns
            ApiResult.Success("The answer.")
        val events = agent.execute(req(), exec()).toList()
        events[0].shouldBeInstanceOf<AgentEvent.Started>()
        events[1].shouldBeInstanceOf<AgentEvent.StatusChanged>()
        events.filterIsInstance<AgentEvent.RetrievalCompleted>().size shouldBe 1
        events.filterIsInstance<AgentEvent.Token>().first().token shouldBe "The answer."
        events.filterIsInstance<AgentEvent.Completed>().first().result.status shouldBe AgentStatus.COMPLETED
    }

    @Test fun `query missing documentId emits MISSING_DOCUMENT_ID`() = runTest {
        val r = req(documentId = null)
        val events = agent.execute(r, exec(r)).toList()
        events.filterIsInstance<AgentEvent.Failed>().first().result.error?.code shouldBe "MISSING_DOCUMENT_ID"
    }

    @Test fun `query repository error emits QUERY_ERROR`() = runTest {
        coEvery { documentRepository.queryDocument(any(), any()) } returns
            ApiResult.Error(DomainError.ServerError(httpStatusCode = 500))
        val events = agent.execute(req(), exec()).toList()
        events.filterIsInstance<AgentEvent.Failed>().first().result.error?.code shouldBe "QUERY_ERROR"
    }

    @Test fun `query network unavailable emits NETWORK_UNAVAILABLE`() = runTest {
        coEvery { documentRepository.queryDocument(any(), any()) } returns ApiResult.NetworkUnavailable
        val events = agent.execute(req(), exec()).toList()
        events.filterIsInstance<AgentEvent.Failed>().first().result.error?.code shouldBe "NETWORK_UNAVAILABLE"
    }

    // ── Summarize ─────────────────────────────────────────────────────────────

    @Test fun `summarize sends summary query to repository`() = runTest {
        var capturedQuery = ""
        coEvery { documentRepository.queryDocument("doc-1", any()) } answers {
            capturedQuery = secondArg()
            ApiResult.Success("Summary text.")
        }
        val r = req(action = "summarize")
        agent.execute(r, exec(r)).toList()
        capturedQuery.lowercase().contains("summary").shouldBeTrue()
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    @Test fun `upload missing file_uri emits MISSING_FILE_URI`() = runTest {
        val r = req(action = "upload", fileUri = null, fileName = null)
        val events = agent.execute(r, exec(r)).toList()
        events.filterIsInstance<AgentEvent.Failed>().first().result.error?.code shouldBe "MISSING_FILE_URI"
    }

    @Test fun `upload missing file_name emits MISSING_FILE_NAME`() = runTest {
        val r = req(action = "upload", fileUri = "content://test.pdf", fileName = null)
        val events = agent.execute(r, exec(r)).toList()
        events.filterIsInstance<AgentEvent.Failed>().first().result.error?.code shouldBe "MISSING_FILE_NAME"
    }

    @Test fun `upload success emits Thinking events and Completed`() = runTest {
        coEvery {
            documentRepository.uploadDocument(any(), any(), any())
        } returns ApiResult.Success(doc("doc-new", IngestionStatus.PENDING))
        coEvery {
            documentRepository.getIngestionStatus("doc-new")
        } returns ApiResult.Success(IngestionStatus.READY)

        val r = req(action = "upload", fileUri = "content://test.pdf", fileName = "test.pdf", documentId = null)
        val events = agent.execute(r, exec(r)).toList()
        events.filterIsInstance<AgentEvent.Thinking>().size shouldBe 2
        events.filterIsInstance<AgentEvent.Completed>().first().result.status shouldBe AgentStatus.COMPLETED
    }

    @Test fun `upload repository error emits UPLOAD_ERROR`() = runTest {
        coEvery { documentRepository.uploadDocument(any(), any(), any()) } returns
            ApiResult.Error(DomainError.ServerError(httpStatusCode = 500))
        val r = req(action = "upload", fileUri = "content://test.pdf", fileName = "test.pdf", documentId = null)
        val events = agent.execute(r, exec(r)).toList()
        events.filterIsInstance<AgentEvent.Failed>().first().result.error?.code shouldBe "UPLOAD_ERROR"
    }

    @Test fun `upload ingestion failed emits INGESTION_FAILED`() = runTest {
        coEvery { documentRepository.uploadDocument(any(), any(), any()) } returns
            ApiResult.Success(doc("doc-fail", IngestionStatus.PENDING))
        coEvery { documentRepository.getIngestionStatus("doc-fail") } returns
            ApiResult.Success(IngestionStatus.FAILED)
        val r = req(action = "upload", fileUri = "content://test.pdf", fileName = "test.pdf", documentId = null)
        val events = agent.execute(r, exec(r)).toList()
        events.filterIsInstance<AgentEvent.Failed>().first().result.error?.code shouldBe "INGESTION_FAILED"
    }

    // ── Unknown action ────────────────────────────────────────────────────────

    @Test fun `unknown pdf_action emits UNKNOWN_ACTION`() = runTest {
        val r = req(action = "rotate")
        val events = agent.execute(r, exec(r)).toList()
        events.filterIsInstance<AgentEvent.Failed>().first().result.error?.code shouldBe "UNKNOWN_ACTION"
    }

    // ── PDF→RAG handoff via AgentGateway ─────────────────────────────────────

    @Test fun `AgentGateway executePdf routes through PdfAgent`() = runTest {
        coEvery { documentRepository.queryDocument("doc-1", any()) } returns ApiResult.Success("RAG answer.")
        val mockChat = mockk<ChatAgent>(relaxed = true)
        val mockCode = mockk<CodeAgent>(relaxed = true)
        val mockRag = mockk<RagAgent>(relaxed = true)
        val toolRegistry = com.aiassistant.domain.agent.DefaultToolRegistry()
        val gateway = AgentGateway(
            mockChat, mockCode, mockRag, agent,
            ToolAgent(toolRegistry),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            toolRegistry,
        )

        val events = gateway.executePdf(
            action = "query", input = "What is this?", documentId = "doc-1"
        ).toList()
        events.filterIsInstance<AgentEvent.Completed>().first().result.content shouldBe "RAG answer."
    }
}
