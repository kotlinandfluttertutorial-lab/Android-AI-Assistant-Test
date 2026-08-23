/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai
 * File       : StreamEvent.kt
 * Purpose    : StreamEvent — core-ai module component
 *
 * Architecture Layer : Core-AI
 * Pattern Used       : Kotlin Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai
 * File       : StreamEvent.kt
 * Purpose    : StreamEvent — core-ai module component
 *
 * Architecture Layer : Core-AI
 * Pattern Used       : Kotlin Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
/**
 * StreamEvent.kt
 *
 * Purpose: Sealed class hierarchy representing every structured event emitted by the
 *          backend WebSocket streaming endpoint at /ws/chat/{conversation_id}.
 * Architecture: core-ai â€” WebSocket streaming layer. No Android framework dependencies.
 * Dependencies: kotlinx.serialization (JsonObject)
 *
 * Design decisions:
 * - Sealed class enables exhaustive `when` expressions at every consumer site.
 * - JsonObject is used for `toolInput` to remain schema-agnostic; individual MCP tools
 *   deserialise into their own strongly-typed payloads downstream.
 * - All subclasses are `data class` for structural equality in tests.
 *
 * Backend event protocol (Requirement 26.5):
 *   {"type":"token",     "data":"..."}
 *   {"type":"done",      "usage":{"inputTokens":N,"outputTokens":N}}
 *   {"type":"error",     "message":"..."}
 *   {"type":"tool_call", "toolName":"...","toolInput":{...}}
 */

package com.aiassistant.core.ai

import kotlinx.serialization.json.JsonObject

/**
 * Discriminated union of all events the backend WebSocket can emit during a chat stream.
 *
 * Consumers collect a `Flow<StreamEvent>` from [AIStreamClient.connect] and handle each
 * subtype:
 *
 * ```kotlin
 * streamClient.connect(conversationId, jwt).collect { event ->
 *     when (event) {
 *         is StreamEvent.Token    -> appendToken(event.text)
 *         is StreamEvent.Done     -> recordUsage(event.usage)
 *         is StreamEvent.Error    -> showError(event.message)
 *         is StreamEvent.ToolCall -> dispatchMcpTool(event.toolName, event.toolInput)
 *     }
 * }
 * ```
 */
sealed class StreamEvent {

    /**
     * An incremental text token from the LLM provider.
     *
     * Corresponds to: `{"type":"token","data":"<text>"}`
     *
     * @param text The token text to append to the current response.
     */
    data class Token(val text: String) : StreamEvent()

    /**
     * The stream has completed successfully.
     *
     * Corresponds to: `{"type":"done","usage":{"inputTokens":N,"outputTokens":N}}`
     *
     * @param usage Token consumption metrics for this completion (Requirement 2.9).
     */
    data class Done(val usage: TokenUsage) : StreamEvent()

    /**
     * The backend encountered an error during streaming.
     *
     * Corresponds to: `{"type":"error","message":"<message>"}`
     *
     * @param message Human-readable error description from the backend.
     */
    data class Error(val message: String) : StreamEvent()

    /**
     * The AI Orchestrator is invoking an MCP tool and notifying the client.
     *
     * Corresponds to: `{"type":"tool_call","toolName":"<name>","toolInput":{...}}`
     *
     * @param toolName  The registered MCP tool identifier (e.g., "github", "gmail").
     * @param toolInput Arbitrary JSON parameters for the tool; the MCP layer validates shape.
     */
    data class ToolCall(val toolName: String, val toolInput: JsonObject) : StreamEvent()
}
