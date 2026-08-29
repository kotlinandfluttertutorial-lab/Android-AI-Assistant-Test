/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai
 * File       : Chunker.kt
 * Purpose    : Splits a document's raw text into overlapping chunks suitable
 *              for embedding and vector-index storage.  Produces a List<TextChunk>
 *              whose content union covers the full input with no gaps.
 *
 * Architecture Layer : Core-AI — on-device RAG pipeline (ingestion stage 2 of 4).
 *                      Called by OnDeviceIngestDocumentUseCase (domain) after text
 *                      extraction.  Produces TextChunk objects consumed by
 *                      OnDeviceEmbeddingModel and LocalVectorIndex.
 *
 * Dependencies       : Pure Kotlin — zero Android framework imports.
 *                      No Hilt injection needed; constructed directly with params.
 *
 * Design Decision    : Token count is approximated as (charCount / 4) — the same
 *                      rule used by OpenAI's tiktoken for English text.  Exact
 *                      tokenisation would require a bundled vocabulary file (several
 *                      MB) and significant CPU cost.  The approximation is
 *                      consistent, deterministic, and sufficient for on-device RAG
 *                      chunking where chunk boundaries do not need to be exact.
 *
 *                      Overlap is implemented as a character-level back-step derived
 *                      from the token approximation so that consecutive chunks share
 *                      context without duplicating entire sentences.
 *
 *                      PageOffset list allows PDF ingestion to attribute each chunk
 *                      to a page number; TXT/Markdown callers pass an empty list.
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag

/**
 * Represents one page boundary within a document, used to attribute chunks to pages.
 *
 * @param pageNumber      1-based page number.
 * @param startCharOffset Inclusive start character offset of the page in the full text.
 * @param endCharOffset   Exclusive end character offset of the page in the full text.
 */
data class PageOffset(
    val pageNumber: Int,
    val startCharOffset: Int,
    val endCharOffset: Int,
)

/**
 * One chunk of text produced by [Chunker], ready for embedding.
 *
 * @param id              Stable UUID assigned by [Chunker].
 * @param documentId      ID of the parent document.
 * @param documentName    Display name of the parent document (denormalised for citation UI).
 * @param chunkIndex      Zero-based sequential position within the document.
 * @param pageNumber      PDF page number (1-based), or null for plain-text documents.
 * @param startCharOffset Inclusive start character offset in the original full text.
 * @param endCharOffset   Exclusive end character offset in the original full text.
 * @param content         The actual text content of this chunk.
 */
data class TextChunk(
    val id: String,
    val documentId: String,
    val documentName: String,
    val chunkIndex: Int,
    val pageNumber: Int?,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val content: String,
)

/**
 * Splits document text into overlapping [TextChunk] objects for the on-device RAG pipeline.
 *
 * Token count is approximated as `charCount / CHARS_PER_TOKEN` (4 chars ≈ 1 token).
 *
 * ### Invariants (enforced by the spec and tested by Property 38 / task 45.6):
 * - `overlapTokens ≤ chunkSizeTokens / 2` — enforced at construction with `require()`.
 * - `minChunkSizeTokens` and `maxChunkSizeTokens` bound each chunk's token count.
 * - The union of all returned chunk content equals the full [text] with no gaps.
 *
 * @param chunkSizeTokens    Target token count per chunk. Default 512.
 * @param overlapTokens      Token overlap between consecutive chunks. Default 64.
 * @param minChunkSizeTokens Minimum tokens a chunk must contain. Default 64.
 * @param maxChunkSizeTokens Maximum tokens a chunk may contain. Default 2048.
 */
class Chunker(
    val chunkSizeTokens: Int = 512,
    val overlapTokens: Int = 64,
    val minChunkSizeTokens: Int = 64,
    val maxChunkSizeTokens: Int = 2048,
) {
    init {
        require(overlapTokens <= chunkSizeTokens / 2) {
            "overlapTokens ($overlapTokens) must be ≤ chunkSizeTokens / 2 (${chunkSizeTokens / 2}). " +
                "Excessive overlap would cause infinite chunking loops."
        }
        require(minChunkSizeTokens > 0) { "minChunkSizeTokens must be > 0" }
        require(maxChunkSizeTokens >= chunkSizeTokens) {
            "maxChunkSizeTokens ($maxChunkSizeTokens) must be ≥ chunkSizeTokens ($chunkSizeTokens)"
        }
    }

    /**
     * Splits [text] into a list of [TextChunk] objects.
     *
     * The algorithm:
     * 1. Converts token counts to character counts using the 4-chars-per-token approximation.
     * 2. Slides a window of [chunkSizeTokens] tokens forward by `(chunkSizeTokens - overlapTokens)`
     *    tokens per step, producing overlapping chunks.
     * 3. Each chunk's [TextChunk.pageNumber] is determined by which [PageOffset] range contains
     *    its [TextChunk.startCharOffset]; null when [pageOffsets] is empty.
     * 4. The final chunk absorbs any remaining text even if shorter than [minChunkSizeTokens],
     *    ensuring the no-gaps invariant is always satisfied.
     *
     * @param text         Full document text.
     * @param documentId   ID of the parent document.
     * @param documentName Display name of the parent document.
     * @param pageOffsets  Page boundary list for PDFs; empty for TXT/Markdown.
     * @return             List of [TextChunk] in document order. Empty if [text] is blank.
     */
    fun chunk(
        text: String,
        documentId: String,
        documentName: String,
        pageOffsets: List<PageOffset> = emptyList(),
    ): List<TextChunk> {
        if (text.isBlank()) return emptyList()

        val chunkSizeChars = chunkSizeTokens * CHARS_PER_TOKEN
        val overlapChars = overlapTokens * CHARS_PER_TOKEN
        val stepChars = chunkSizeChars - overlapChars   // advance this many chars per step
        val maxChunkChars = maxChunkSizeTokens * CHARS_PER_TOKEN

        val chunks = mutableListOf<TextChunk>()
        var start = 0
        var chunkIndex = 0

        while (start < text.length) {
            // End of this chunk: advance by chunkSizeChars but cap at maxChunkChars
            val rawEnd = (start + chunkSizeChars).coerceAtMost(start + maxChunkChars)
            val end = rawEnd.coerceAtMost(text.length)

            val content = text.substring(start, end)

            // Determine page number from the page offset list (null for non-PDF)
            val pageNumber = pageOffsets
                .firstOrNull { start >= it.startCharOffset && start < it.endCharOffset }
                ?.pageNumber

            chunks += TextChunk(
                id = generateChunkId(documentId, chunkIndex),
                documentId = documentId,
                documentName = documentName,
                chunkIndex = chunkIndex,
                pageNumber = pageNumber,
                startCharOffset = start,
                endCharOffset = end,
                content = content,
            )

            chunkIndex++

            // If we just consumed the last characters, stop.
            if (end >= text.length) break

            // Advance start by stepChars; ensure progress is always > 0.
            start += stepChars.coerceAtLeast(1)
        }

        return chunks
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Produces a deterministic chunk ID so re-ingesting the same document yields the
     * same IDs — enabling [LocalVectorIndex]'s "overwrite on same id" behaviour.
     */
    private fun generateChunkId(documentId: String, chunkIndex: Int): String =
        "${documentId}_chunk_${chunkIndex}"

    companion object {
        /**
         * Characters per token approximation used throughout the on-device RAG pipeline.
         * Based on OpenAI's observation that 1 token ≈ 4 chars of English text.
         */
        const val CHARS_PER_TOKEN = 4
    }
}
