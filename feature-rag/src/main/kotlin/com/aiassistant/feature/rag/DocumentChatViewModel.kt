/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-rag
 * File       : DocumentChatViewModel.kt
 * Purpose    : Manages UI state for the DocumentChat screen.
 *              Uses QueryDocumentWithSourcesUseCase (POST /api/v1/rag/query)
 *              for structured citations; falls back to QueryDocumentUseCase
 *              (text parsing) when the structured endpoint is unavailable.
 *
 * Architecture Layer : Feature (feature-rag) — MVVM ViewModel
 * Requirements       : 4.6, 4.7
 * ============================================================
 */

package com.aiassistant.feature.rag

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.model.RagCitationSource
import com.aiassistant.domain.repository.DocumentRepository
import com.aiassistant.domain.usecase.document.QueryDocumentUseCase
import com.aiassistant.domain.usecase.document.QueryDocumentWithSourcesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the DocumentChat screen.
 *
 * ## Query strategy
 * [submitQuery] first calls [QueryDocumentWithSourcesUseCase] which posts to
 * `POST /api/v1/rag/query` and returns a structured [com.aiassistant.domain.model.RagAnswer]
 * with per-source excerpts and similarity scores.
 *
 * If the structured endpoint fails (e.g. older backend without `/rag/query`), the ViewModel
 * automatically falls back to [QueryDocumentUseCase] and parses citations from the response
 * text using [parseResponse].
 *
 * ## Citation rendering
 * `DocumentChatUiState.Success.ragAnswer.sources` carries [RagCitationSource] objects
 * suitable for rendering excerpt previews and score badges.
 * `DocumentChatUiState.Success.exchange.sources` mirrors the same list for
 * `SourcesPanel` in the Compose layer.
 */
@HiltViewModel
class DocumentChatViewModel @Inject constructor(
    private val queryDocumentWithSourcesUseCase: QueryDocumentWithSourcesUseCase,
    private val queryDocumentUseCase: QueryDocumentUseCase,
    private val documentRepository: DocumentRepository,
    private val dispatchers: DispatcherProvider,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Document ID extracted from the navigation back-stack entry. */
    private val documentId: String = checkNotNull(savedStateHandle["documentId"]) {
        "DocumentChatViewModel requires a 'documentId' navigation argument."
    }

    private val _uiState = MutableStateFlow<DocumentChatUiState>(DocumentChatUiState.Idle())
    val uiState: StateFlow<DocumentChatUiState> = _uiState.asStateFlow()

    init {
        loadDocumentFileName()
    }

    // ── Public actions ────────────────────────────────────────────────────────

    /**
     * Submits a natural language question to the RAG pipeline.
     *
     * Strategy:
     * 1. Try [QueryDocumentWithSourcesUseCase] → structured [RagAnswer] with excerpts + scores.
     * 2. On any error, fall back to [QueryDocumentUseCase] + [parseResponse] for legacy backends.
     *
     * @param query The user's question.  Blank values are silently ignored.
     */
    fun submitQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return

        val currentFileName = currentDocumentFileName()
        _uiState.value = DocumentChatUiState.Loading(
            query = trimmed,
            documentFileName = currentFileName,
        )

        viewModelScope.launch {
            // ── Strategy 1: structured /rag/query ────────────────────────────
            val structuredResult = withContext(dispatchers.io) {
                queryDocumentWithSourcesUseCase(
                    question = trimmed,
                    documentIds = listOf(documentId),
                )
            }

            if (structuredResult is ApiResult.Success) {
                val ragAnswer = structuredResult.data
                _uiState.value = DocumentChatUiState.Success(
                    exchange = RAGExchange(
                        userQuery = trimmed,
                        aiResponse = ragAnswer.answer,
                        sources = ragAnswer.sources,
                    ),
                    documentFileName = currentFileName,
                    ragAnswer = ragAnswer,
                )
                return@launch
            }

            // ── Strategy 2: legacy /documents/query + text parsing ────────────
            val legacyResult = withContext(dispatchers.io) {
                queryDocumentUseCase(documentId = documentId, query = trimmed)
            }

            _uiState.value = when (legacyResult) {
                is ApiResult.Success -> {
                    val (responseText, citations) = parseResponse(legacyResult.data)
                    DocumentChatUiState.Success(
                        exchange = RAGExchange(
                            userQuery = trimmed,
                            aiResponse = responseText,
                            citations = citations,
                        ),
                        documentFileName = currentFileName,
                    )
                }

                is ApiResult.Error -> DocumentChatUiState.Error(
                    message = legacyResult.error.message,
                    lastQuery = trimmed,
                    documentFileName = currentFileName,
                )

                is ApiResult.NetworkUnavailable -> DocumentChatUiState.Error(
                    message = "No network connection. Please check your connectivity and try again.",
                    lastQuery = trimmed,
                    documentFileName = currentFileName,
                )

                is ApiResult.Loading -> DocumentChatUiState.Loading(
                    query = trimmed,
                    documentFileName = currentFileName,
                )
            }
        }
    }

    /**
     * Resets to [DocumentChatUiState.Idle] so the user can submit another query.
     * Preserves the document file name.
     */
    fun resetToIdle() {
        _uiState.value = DocumentChatUiState.Idle(
            documentFileName = currentDocumentFileName()
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Loads the document's display name for the TopAppBar title. */
    private fun loadDocumentFileName() {
        viewModelScope.launch {
            try {
                val result = withContext(dispatchers.io) {
                    documentRepository.getDocuments().first()
                }
                if (result is ApiResult.Success) {
                    val doc = result.data.firstOrNull { it.id == documentId }
                    val name = doc?.fileName ?: documentId
                    updateDocumentFileName(name)
                }
            } catch (_: Exception) {
                // Non-critical — the screen still works without the file name.
            }
        }
    }

    private fun currentDocumentFileName(): String = when (val s = _uiState.value) {
        is DocumentChatUiState.Idle    -> s.documentFileName.ifEmpty { documentId }
        is DocumentChatUiState.Loading -> s.documentFileName.ifEmpty { documentId }
        is DocumentChatUiState.Success -> s.documentFileName.ifEmpty { documentId }
        is DocumentChatUiState.Error   -> s.documentFileName.ifEmpty { documentId }
    }

    private fun updateDocumentFileName(name: String) {
        _uiState.value = when (val s = _uiState.value) {
            is DocumentChatUiState.Idle    -> s.copy(documentFileName = name)
            is DocumentChatUiState.Loading -> s.copy(documentFileName = name)
            is DocumentChatUiState.Success -> s.copy(documentFileName = name)
            is DocumentChatUiState.Error   -> s.copy(documentFileName = name)
        }
    }

    /**
     * Parses legacy response text for embedded citation markers.
     *
     * Handles two formats:
     * - **Format A** — "Sources:" / "References:" section at the end with numbered entries.
     * - **Format B** — bare numbered list at the end without a section header.
     *
     * Returns the clean response text plus a [Citation] list.  When no pattern
     * is detected, returns the full text with an empty list.
     *
     * @internal Visible for testing.
     */
    internal fun parseResponse(raw: String): Pair<String, List<Citation>> {
        val referencesSectionRegex = Regex(
            """(?:^|\n)(?:Sources|References|Citations):\s*\n((?:\[\d+\][^\n]+\n?)+)""",
            RegexOption.IGNORE_CASE
        )
        val refLineRegex = Regex(
            """^\[(\d+)]\s+(.+?),\s*(?:page|p\.?)\s+(\d+)\s*$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )

        // Format A
        val sectionMatch = referencesSectionRegex.find(raw)
        if (sectionMatch != null) {
            val responseText = raw.substring(0, sectionMatch.range.first).trimEnd()
            val refBlock = sectionMatch.groupValues[1]
            val citations = refLineRegex.findAll(refBlock).map { m ->
                Citation(
                    documentName = m.groupValues[2].trim(),
                    pageNumber = m.groupValues[3].toIntOrNull()
                )
            }.toList()
            return Pair(responseText, citations)
        }

        // Format B
        val lines = raw.trimEnd().lines()
        val lastRefIndex = lines.indexOfLast { refLineRegex.matches(it.trim()) }
        if (lastRefIndex != -1) {
            var firstRefIndex = lastRefIndex
            while (firstRefIndex > 0 && refLineRegex.matches(lines[firstRefIndex - 1].trim())) {
                firstRefIndex--
            }
            val responseText = lines.take(firstRefIndex).joinToString("\n").trimEnd()
            val citations = lines.subList(firstRefIndex, lastRefIndex + 1).mapNotNull { line ->
                val m = refLineRegex.find(line.trim()) ?: return@mapNotNull null
                Citation(
                    documentName = m.groupValues[2].trim(),
                    pageNumber = m.groupValues[3].toIntOrNull()
                )
            }
            if (citations.isNotEmpty()) return Pair(responseText, citations)
        }

        return Pair(raw, emptyList())
    }
}
