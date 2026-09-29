/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentMode.kt
 * Purpose    : Caller-facing agent mode selection — drives routing hints
 *              to the AgentOrchestrator without exposing internal agent names.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Enum + strategy mapper
 *
 * Key Concepts:
 *   - Pure Kotlin, zero Android/framework dependencies
 *   - AgentMode is a UX-level concept; it maps to capabilities and routing hints
 *   - AUTO delegates routing entirely to DefaultAgentOrchestrator
 *   - Explicit modes add metadata hints so the orchestrator can route efficiently
 *     without changing any agent logic
 *   - Screens MUST NOT call LLM providers directly — they pass an AgentMode to
 *     the ViewModel which passes it to AgentGateway via metadata
 *
 * Phase 9 requirement:
 *   "Auto should use the orchestrator. Explicit modes should provide a routing hint."
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.serialization.Serializable

/**
 * The agent mode selected by the user or inferred from context.
 *
 * Feature modules set the mode on `AgentRequest.metadata[AgentMode.METADATA_KEY]`.
 * The AgentGateway reads this and adds capability hints + agent name hints so the
 * orchestrator routes efficiently.
 *
 * ## Routing behaviour
 * | Mode       | Routing strategy                                            |
 * |------------|-------------------------------------------------------------|
 * | AUTO       | Orchestrator decides; no capability constraint added        |
 * | CHAT       | Routes to `ChatAgent` (conversational)                     |
 * | CODE       | Routes to `CodeAgent` (code analysis / generation)         |
 * | RESEARCH   | Routes to `WebAgent` (web search + citations)              |
 * | DOCUMENT   | Routes to `RagAgent` (document retrieval / PDF)            |
 * | IMAGE      | Routes to `ImageAgent` (vision OCR / analysis)             |
 * | VOICE      | Routes to `VoiceAgent` (STT → LLM → TTS pipeline)         |
 * | LOCAL      | Routes to `OnDeviceAgent` with `LOCAL_ONLY` routing mode   |
 */
@Serializable
enum class AgentMode {

    /**
     * Let the orchestrator decide the best agent based on content and capabilities.
     * No explicit capability constraint or agent name hint is added.
     */
    AUTO,

    /**
     * Conversational chat. Routes to the `ChatAgent` (WebSocket streaming path).
     * Best for open-ended questions, brainstorming, and follow-up conversation.
     */
    CHAT,

    /**
     * Code analysis, debugging, and generation.
     * Routes to `CodeAgent`. Sets [AgentCapability.CODE_ANALYSIS] capability hint.
     */
    CODE,

    /**
     * Web research with cited sources.
     * Routes to `WebAgent`. Sets [AgentCapability.SEMANTIC_SEARCH] capability hint.
     * Requires a configured [WebSearchProvider] — falls back to `ChatAgent` when not ready.
     */
    RESEARCH,

    /**
     * Document retrieval and PDF Q&A (RAG pipeline).
     * Routes to `RagAgent` / `PdfAgent`. Sets [AgentCapability.DOCUMENT_RETRIEVAL].
     */
    DOCUMENT,

    /**
     * Image analysis — OCR, barcode, vision Q&A.
     * Routes to `ImageAgent`. Sets [AgentCapability.IMAGE_UNDERSTANDING].
     */
    IMAGE,

    /**
     * Voice interaction — Speech-to-Text → LLM → Text-to-Speech.
     * Routes to `VoiceAgent`. Sets [AgentCapability.SPEECH_TO_TEXT].
     */
    VOICE,

    /**
     * Private on-device inference. Equivalent to [ModelRoutingMode.LOCAL_ONLY].
     * Routes to `OnDeviceAgent` with `routing_mode = LOCAL_ONLY`.
     * Emits a clear error if the on-device model is not downloaded.
     */
    LOCAL,
    ;

    companion object {
        /** Metadata key used in [AgentRequest.metadata] to carry the mode. */
        const val METADATA_KEY: String = "agent_mode"

        /** Returns the [AgentMode] whose name matches [value] (case-insensitive), or [AUTO]. */
        fun fromName(value: String?): AgentMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AUTO

        /**
         * Returns the [AgentCapability] set that best matches [mode].
         * An empty set means no capability constraint — the orchestrator will route freely.
         */
        fun toCapabilities(mode: AgentMode): Set<AgentCapability> = when (mode) {
            AUTO  -> emptySet()
            CHAT  -> emptySet()                                         // ChatAgent: no capability constraint
            CODE  -> setOf(AgentCapability.CODE_ANALYSIS)
            RESEARCH -> setOf(AgentCapability.SEMANTIC_SEARCH)
            DOCUMENT -> setOf(AgentCapability.DOCUMENT_RETRIEVAL)
            IMAGE -> setOf(AgentCapability.IMAGE_UNDERSTANDING)
            VOICE -> setOf(AgentCapability.SPEECH_TO_TEXT)
            LOCAL -> setOf(AgentCapability.ON_DEVICE_INFERENCE)
        }

        /**
         * Returns the explicit agent name hint for [mode], or null when the orchestrator
         * should determine the target agent automatically.
         */
        fun toAgentNameHint(mode: AgentMode): String? = when (mode) {
            AUTO  -> null
            CHAT  -> "conversational"
            CODE  -> "code-analysis"
            RESEARCH -> "web-search"
            DOCUMENT -> "rag"
            IMAGE -> "image-analysis"
            VOICE -> "voice"
            LOCAL -> "on_device"
        }

        /**
         * Human-readable label shown in the mode selector UI.
         */
        fun displayName(mode: AgentMode): String = when (mode) {
            AUTO     -> "Auto"
            CHAT     -> "Chat"
            CODE     -> "Code"
            RESEARCH -> "Research"
            DOCUMENT -> "Document"
            IMAGE    -> "Image"
            VOICE    -> "Voice"
            LOCAL    -> "Local"
        }
    }
}
