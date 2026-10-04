/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : DocumentChunkerImpl.kt
 * Purpose    : Adapts the existing core-common Chunker (character-
 *              approximation sliding window) to the domain's VectorChunk
 *              model.  Also extends PageOffset from RagContracts so PDF
 *              page numbers are preserved per chunk.
 *
 * Architecture Layer : Data — document/chunker
 * Pattern Used       : Adapter
 *
 * Design Rules:
 *   - Depends only on domain types (VectorChunk) and core-common types
 *     (Chunker, TextChunk, PageOffset).  Zero Android framework imports.
 *   - ChunkerConfig mirrors the backend ChunkingConfig so the two
 *     platforms stay in sync conceptually.
 *   - Uses the EXISTING Chunker from core-common/RagContracts.kt
 *     (character-approximation, CHARS_PER_TOKEN = 4) — NOT a new
 *     implementation.  This is intentional: on-device chunking should
 *     match the on-device RAG pipeline already established.
 * ============================================================
 */

package com.aiassistant.data.document.chunker

import com.aiassistant.core.common.Chunker
import com.aiassistant.core.common.PageOffset
import com.aiassistant.core.common.TextChunk
import com.aiassistant.domain.agent.LoadedDocument
import com.aiassistant.domain.agent.VectorChunk
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Configuration for [DocumentChunkerImpl].
 *
 * Mirrors the backend `ChunkingConfig` so both platforms share the same
 * conceptual parameters.
 *
 * Validation (enforced in `init`):
 * - `minChunkSizeTokens ≥ 1`
 * - `chunkSizeTokens` clamped to `[minChunkSizeTokens, maxChunkSizeTokens]`
 * - `overlapTokens ≤ chunkSizeTokens / 2`
 *
 * @param chunkSizeTokens    Target chunk size in approximate tokens.
 * @param overlapTokens      Token overlap between consecutive chunks.
 * @param minChunkSizeTokens Hard lower bound on [chunkSizeTokens].
 * @param maxChunkSizeTokens Hard upper bound on [chunkSizeTokens].
 */
data class ChunkerConfig(
    val chunkSizeTokens: Int = 512,
    val overlapTokens: Int = 64,
    val minChunkSizeTokens: Int = 64,
    val maxChunkSizeTokens: Int = 2048,
) {
    val effectiveChunkSize: Int = chunkSizeTokens
        .coerceAtLeast(minChunkSizeTokens)
        .coerceAtMost(maxChunkSizeTokens)

    val effectiveOverlap: Int = overlapTokens.coerceAtMost(effectiveChunkSize / 2)

    init {
        require(minChunkSizeTokens >= 1) {
            "ChunkerConfig.minChunkSizeTokens must be ≥ 1, got $minChunkSizeTokens."
        }
        require(maxChunkSizeTokens >= minChunkSizeTokens) {
            "ChunkerConfig.maxChunkSizeTokens ($maxChunkSizeTokens) must be ≥ " +
                "minChunkSizeTokens ($minChunkSizeTokens)."
        }
        require(overlapTokens >= 0) {
            "ChunkerConfig.overlapTokens must be ≥ 0, got $overlapTokens."
        }
    }
}

/**
 * Splits a [LoadedDocument] into [VectorChunk] objects using the existing
 * [Chunker] from `core-common`.
 *
 * Page numbers are resolved from the `pageOffsets` list stored in
 * [LoadedDocument.extractionMetadata] under the key `"pageOffsets"`.
 * The value is expected to be a JSON-serialised `List<PageOffset>` produced
 * by the on-device PDF extractor.  When the key is absent (TXT/MD), all
 * chunks receive `pageNumber = null`.
 *
 * Each [VectorChunk] gets a stable `chunkId` of the form
 * `"{documentId}_chunk_{index}_{shortSha1}"`.
 *
 * @param config Chunking parameters.
 */
class DocumentChunkerImpl @Inject constructor(
    private val config: ChunkerConfig = ChunkerConfig(),
) {

    private val chunker: Chunker by lazy {
        Chunker(
            chunkSizeTokens = config.effectiveChunkSize,
            overlapTokens = config.effectiveOverlap,
            minChunkSizeTokens = config.minChunkSizeTokens,
            maxChunkSizeTokens = config.maxChunkSizeTokens,
        )
    }

    /**
     * Chunk [document] into a list of [VectorChunk].
     *
     * @param document  Loaded document whose [LoadedDocument.text] is split.
     * @param userId    Owner of the resulting chunks; propagated to each [VectorChunk].
     * @param pageOffsets  Optional page boundary list for page-number annotation.
     *                     Pass an empty list for TXT/MD (no page concept).
     * @return Ordered list of [VectorChunk].  Empty when [LoadedDocument.isEmpty].
     */
    fun chunk(
        document: LoadedDocument,
        userId: String = "",
        pageOffsets: List<PageOffset> = emptyList(),
    ): List<VectorChunk> {
        if (document.isEmpty) return emptyList()

        val textChunks: List<TextChunk> = chunker.chunk(
            text = document.text,
            documentId = document.documentId,
            documentName = document.filename.ifBlank { document.documentId },
            pageOffsets = pageOffsets,
        )

        return textChunks.map { tc -> tc.toVectorChunk(userId) }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun TextChunk.toVectorChunk(userId: String): VectorChunk {
        val shortHash = sha1Short(content)
        return VectorChunk(
            chunkId = "${documentId}_chunk_${chunkIndex}_$shortHash",
            documentId = documentId,
            documentName = documentName,
            userId = userId,
            chunkIndex = chunkIndex,
            text = content,
            pageNumber = pageNumber,
            charStart = startCharOffset,
            charEnd = endCharOffset,
        )
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────────

/**
 * Compute the first 8 hex characters of the SHA-1 digest of [text].
 * Used for stable chunk IDs — not a security primitive.
 */
private fun sha1Short(text: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
    return digest.take(4).joinToString("") { "%02x".format(it) }
}
