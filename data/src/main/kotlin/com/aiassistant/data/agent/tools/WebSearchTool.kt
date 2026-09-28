/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : tools/WebSearchTool.kt
 * Purpose    : Stub web search tool — returns a safe placeholder while
 *              a real web search integration is pending.
 *
 * Design Decision:
 *   Phase 5 requires a WebSearch tool to be declared in the ToolRegistry.
 *   The real implementation would call a search API (Google Custom Search,
 *   Bing, etc.) but that requires an API key not yet configured.
 *   This stub declares all the required metadata, validates arguments, and
 *   returns a clearly-labelled placeholder result rather than silently failing
 *   or making unauthenticated external calls.
 *
 * Security: requires NETWORK_ACCESS permission; no shell, no filesystem.
 * When a real implementation is added, replace execute() body only.
 * ============================================================
 */

package com.aiassistant.data.agent.tools

import com.aiassistant.domain.agent.Tool
import com.aiassistant.domain.agent.ToolPermission
import com.aiassistant.domain.agent.ToolResult
import com.aiassistant.domain.agent.ToolSchema
import com.aiassistant.domain.agent.ToolValidationError
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebSearchTool @Inject constructor() : Tool {

    override val schema = ToolSchema(
        name = NAME,
        displayName = "Web Search",
        description = "Searches the web for current information. (Stub — real integration pending.)",
        parametersSchema = mapOf(
            "query" to "The search query.",
            "num_results" to "Number of results to return (1–10, default 3).",
        ),
        requiredPermissions = setOf(ToolPermission.NETWORK_ACCESS),
        timeoutMs = 15_000L,
    )

    override fun validate(args: Map<String, String>) {
        val query = args["query"]
            ?: throw ToolValidationError(NAME, "query", "is required")
        if (query.isBlank())
            throw ToolValidationError(NAME, "query", "must not be blank")
        if (query.length > 500)
            throw ToolValidationError(NAME, "query", "exceeds 500 character limit")

        val numResults = args["num_results"]
        if (numResults != null && numResults.isNotBlank()) {
            val n = numResults.toIntOrNull()
                ?: throw ToolValidationError(NAME, "num_results", "must be an integer")
            if (n < 1 || n > 10)
                throw ToolValidationError(NAME, "num_results", "must be between 1 and 10")
        }
    }

    override suspend fun execute(args: Map<String, String>, userId: String): ToolResult {
        val start = System.currentTimeMillis()
        // Stub: return a placeholder until a real web search API is configured.
        return ToolResult(
            toolName = NAME,
            success = false,
            error = "Web search is not yet configured. Please add a search API key in Settings.",
            durationMs = System.currentTimeMillis() - start,
            metadata = mapOf("query" to (args["query"] ?: ""), "stub" to "true"),
        )
    }

    companion object { const val NAME = "web_search" }
}
