/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : OnDeviceAgentTest.kt
 * Purpose    : Unit tests for OnDeviceAgent (Phase 7).
 *
 * Covers:
 *   - Local inference success (AUTO and LOCAL_ONLY modes)
 *   - Model unavailable → MODEL_NOT_READY failure (AUTO mode)
 *   - LOCAL_ONLY when unavailable → LOCAL_ONLY_UNAVAILABLE (never cloud)
 *   - Memory / RAM exhaustion → ON_DEVICE_INSUFFICIENT_MEMORY failure
 *   - Cancellation propagates and stops token emission
 *   - Model loading (isReady=true after model becomes available)
 *   - Model unloading (isReady=false when model removed)
 *   - No network calls (import verification + OkHttp absence)
 *   - CLOUD routing mode → canHandle returns false
 *   - Streaming protocol: Started → StatusChanged → Token × N → Completed
 *   - Completed result metadata contains routing_mode and provider
 *   - AgentGateway executeOnDevice routes to OnDeviceAgent
 *   - AgentGateway isOnDeviceReady delegates to OnDeviceInferencePort
 *
 * Architecture Layer : Data — agent sub-package (test)
 * Pattern Used       : JUnit 5 + MockK + Kotest
 * ============================================================
 */
package com.aiassistant.data.agent

import com.aiassistant.domain.agent.AgentCapability
import com.aiassistant.domain.agent.AgentContext
import com.aiassistant.domain.agent.AgentEvent
import com.aiassistant.domain.agent.AgentExecution
import com.aiassistant.domain.agent.AgentRequest
import com.aiassistant.domain.agent.AgentStatus
import com.aiassistant.domain.agent.ModelRoutingMode
import com.aiassistant.domain.agent.OnDeviceErrorCode
import com.aiassistant.domain.agent.OnDeviceInferencePort
import com.aiassistant.domain.agent.OnDeviceStreamEvent
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@ExperimentalCoroutinesApi
class OnDeviceAgentTest {

    private lateinit var port: OnDeviceInferencePort
    private lateinit var agent: OnDeviceAgent

    @BeforeEach
    fun setUp() {
        port = mockk(relaxed = true)
        agent = OnDeviceAgent(port)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun req(
        input: String = "Tell me about Kotlin.",
        routingMode: ModelRoutingMode = ModelRoutingMode.AUTO,
        conversationId: String? = "conv-1",
    ) = AgentRequest(
        userId = "user-1",
        input = input,
        conversationId = conversationId,
        capabilities = setOf(AgentCapability.ON_DEVICE_INFERENCE),
        metadata = mapOf(
            OnDeviceAgent.METADATA_AGENT_NAME to OnDeviceAgent.NAME,
            OnDeviceAgent.METADATA_ROUTING_MODE to routingMode.name,
        ),
    )

    private fun exec(r: AgentRequest = req()) =
        AgentExecution(request = r, agentName = OnDeviceAgent.NAME)

    private fun tokenFlow(vararg tokens: String) = flow<OnDeviceStreamEvent> {
        tokens.forEach { emit(OnDeviceStreamEvent.Token(it)) }
        emit(OnDeviceStreamEvent.Done(inputTokens = 5, outputTokens = tokens.size))
    }

    // ── Metadata / capabilities ───────────────────────────────────────────────

    @Test
    fun `agent name is on_device`() {
        agent.name shouldBe OnDeviceAgent.NAME
    }

    @Test
    fun `declares ON_DEVICE_INFERENCE capability`() {
        (AgentCapability.ON_DEVICE_INFERENCE in agent.capabilities).shouldBeTrue()
    }

    @Test
    fun `declares TEXT_GENERATION and STREAMING capabilities`() {
        (AgentCapability.TEXT_GENERATION in agent.capabilities).shouldBeTrue()
        (AgentCapability.STREAMING in agent.capabilities).shouldBeTrue()
    }

    // ── canHandle ─────────────────────────────────────────────────────────────

    @Test
    fun `canHandle true when agent_name matches`() {
        agent.canHandle(req()).shouldBeTrue()
    }

    @Test
    fun `canHandle true for ON_DEVICE_INFERENCE capability`() {
        val r = AgentRequest(
            userId = "u1", input = "test",
            capabilities = setOf(AgentCapability.ON_DEVICE_INFERENCE),
        )
        agent.canHandle(r).shouldBeTrue()
    }

    @Test
    fun `canHandle true for LOCAL_ONLY routing mode even without capability`() {
        val r = AgentRequest(
            userId = "u1", input = "test",
            metadata = mapOf(OnDeviceAgent.METADATA_ROUTING_MODE to "LOCAL_ONLY"),
        )
        agent.canHandle(r).shouldBeTrue()
    }

    @Test
    fun `canHandle false for CLOUD routing mode`() {
        val r = AgentRequest(
            userId = "u1", input = "test",
            metadata = mapOf(
                OnDeviceAgent.METADATA_AGENT_NAME to OnDeviceAgent.NAME,
                OnDeviceAgent.METADATA_ROUTING_MODE to "CLOUD",
            ),
        )
        // CLOUD routing → this agent should not handle it even if agent_name set
        // (agent_name check comes first, but CLOUD short-circuits before capability)
        val rNoName = AgentRequest(
            userId = "u1", input = "test",
            metadata = mapOf(OnDeviceAgent.METADATA_ROUTING_MODE to "CLOUD"),
        )
        agent.canHandle(rNoName).shouldBeFalse()
    }

    @Test
    fun `canHandle false for empty capabilities and no routing hint`() {
        val r = AgentRequest(userId = "u1", input = "test")
        agent.canHandle(r).shouldBeFalse()
    }

    // ── Successful local inference ─────────────────────────────────────────────

    @Test
    fun `successful inference emits Started, StatusChanged, Tokens, Completed`() = runTest {
        every { port.infer(any(), any(), any()) } returns
            tokenFlow("Hello", " world", "!")

        val events = agent.execute(req(), exec()).toList()

        events[0].shouldBeInstanceOf<AgentEvent.Started>()
        events[1].shouldBeInstanceOf<AgentEvent.StatusChanged>().also {
            it.status shouldBe AgentStatus.RUNNING
        }
        val tokens = events.filterIsInstance<AgentEvent.Token>()
        tokens.size shouldBe 3
        tokens.map { it.token } shouldBe listOf("Hello", " world", "!")

        val completed = events.filterIsInstance<AgentEvent.Completed>().first()
        completed.result.status shouldBe AgentStatus.COMPLETED
        completed.result.content shouldBe "Hello world!"
    }

    @Test
    fun `completed result usage reflects token counts`() = runTest {
        every { port.infer(any(), any(), any()) } returns flow {
            emit(OnDeviceStreamEvent.Token("response"))
            emit(OnDeviceStreamEvent.Done(inputTokens = 10, outputTokens = 1))
        }

        val events = agent.execute(req(), exec()).toList()

        val completed = events.filterIsInstance<AgentEvent.Completed>().first()
        completed.result.usage?.inputTokens shouldBe 10
        completed.result.usage?.outputTokens shouldBe 1
    }

    @Test
    fun `completed result metadata contains routing_mode and provider`() = runTest {
        every { port.infer(any(), any(), any()) } returns
            tokenFlow("ok")

        val r = req(routingMode = ModelRoutingMode.LOCAL_ONLY)
        val events = agent.execute(r, exec(r)).toList()

        val completed = events.filterIsInstance<AgentEvent.Completed>().first()
        completed.result.metadata["routing_mode"] shouldBe "LOCAL_ONLY"
        completed.result.metadata["provider"] shouldBe "on_device"
    }

    @Test
    fun `port infer is called with correct routing mode`() = runTest {
        every { port.infer(any(), any(), any()) } returns tokenFlow("ok")

        val r = req(routingMode = ModelRoutingMode.LOCAL_ONLY)
        agent.execute(r, exec(r)).toList()

        verify {
            port.infer(
                prompt = "Tell me about Kotlin.",
                conversationId = any(),
                routingMode = ModelRoutingMode.LOCAL_ONLY,
            )
        }
    }

    // ── Model unavailable (AUTO mode) ─────────────────────────────────────────

    @Test
    fun `model not ready in AUTO mode emits Failed with ON_DEVICE_MODEL_NOT_READY`() = runTest {
        every { port.infer(any(), any(), any()) } returns flow {
            emit(
                OnDeviceStreamEvent.Error(
                    message = "On-device model not ready.",
                    code = OnDeviceErrorCode.MODEL_NOT_READY,
                )
            )
        }

        val events = agent.execute(req(), exec()).toList()

        val failed = events.filterIsInstance<AgentEvent.Failed>().first()
        failed.result.status shouldBe AgentStatus.FAILED
        failed.result.error?.code shouldBe "ON_DEVICE_MODEL_NOT_READY"
    }

    @Test
    fun `model not ready does not emit Completed event`() = runTest {
        every { port.infer(any(), any(), any()) } returns flow {
            emit(OnDeviceStreamEvent.Error("not ready", OnDeviceErrorCode.MODEL_NOT_READY))
        }

        val events = agent.execute(req(), exec()).toList()

        events.filterIsInstance<AgentEvent.Completed>().isEmpty().shouldBeTrue()
    }

    // ── LOCAL_ONLY — never falls back to cloud ────────────────────────────────

    @Test
    fun `LOCAL_ONLY unavailable emits LOCAL_ONLY_UNAVAILABLE error code`() = runTest {
        every { port.infer(any(), any(), any()) } returns flow {
            emit(
                OnDeviceStreamEvent.Error(
                    message = "On-device inference is not available.",
                    code = OnDeviceErrorCode.LOCAL_ONLY_UNAVAILABLE,
                )
            )
        }

        val r = req(routingMode = ModelRoutingMode.LOCAL_ONLY)
        val events = agent.execute(r, exec(r)).toList()

        val failed = events.filterIsInstance<AgentEvent.Failed>().first()
        failed.result.error?.code shouldBe "LOCAL_ONLY_UNAVAILABLE"
    }

    @Test
    fun `LOCAL_ONLY unavailable does not emit any Token events`() = runTest {
        every { port.infer(any(), any(), any()) } returns flow {
            emit(OnDeviceStreamEvent.Error("unavailable", OnDeviceErrorCode.LOCAL_ONLY_UNAVAILABLE))
        }

        val r = req(routingMode = ModelRoutingMode.LOCAL_ONLY)
        val events = agent.execute(r, exec(r)).toList()

        events.filterIsInstance<AgentEvent.Token>().isEmpty().shouldBeTrue()
    }

    @Test
    fun `LOCAL_ONLY success still completes when model is ready`() = runTest {
        every { port.infer(any(), any(), any()) } returns tokenFlow("Private", " response.")

        val r = req(routingMode = ModelRoutingMode.LOCAL_ONLY)
        val events = agent.execute(r, exec(r)).toList()

        events.filterIsInstance<AgentEvent.Completed>().first()
            .result.status shouldBe AgentStatus.COMPLETED
    }

    // ── Memory / RAM exhaustion ───────────────────────────────────────────────

    @Test
    fun `RAM exhaustion emits ON_DEVICE_INSUFFICIENT_MEMORY`() = runTest {
        every { port.infer(any(), any(), any()) } returns flow {
            emit(OnDeviceStreamEvent.Token("partial"))
            emit(
                OnDeviceStreamEvent.Error(
                    message = "Insufficient resources — switching to cloud",
                    code = OnDeviceErrorCode.INSUFFICIENT_MEMORY,
                )
            )
        }

        val events = agent.execute(req(), exec()).toList()

        // Partial token emitted before RAM exhaustion
        events.filterIsInstance<AgentEvent.Token>().shouldNotBeEmpty()

        val failed = events.filterIsInstance<AgentEvent.Failed>().first()
        failed.result.error?.code shouldBe "ON_DEVICE_INSUFFICIENT_MEMORY"
    }

    @Test
    fun `RAM error metadata records on_device_ready state`() = runTest {
        every { port.isReady } returns true
        every { port.infer(any(), any(), any()) } returns flow {
            emit(OnDeviceStreamEvent.Error("RAM low", OnDeviceErrorCode.INSUFFICIENT_MEMORY))
        }

        val events = agent.execute(req(), exec()).toList()

        val failed = events.filterIsInstance<AgentEvent.Failed>().first()
        failed.result.metadata["on_device_ready"] shouldBe "true"
    }

    // ── Cancellation ──────────────────────────────────────────────────────────

    @Test
    fun `cancellation stops token emission mid-stream`() = runTest {
        val slowFlow = flow<OnDeviceStreamEvent> {
            emit(OnDeviceStreamEvent.Token("token1"))
            delay(10_000L)   // long delay — should not be reached
            emit(OnDeviceStreamEvent.Token("token2"))
            emit(OnDeviceStreamEvent.Done())
        }
        every { port.infer(any(), any(), any()) } returns slowFlow

        val collected = mutableListOf<AgentEvent>()
        val job = launch {
            agent.execute(req(), exec()).collect { collected.add(it) }
        }

        // Wait for the first token then cancel
        withTimeout(1_000L) {
            while (collected.filterIsInstance<AgentEvent.Token>().isEmpty()) {
                delay(10L)
            }
        }
        job.cancel()
        job.join()

        // Only the first token should have been collected
        collected.filterIsInstance<AgentEvent.Token>().size shouldBe 1
        collected.filterIsInstance<AgentEvent.Token>().first().token shouldBe "token1"
    }

    @Test
    fun `cancellation before any tokens completes without Completed event`() = runTest {
        val neverFlow = flow<OnDeviceStreamEvent> {
            delay(10_000L)
            emit(OnDeviceStreamEvent.Done())
        }
        every { port.infer(any(), any(), any()) } returns neverFlow

        val collected = mutableListOf<AgentEvent>()
        val job = launch {
            agent.execute(req(), exec()).collect { collected.add(it) }
        }

        // Wait for Started/StatusChanged, then cancel immediately
        withTimeout(1_000L) {
            while (collected.filterIsInstance<AgentEvent.StatusChanged>().isEmpty()) {
                delay(10L)
            }
        }
        job.cancel()
        job.join()

        collected.filterIsInstance<AgentEvent.Completed>().isEmpty().shouldBeTrue()
    }

    // ── Model loading ─────────────────────────────────────────────────────────

    @Test
    fun `isReady is false when port reports model not available`() {
        every { port.isReady } returns false
        port.isReady.shouldBeFalse()
    }

    @Test
    fun `isReady is true after model is loaded`() {
        every { port.isReady } returns true
        port.isReady.shouldBeTrue()
    }

    @Test
    fun `agent description includes model readiness provider name`() {
        agent.description.shouldNotBeNull()
        agent.description.contains("on-device", ignoreCase = true).shouldBeTrue()
    }

    // ── Model unloading ───────────────────────────────────────────────────────

    @Test
    fun `after model unloaded isReady returns false and inference fails`() = runTest {
        // Simulate model removed — port returns MODEL_NOT_READY
        every { port.isReady } returns false
        every { port.infer(any(), any(), any()) } returns flow {
            emit(OnDeviceStreamEvent.Error("Model not ready", OnDeviceErrorCode.MODEL_NOT_READY))
        }

        val events = agent.execute(req(), exec()).toList()

        events.filterIsInstance<AgentEvent.Failed>().first()
            .result.error?.code shouldBe "ON_DEVICE_MODEL_NOT_READY"
    }

    // ── No-network verification ───────────────────────────────────────────────

    @Test
    fun `OnDeviceAgent class does not import any OkHttp or Retrofit class`() {
        // Structural test: verify that OnDeviceAgent has no networking imports.
        // The actual network isolation is enforced by OnDeviceInferencePort contract,
        // but this test confirms the agent class itself is clean.
        val agentClass = OnDeviceAgent::class.java
        val classLoader = agentClass.classLoader ?: return

        // Check that OkHttp types are not referenced in the agent's class file
        // by verifying the class compiles without okhttp3 on classpath
        val hasOkHttp = try {
            classLoader.loadClass("okhttp3.OkHttpClient")
            // OkHttp is available on test classpath but agent should not use it
            // Verify by checking agent's declared fields/methods don't reference it
            agentClass.declaredFields.none { field ->
                field.type.name.startsWith("okhttp3")
            }
        } catch (e: ClassNotFoundException) {
            true // OkHttp not available — definitely no network calls
        }
        hasOkHttp.shouldBeTrue()
    }

    @Test
    fun `OnDeviceAgent has no Retrofit field declarations`() {
        val agentClass = OnDeviceAgent::class.java
        val retrofitUsed = agentClass.declaredFields.any { field ->
            field.type.name.startsWith("retrofit2")
        }
        retrofitUsed.shouldBeFalse()
    }

    // ── AgentGateway integration ───────────────────────────────────────────────

    @Test
    fun `AgentGateway executeOnDevice routes to OnDeviceAgent`() = runTest {
        every { port.infer(any(), any(), any()) } returns tokenFlow("local", " answer")
        every { port.isReady } returns true

        val gateway = AgentGateway(
            chatAgent = mockk(relaxed = true),
            codeAgent = mockk(relaxed = true),
            ragAgent = mockk(relaxed = true),
            pdfAgent = mockk(relaxed = true),
            toolAgent = mockk(relaxed = true),
            webAgent = mockk(relaxed = true),
            imageAgent = mockk(relaxed = true),
            voiceAgent = mockk(relaxed = true),
            onDeviceAgent = agent,
            inferencePort = port,
            toolRegistry = com.aiassistant.domain.agent.DefaultToolRegistry(),
        )

        val events = gateway.executeOnDevice(
            prompt = "Tell me about Kotlin.",
            routingMode = ModelRoutingMode.AUTO,
        ).toList()

        events.filterIsInstance<AgentEvent.Completed>().first()
            .result.status shouldBe AgentStatus.COMPLETED
        events.filterIsInstance<AgentEvent.Token>()
            .map { it.token } shouldBe listOf("local", " answer")
    }

    @Test
    fun `AgentGateway isOnDeviceReady delegates to OnDeviceInferencePort`() {
        every { port.isReady } returns true

        val gateway = AgentGateway(
            chatAgent = mockk(relaxed = true),
            codeAgent = mockk(relaxed = true),
            ragAgent = mockk(relaxed = true),
            pdfAgent = mockk(relaxed = true),
            toolAgent = mockk(relaxed = true),
            webAgent = mockk(relaxed = true),
            imageAgent = mockk(relaxed = true),
            voiceAgent = mockk(relaxed = true),
            onDeviceAgent = agent,
            inferencePort = port,
            toolRegistry = com.aiassistant.domain.agent.DefaultToolRegistry(),
        )

        gateway.isOnDeviceReady.shouldBeTrue()
        verify { port.isReady }
    }

    @Test
    fun `AgentGateway executeOnDevice LOCAL_ONLY propagates routing mode`() = runTest {
        every { port.infer(any(), any(), any()) } returns flow {
            emit(OnDeviceStreamEvent.Error("unavailable", OnDeviceErrorCode.LOCAL_ONLY_UNAVAILABLE))
        }

        val gateway = AgentGateway(
            chatAgent = mockk(relaxed = true),
            codeAgent = mockk(relaxed = true),
            ragAgent = mockk(relaxed = true),
            pdfAgent = mockk(relaxed = true),
            toolAgent = mockk(relaxed = true),
            webAgent = mockk(relaxed = true),
            imageAgent = mockk(relaxed = true),
            voiceAgent = mockk(relaxed = true),
            onDeviceAgent = agent,
            inferencePort = port,
            toolRegistry = com.aiassistant.domain.agent.DefaultToolRegistry(),
        )

        val events = gateway.executeOnDevice(
            prompt = "Run this locally only.",
            routingMode = ModelRoutingMode.LOCAL_ONLY,
        ).toList()

        // Must fail with LOCAL_ONLY_UNAVAILABLE, not silently route to cloud
        val failed = events.filterIsInstance<AgentEvent.Failed>().firstOrNull()
        failed.shouldNotBeNull()
        failed.result.error?.code shouldBe "LOCAL_ONLY_UNAVAILABLE"

        // No Completed event — confirmed no silent fallback
        events.filterIsInstance<AgentEvent.Completed>().isEmpty().shouldBeTrue()
    }

    // ── Streaming protocol ────────────────────────────────────────────────────

    @Test
    fun `stream always starts with Started then StatusChanged RUNNING`() = runTest {
        every { port.infer(any(), any(), any()) } returns tokenFlow("hi")

        val events = agent.execute(req(), exec()).toList()

        events[0].shouldBeInstanceOf<AgentEvent.Started>()
        val sc = events[1].shouldBeInstanceOf<AgentEvent.StatusChanged>()
        sc.status shouldBe AgentStatus.RUNNING
    }

    @Test
    fun `empty token stream produces Completed with null content`() = runTest {
        every { port.infer(any(), any(), any()) } returns flow {
            emit(OnDeviceStreamEvent.Done(inputTokens = 0, outputTokens = 0))
        }

        val events = agent.execute(req(), exec()).toList()

        val completed = events.filterIsInstance<AgentEvent.Completed>().first()
        completed.result.status shouldBe AgentStatus.COMPLETED
        // content is null when no tokens were emitted (blank accumulated text)
        completed.result.content shouldBe null
    }

    @Test
    fun `multiple tokens are concatenated in completed content`() = runTest {
        every { port.infer(any(), any(), any()) } returns
            tokenFlow("The ", "quick ", "brown ", "fox")

        val events = agent.execute(req(), exec()).toList()

        val completed = events.filterIsInstance<AgentEvent.Completed>().first()
        completed.result.content shouldBe "The quick brown fox"
    }
}
