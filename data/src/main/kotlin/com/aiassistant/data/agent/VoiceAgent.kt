/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : VoiceAgent.kt
 * Purpose    : Agent that orchestrates the STT→LLM→TTS pipeline.
 *              Reuses existing SpeechToTextProvider / TextToSpeechProvider
 *              contracts — the Android VoiceAssistantManager is one
 *              possible implementation of those contracts but is NOT
 *              injected here (it stays composable-owned by design).
 *
 * Architecture Layer : Data — agent sub-package
 * Pattern Used       : Adapter (implements Agent from domain)
 *
 * Key Concepts:
 *   - Provider-independent: STT and TTS are injected interfaces
 *   - STT flow: listen() → collect SttEvent.Partial (emit Thinking),
 *     SttEvent.Final (emit Token with transcript), SttEvent.Error
 *   - LLM step: delegates to AgentGatewayRepository.executeChat()
 *     using the transcript as the message content
 *   - TTS step: speak(llm_response) → collect TtsEvent.Done
 *   - Cancellation propagates through the entire pipeline naturally
 *
 * Request metadata keys:
 *   "voice_action"     — "listen_and_respond" | "speak_only" | "listen_only"
 *   "conversation_id"  — conversation UUID for the LLM step
 *   "provider"         — LLM provider
 *   "language"         — BCP-47 language tag (default "en-US")
 *   "text_to_speak"    — literal text for "speak_only" action
 *
 * Streaming protocol:
 *   Started → STT: Thinking(partial) → Token(transcript)
 *   → LLM: Token(llm_chunk) × N → TTS: Completed
 * ============================================================
 */

package com.aiassistant.data.agent

import com.aiassistant.domain.agent.Agent
import com.aiassistant.domain.agent.AgentCapability
import com.aiassistant.domain.agent.AgentError
import com.aiassistant.domain.agent.AgentEvent
import com.aiassistant.domain.agent.AgentExecution
import com.aiassistant.domain.agent.AgentGatewayRepository
import com.aiassistant.domain.agent.AgentRequest
import com.aiassistant.domain.agent.AgentResult
import com.aiassistant.domain.agent.AgentStatus
import com.aiassistant.domain.agent.SpeechToTextProvider
import com.aiassistant.domain.agent.SttEvent
import com.aiassistant.domain.agent.TextToSpeechProvider
import com.aiassistant.domain.agent.TtsEvent
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import timber.log.Timber

@Singleton
class VoiceAgent @Inject constructor(
    private val sttProvider: SpeechToTextProvider,
    private val ttsProvider: TextToSpeechProvider,
    private val chatGateway: Lazy<AgentGatewayRepository>,
) : Agent {

    override val name: String = NAME

    override val description: String =
        "Voice assistant agent: Speech-to-Text → LLM response → Text-to-Speech pipeline."

    override val capabilities: Set<AgentCapability> = setOf(
        AgentCapability.SPEECH_TO_TEXT,
        AgentCapability.TEXT_GENERATION,
        AgentCapability.TEXT_TO_SPEECH,
        AgentCapability.STREAMING,
    )

    override fun canHandle(request: AgentRequest): Boolean {
        val explicit = request.metadata[METADATA_AGENT_NAME]
        if (!explicit.isNullOrBlank()) return explicit == name
        return request.capabilities.isEmpty() ||
            AgentCapability.SPEECH_TO_TEXT in request.capabilities
    }

    override fun execute(
        request: AgentRequest,
        execution: AgentExecution,
    ): Flow<AgentEvent> = flow {

        emit(AgentEvent.Started(execution.executionId, name))
        emit(AgentEvent.StatusChanged(execution.executionId, AgentStatus.RUNNING))

        val action = request.metadata[METADATA_VOICE_ACTION]?.trim()?.lowercase()
            ?: "listen_and_respond"
        val conversationId = request.conversationId
            ?: request.metadata[METADATA_CONVERSATION_ID]
            ?: ""
        val provider = request.metadata[METADATA_PROVIDER]?.trim() ?: "gemini"
        val language = request.metadata[METADATA_LANGUAGE]?.trim() ?: "en-US"

        when (action) {
            "speak_only" -> handleSpeakOnly(request, execution, language, this)
            "listen_only" -> handleListenOnly(request, execution, language, this)
            "listen_and_respond" -> handleListenAndRespond(
                request, execution, conversationId, provider, language, this
            )
            else -> emit(failed(execution, request, "UNKNOWN_ACTION",
                "Unknown voice_action '$action'. Use: listen_and_respond, speak_only, listen_only."))
        }
    }

    // ── listen_and_respond ────────────────────────────────────────────────────

    private suspend fun handleListenAndRespond(
        request: AgentRequest,
        execution: AgentExecution,
        conversationId: String,
        provider: String,
        language: String,
        collector: kotlinx.coroutines.flow.FlowCollector<AgentEvent>,
    ) {
        if (!sttProvider.isAvailable) {
            collector.emit(failed(execution, request, "STT_UNAVAILABLE",
                "Speech recognition is not available or microphone permission was denied."))
            return
        }

        // ── STT phase ────────────────────────────────────────────────────────
        emit(collector, AgentEvent.Thinking(0, "Listening…"))

        var transcript = ""
        var sttError: SttEvent.Error? = null

        sttProvider.listen(language).collect { event ->
            when (event) {
                is SttEvent.Partial -> collector.emit(
                    AgentEvent.Thinking(0, "Heard: ${event.text}")
                )
                is SttEvent.Final -> {
                    transcript = event.text
                    collector.emit(AgentEvent.Token("[You said]: ${event.text}"))
                }
                is SttEvent.Error -> sttError = event
                is SttEvent.EndOfSpeech -> { /* flow completes */ }
            }
        }

        if (sttError != null) {
            collector.emit(failed(execution, request, "STT_ERROR",
                sttError!!.message.ifBlank { "Speech recognition error (code ${sttError!!.code})." }))
            return
        }

        if (transcript.isBlank()) {
            collector.emit(failed(execution, request, "NO_TRANSCRIPT",
                "No speech was detected. Please try again."))
            return
        }

        // ── LLM phase ─────────────────────────────────────────────────────────
        emit(collector, AgentEvent.Thinking(1, "Processing…"))

        var llmResponse = ""
        var llmFailed = false

        chatGateway.get().executeChat(
            conversationId = conversationId,
            content = transcript,
            provider = provider,
        ).collect { event ->
            when (event) {
                is AgentEvent.Token -> {
                    llmResponse += event.token
                    collector.emit(event)
                }
                is AgentEvent.Failed -> {
                    llmFailed = true
                    collector.emit(event)
                }
                else -> collector.emit(event)
            }
        }

        if (llmFailed || llmResponse.isBlank()) return

        // ── TTS phase ─────────────────────────────────────────────────────────
        if (ttsProvider.isAvailable) {
            emit(collector, AgentEvent.Thinking(2, "Speaking…"))
            ttsProvider.speak(llmResponse, language).collect { ttsEvent ->
                when (ttsEvent) {
                    is TtsEvent.Done -> { /* continue to Completed */ }
                    is TtsEvent.Error -> Timber.w("VoiceAgent TTS error: ${ttsEvent.message}")
                    is TtsEvent.Started -> { /* no-op */ }
                }
            }
        }

        collector.emit(
            AgentEvent.Completed(
                AgentResult(
                    executionId = execution.executionId,
                    requestId = request.requestId,
                    agentName = name,
                    status = AgentStatus.COMPLETED,
                    content = llmResponse,
                    metadata = mapOf(
                        "transcript" to transcript,
                        "action" to "listen_and_respond",
                        "provider" to provider,
                    ),
                )
            )
        )
    }

    // ── speak_only ────────────────────────────────────────────────────────────

    private suspend fun handleSpeakOnly(
        request: AgentRequest,
        execution: AgentExecution,
        language: String,
        collector: kotlinx.coroutines.flow.FlowCollector<AgentEvent>,
    ) {
        val text = request.metadata[METADATA_TEXT_TO_SPEAK]?.trim()
            ?: request.input.trim()

        if (text.isBlank()) {
            collector.emit(failed(execution, request, "BLANK_TEXT", "No text to speak."))
            return
        }

        if (!ttsProvider.isAvailable) {
            collector.emit(failed(execution, request, "TTS_UNAVAILABLE",
                "Text-to-speech is not available."))
            return
        }

        collector.emit(AgentEvent.Token(text))
        ttsProvider.speak(text, language).toList()

        collector.emit(
            AgentEvent.Completed(
                AgentResult(
                    executionId = execution.executionId,
                    requestId = request.requestId,
                    agentName = name,
                    status = AgentStatus.COMPLETED,
                    content = text,
                    metadata = mapOf("action" to "speak_only"),
                )
            )
        )
    }

    // ── listen_only ───────────────────────────────────────────────────────────

    private suspend fun handleListenOnly(
        request: AgentRequest,
        execution: AgentExecution,
        language: String,
        collector: kotlinx.coroutines.flow.FlowCollector<AgentEvent>,
    ) {
        if (!sttProvider.isAvailable) {
            collector.emit(failed(execution, request, "STT_UNAVAILABLE",
                "Speech recognition is not available."))
            return
        }

        var transcript = ""
        sttProvider.listen(language).collect { event ->
            when (event) {
                is SttEvent.Partial -> collector.emit(AgentEvent.Thinking(0, event.text))
                is SttEvent.Final -> {
                    transcript = event.text
                    collector.emit(AgentEvent.Token(event.text))
                }
                is SttEvent.Error -> {
                    collector.emit(failed(execution, request, "STT_ERROR", event.message))
                    return@collect
                }
                is SttEvent.EndOfSpeech -> {}
            }
        }

        if (transcript.isNotBlank()) {
            collector.emit(
                AgentEvent.Completed(
                    AgentResult(
                        executionId = execution.executionId,
                        requestId = request.requestId,
                        agentName = name,
                        status = AgentStatus.COMPLETED,
                        content = transcript,
                        metadata = mapOf("action" to "listen_only"),
                    )
                )
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun emit(
        collector: kotlinx.coroutines.flow.FlowCollector<AgentEvent>,
        event: AgentEvent,
    ) = collector.emit(event)

    private fun failed(
        execution: AgentExecution,
        request: AgentRequest,
        code: String,
        message: String,
    ) = AgentEvent.Failed(
        AgentResult(
            executionId = execution.executionId,
            requestId = request.requestId,
            agentName = name,
            status = AgentStatus.FAILED,
            error = AgentError(code = code, message = message),
        )
    )

    companion object {
        const val NAME = "voice"
        const val METADATA_AGENT_NAME = "agent_name"
        const val METADATA_VOICE_ACTION = "voice_action"
        const val METADATA_CONVERSATION_ID = "conversation_id"
        const val METADATA_PROVIDER = "provider"
        const val METADATA_LANGUAGE = "language"
        const val METADATA_TEXT_TO_SPEAK = "text_to_speak"
    }
}
