/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentGatewayMediaExtension.kt
 * Purpose    : Domain interfaces for Phase 6 Web/Image/Voice agent
 *              entry points, keeping feature modules off :data.
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.coroutines.flow.Flow

/**
 * Gateway interface for web search operations (Phase 6).
 * Implemented by [com.aiassistant.data.agent.AgentGateway].
 */
interface AgentGatewayWebExtension {

    /**
     * Execute a web search via [WebAgent].
     *
     * @param query      Search query string.
     * @param maxResults Max results to return (1–20, default 5).
     * @param context    Optional [AgentContext].
     */
    fun executeWebSearch(
        query: String,
        maxResults: Int = 5,
        context: AgentContext? = null,
    ): Flow<AgentEvent>
}

/**
 * Gateway interface for image and voice operations (Phase 6).
 * Implemented by [com.aiassistant.data.agent.AgentGateway].
 */
interface AgentGatewayMediaExtension {

    /**
     * Execute image analysis via [ImageAgent].
     *
     * @param action       "ocr" | "vision" (default "ocr").
     * @param imageBase64  Base64-encoded image bytes.
     * @param imageUri     Android content:// or file:// URI.
     * @param prompt       Vision Q&A question (for "vision" action).
     * @param provider     LLM provider for vision analysis.
     * @param imageWidth   Image width in px for bounding-box normalisation.
     * @param imageHeight  Image height in px.
     * @param context      Optional [AgentContext].
     */
    fun executeImageAnalysis(
        action: String = "ocr",
        imageBase64: String? = null,
        imageUri: String? = null,
        prompt: String? = null,
        provider: String? = null,
        imageWidth: Int? = null,
        imageHeight: Int? = null,
        context: AgentContext? = null,
    ): Flow<AgentEvent>

    /**
     * Execute a voice interaction via [VoiceAgent].
     *
     * @param action         "listen_and_respond" | "speak_only" | "listen_only".
     * @param conversationId Conversation UUID for the LLM step.
     * @param provider       LLM provider.
     * @param language       BCP-47 language tag (default "en-US").
     * @param textToSpeak    Literal text for "speak_only" action.
     * @param context        Optional [AgentContext].
     */
    fun executeVoice(
        action: String = "listen_and_respond",
        conversationId: String? = null,
        provider: String = "gemini",
        language: String = "en-US",
        textToSpeak: String? = null,
        context: AgentContext? = null,
    ): Flow<AgentEvent>
}
