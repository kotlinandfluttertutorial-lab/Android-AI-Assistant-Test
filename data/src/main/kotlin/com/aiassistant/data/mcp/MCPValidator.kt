/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : mcp/MCPValidator.kt
 * Purpose    : Validates MCP tool invocation params against the
 *              tool's declared parameter schema before execution.
 *
 * Architecture Layer : Data — mcp
 * Pattern Used       : Validator / Strategy
 *
 * Validation pipeline (in order):
 *   1. Required params — all required params must be present.
 *   2. Allowed values  — when allowedValues is non-empty, value must be a member.
 *   3. Result check    — MCPInvocationResult must be non-null (optional).
 *
 * Design rules:
 *   - Pure Kotlin, zero Android/framework dependencies.
 *   - Raises MCPValidationException on first failure (fail-fast).
 *   - Safe messages only — no secrets or stack traces.
 * ============================================================
 */

package com.aiassistant.data.mcp

import com.aiassistant.domain.agent.MCPInvocationResult

/**
 * Validates MCP tool invocation parameters against a tool's descriptor.
 */
interface MCPValidator {
    /**
     * Validate [params] against [model]'s [MCPParamDescriptor] list.
     *
     * @throws MCPValidationException On the first validation failure.
     */
    fun validate(params: Map<String, String>, model: MCPToolModel)

    /**
     * Validate an [MCPInvocationResult] for structural soundness.
     *
     * @throws MCPValidationException When the result is structurally invalid.
     */
    fun validateResult(result: MCPInvocationResult, toolName: String)
}

/**
 * Default [MCPValidator] implementation.
 *
 * Applies required-param checks, allowed-value (enum) checks, and a basic
 * result sanity check.
 */
class DefaultMCPValidator : MCPValidator {

    override fun validate(params: Map<String, String>, model: MCPToolModel) {
        for (descriptor in model.paramDescriptors) {
            val value = params[descriptor.name]

            // 1. Required check
            if (descriptor.required && value == null) {
                throw MCPValidationException(
                    toolName = model.name,
                    paramName = descriptor.name,
                    reason = "is required but was not provided.",
                )
            }

            // Skip further checks when parameter is absent (and not required)
            if (value == null) continue

            // 2. Allowed-values (enum) check
            if (descriptor.allowedValues.isNotEmpty() && value !in descriptor.allowedValues) {
                throw MCPValidationException(
                    toolName = model.name,
                    paramName = descriptor.name,
                    reason = "value '${value}' is not in the allowed set ${descriptor.allowedValues}.",
                )
            }
        }
    }

    override fun validateResult(result: MCPInvocationResult, toolName: String) {
        // Result carries no structural invariants beyond what the data class enforces,
        // but we can sanity-check the error contract:
        if (result.success && result.output.isBlank() && result.error.isBlank()) {
            // A successful result with no output is odd but not invalid — log-worthy only.
            return
        }
        if (!result.success && result.error.isBlank() && !result.requiresConfirmation) {
            throw MCPValidationException(
                toolName = toolName,
                paramName = null,
                reason = "Tool reported failure but provided no error message.",
            )
        }
    }
}
