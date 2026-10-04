/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : mcp/MCPToolModel.kt
 * Purpose    : Richer MCP tool model with typed parameter schema,
 *              extending the lighter [MCPTool] domain entity.
 *              Used by MCPRegistry, MCPValidator, and MCPExecutor.
 *
 * Architecture Layer : Data — mcp
 * Pattern Used       : Value object / richer domain model
 *
 * Design rules:
 *   - Pure Kotlin, zero Android/framework dependencies.
 *   - [MCPTool] (domain/model) is kept simple for UI use.
 *   - [MCPToolModel] is used by the data-layer MCP infrastructure
 *     that needs to validate params before invoking tools.
 *
 * Dependencies: domain/model/MCPTool.kt, kotlinx.serialization
 * ============================================================
 */

package com.aiassistant.data.mcp

import com.aiassistant.domain.model.MCPTool
import kotlinx.serialization.Serializable

/**
 * Valid JSON Schema-style types for [MCPParamDescriptor.type].
 */
enum class MCPParamType {
    STRING, INTEGER, BOOLEAN, NUMBER, ARRAY, OBJECT;

    companion object {
        fun fromString(value: String): MCPParamType =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: STRING
    }
}

/**
 * Typed descriptor for a single parameter accepted by an MCP tool.
 *
 * @param name         Parameter key in the params map.
 * @param type         Expected JSON Schema type.
 * @param description  Human-readable description.
 * @param required     Whether this parameter must be present.
 * @param allowedValues When non-empty, the value must be one of these strings.
 * @param defaultValue Default when absent and not required.
 */
@Serializable
data class MCPParamDescriptor(
    val name: String,
    val type: MCPParamType = MCPParamType.STRING,
    val description: String = "",
    val required: Boolean = false,
    val allowedValues: List<String> = emptyList(),
    val defaultValue: String? = null,
) {
    init {
        require(name.isNotBlank()) { "MCPParamDescriptor.name must not be blank." }
    }
}

/**
 * Richer MCP tool description used by the data-layer validation and execution
 * infrastructure.
 *
 * This extends [MCPTool] (the domain entity used for UI display) with typed
 * parameter descriptors needed by [MCPValidator] to validate params before
 * [MCPExecutor] calls the backend.
 *
 * @param name                 Unique tool identifier (matches [MCPTool.name]).
 * @param displayName          Human-readable name for UI.
 * @param description          One-sentence capability description.
 * @param paramDescriptors     Typed parameter definitions.
 * @param requiresConfirmation Whether the tool requires user consent.
 * @param timeoutMs            Per-invocation timeout in milliseconds (0 = use default).
 * @param category             Optional grouping tag ("read" / "write" / etc.).
 */
@Serializable
data class MCPToolModel(
    val name: String,
    val displayName: String,
    val description: String,
    val paramDescriptors: List<MCPParamDescriptor> = emptyList(),
    val requiresConfirmation: Boolean = false,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val category: String = "",
) {
    init {
        require(name.isNotBlank()) { "MCPToolModel.name must not be blank." }
        require(timeoutMs >= 0) { "MCPToolModel.timeoutMs must be ≥ 0, got $timeoutMs." }
    }

    /** Names of all required parameters. */
    val requiredParams: List<String> get() = paramDescriptors.filter { it.required }.map { it.name }

    /** Names of all declared parameters. */
    val paramNames: List<String> get() = paramDescriptors.map { it.name }

    /** Convert to the lighter [MCPTool] domain model for UI consumption. */
    fun toMCPTool(): MCPTool = MCPTool(
        name = name,
        displayName = displayName,
        description = description,
        requiresConfirmation = requiresConfirmation,
        isAvailable = true,
    )

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 30_000L

        /** Build an [MCPToolModel] from a lighter [MCPTool]. */
        fun fromMCPTool(tool: MCPTool): MCPToolModel = MCPToolModel(
            name = tool.name,
            displayName = tool.displayName,
            description = tool.description,
            requiresConfirmation = tool.requiresConfirmation,
        )
    }
}

// ── Error types ───────────────────────────────────────────────────────────────

/**
 * Thrown when params fail validation before an MCP tool invocation.
 *
 * Safe to display — carries no secrets or internal details.
 *
 * @param toolName   The tool whose validation failed.
 * @param paramName  The offending parameter name, if applicable.
 * @param reason     Human-readable description.
 */
class MCPValidationException(
    val toolName: String,
    val paramName: String? = null,
    val reason: String,
) : Exception(
    if (paramName != null) "[$toolName] Parameter '$paramName': $reason"
    else "[$toolName] $reason"
)

/**
 * Thrown when an MCP tool invocation fails after executor error handling.
 *
 * @param toolName     The tool that failed.
 * @param safeMessage  Human-readable error. MUST NOT contain stack traces.
 * @param retryable    True when the caller may retry.
 */
open class MCPExecutionException(
    val toolName: String,
    val safeMessage: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception("[$toolName] $safeMessage", cause)

/**
 * Thrown when an MCP tool invocation exceeds its timeout.
 *
 * @param toolName  The tool that timed out.
 * @param timeoutMs The limit that was exceeded.
 */
class MCPTimeoutException(
    toolName: String,
    val timeoutMs: Long,
) : MCPExecutionException(
    toolName = toolName,
    safeMessage = "Invocation timed out after ${timeoutMs}ms.",
    retryable = true,
)
