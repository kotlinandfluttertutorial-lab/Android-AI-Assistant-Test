/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentCapability.kt
 * Purpose    : Typed set of capabilities an Agent declares it can handle.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Enum + value type
 *
 * Key Concepts:
 *   - Pure Kotlin, zero Android/framework dependencies
 *   - Used by Agent.canHandle() to determine request routing
 *   - Serialisable so capability sets can be stored and transported
 *
 * Design Decision:
 *   Enum rather than a String tag so that `Agent.capabilities` is a typed
 *   Set<AgentCapability> that the compiler can check exhaustively.  New
 *   capabilities are added here — no scattered string constants needed.
 *
 * Dependencies: kotlinx-serialization-json
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.serialization.Serializable

/**
 * Enumerates every distinct capability that an [Agent] implementation may declare.
 *
 * An agent advertises its capabilities via [Agent.capabilities].  The router
 * queries [Agent.canHandle] (which typically checks whether the request requires
 * a subset of the agent's capabilities) to select the right agent for a given
 * [AgentRequest].
 *
 * Capabilities are not mutually exclusive — a single agent may declare many.
 */
@Serializable
enum class AgentCapability {

    /** Can respond to free-text conversational queries using an LLM. */
    TEXT_GENERATION,

    /** Can stream tokens incrementally to the caller rather than returning a bulk reply. */
    STREAMING,

    /** Can retrieve and cite information from a document corpus (RAG). */
    DOCUMENT_RETRIEVAL,

    /** Can analyse, explain, debug, or generate source code in supported languages. */
    CODE_ANALYSIS,

    /** Can transcribe spoken audio to text. */
    SPEECH_TO_TEXT,

    /** Can synthesise text to spoken audio. */
    TEXT_TO_SPEECH,

    /** Can interpret image content (OCR, object recognition, visual Q&A). */
    IMAGE_UNDERSTANDING,

    /** Can invoke registered Model Context Protocol (MCP) tools on behalf of the user. */
    TOOL_USE,

    /** Can maintain and inject long-term user memories across sessions. */
    MEMORY_ACCESS,

    /** Can run entirely on-device with zero network calls. */
    ON_DEVICE_INFERENCE,

    /** Can perform semantic search across the user's content corpus. */
    SEMANTIC_SEARCH,

    /** Can reason over multiple steps, using tool outputs to guide subsequent actions. */
    MULTI_STEP_REASONING,

    /** Can translate text between languages. */
    TRANSLATION,

    /** Can generate structured documents such as résumés, emails, or meeting summaries. */
    DOCUMENT_GENERATION,

    /** Can interact with productivity items: todos, reminders, calendar events, habits. */
    PRODUCTIVITY_MANAGEMENT,
}
