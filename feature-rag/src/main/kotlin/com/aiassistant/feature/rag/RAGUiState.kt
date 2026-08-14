/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-rag
 * File       : RAGUiState.kt
 * Purpose    : RAGUiState — feature-rag module component
 *
 * Architecture Layer : Feature (feature-rag)
 * Pattern Used       : UI State Data Class
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
 * File       : RAGUiState.kt
 * Purpose    : RAGUiState — feature-rag module component
 *
 * Architecture Layer : Feature (feature-rag)
 * Pattern Used       : UI State Data Class
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
 * RAGUiState.kt
 *
 * Purpose: Sealed class representing every observable UI state for the Document List screen,
 *          including upload lifecycle states.
 * Architecture: feature-rag â€” MVVM presentation layer.
 * Dependencies: domain (Document)
 *
 * Requirements: 4.1, 27.2, 27.5
 */
package com.aiassistant.feature.rag

import com.aiassistant.domain.model.Document

/**
 * Represents every possible UI state for the RAG Document List screen.
 *
 * [RAGViewModel] exposes a [kotlinx.coroutines.flow.StateFlow] of this sealed class.
 * Composables observe it and render accordingly.
 */
sealed class RAGUiState {

    /**
     * Document list is being loaded for the first time.
     * Show a loading indicator to the user.
     */
    data object Loading : RAGUiState()

    /**
     * Documents loaded successfully; the list (paged via Paging 3) is ready for display.
     *
     * @param isOffline When `true`, the device has no network connectivity and the
     *                  persistent [OfflineBanner] should be shown.
     */
    data class DocumentList(val isOffline: Boolean = false) : RAGUiState()

    /**
     * A non-recoverable error occurred loading documents.
     *
     * @param message Human-readable description of the error for display.
     */
    data class Error(val message: String) : RAGUiState()

    /**
     * A file upload is currently in progress.
     *
     * @param fileName   The name of the file being uploaded.
     * @param isOffline  When `true`, the device has no network connectivity.
     */
    data class UploadInProgress(val fileName: String, val isOffline: Boolean = false) : RAGUiState()

    /**
     * A file was successfully uploaded and the RAG ingestion job has been queued.
     *
     * @param document  The newly created [Document] with [IngestionStatus.PENDING] status.
     * @param isOffline When `true`, the device has no network connectivity.
     */
    data class UploadSuccess(val document: Document, val isOffline: Boolean = false) : RAGUiState()

    /**
     * A file upload failed before reaching the backend.
     *
     * @param message  Human-readable description of the failure.
     * @param isOffline When `true`, the device has no network connectivity.
     */
    data class UploadError(val message: String, val isOffline: Boolean = false) : RAGUiState()
}
