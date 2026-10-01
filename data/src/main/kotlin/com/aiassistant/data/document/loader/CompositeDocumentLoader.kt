/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : CompositeDocumentLoader.kt
 * Purpose    : Composite DocumentLoader that delegates to the best
 *              available loader based on the file format.
 *
 *              On-device path:  TXT / MD  → PlainTextDocumentLoader
 *              Unsupported on-device: PDF / DOCX → throws
 *                UnsupportedDocumentFormatException with a clear
 *                message directing callers to use the cloud pipeline.
 *
 *              This class is the production binding for the
 *              DocumentLoader domain interface on Android.  Future
 *              versions can add a PdfDocumentLoader (using Android's
 *              PdfRenderer API) without changing callers.
 *
 * Architecture Layer : Data — document/loader
 * Pattern Used       : Composite / Chain of Responsibility
 *
 * Design Rules:
 *   - Pure Kotlin + stdlib — no Android framework dependency in the
 *     core delegation logic (Android PdfRenderer is added in a future
 *     subclass, not here).
 *   - Implements DocumentLoader so the Hilt graph can bind it once.
 * ============================================================
 */

package com.aiassistant.data.document.loader

import com.aiassistant.domain.agent.DocumentLoadException
import com.aiassistant.domain.agent.DocumentLoader
import com.aiassistant.domain.agent.LoadedDocument
import com.aiassistant.domain.agent.UnsupportedDocumentFormatException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [DocumentLoader] for the Android on-device pipeline.
 *
 * Delegates TXT and Markdown files to [PlainTextDocumentLoader].
 * PDF and DOCX are not supported on-device in this version — callers
 * should use the cloud pipeline (POST /documents) for those formats.
 *
 * The [maxFileSizeBytes] is the minimum of all delegate loaders' limits.
 *
 * @param textLoader Loader for TXT and Markdown files.
 */
@Singleton
class CompositeDocumentLoader @Inject constructor(
    private val textLoader: PlainTextDocumentLoader,
) : DocumentLoader {

    private val delegates: List<DocumentLoader> = listOf(textLoader)

    override val maxFileSizeBytes: Long
        get() = delegates.minOf { it.maxFileSizeBytes }

    override fun supports(mimeType: String, filename: String): Boolean =
        delegates.any { it.supports(mimeType, filename) }

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

        val delegate = delegates.firstOrNull { it.supports(mimeType, filename) }
            ?: throw UnsupportedDocumentFormatException(filename, mimeType)

        return delegate.load(fileBytes, filename, documentId, mimeType)
    }
}
