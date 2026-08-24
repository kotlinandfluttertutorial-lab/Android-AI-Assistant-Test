/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-rag
 * File       : DocumentChatViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the DocumentChat feature
 *
 * Architecture Layer : Feature (feature-rag)
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
 * Module     : feature-rag
 * File       : DocumentChatViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the DocumentChat feature
 *
 * Architecture Layer : Feature (feature-rag)
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
 * DocumentChatViewModel.kt
 *
 * Purpose: Manages all UI state for the DocumentChat screen, including query submission,
 *          RAG response with citations, and error handling.
 * Architecture: feature-rag â€” MVVM ViewModel; injected via Hilt assisted injection.
 * Dependencies: domain (QueryDocumentUseCase, DocumentRepository),
 *               core-common (ApiResult, DispatcherProvider)
 *
 * Requirements: 4.6, 4.7
 */
package com.aiassistant.feature.rag

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.repository.DocumentRepository
import com.aiassistant.domain.usecase.document.QueryDocumentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the DocumentChat screen.
 *
 * Exposes a [StateFlow] of [DocumentChatUiState] for Compose screens to observe.
 * The [documentId] is read from [SavedStateHandle] (injected by Hilt / Navigation).
 *
 * Citation parsing:
 * The backend embeds citation markers in the response text (e.g. "[1]", "[2]") and
 * appends a references section at the end in the format:
 * ```
 * [1] annual_report.pdf, page 5
 * [2] product_spec.docx, page 12
 * ```
 * [parseCitations] extracts these references and maps them to [Citation] objects.
 * If the response does not contain a references section the citations list is empty
 * (the response text is shown as-is).
 *
 * @param queryDocumentUseCase Use case wrapping the RAG query repository call.
 * @param documentRepository   Used to resolve the document file name from its ID.
 * @param dispatchers          Coroutine dispatcher provider for background IO.
 * @param savedStateHandle     Provides the "documentId" navigation argument.
 */
@HiltViewModel
class DocumentChatViewModel @Inject constructor(
    private val queryDocumentUseCase: QueryDocumentUseCase,
    private val documentRepository: DocumentRepository,
    private val dispatchers: DispatcherProvider,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** The document ID extracted from the navigation back-stack entry. */
    private val documentId: String = checkNotNull(savedStateHandle["documentId"]) {
        "DocumentChatViewModel requires a 'documentId' navigation argument."
    }

    private val _uiState = MutableStateFlow<DocumentChatUiState>(DocumentChatUiState.Idle())
    val uiState: StateFlow<DocumentChatUiState> = _uiState.asStateFlow()

    init {
        loadDocumentFileName()
    }

    // â”€â”€â”€ Public actions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Submits a natural language query to the RAG pipeline for [documentId].
     *
     * On success the state transitions to [DocumentChatUiState.Success] with the AI
     * response and parsed [Citation] list. On failure it transitions to
     * [DocumentChatUiState.Error] so the user can retry.
     *
     * @param query The user's natural language question. Blank queries are silently ignored.
     */
    fun submitQuery(query: String) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return

        val currentFileName = currentDocumentFileName()

        _uiState.value = DocumentChatUiState.Loading(
            query = trimmedQuery,
            documentFileName = currentFileName
        )

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                queryDocumentUseCase(documentId = documentId, query = trimmedQuery)
            }

            _uiState.value = when (result) {
                is ApiResult.Success -> {
                    val (responseText, citations) = parseResponse(result.data)
                    DocumentChatUiState.Success(
                        exchange = RAGExchange(
                            userQuery = trimmedQuery,
                            aiResponse = responseText,
                            citations = citations
                        ),
                        documentFileName = currentFileName
                    )
                }

                is ApiResult.Error -> DocumentChatUiState.Error(
                    message = result.error.message,
                    lastQuery = trimmedQuery,
                    documentFileName = currentFileName
                )

                is ApiResult.NetworkUnavailable -> DocumentChatUiState.Error(
                    message = "No network connection. Please check your connectivity and try again.",
                    lastQuery = trimmedQuery,
                    documentFileName = currentFileName
                )

                is ApiResult.Loading -> {
                    // Stays in Loading state â€” shouldn't happen for a suspend call but handle defensively.
                    DocumentChatUiState.Loading(
                        query = trimmedQuery,
                        documentFileName = currentFileName
                    )
                }
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

    // â”€â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Loads the document file name from the repository to display in the top bar.
     * Falls back to the raw [documentId] if the fetch fails.
     */
    private fun loadDocumentFileName() {
        viewModelScope.launch {
            try {
                val documents = withContext(dispatchers.io) {
                    documentRepository.getDocuments()
                }
                documents.collect { result ->
                    if (result is ApiResult.Success) {
                        val doc = result.data.firstOrNull { it.id == documentId }
                        val name = doc?.fileName ?: documentId
                        // Update state with the resolved file name without changing the state type.
                        updateDocumentFileName(name)
                    }
                }
            } catch (_: Exception) {
                // Non-critical â€” the screen still works without the file name.
            }
        }
    }

    /** Returns the document file name from the current UI state, falling back to the ID. */
    private fun currentDocumentFileName(): String = when (val s = _uiState.value) {
        is DocumentChatUiState.Idle -> s.documentFileName.ifEmpty { documentId }
        is DocumentChatUiState.Loading -> s.documentFileName.ifEmpty { documentId }
        is DocumentChatUiState.Success -> s.documentFileName.ifEmpty { documentId }
        is DocumentChatUiState.Error -> s.documentFileName.ifEmpty { documentId }
    }

    /** Updates the [documentFileName] field in whatever state we are currently in. */
    private fun updateDocumentFileName(name: String) {
        _uiState.value = when (val s = _uiState.value) {
            is DocumentChatUiState.Idle -> s.copy(documentFileName = name)
            is DocumentChatUiState.Loading -> s.copy(documentFileName = name)
            is DocumentChatUiState.Success -> s.copy(documentFileName = name)
            is DocumentChatUiState.Error -> s.copy(documentFileName = name)
        }
    }

    /**
     * Parses the raw response string returned by the backend.
     *
     * The backend may append a "References" or "Sources" section in one of two formats:
     *
     * Format A (newline-separated markers):
     * ```
     * [1] annual_report.pdf, page 5
     * [2] product_spec.docx, page 12
     * ```
     *
     * Format B (compact inline, no section header):
     * The response text contains `[docName, page N]` markers inline.
     *
     * If neither pattern is detected the full response is returned as-is with no citations.
     *
     * @return A [Pair] of the cleaned response text and the parsed [Citation] list.
     */
    internal fun parseResponse(raw: String): Pair<String, List<Citation>> {
        // â”€â”€ Format A: numbered reference list at the end â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // Look for a "Sources:" / "References:" section or a line matching "[N] name, page P".
        val referencesSectionRegex = Regex(
            """(?:^|\n)(?:Sources|References|Citations):\s*\n((?:\[\d+\][^\n]+\n?)+)""",
            RegexOption.IGNORE_CASE
        )
        val refLineRegex = Regex(
            """^\[(\d+)]\s+(.+?),\s*(?:page|p\.?)\s+(\d+)\s*$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )

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

        // â”€â”€ Format B: bare numbered lines at the end (no section header) â”€â”€â”€â”€â”€
        val lines = raw.trimEnd().lines()
        val lastRefIndex = lines.indexOfLast { refLineRegex.matches(it.trim()) }
        if (lastRefIndex != -1) {
            // Walk backwards from the last ref line to find where refs begin.
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
            if (citations.isNotEmpty()) {
                return Pair(responseText, citations)
            }
        }

        // No parseable citation block found â€” return as-is with empty list.
        return Pair(raw, emptyList())
    }
}
