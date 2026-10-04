/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : DocumentLoader.kt
 * Purpose    : Domain port for loading and extracting text content
 *              from raw document files (PDF, DOCX, TXT, etc.).
 *              Agent implementations depend only on this interface —
 *              never on pdfplumber, python-docx, MinIO, or any I/O
 *              library directly.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Interface (Port — Hexagonal Architecture)
 *
 * Design Rules:
 *   - Pure Kotlin, zero Android/framework/infrastructure dependencies.
 *   - Receives raw ByteArray; never a URI or file path (those are
 *     resolved by the data layer before calling this interface).
 *   - Returns value objects defined here — no framework types.
 *   - Relationship to DocumentRepository: DocumentRepository handles
 *     network upload/query; DocumentLoader handles the local text
 *     extraction step performed before or during on-device ingestion.
 *
 * Dependencies: kotlinx.serialization (for LoadedDocument)
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.serialization.Serializable

// ── Value objects ─────────────────────────────────────────────────────────────

/**
 * Plain-text content extracted from a source document.
 *
 * @param documentId          Stable identifier for the source document.
 * @param text                Full extracted plain-text.
 * @param pageCount           Number of pages in the source (0 if not applicable).
 * @param mimeType            MIME type of the source file.
 * @param filename            Original filename.
 * @param extractionMetadata  Key-value metadata from the extractor
 *                            (e.g. `"author"`, `"creationDate"`, `"ocrConfidence"`).
 */
@Serializable
data class LoadedDocument(
    val documentId: String,
    val text: String,
    val pageCount: Int = 0,
    val mimeType: String = "",
    val filename: String = "",
    val extractionMetadata: Map<String, String> = emptyMap(),
) {
    init {
        require(documentId.isNotBlank()) { "LoadedDocument.documentId must not be blank." }
    }

    /** True when extraction produced no usable text. */
    val isEmpty: Boolean get() = text.isBlank()
}

// ── Error types ───────────────────────────────────────────────────────────────

/**
 * Thrown by [DocumentLoader] when extraction fails irrecoverably.
 *
 * @param stage    Pipeline stage where failure occurred (`"read"`, `"parse"`,
 *                 `"ocr"`, `"decode"`).
 * @param filename Source filename, for diagnostic context.
 * @param detail   Human-readable description.  Must not contain stack traces.
 * @param cause    Underlying throwable, if any.
 */
open class DocumentLoadException(
    val stage: String,
    val filename: String,
    val detail: String = "",
    cause: Throwable? = null,
) : Exception(
    if (detail.isNotBlank()) "[$stage] $filename: $detail" else "[$stage] $filename",
    cause,
)

/**
 * Thrown when a file format is not supported by the loader.
 *
 * @param filename The file whose format is not supported.
 * @param mimeType MIME type of the file, if known.
 */
class UnsupportedDocumentFormatException(
    filename: String,
    val mimeType: String = "",
) : DocumentLoadException(
    stage = "format_check",
    filename = filename,
    detail = "Unsupported format${if (mimeType.isNotBlank()) ": $mimeType" else ""}",
)

// ── Interface ─────────────────────────────────────────────────────────────────

/**
 * Domain port for extracting plain text from raw document bytes.
 *
 * Implementations live in `:data` and wrap platform-specific extraction
 * libraries (e.g. PdfRenderer on Android, Apache Tika in a JVM service).
 * Neither library is visible at this layer.
 *
 * ## Supported format discovery
 * Call [supports] before [load] to avoid unnecessary work or unhelpful errors:
 * ```kotlin
 * if (!loader.supports(mimeType = "application/pdf", filename = "report.pdf")) {
 *     return Result.failure(UnsupportedDocumentFormatException("report.pdf", "application/pdf"))
 * }
 * val document = loader.load(bytes, "report.pdf", documentId, "application/pdf")
 * ```
 *
 * ## On-device vs. cloud
 * The same interface is implemented for:
 * - On-device loading (Android PdfRenderer, plain-text reader) — zero network calls.
 * - Backend-delegated loading (calls `/documents/{id}/extract`) — used when
 *   on-device extraction is insufficient (e.g. complex scanned PDFs needing OCR).
 */
interface DocumentLoader {

    /**
     * Extract plain text from [fileBytes].
     *
     * @param fileBytes   Raw file content (must not be empty).
     * @param filename    Original filename — used for format detection when
     *                    [mimeType] is blank, and for citation display.
     * @param documentId  Stable document identifier assigned before loading.
     * @param mimeType    MIME type hint.  May be blank — implementations
     *                    fall back to extension-based detection.
     * @return [LoadedDocument] containing extracted text and metadata.
     * @throws DocumentLoadException       When extraction fails irrecoverably.
     * @throws UnsupportedDocumentFormatException When the format is not supported.
     */
    suspend fun load(
        fileBytes: ByteArray,
        filename: String,
        documentId: String,
        mimeType: String = "",
    ): LoadedDocument

    /**
     * Return `true` when this loader can handle the given format.
     *
     * Callers should check this before calling [load] to surface a clear
     * error rather than a generic failure.
     *
     * @param mimeType MIME type of the candidate file.
     * @param filename Original filename (used as fallback for format detection).
     */
    fun supports(mimeType: String, filename: String): Boolean

    /**
     * Maximum file size in bytes this loader accepts.
     *
     * Files larger than this limit must be rejected by the caller
     * before invoking [load].
     */
    val maxFileSizeBytes: Long
}
