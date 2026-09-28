/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : CodeAgentTest.kt
 * Purpose    : Unit tests for CodeAgent — all 6 actions, errors, streaming.
 * ============================================================
 */
package com.aiassistant.data.agent

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.agent.AgentCapability
import com.aiassistant.domain.agent.AgentEvent
import com.aiassistant.domain.agent.AgentExecution
import com.aiassistant.domain.agent.AgentRequest
import com.aiassistant.domain.agent.AgentStatus
import com.aiassistant.domain.agent.CodeAgentAction
import com.aiassistant.domain.model.CodeAction
import com.aiassistant.domain.model.CodeAnalysisRequest
import com.aiassistant.domain.model.CodeAnalysisResult
import com.aiassistant.domain.model.SupportedLanguage
import com.aiassistant.domain.repository.CodeRepository
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class CodeAgentTest {

    private lateinit var codeRepository: CodeRepository
    private lateinit var agent: CodeAgent

    @Before
    fun setUp() {
        codeRepository = mockk()
        agent = CodeAgent(codeRepository)
    }

    private fun makeRequest(
        input: String = "fun hello() = println(\"hi\")",
        action: String = "explain",
        language: String = "KOTLIN",
        caps: Set<AgentCapability> = emptySet(),
    ) = AgentRequest(
        userId = "u1",
        input = input,
        capabilities = caps,
        metadata = mapOf(
            CodeAgent.METADATA_CODE_ACTION to action,
            CodeAgent.METADATA_LANGUAGE to language,
        ),
    )

    private fun makeExecution(req: AgentRequest = makeRequest()) =
        AgentExecution(request = req, agentName = CodeAgent.NAME)

    private fun successResult(content: String = "## Explanation\nIt prints hi.") =
        ApiResult.Success(
            CodeAnalysisResult(
                languageId = "kotlin",
                originalCode = "fun hello() = println(\"hi\")",
                action = CodeAction.EXPLAIN,
                content = content,
            )
        )

    // ── Metadata / capabilities ──────────────────────────────────────────────

    @Test
    fun `name is code-analysis`() {
        agent.name shouldBe "code-analysis"
    }

    @Test
    fun `declares CODE_ANALYSIS capability`() {
        (AgentCapability.CODE_ANALYSIS in agent.capabilities).shouldBeTrue()
    }

    @Test
    fun `canHandle with no capability constraint`() {
        agent.canHandle(makeRequest()).shouldBeTrue()
    }

    @Test
    fun `canHandle with CODE_ANALYSIS capability`() {
        agent.canHandle(makeRequest(caps = setOf(AgentCapability.CODE_ANALYSIS))).shouldBeTrue()
    }

    @Test
    fun `canHandle returns false for SPEECH_TO_TEXT`() {
        agent.canHandle(makeRequest(caps = setOf(AgentCapability.SPEECH_TO_TEXT))).shouldBeFalse()
    }

    // ── All 6 actions ────────────────────────────────────────────────────────

    @Test
    fun `EXPLAIN action completes successfully`() = runTest {
        coEvery { codeRepository.analyzeCode(any()) } returns successResult("Explanation here")
        val events = agent.execute(makeRequest(action = "explain"), makeExecution()).toList()
        val completed = events.filterIsInstance<AgentEvent.Completed>().first()
        completed.result.content shouldBe "Explanation here"
        completed.result.metadata["action"] shouldBe "explain"
    }

    @Test
    fun `FIX_BUG action completes successfully`() = runTest {
        coEvery { codeRepository.analyzeCode(any()) } returns successResult("fun hello() = println(\"fixed\")")
        val events = agent.execute(makeRequest(action = "fix_bug"), makeExecution()).toList()
        events.filterIsInstance<AgentEvent.Completed>().size shouldBe 1
    }

    @Test
    fun `GENERATE_TESTS action completes successfully`() = runTest {
        coEvery { codeRepository.analyzeCode(any()) } returns successResult("@Test fun test() {}")
        val events = agent.execute(makeRequest(action = "generate_tests"), makeExecution()).toList()
        events.filterIsInstance<AgentEvent.Completed>().size shouldBe 1
    }

    @Test
    fun `GENERATE action maps to existing backend action and completes`() = runTest {
        coEvery { codeRepository.analyzeCode(any()) } returns successResult("fun newFn() {}")
        val events = agent.execute(makeRequest(action = "generate"), makeExecution()).toList()
        events.filterIsInstance<AgentEvent.Completed>().size shouldBe 1
    }

    @Test
    fun `REFACTOR action completes successfully`() = runTest {
        coEvery { codeRepository.analyzeCode(any()) } returns successResult("val x = 1")
        val events = agent.execute(makeRequest(action = "refactor"), makeExecution()).toList()
        events.filterIsInstance<AgentEvent.Completed>().size shouldBe 1
    }

    @Test
    fun `REVIEW action completes successfully`() = runTest {
        coEvery { codeRepository.analyzeCode(any()) } returns successResult("## Review\nLooks good.")
        val events = agent.execute(makeRequest(action = "review"), makeExecution()).toList()
        events.filterIsInstance<AgentEvent.Completed>().size shouldBe 1
    }

    // ── Streaming ────────────────────────────────────────────────────────────

    @Test
    fun `streaming emits Started, StatusChanged, Token, Completed in order`() = runTest {
        coEvery { codeRepository.analyzeCode(any()) } returns successResult("Content")
        val events = agent.execute(makeRequest(), makeExecution()).toList()

        events[0].shouldBeInstanceOf<AgentEvent.Started>()
        events[1].shouldBeInstanceOf<AgentEvent.StatusChanged>()
        val token = events.filterIsInstance<AgentEvent.Token>().first()
        token.token shouldBe "Content"
        events.last().shouldBeInstanceOf<AgentEvent.Completed>()
    }

    // ── Metadata in result ───────────────────────────────────────────────────

    @Test
    fun `result metadata contains language_id`() = runTest {
        coEvery { codeRepository.analyzeCode(any()) } returns successResult("x")
        val events = agent.execute(makeRequest(language = "KOTLIN"), makeExecution()).toList()
        val completed = events.filterIsInstance<AgentEvent.Completed>().first()
        completed.result.metadata["language_id"] shouldBe "kotlin"
    }

    // ── Error paths ──────────────────────────────────────────────────────────

    @Test
    fun `repository error emits Failed with CODE_ANALYSIS_ERROR`() = runTest {
        coEvery { codeRepository.analyzeCode(any()) } returns
            ApiResult.Error(DomainError.ServerError(httpStatusCode = 503))
        val events = agent.execute(makeRequest(), makeExecution()).toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().first()
        failed.result.error?.code shouldBe "CODE_ANALYSIS_ERROR"
        failed.result.status shouldBe AgentStatus.FAILED
    }

    @Test
    fun `network unavailable emits Failed with NETWORK_UNAVAILABLE`() = runTest {
        coEvery { codeRepository.analyzeCode(any()) } returns ApiResult.NetworkUnavailable
        val events = agent.execute(makeRequest(), makeExecution()).toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().first()
        failed.result.error?.code shouldBe "NETWORK_UNAVAILABLE"
    }

    // ── Language fallback ─────────────────────────────────────────────────────

    @Test
    fun `unknown language falls back to KOTLIN`() = runTest {
        var capturedRequest: CodeAnalysisRequest? = null
        coEvery { codeRepository.analyzeCode(any()) } answers {
            capturedRequest = firstArg()
            successResult("ok")
        }
        agent.execute(makeRequest(language = "COBOL"), makeExecution()).toList()
        capturedRequest?.language shouldBe SupportedLanguage.KOTLIN
    }

    // ── AgentGateway integration (unit) ───────────────────────────────────────

    @Test
    fun `AgentGateway executeCode routes through CodeAgent`() = runTest {
        coEvery { codeRepository.analyzeCode(any()) } returns successResult("Generated code")
        val mockChat = mockk<ChatAgent>(relaxed = true)
        val mockRag = mockk<RagAgent>(relaxed = true)
        val gateway = AgentGateway(
            mockChat, agent, mockRag,
            mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true),
            com.aiassistant.domain.agent.DefaultToolRegistry(),
        )

        val events = gateway.executeCode(
            code = "fun x() {}",
            action = "explain",
            language = "KOTLIN",
        ).toList()

        events.filterIsInstance<AgentEvent.Completed>().size shouldBe 1
        events.filterIsInstance<AgentEvent.Completed>().first().result.content shouldBe "Generated code"
    }
}
