/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : IngestDocumentUseCase.kt
 * Purpose    : On-device document ingestion use case.
 *              Orchestrates: validate → load → chunk.
 *              Returns a sealed result so callers never receive
 *              raw exceptions from the pipeline.
 *
 * Architecture Layer : Domain — use case
 * Pattern Used       : Use Case (single-responsibility)
 *
 * Design Rules:
 *   - Pure Kotlin, zero Android framework dependencies.
 *   - Depends only on domain interfaces: DocumentLoader, DocumentValidator.
 *   - The data layer provides implementations via Hilt.
 * ============================================================
 */

package com.aiassistant.domain.usecase.document

import com.aiassistant.domain.agent.DocumentLoadException
import com.aiassistant.domain.agent.DocumentLoader
import com.aiassistant.domain.agent.LoadedDocument
import com.aiassistant.domain.agent.UnsupportedDocumentFormatException
import javax.inject.Inject

/**
 * Result of [IngestDocumentUseCase.execute].
 */
sealed class IngestResult {
    /** Extraction and validation succeeded. */
    data class Success(val document: LoadedDocument) : IngestResult()

    /** The file format is not supported. */
    data class UnsupportedFormat(val filename: String, val mimeType: String) : IngestResult()

    /** The file exceeds the maximum allowed size. */
    data class FileTooLarge(val filename: String, val sizeBytes: Long, val maxBytes: Long) : IngestResult()

    /** Extraction failed for a recoverable reason (malformed content). */
    data class ExtractionFailed(val stage: String, val filename: String, val detail: String) : IngestResult()

    /** Extraction failed for an unexpected reason. */
    data class UnexpectedError(val message: String) : IngestResult()
}

/**
 * On-device document ingestion: validates, extracts text, and returns
 * a [LoadedDocument] ready for chunking and embedding.
 *
 * This use case does NOT perform embedding or vector storage — those are
 * separate use cases ([OnDeviceIngestDocumentUseCase] in the on-device RAG
 * pipeline).  It is responsible only for the load step.
 *
 * @param loader  [DocumentLoader] implementation (injected by Hilt).
 */
class IngestDocumentUseCase @Inject constructor(
    private val loader: DocumentLoader,
) {
    /**
     * Validate and extract text from [fileBytes].
     *
     * @param fileBytes   Raw file content.
     * @param filename    Original filename (for format detection and citations).
     * @param documentId  Stable document identifier.
     * @param mimeType    MIME type hint; may be blank.
     * @return [IngestResult] — never throws.
     */
    suspend fun execute(
        fileBytes: ByteArray,
        filename: String,
        documentId: String,
        mimeType: String = "",
    ): IngestResult {
        // Guard: empty file
        if (fileBytes.isEmpty()) {
            return IngestResult.ExtractionFailed(
                stage = "validation",
                filename = filename,
                detail = "File is empty.",
            )
        }

        // Guard: size limit
        if (fileBytes.size > loader.maxFileSizeBytes) {
            return IngestResult.FileTooLarge(
                filename = filename,
                sizeBytes = fileBytes.size.toLong(),
                maxBytes = loader.maxFileSizeBytes,
            )
        }

        // Guard: format support
        if (!loader.supports(mimeType, filename)) {
            return IngestResult.UnsupportedFormat(filename = filename, mimeType = mimeType)
        }

        return try {
            val doc = loader.load(fileBytes, filename, documentId, mimeType)
            IngestResult.Success(doc)
        } catch (e: UnsupportedDocumentFormatException) {
            IngestResult.UnsupportedFormat(filename = e.filename, mimeType = e.mimeType)
        } catch (e: DocumentLoadException) {
            IngestResult.ExtractionFailed(stage = e.stage, filename = e.filename, detail = e.detail)
        } catch (e: IllegalArgumentException) {
            IngestResult.ExtractionFailed(stage = "validation", filename = filename, detail = e.message ?: "")
        } catch (e: Exception) {
            IngestResult.UnexpectedError(message = e.message ?: "Unknown error during ingestion.")
        }
    }
}
