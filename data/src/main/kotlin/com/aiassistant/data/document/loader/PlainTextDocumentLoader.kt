/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : PlainTextDocumentLoader.kt
 * Purpose    : Loads TXT and Markdown documents on-device without any
 *              third-party library. Handles UTF-8, UTF-8-BOM, and
 *              Latin-1 encodings, cleans whitespace, and computes a
 *              SHA-256 checksum for deduplication.
 *
 * Architecture Layer : Data — document/loader
 * Pattern Used       : Adapter (implements domain DocumentLoader port)
 *
 * Design Rules:
 *   - Pure Kotlin + stdlib only — no Android framework dependency.
 *   - Implements domain/agent/DocumentLoader so use-case code never
 *     depends on this class directly.
 *   - All work runs on the caller's coroutine context; caller is
 *     responsible for dispatching to IO thread if needed.
 *
 * Supported formats: text/plain (.txt), text/markdown (.md)
 * ============================================================
 */

package com.aiassistant.data.document.loader

import com.aiassistant.domain.agent.DocumentLoadException
import com.aiassistant.domain.agent.DocumentLoader
import com.aiassistant.domain.agent.LoadedDocument
import com.aiassistant.domain.agent.UnsupportedDocumentFormatException
import java.security.MessageDigest

/** Default upload size cap for plain-text documents: 10 MB. */
private const val DEFAULT_MAX_BYTES: Long = 10L * 1024 * 1024

private val SUPPORTED_MIME_TYPES: Set<String> = setOf(
    "text/plain",
    "text/markdown",
    "text/x-markdown",
)

private val SUPPORTED_EXTENSIONS: Set<String> = setOf(".txt", ".md")

/**
 * On-device [DocumentLoader] for plain-text (TXT) and Markdown (MD) files.
 *
 * Extraction is pure Kotlin — no third-party library required. The
 * implementation tries UTF-8 first, then UTF-8-BOM, then Latin-1.
 *
 * The resulting [LoadedDocument] contains:
 * - `text`              – cleaned plain text.
 * - `pageCount`         – always 1 (plain-text files have no page concept).
 * - `extractionMetadata["checksum"]` – SHA-256 hex of the raw bytes.
 * - `extractionMetadata["encoding"]` – encoding actually used.
 * - `extractionMetadata["lineCount"]` – number of non-blank lines.
 *
 * @param maxFileSizeBytes  Maximum accepted file size.  Defaults to 10 MB.
 */
class PlainTextDocumentLoader(
    override val maxFileSizeBytes: Long = DEFAULT_MAX_BYTES,
) : DocumentLoader {

    override fun supports(mimeType: String, filename: String): Boolean {
        val normMime = mimeType.substringBefore(";").trim().lowercase()
        val ext = filename.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }.lowercase()
        return normMime in SUPPORTED_MIME_TYPES || ext in SUPPORTED_EXTENSIONS
    }

    override suspend fun load(
        fileBytes: ByteArray,
        filename: String,
        documentId: String,
        mimeType: String,
    ): LoadedDocument {
        require(fileBytes.isNotEmpty()) { "File '$filename' is empty." }
        require(fileBytes.size <= maxFileSizeBytes) {
            "File '$filename' is ${fileBytes.size} bytes, which exceeds the " +
                "${maxFileSizeBytes / 1024 / 1024} MB limit."
        }
        if (!supports(mimeType, filename)) {
            throw UnsupportedDocumentFormatException(filename, mimeType)
        }

        val (text, encoding) = decodeBytes(fileBytes, filename)
        val cleaned = cleanText(text)
        val checksum = sha256Hex(fileBytes)
        val lineCount = cleaned.lines().count { it.isNotBlank() }

        return LoadedDocument(
            documentId = documentId,
            text = cleaned,
            pageCount = 1,
            mimeType = normalisedMime(mimeType, filename),
            filename = filename,
            extractionMetadata = mapOf(
                "checksum" to checksum,
                "encoding" to encoding,
                "lineCount" to lineCount.toString(),
            ),
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun decodeBytes(bytes: ByteArray, filename: String): Pair<String, String> {
        // Try UTF-8 first (with and without BOM), then Latin-1.
        val utf8BomPrefix = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val hasBom = bytes.size >= 3 && bytes.take(3).toByteArray().contentEquals(utf8BomPrefix)
        val bytesToDecode = if (hasBom) bytes.drop(3).toByteArray() else bytes

        for (charset in listOf("UTF-8", "ISO-8859-1")) {
            try {
                val cs = java.nio.charset.Charset.forName(charset)
                val text = bytesToDecode.toString(cs)
                // Sanity-check: UTF-8 decode of Latin-1 bytes may succeed but contain
                // replacement characters — accept only if no replacement char present.
                if (charset == "UTF-8" && text.contains('\uFFFD')) continue
                return Pair(text, if (hasBom) "UTF-8-BOM" else charset)
            } catch (_: Exception) {
                continue
            }
        }
        throw DocumentLoadException(
            stage = "text_decode",
            filename = filename,
            detail = "Could not decode file as UTF-8, UTF-8-BOM, or Latin-1.",
        )
    }

    /**
     * Normalise whitespace in extracted text:
     * 1. Replace Windows-style `\r\n` and lone `\r` with `\n`.
     * 2. Trim trailing whitespace from each line.
     * 3. Collapse runs of more than two blank lines into two.
     * 4. Strip overall leading/trailing whitespace.
     */
    internal fun cleanText(text: String): String {
        if (text.isEmpty()) return text
        var result = text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
        val lines = result.split("\n").map { it.trimEnd() }
        // Collapse 3+ consecutive blank lines → 2
        val collapsed = buildString {
            var blankRun = 0
            for (line in lines) {
                if (line.isBlank()) {
                    blankRun++
                    if (blankRun <= 1) appendLine(line)
                } else {
                    blankRun = 0
                    appendLine(line)
                }
            }
        }
        return collapsed.trimEnd('\n').trim()
    }

    private fun normalisedMime(mimeType: String, filename: String): String {
        val norm = mimeType.substringBefore(";").trim().lowercase()
        if (norm.isNotEmpty() && norm != "application/octet-stream") return norm
        return when (filename.substringAfterLast('.', "").lowercase()) {
            "md" -> "text/markdown"
            else -> "text/plain"
        }
    }
}

// ── Standalone helper ─────────────────────────────────────────────────────────

/**
 * Compute the SHA-256 hex digest of [bytes].
 * Used by all DocumentLoader implementations for deduplication.
 */
fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}
