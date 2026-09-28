/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : VoiceAgentTest.kt
 * Purpose    : Unit tests for VoiceAgent — STT→LLM→TTS pipeline,
 *              speak_only, listen_only, error paths.
 *
 * Architecture Layer : Data — agent sub-package (test)
 * Pattern Used       : JUnit4 + MockK + Kotest
 * ============================================================
 */
package com.aiassistant.data.agent

import com.aiassistant.domain.agent.AgentCapability
import com.aiassistant.domain.agent.AgentEvent
import com.aiassistant.domain.agent.AgentExecution
import com.aiassistant.domain.agent.AgentGatewayRepository
import com.aiassistant.domain.agent.AgentRequest
import com.aiassistant.domain.agent.AgentStatus
import com.aiassistant.domain.agent.SpeechToTextProvider
import com.aiassistant.domain.agent.SttEvent
import com.aiassistant.domain.agent.TextToSpeechProvider
import com.aiassistant.domain.agent.TtsEvent
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class VoiceAgentTest {

    private lateinit var sttProvider: SpeechToTextProvider
    private lateinit var ttsProvider: TextToSpeechProvider
    private lateinit var chatGateway: AgentGatewayRepository
    private lateinit var agent: VoiceAgent

    @Before
    fun setUp() {
        sttProvider = mockk(relaxed = true)
        ttsProvider = mockk(relaxed = true)
        chatGateway = mockk(relaxed = true)
        agent = VoiceAgent(sttProvider, ttsProvider, chatGateway)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun req(
        action: String = "listen_and_respond",
        conversationId: String? = "conv-1",
        textToSpeak: String? = null,
        input: String = "test",
    ) = AgentRequest(
        userId = "user-1",
        input = input,
        conversationId = conversationId,
        metadata = buildMap {
            put(VoiceAgent.METADATA_AGENT_NAME, VoiceAgent.NAME)
            put(VoiceAgent.METADATA_VOICE_ACTION, action)
            put(VoiceAgent.METADATA_PROVIDER, "gemini")
            put(VoiceAgent.METADATA_LANGUAGE, "en-US")
            textToSpeak?.let { put(VoiceAgent.METADATA_TEXT_TO_SPEAK, it) }
        },
    )

    private fun exec(r: AgentRequest = req()) =
        AgentExecution(request = r, agentName = VoiceAgent.NAME)

    // ── Metadata / capabilities ───────────────────────────────────────────────

    @Test
    fun `agent name is voice`() {
        agent.name shouldBe VoiceAgent.NAME
    }

    @Test
    fun `declares SPEECH_TO_TEXT and TEXT_TO_SPEECH capabilities`() {
        (AgentCapability.SPEECH_TO_TEXT in agent.capabilities).shouldBeTrue()
        (AgentCapability.TEXT_TO_SPEECH in agent.capabilities).shouldBeTrue()
    }

    @Test
    fun `canHandle returns true when agent_name matches`() {
        agent.canHandle(req()).shouldBeTrue()
    }

    @Test
    fun `canHandle returns true for SPEECH_TO_TEXT capability`() {
        val r = AgentRequest(
            userId = "u1", input = "test",
            capabilities = setOf(AgentCapability.SPEECH_TO_TEXT),
        )
        agent.canHandle(r).shouldBeTrue()
    }

    // ── speak_only ────────────────────────────────────────────────────────────

    @Test
    fun `speak_only emits Token with text then Completed`() = runTest {
        every { ttsProvider.isAvailable } returns true
        every { ttsProvider.speak(any(), any()) } returns flowOf(TtsEvent.Done)

        val r = req(action = "speak_only", textToSpeak = "Hello there")
        val events = agent.execute(r, exec(r)).toList()

        events[0].shouldBeInstanceOf<AgentEvent.Started>()
        val token = events.filterIsInstance<AgentEvent.Token>().first()
        token.token shouldBe "Hello there"
        events.filterIsInstance<AgentEvent.Completed>().first()
            .result.status shouldBe AgentStatus.COMPLETED
    }

    @Test
    fun `speak_only blank text emits BLANK_TEXT`() = runTest {
        // AgentRequest validates input.isNotBlank() so we pass a valid input,
        // but explicitly set text_to_speak to empty in metadata so VoiceAgent sees blank text
        val r = AgentRequest(
            userId = "u1", input = "placeholder",
            metadata = mapOf(
                VoiceAgent.METADATA_VOICE_ACTION to "speak_only",
                VoiceAgent.METADATA_AGENT_NAME to VoiceAgent.NAME,
                VoiceAgent.METADATA_TEXT_TO_SPEAK to "   ",
            ),
        )
        val events = agent.execute(r, exec(r)).toList()

        events.filterIsInstance<AgentEvent.Failed>().first()
            .result.error?.code shouldBe "BLANK_TEXT"
    }

    @Test
    fun `speak_only uses request input when text_to_speak not in metadata`() = runTest {
        every { ttsProvider.isAvailable } returns true
        every { ttsProvider.speak(any(), any()) } returns flowOf(TtsEvent.Done)

        val r = AgentRequest(
            userId = "u1", input = "Speak this",
            metadata = mapOf(VoiceAgent.METADATA_VOICE_ACTION to "speak_only"),
        )
        val events = agent.execute(r, exec(r)).toList()

        val token = events.filterIsInstance<AgentEvent.Token>().first()
        token.token shouldBe "Speak this"
    }

    // ── listen_only ───────────────────────────────────────────────────────────

    @Test
    fun `listen_only emits STT Thinking events and Token with final transcript`() = runTest {
        every { sttProvider.isAvailable } returns true
        every { sttProvider.listen(any()) } returns flowOf(
            SttEvent.Partial("Hel"),
            SttEvent.Partial("Hello"),
            SttEvent.Final("Hello world"),
            SttEvent.EndOfSpeech,
        )

        val r = req(action = "listen_only")
        val events = agent.execute(r, exec(r)).toList()

        events.filterIsInstance<AgentEvent.Thinking>().shouldNotBeEmpty()
        val tokens = events.filterIsInstance<AgentEvent.Token>()
        tokens.any { it.token == "Hello world" }.shouldBeTrue()
        events.filterIsInstance<AgentEvent.Completed>().first()
            .result.status shouldBe AgentStatus.COMPLETED
    }

    @Test
    fun `listen_only STT unavailable emits STT_UNAVAILABLE`() = runTest {
        every { sttProvider.isAvailable } returns false

        val r = req(action = "listen_only")
        val events = agent.execute(r, exec(r)).toList()

        events.filterIsInstance<AgentEvent.Failed>().first()
            .result.error?.code shouldBe "STT_UNAVAILABLE"
    }

    @Test
    fun `listen_only STT error emits STT_ERROR`() = runTest {
        every { sttProvider.isAvailable } returns true
        every { sttProvider.listen(any()) } returns flowOf(
            SttEvent.Error(code = 7, message = "No speech detected")
        )

        val r = req(action = "listen_only")
        val events = agent.execute(r, exec(r)).toList()

        events.filterIsInstance<AgentEvent.Failed>().first()
            .result.error?.code shouldBe "STT_ERROR"
    }

    // ── listen_and_respond ────────────────────────────────────────────────────

    @Test
    fun `listen_and_respond flows STT → LLM token → TTS → Completed`() = runTest {
        every { sttProvider.isAvailable } returns true
        every { sttProvider.listen(any()) } returns flowOf(
            SttEvent.Final("What is Kotlin?"),
            SttEvent.EndOfSpeech,
        )
        every { chatGateway.executeChat(any(), any(), any(), any()) } returns flowOf(
            AgentEvent.Token("Kotlin is a modern JVM language."),
            AgentEvent.Completed(
                com.aiassistant.domain.agent.AgentResult(
                    executionId = "e1", requestId = "r1", agentName = "conversational",
                    status = AgentStatus.COMPLETED,
                    content = "Kotlin is a modern JVM language.",
                )
            )
        )
        every { ttsProvider.isAvailable } returns true
        every { ttsProvider.speak(any(), any()) } returns flowOf(TtsEvent.Done)

        val r = req(action = "listen_and_respond")
        val events = agent.execute(r, exec(r)).toList()

        // Transcript token emitted
        events.filterIsInstance<AgentEvent.Token>()
            .any { it.token.contains("What is Kotlin?") }.shouldBeTrue()
        // LLM token emitted
        events.filterIsInstance<AgentEvent.Token>()
            .any { it.token.contains("Kotlin is a modern JVM language") }.shouldBeTrue()
        // Final Completed from VoiceAgent
        events.filterIsInstance<AgentEvent.Completed>().last()
            .result.status shouldBe AgentStatus.COMPLETED
    }

    @Test
    fun `listen_and_respond STT unavailable emits STT_UNAVAILABLE`() = runTest {
        every { sttProvider.isAvailable } returns false

        val r = req(action = "listen_and_respond")
        val events = agent.execute(r, exec(r)).toList()

        events.filterIsInstance<AgentEvent.Failed>().first()
            .result.error?.code shouldBe "STT_UNAVAILABLE"
    }

    @Test
    fun `listen_and_respond empty transcript emits NO_TRANSCRIPT`() = runTest {
        every { sttProvider.isAvailable } returns true
        every { sttProvider.listen(any()) } returns flowOf(SttEvent.EndOfSpeech)

        val r = req(action = "listen_and_respond")
        val events = agent.execute(r, exec(r)).toList()

        events.filterIsInstance<AgentEvent.Failed>().first()
            .result.error?.code shouldBe "NO_TRANSCRIPT"
    }

    @Test
    fun `listen_and_respond skips TTS when ttsProvider unavailable`() = runTest {
        every { sttProvider.isAvailable } returns true
        every { sttProvider.listen(any()) } returns flowOf(
            SttEvent.Final("Hello"),
            SttEvent.EndOfSpeech,
        )
        every { chatGateway.executeChat(any(), any(), any(), any()) } returns flowOf(
            AgentEvent.Token("Hi there."),
            AgentEvent.Completed(
                com.aiassistant.domain.agent.AgentResult(
                    executionId = "e1", requestId = "r1", agentName = "conversational",
                    status = AgentStatus.COMPLETED, content = "Hi there.",
                )
            )
        )
        every { ttsProvider.isAvailable } returns false

        val r = req(action = "listen_and_respond")
        val events = agent.execute(r, exec(r)).toList()

        // Still completes even without TTS
        events.filterIsInstance<AgentEvent.Completed>().last()
            .result.status shouldBe AgentStatus.COMPLETED
    }

    // ── Unknown action ────────────────────────────────────────────────────────

    @Test
    fun `unknown voice_action emits UNKNOWN_ACTION`() = runTest {
        val r = AgentRequest(
            userId = "u1", input = "test",
            metadata = mapOf(VoiceAgent.METADATA_VOICE_ACTION to "sing"),
        )
        val events = agent.execute(r, exec(r)).toList()

        events.filterIsInstance<AgentEvent.Failed>().first()
            .result.error?.code shouldBe "UNKNOWN_ACTION"
    }

    // ── Completed result metadata ─────────────────────────────────────────────

    @Test
    fun `speak_only completed result has action in metadata`() = runTest {
        every { ttsProvider.isAvailable } returns true
        every { ttsProvider.speak(any(), any()) } returns flowOf(TtsEvent.Done)

        val r = req(action = "speak_only", textToSpeak = "Test")
        val events = agent.execute(r, exec(r)).toList()

        val completed = events.filterIsInstance<AgentEvent.Completed>().first()
        completed.result.metadata["action"] shouldBe "speak_only"
    }
}
