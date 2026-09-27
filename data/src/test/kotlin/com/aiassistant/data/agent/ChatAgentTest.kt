/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ChatAgentTest.kt
 * Purpose    : Unit tests for ChatAgent — streaming, auth, errors, fallback.
 *
 * Architecture Layer : Data — agent sub-package (test)
 * Pattern Used       : JUnit4 + MockK + Kotest + Turbine
 * ============================================================
 */
package com.aiassistant.data.agent

import com.aiassistant.core.ai.AIStreamClient
import com.aiassistant.core.ai.MessagePayload
import com.aiassistant.core.ai.StreamEvent
import com.aiassistant.core.ai.TokenUsage
import com.aiassistant.core.security.SecureStorage
import com.aiassistant.domain.agent.AgentCapability
import com.aiassistant.domain.agent.AgentContext
import com.aiassistant.domain.agent.AgentEvent
import com.aiassistant.domain.agent.AgentExecution
import com.aiassistant.domain.agent.AgentRequest
import com.aiassistant.domain.agent.AgentStatus
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Before

class ChatAgentTest {

    private lateinit var streamClient: AIStreamClient
    private lateinit var secureStorage: SecureStorage
    private lateinit var agent: ChatAgent

    @Before
    fun setUp() {
        streamClient = mockk(relaxed = true)
        secureStorage = mockk(relaxed = true)
        agent = ChatAgent(streamClient, secureStorage)
    }

    private fun makeRequest(
        input: String = "Hello",
        conversationId: String? = "conv-1",
        provider: String? = "gemini",
        metadata: Map<String, String> = emptyMap(),
    ) = AgentRequest(
        userId = "user-1",
        input = input,
        conversationId = conversationId,
        provider = provider,
        metadata = metadata,
    )

    private fun makeExecution(request: AgentRequest = makeRequest()) =
        AgentExecution(request = request, agentName = ChatAgent.NAME)

    // ── Metadata / capabilities ──────────────────────────────────────────────

    @Test
    fun `agent name is conversational`() {
        agent.name shouldBe "conversational"
    }

    @Test
    fun `agent declares TEXT_GENERATION and STREAMING capabilities`() {
        agent.capabilities.shouldContain(AgentCapability.TEXT_GENERATION)
        agent.capabilities.shouldContain(AgentCapability.STREAMING)
    }

    @Test
    fun `canHandle returns true for empty capability set`() {
        agent.canHandle(makeRequest()).shouldBeTrue()
    }

    @Test
    fun `canHandle returns true for TEXT_GENERATION + STREAMING`() {
        val req = makeRequest().copy(
            capabilities = setOf(AgentCapability.TEXT_GENERATION, AgentCapability.STREAMING)
        )
        agent.canHandle(req).shouldBeTrue()
    }

    @Test
    fun `canHandle returns false for unsupported capability`() {
        val req = makeRequest().copy(
            capabilities = setOf(AgentCapability.SPEECH_TO_TEXT)
        )
        agent.canHandle(req).shouldBeFalse()
    }

    // ── Auth / JWT ────────────────────────────────────────────────────────────

    @Test
    fun `JWT from SecureStorage is passed to streamClient connect`() = runTest {
        every { secureStorage.getJwt() } returns "real-jwt-token"
        every { streamClient.connect(any(), any()) } returns flowOf(
            StreamEvent.Done(TokenUsage(10, 20))
        )

        val request = makeRequest()
        agent.execute(request, makeExecution(request)).toList()

        verify { streamClient.connect("conv-1", "real-jwt-token") }
    }

    @Test
    fun `empty string JWT used when SecureStorage returns null`() = runTest {
        every { secureStorage.getJwt() } returns null
        every { streamClient.connect(any(), any()) } returns flowOf(
            StreamEvent.Done(TokenUsage(0, 0))
        )

        val request = makeRequest()
        agent.execute(request, makeExecution(request)).toList()

        verify { streamClient.connect("conv-1", "") }
    }

    // ── Normal chat / streaming ───────────────────────────────────────────────

    @Test
    fun `normal streaming emits Started, StatusChanged, Token events, then Completed`() = runTest {
        every { secureStorage.getJwt() } returns "jwt"
        every { streamClient.connect(any(), any()) } returns flow {
            emit(StreamEvent.Token("Hello"))
            emit(StreamEvent.Token(" world"))
            emit(StreamEvent.Done(TokenUsage(5, 10)))
        }

        val request = makeRequest()
        val events = agent.execute(request, makeExecution(request)).toList()

        events[0].shouldBeInstanceOf<AgentEvent.Started>()
        events[1].shouldBeInstanceOf<AgentEvent.StatusChanged>()

        val tokens = events.filterIsInstance<AgentEvent.Token>()
        tokens.size shouldBe 2
        tokens[0].token shouldBe "Hello"
        tokens[1].token shouldBe " world"

        val completed = events.filterIsInstance<AgentEvent.Completed>().first()
        completed.result.status shouldBe AgentStatus.COMPLETED
        completed.result.content shouldBe "Hello world"
        completed.result.usage?.inputTokens shouldBe 5
        completed.result.usage?.outputTokens shouldBe 10
    }

    @Test
    fun `token accumulation produces correct final content`() = runTest {
        every { secureStorage.getJwt() } returns "jwt"
        every { streamClient.connect(any(), any()) } returns flow {
            listOf("The ", "quick ", "brown ", "fox").forEach { emit(StreamEvent.Token(it)) }
            emit(StreamEvent.Done(TokenUsage(4, 4)))
        }

        val request = makeRequest()
        val events = agent.execute(request, makeExecution(request)).toList()
        val completed = events.filterIsInstance<AgentEvent.Completed>().first()
        completed.result.content shouldBe "The quick brown fox"
    }

    // ── Streaming errors ──────────────────────────────────────────────────────

    @Test
    fun `StreamEvent Error emits AgentEvent Failed`() = runTest {
        every { secureStorage.getJwt() } returns "jwt"
        every { streamClient.connect(any(), any()) } returns flowOf(
            StreamEvent.Error("LLM quota exceeded")
        )

        val request = makeRequest()
        val events = agent.execute(request, makeExecution(request)).toList()

        val failed = events.filterIsInstance<AgentEvent.Failed>().firstOrNull()
        failed.shouldNotBeNull()
        failed.result.status shouldBe AgentStatus.FAILED
        failed.result.error?.code shouldBe "STREAM_ERROR"
        failed.result.error?.message shouldBe "LLM quota exceeded"
    }

    @Test
    fun `unexpected exception during streaming emits AgentEvent Failed`() = runTest {
        every { secureStorage.getJwt() } returns "jwt"
        every { streamClient.connect(any(), any()) } returns flow {
            throw RuntimeException("Socket closed unexpectedly")
        }

        val request = makeRequest()
        val events = agent.execute(request, makeExecution(request)).toList()

        val failed = events.filterIsInstance<AgentEvent.Failed>().firstOrNull()
        failed.shouldNotBeNull()
        failed.result.error?.code shouldBe "UNEXPECTED_ERROR"
    }

    // ── Missing conversationId ────────────────────────────────────────────────

    @Test
    fun `missing conversationId emits Failed with MISSING_CONVERSATION_ID`() = runTest {
        val request = makeRequest(conversationId = null)
        val events = agent.execute(request, makeExecution(request)).toList()

        val failed = events.filterIsInstance<AgentEvent.Failed>().first()
        failed.result.error?.code shouldBe "MISSING_CONVERSATION_ID"
    }

    @Test
    fun `conversationId from metadata works as fallback`() = runTest {
        every { secureStorage.getJwt() } returns "jwt"
        every { streamClient.connect(any(), any()) } returns flowOf(
            StreamEvent.Done(TokenUsage(0, 0))
        )

        val request = makeRequest(
            conversationId = null,
            metadata = mapOf(ChatAgent.METADATA_CONVERSATION_ID to "meta-conv-1"),
        )
        val events = agent.execute(request, makeExecution(request)).toList()

        verify { streamClient.connect("meta-conv-1", "jwt") }
        events.filterIsInstance<AgentEvent.Completed>().size shouldBe 1
    }

    // ── Tool call passthrough ─────────────────────────────────────────────────

    @Test
    fun `ToolCall StreamEvent emits ToolStarted then ToolCompleted events`() = runTest {
        val toolInput = kotlinx.serialization.json.buildJsonObject {
            put("title", kotlinx.serialization.json.JsonPrimitive("Bug report"))
        }
        every { secureStorage.getJwt() } returns "jwt"
        every { streamClient.connect(any(), any()) } returns flow {
            emit(StreamEvent.ToolCall(toolName = "github", toolInput = toolInput))
            emit(StreamEvent.Token("Created issue"))
            emit(StreamEvent.Done(TokenUsage(1, 5)))
        }

        val request = makeRequest()
        val events = agent.execute(request, makeExecution(request)).toList()

        events.filterIsInstance<AgentEvent.ToolStarted>().also { ts ->
            ts.size shouldBe 1
            ts[0].toolName shouldBe "github"
        }
        events.filterIsInstance<AgentEvent.ToolCompleted>().also { tc ->
            tc.size shouldBe 1
            tc[0].toolName shouldBe "github"
        }
    }

    // ── Disconnect called after streaming ─────────────────────────────────────

    @Test
    fun `disconnect is called after successful streaming`() = runTest {
        every { secureStorage.getJwt() } returns "jwt"
        every { streamClient.connect(any(), any()) } returns flowOf(
            StreamEvent.Done(TokenUsage(0, 0))
        )

        val request = makeRequest()
        agent.execute(request, makeExecution(request)).toList()

        verify { streamClient.disconnect() }
    }

    @Test
    fun `disconnect is called even when streaming fails`() = runTest {
        every { secureStorage.getJwt() } returns "jwt"
        every { streamClient.connect(any(), any()) } returns flowOf(
            StreamEvent.Error("error")
        )

        val request = makeRequest()
        agent.execute(request, makeExecution(request)).toList()

        verify { streamClient.disconnect() }
    }

    // ── Cancellation ──────────────────────────────────────────────────────────

    @Test
    fun `cancellation stops collection and disconnects`() = runTest {
        every { secureStorage.getJwt() } returns "jwt"
        every { streamClient.connect(any(), any()) } returns flow {
            emit(StreamEvent.Token("first"))
            kotlinx.coroutines.delay(5_000L) // long delay
            emit(StreamEvent.Token("should not reach"))
        }

        val request = makeRequest()
        val events = mutableListOf<AgentEvent>()
        try {
            kotlinx.coroutines.withTimeout(100L) {
                agent.execute(request, makeExecution(request)).toList(events)
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            // expected
        }

        // disconnect must be called even on cancellation
        verify { streamClient.disconnect() }
    }

    // ── Model fallback / provider ─────────────────────────────────────────────

    @Test
    fun `provider from request is forwarded in MessagePayload`() = runTest {
        every { secureStorage.getJwt() } returns "jwt"
        every { streamClient.connect(any(), any()) } returns flowOf(
            StreamEvent.Done(TokenUsage(0, 0))
        )

        val request = makeRequest(provider = "claude")
        agent.execute(request, makeExecution(request)).toList()

        verify {
            streamClient.sendMessage(
                match { it is MessagePayload && it.provider == "claude" }
            )
        }
    }

    @Test
    fun `on_device provider is forwarded correctly`() = runTest {
        every { secureStorage.getJwt() } returns "jwt"
        every { streamClient.connect(any(), any()) } returns flowOf(
            StreamEvent.Done(TokenUsage(0, 0))
        )

        val request = makeRequest(provider = "on_device")
        agent.execute(request, makeExecution(request)).toList()

        verify {
            streamClient.sendMessage(
                match { it is MessagePayload && it.provider == "on_device" }
            )
        }
    }

    // ── Conversation history (via context) ────────────────────────────────────

    @Test
    fun `agentContext userId is respected`() = runTest {
        every { secureStorage.getJwt() } returns "jwt"
        every { streamClient.connect(any(), any()) } returns flowOf(
            StreamEvent.Done(TokenUsage(0, 0))
        )

        val ctx = AgentContext(
            userId = "user-from-context",
            conversationId = "conv-ctx",
        )
        val request = makeRequest(conversationId = "conv-1").copy(context = ctx)
        val events = agent.execute(request, makeExecution(request)).toList()

        events.filterIsInstance<AgentEvent.Completed>().size shouldBe 1
    }

    // ── AgentGatewayRepository ────────────────────────────────────────────────

    @Test
    fun `AgentGateway executeChat routes through ChatAgent`() = runTest {
        every { secureStorage.getJwt() } returns "jwt"
        every { streamClient.connect(any(), any()) } returns flowOf(
            StreamEvent.Token("hi"),
            StreamEvent.Done(TokenUsage(1, 1)),
        )

        val gateway = AgentGateway(chatAgent = agent)
        val events = gateway.executeChat(
            conversationId = "conv-1",
            content = "Hello",
            provider = "gemini",
        ).toList()

        events.filterIsInstance<AgentEvent.Completed>().size shouldBe 1
        events.filterIsInstance<AgentEvent.Token>().map { it.token } shouldBe listOf("hi")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun Set<AgentCapability>.shouldContain(cap: AgentCapability) {
        contains(cap).shouldBeTrue()
    }

    private fun AgentRequest.copy(
        capabilities: Set<AgentCapability> = this.capabilities,
        conversationId: String? = this.conversationId,
        provider: String? = this.provider,
        metadata: Map<String, String> = this.metadata,
        context: AgentContext? = this.context,
    ) = AgentRequest(
        requestId = this.requestId,
        userId = this.userId,
        input = this.input,
        conversationId = conversationId,
        provider = provider,
        capabilities = capabilities,
        context = context,
        maxSteps = this.maxSteps,
        timeoutMs = this.timeoutMs,
        streamingEnabled = this.streamingEnabled,
        metadata = metadata,
    )
}
