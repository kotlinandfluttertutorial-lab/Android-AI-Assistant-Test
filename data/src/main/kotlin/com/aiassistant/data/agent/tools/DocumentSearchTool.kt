/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : tools/DocumentSearchTool.kt
 * Purpose    : Searches the user's documents via the existing
 *              DocumentRepository.queryDocument() — no new RAG pipeline created.
 *
 * Security:
 *   - Requires READ_DOCUMENTS permission.
 *   - Only queries documents owned by the authenticated userId.
 *   - Scoped to a specific documentId (required arg) so no cross-user access.
 *   - No raw file bytes are returned — only the AI-generated answer text.
 *
 * Dependencies: domain (DocumentRepository, Tool, ToolPermission, etc.)
 * ============================================================
 */

package com.aiassistant.data.agent.tools

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.agent.Tool
import com.aiassistant.domain.agent.ToolPermission
import com.aiassistant.domain.agent.ToolResult
import com.aiassistant.domain.agent.ToolSchema
import com.aiassistant.domain.agent.ToolValidationError
import com.aiassistant.domain.repository.DocumentRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Queries a specific document using the existing RAG pipeline.
 *
 * Wraps [DocumentRepository.queryDocument] — does NOT create a second RAG
 * implementation.
 */
@Singleton
class DocumentSearchTool @Inject constructor(
    private val documentRepository: DocumentRepository,
) : Tool {

    override val schema = ToolSchema(
        name = NAME,
        displayName = "Document Search",
        description = "Answers questions about a specific uploaded document using RAG retrieval.",
        parametersSchema = mapOf(
            "document_id" to "UUID of the document to query.",
            "query" to "Natural language question about the document.",
        ),
        requiredPermissions = setOf(ToolPermission.READ_DOCUMENTS),
        timeoutMs = 30_000L,
    )

    override fun validate(args: Map<String, String>) {
        val docId = args["document_id"]
            ?: throw ToolValidationError(NAME, "document_id", "is required")
        if (docId.isBlank())
            throw ToolValidationError(NAME, "document_id", "must not be blank")

        val query = args["query"]
            ?: throw ToolValidationError(NAME, "query", "is required")
        if (query.isBlank())
            throw ToolValidationError(NAME, "query", "must not be blank")
        if (query.length > MAX_QUERY_LENGTH)
            throw ToolValidationError(NAME, "query", "exceeds maximum length of $MAX_QUERY_LENGTH characters")
    }

    override suspend fun execute(args: Map<String, String>, userId: String): ToolResult {
        val documentId = args["document_id"]!!.trim()
        val query = args["query"]!!.trim()
        val start = System.currentTimeMillis()

        return when (val result = documentRepository.queryDocument(documentId, query)) {
            is ApiResult.Success -> ToolResult(
                toolName = NAME,
                success = true,
                output = result.data,
                durationMs = System.currentTimeMillis() - start,
                metadata = mapOf("document_id" to documentId),
            )
            is ApiResult.Error -> ToolResult(
                toolName = NAME,
                success = false,
                error = result.error.message,
                durationMs = System.currentTimeMillis() - start,
            )
            is ApiResult.NetworkUnavailable -> ToolResult(
                toolName = NAME,
                success = false,
                error = "Network unavailable. Document search requires internet access.",
                durationMs = System.currentTimeMillis() - start,
            )
            is ApiResult.Loading -> ToolResult(
                toolName = NAME,
                success = false,
                error = "Unexpected loading state.",
                durationMs = System.currentTimeMillis() - start,
            )
        }
    }

    companion object {
        const val NAME = "document_search"
        private const val MAX_QUERY_LENGTH = 2_000
    }
}
