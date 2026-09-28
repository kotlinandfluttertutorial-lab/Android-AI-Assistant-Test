/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentGatewayDocumentExtension.kt
 * Purpose    : Domain interfaces for PDF and Tool agent entry points,
 *              allowing feature modules to call agents without importing :data.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Interface (Gateway)
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.coroutines.flow.Flow

/** Gateway interface for PDF/document agent operations (Phase 5). */
interface AgentGatewayDocumentExtension {

    /**
     * Upload, ingest, query, summarize, or search a document via [PdfAgent].
     *
     * @param action       One of: "upload", "query", "summarize", "search"
     * @param input        User query text (for query/summarize/search)
     * @param documentId   UUID of an already-ingested document (query/summarize/search)
     * @param fileUri      content:// or file:// URI (upload only)
     * @param fileName     File display name (upload only)
     * @param mimeType     MIME type (upload only; defaults to "application/pdf")
     * @param context      Optional [AgentContext]
     */
    fun executePdf(
        action: String = "query",
        input: String = "",
        documentId: String? = null,
        fileUri: String? = null,
        fileName: String? = null,
        mimeType: String = "application/pdf",
        context: AgentContext? = null,
    ): Flow<AgentEvent>

    /**
     * Execute a single registered tool via [ToolAgent].
     *
     * @param toolName         Name of the tool to invoke (e.g. "calculator").
     * @param toolArgs         Tool-specific arguments.
     * @param callerPermissions Permissions the caller grants for this invocation.
     * @param confirmed        True when the user has confirmed a write-action tool.
     * @param context          Optional [AgentContext].
     */
    fun executeTool(
        toolName: String,
        toolArgs: Map<String, String> = emptyMap(),
        callerPermissions: Set<ToolPermission> = emptySet(),
        confirmed: Boolean = false,
        context: AgentContext? = null,
    ): Flow<AgentEvent>

    /** Return the [ToolSchema] for every registered tool. */
    fun listTools(): List<ToolSchema>
}
