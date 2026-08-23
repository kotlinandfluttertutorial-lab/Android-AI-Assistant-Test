/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-resume
 * File       : ResumeViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Resume feature
 *
 * Architecture Layer : Feature (feature-resume)
 * Pattern Used       : MVVM ViewModel
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-resume
 * File       : ResumeViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Resume feature
 *
 * Architecture Layer : Feature (feature-resume)
 * Pattern Used       : MVVM ViewModel
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
/**
 * ResumeViewModel.kt
 *
 * Purpose: Manages all UI state and orchestrates use case calls for resume generation,
 *          cover letter generation, and content export.
 * Architecture: feature-resume â€” MVVM ViewModel; injected via Hilt.
 * Dependencies: domain (GenerateResumeUseCase, GenerateCoverLetterUseCase),
 *               core-common (DispatcherProvider, ApiResult, DomainError)
 *
 * Requirements: 14.1, 14.2, 14.3
 */
package com.aiassistant.feature.resume

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.usecase.resume.GenerateCoverLetterUseCase
import com.aiassistant.domain.usecase.resume.GenerateResumeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the resume builder and cover letter editor flows.
 *
 * Exposes a [StateFlow] of [ResumeUiState] that composables observe. All blocking work
 * (network calls, file I/O) is dispatched on [DispatcherProvider.io].
 */
@HiltViewModel
class ResumeViewModel @Inject constructor(
    private val generateResumeUseCase: GenerateResumeUseCase,
    private val generateCoverLetterUseCase: GenerateCoverLetterUseCase,
    private val dispatchers: DispatcherProvider,
    @ApplicationContext private val applicationContext: Context
) : ViewModel() {

    // â”€â”€â”€ State â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private val _uiState = MutableStateFlow<ResumeUiState>(ResumeUiState.Idle)

    /** Observable resume / cover letter UI state. */
    val uiState: StateFlow<ResumeUiState> = _uiState.asStateFlow()

    // â”€â”€â”€ Public API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Generates an ATS-optimised resume in Markdown format.
     *
     * THE AI_Orchestrator SHALL generate an ATS-optimized resume in Markdown format within
     * 30 seconds (Requirement 14.1).
     *
     * @param professionalHistory User's work experience, education, and skills.
     * @param jobDescription      Target job posting text to tailor the resume.
     */
    fun generateResume(professionalHistory: String, jobDescription: String) {
        viewModelScope.launch {
            _uiState.value = ResumeUiState.Loading("Generating ATS-optimized resumeâ€¦")
            val result = withContext(dispatchers.io) {
                generateResumeUseCase(professionalHistory, jobDescription)
            }
            _uiState.value = when (result) {
                is ApiResult.Success -> ResumeUiState.ResumeGenerated(result.data)
                is ApiResult.Error -> ResumeUiState.Error(result.error.message)
                is ApiResult.NetworkUnavailable -> ResumeUiState.Error(
                    "No network connection. Please check your connection and try again."
                )
                is ApiResult.Loading -> ResumeUiState.Loading("Generating ATS-optimized resumeâ€¦")
            }
        }
    }

    /**
     * Generates a tailored cover letter (â‰¤ 400 words).
     *
     * THE AI_Orchestrator SHALL generate a cover letter tailored to the provided job
     * description and resume data, not exceeding 400 words (Requirement 14.2).
     *
     * @param professionalHistory User's work experience, education, and skills.
     * @param jobDescription      Target job posting text to tailor the cover letter.
     */
    fun generateCoverLetter(professionalHistory: String, jobDescription: String) {
        viewModelScope.launch {
            _uiState.value = ResumeUiState.Loading("Generating cover letterâ€¦")
            val result = withContext(dispatchers.io) {
                generateCoverLetterUseCase(professionalHistory, jobDescription)
            }
            _uiState.value = when (result) {
                is ApiResult.Success -> ResumeUiState.CoverLetterGenerated(result.data)
                is ApiResult.Error -> ResumeUiState.Error(result.error.message)
                is ApiResult.NetworkUnavailable -> ResumeUiState.Error(
                    "No network connection. Please check your connection and try again."
                )
                is ApiResult.Loading -> ResumeUiState.Loading("Generating cover letterâ€¦")
            }
        }
    }

    /**
     * Exports [content] to the app's external Documents directory in the requested [format].
     *
     * Files are written to [Context.getExternalFilesDir] which requires no storage
     * permission on API 19+ (Requirement 14.3).
     *
     * @param content  The text content (Markdown or plain text) to export.
     * @param format   [ResumeExportFormat.PDF] or [ResumeExportFormat.DOCX].
     * @param fileName Base name for the output file (without extension).
     */
    fun exportContent(content: String, format: ResumeExportFormat, fileName: String = "resume_export") {
        viewModelScope.launch {
            _uiState.value = ResumeUiState.Exporting
            val result = withContext(dispatchers.io) {
                runCatching {
                    when (format) {
                        ResumeExportFormat.PDF -> exportAsPdf(content, fileName)
                        ResumeExportFormat.DOCX -> exportAsDocx(content, fileName)
                    }
                }
            }
            _uiState.value = result.fold(
                onSuccess = { file -> ResumeUiState.ExportSuccess(file.absolutePath, format) },
                onFailure = { e -> ResumeUiState.Error("Export failed: ${e.message}") }
            )
        }
    }

    /**
     * Resets the ViewModel back to [ResumeUiState.Idle].
     *
     * Call this when navigating away or when the user taps a "start over" action.
     */
    fun resetState() {
        _uiState.value = ResumeUiState.Idle
    }

    // â”€â”€â”€ Private export helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Renders [content] as a multi-page PDF using Android's built-in [PdfDocument] API.
     *
     * Each page is A4-sized (595 Ã— 842 points at 72 dpi). Long content flows across
     * multiple pages automatically.
     *
     * @return The written [File].
     */
    private fun exportAsPdf(content: String, fileName: String): File {
        val file = outputFile("$fileName.pdf")
        val document = PdfDocument()
        val paint = Paint().apply {
            textSize = 12f
            isAntiAlias = true
        }
        val pageWidth = 595 // A4 width in PDF user-space units (points)
        val pageHeight = 842 // A4 height in PDF user-space units
        val margin = 40f
        val lineHeight = paint.textSize * 1.4f
        val usableWidth = pageWidth - 2 * margin
        val usableHeight = pageHeight - 2 * margin

        // Split content into lines that fit the page width
        val wrappedLines = wrapTextToLines(content, usableWidth.toInt(), paint)

        var lineIndex = 0
        var pageNumber = 1

        while (lineIndex < wrappedLines.size) {
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            val page = document.startPage(pageInfo)
            val canvas: Canvas = page.canvas

            var y = margin + lineHeight
            while (lineIndex < wrappedLines.size && y + lineHeight <= margin + usableHeight) {
                canvas.drawText(wrappedLines[lineIndex], margin, y, paint)
                y += lineHeight
                lineIndex++
            }

            document.finishPage(page)
            pageNumber++
        }

        FileOutputStream(file).use { stream ->
            document.writeTo(stream)
        }
        document.close()
        return file
    }

    /**
     * Writes [content] as a minimal DOCX file (ZIP-based OOXML with a single document
     * part). The output is compatible with Microsoft Word and LibreOffice.
     *
     * @return The written [File].
     */
    private fun exportAsDocx(content: String, fileName: String): File {
        val file = outputFile("$fileName.docx")

        // Build Word XML body â€” each line becomes a paragraph element
        val paragraphs = content.lines().joinToString(separator = "\n") { line ->
            val escapedLine = line
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
            """<w:p><w:r><w:t xml:space="preserve">$escapedLine</w:t></w:r></w:p>"""
        }

        val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:wpc="http://schemas.microsoft.com/office/word/2010/wordprocessingCanvas"
            xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
$paragraphs
    <w:sectPr/>
  </w:body>
</w:document>"""

        val relsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1"
    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
    Target="word/document.xml"/>
</Relationships>"""

        val wordRelsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
</Relationships>"""

        val contentTypesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels"
    ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml"
    ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(contentTypesXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(relsXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(documentXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
            zip.write(wordRelsXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        return file
    }

    /**
     * Returns a [File] in the app's external Documents directory.
     * Creates the directory if it does not yet exist.
     */
    private fun outputFile(name: String): File {
        val dir = applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: applicationContext.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, name)
    }

    /**
     * Wraps [text] into a list of strings each of which fits within [maxWidthPx] pixels
     * when drawn with [paint]. Newlines in the source text force a new line.
     */
    private fun wrapTextToLines(text: String, maxWidthPx: Int, paint: Paint): List<String> {
        val result = mutableListOf<String>()
        for (sourceLine in text.lines()) {
            val words = sourceLine.split(" ")
            var current = StringBuilder()
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) <= maxWidthPx) {
                    current = StringBuilder(candidate)
                } else {
                    if (current.isNotEmpty()) result.add(current.toString())
                    current = StringBuilder(word)
                }
            }
            result.add(current.toString())
        }
        return result
    }
}
