/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : RagCitation.kt
 * Purpose    : Typed citation returned by RAGAgent alongside an answer.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Value object
 *
 * Design Decision:
 *   The existing DocumentRepository.queryDocument() returns only a plain
 *   String answer (no citation objects).  RagCitation is a new domain type
 *   used exclusively by RAGAgent so that citation data can flow up to the
 *   UI via AgentResult.citations without changing any existing repository
 *   interface or Retrofit DTO.
 *
 * Dependencies: kotlinx.serialization
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.serialization.Serializable

/**
 * A single source citation from a RAG retrieval result.
 *
 * Corresponds to the `Citation` Pydantic schema in the backend
 * (`app/schemas/rag.py`).
 *
 * @param documentName  Original filename of the source document.
 * @param pageNumber    1-based page number (for PDF/DOCX).
 * @param chunkIndex    0-based chunk index within the document.
 * @param citationType  `"page"` (PDF/DOCX) or `"char_offset"` (TXT/MD).
 * @param excerpt       Short excerpt from the chunk (≤300 chars). May be
 *                      empty when the backend omits chunk text.
 * @param score         Cosine similarity score (0.0–1.0). Defaults to 0
 *                      when not reported by the backend.
 */
@Serializable
data class RagCitation(
    val documentName: String,
    val pageNumber: Int,
    val chunkIndex: Int,
    val citationType: String = "page",
    val excerpt: String = "",
    val score: Float = 0f,
)

/**
 * Combined RAG result — the answer text plus its source citations.
 *
 * Used as the [AgentContext.extraContext] transfer vehicle between
 * RAGAgent and a subsequent CodeAgent step during a Code→RAG→Code handoff.
 *
 * @param answer    The AI-generated answer text with inline citation markers.
 * @param citations All source citations returned by the backend.
 */
@Serializable
data class RagQueryResult(
    val answer: String,
    val citations: List<RagCitation> = emptyList(),
)
