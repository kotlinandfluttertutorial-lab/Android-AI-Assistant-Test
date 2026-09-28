/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : WebAgentTest.kt
 * Purpose    : Unit tests for WebAgent — provider independence,
 *              stub fallback, error paths, citation mapping.
 *
 * Architecture Layer : Data — agent sub-package (test)
 * Pattern Used       : JUnit4 + MockK + Kotest
 * ============================================================
 */
package com.aiassistant.data.agent

import com.aiassistant.domain.agent.AgentCapability
import com.aiassistant.domain.agent.AgentEvent
import com.aiassistant.domain.agent.AgentExecution
import com.aiassistant.domain.agent.AgentRequest
import com.aiassistant.domain.agent.AgentStatus
import com.aiassistant.domain.agent.WebSearchProvider
import com.aiassistant.domain.agent.WebSearchResult
import com.aiassistant.data.agent.web.StubWebSearchProvider
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class WebAgentTest {

    private lateinit var provider: WebSearchProvider
    private lateinit var agent: WebAgent

    @Before
    fun setUp() {
        provider = mockk(relaxed = true)
        agent = WebAgent(provider)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun configuredProvider(results: List<WebSearchResult> = listOf(
        WebSearchResult(title = "Result 1", url = "https://example.com/1", snippet = "Snippet 1"),
        WebSearchResult(title = "Result 2", url = "https://example.com/2", snippet = "Snippet 2"),
    )): WebSearchProvider {
        val p = mockk<WebSearchProvider>()
        every { p.providerName } returns "test-provider"
        every { p.isConfigured } returns true
        coEvery { p.search(any(), any()) } returns Result.success(results)
        return p
    }

    private fun req(
        input: String = "kotlin coroutines",
        metadata: Map<String, String> = emptyMap(),
    ) = AgentRequest(
        userId = "user-1",
        input = input,
        metadata = metadata,
    )

    private fun exec(r: AgentRequest = req()) =
        AgentExecution(request = r, agentName = WebAgent.NAME)

    // ── Metadata / capabilities ───────────────────────────────────────────────

    @Test
    fun `agent name is web-search`() {
        agent.name shouldBe WebAgent.NAME
    }

    @Test
    fun `declares SEMANTIC_SEARCH and TEXT_GENERATION capabilities`() {
        (AgentCapability.SEMANTIC_SEARCH in agent.capabilities).shouldBeTrue()
        (AgentCapability.TEXT_GENERATION in agent.capabilities).shouldBeTrue()
    }

    @Test
    fun `canHandle true when agent_name matches`() {
        val r = req(metadata = mapOf(WebAgent.METADATA_AGENT_NAME to WebAgent.NAME))
        agent.canHandle(r).shouldBeTrue()
    }

    @Test
    fun `canHandle true when SEMANTIC_SEARCH in capabilities`() {
        val r = AgentRequest(
            userId = "u1", input = "test",
            capabilities = setOf(AgentCapability.SEMANTIC_SEARCH),
        )
        agent.canHandle(r).shouldBeTrue()
    }

    @Test
    fun `canHandle true for empty capability set`() {
        agent.canHandle(req()).shouldBeTrue()
    }

    // ── Provider not configured ───────────────────────────────────────────────

    @Test
    fun `stub provider emits PROVIDER_NOT_CONFIGURED`() = runTest {
        every { provider.isConfigured } returns false

        val events = agent.execute(req(), exec()).toList()

        val failed = events.filterIsInstance<AgentEvent.Failed>().first()
        failed.result.error?.code shouldBe "PROVIDER_NOT_CONFIGURED"
        failed.result.status shouldBe AgentStatus.FAILED
    }

    @Test
    fun `default WebAgent uses stub and emits PROVIDER_NOT_CONFIGURED`() = runTest {
        val stubAgent = WebAgent(StubWebSearchProvider())
        val r = req()
        val events = stubAgent.execute(r, exec(r)).toList()

        events.filterIsInstance<AgentEvent.Failed>().first()
            .result.error?.code shouldBe "PROVIDER_NOT_CONFIGURED"
    }

    // ── Blank query ───────────────────────────────────────────────────────────

    @Test
    fun `blank query emits BLANK_QUERY`() = runTest {
        val p = configuredProvider()
        val a = WebAgent(p)
        // Pass blank via metadata query key; input must be non-blank for AgentRequest validation
        val r = req(
            input = "ignored",
            metadata = mapOf(WebAgent.METADATA_QUERY to "   "),
        )
        val events = a.execute(r, exec(r)).toList()

        events.filterIsInstance<AgentEvent.Failed>().first()
            .result.error?.code shouldBe "BLANK_QUERY"
    }

    // ── Search error ──────────────────────────────────────────────────────────

    @Test
    fun `search failure emits SEARCH_ERROR`() = runTest {
        every { provider.isConfigured } returns true
        coEvery { provider.search(any(), any()) } returns
            Result.failure(RuntimeException("network error"))

        val events = agent.execute(req(), exec()).toList()

        events.filterIsInstance<AgentEvent.Failed>().first()
            .result.error?.code shouldBe "SEARCH_ERROR"
    }

    // ── Successful search ─────────────────────────────────────────────────────

    @Test
    fun `successful search emits Started, StatusChanged, RetrievalCompleted, Token, Completed`() = runTest {
        val a = WebAgent(configuredProvider())
        val r = req()
        val events = a.execute(r, exec(r)).toList()

        events[0].shouldBeInstanceOf<AgentEvent.Started>()
        events[1].shouldBeInstanceOf<AgentEvent.StatusChanged>()
        events.filterIsInstance<AgentEvent.RetrievalCompleted>().first()
            .chunkCount shouldBe 2
        events.filterIsInstance<AgentEvent.Token>().shouldNotBeEmpty()
        val completed = events.filterIsInstance<AgentEvent.Completed>().first()
        completed.result.status shouldBe AgentStatus.COMPLETED
    }

    @Test
    fun `completed result has citations equal to result count`() = runTest {
        val a = WebAgent(configuredProvider())
        val r = req()
        val events = a.execute(r, exec(r)).toList()

        val completed = events.filterIsInstance<AgentEvent.Completed>().first()
        completed.result.citations.size shouldBe 2
        completed.result.citations[0].documentId shouldBe "https://example.com/1"
        completed.result.citations[0].documentName shouldBe "Result 1"
    }

    @Test
    fun `metadata query key takes precedence over request input`() = runTest {
        val p = configuredProvider()
        var capturedQuery = ""
        coEvery { p.search(any(), any()) } answers {
            capturedQuery = firstArg()
            Result.success(emptyList())
        }
        val a = WebAgent(p)
        val r = req(
            input = "fallback input",
            metadata = mapOf(WebAgent.METADATA_QUERY to "metadata query"),
        )
        a.execute(r, exec(r)).toList()

        capturedQuery shouldBe "metadata query"
    }

    @Test
    fun `max_results metadata clamped to 1-20 range`() = runTest {
        val p = configuredProvider()
        var capturedMax = 0
        coEvery { p.search(any(), any()) } answers {
            capturedMax = secondArg<Int>()
            Result.success(emptyList())
        }
        val a = WebAgent(p)

        // Over 20 → clamped to 20
        val r1 = req(metadata = mapOf(WebAgent.METADATA_MAX_RESULTS to "99"))
        a.execute(r1, exec(r1)).toList()
        capturedMax shouldBe 20

        // Under 1 → clamped to 1
        val r2 = req(metadata = mapOf(WebAgent.METADATA_MAX_RESULTS to "0"))
        a.execute(r2, exec(r2)).toList()
        capturedMax shouldBe 1
    }

    // ── Empty results ─────────────────────────────────────────────────────────

    @Test
    fun `empty results emits Completed with no-results message`() = runTest {
        val p = configuredProvider(emptyList())
        val a = WebAgent(p)
        val r = req()
        val events = a.execute(r, exec(r)).toList()

        val completed = events.filterIsInstance<AgentEvent.Completed>().first()
        completed.result.status shouldBe AgentStatus.COMPLETED
        completed.result.content.shouldNotBeNull()
        completed.result.content!!.contains("No web results found").shouldBeTrue()
    }

    // ── Result metadata ───────────────────────────────────────────────────────

    @Test
    fun `completed result metadata contains provider and result_count`() = runTest {
        val p = configuredProvider()
        every { p.providerName } returns "mock-provider"
        val a = WebAgent(p)
        val r = req()
        val events = a.execute(r, exec(r)).toList()

        val completed = events.filterIsInstance<AgentEvent.Completed>().first()
        completed.result.metadata["provider"] shouldBe "mock-provider"
        completed.result.metadata["result_count"] shouldBe "2"
    }
}
