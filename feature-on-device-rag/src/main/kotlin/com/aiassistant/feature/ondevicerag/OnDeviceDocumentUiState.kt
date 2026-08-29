/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-rag
 * File       : OnDeviceDocumentUiState.kt
 * Purpose    : Sealed class representing every observable UI state for the
 *              OnDeviceDocumentsScreen — document list, ingestion progress,
 *              file-size rejection, and low-storage warning.
 *
 * Architecture Layer : Feature (feature-on-device-rag) — presentation layer.
 *                      Consumed only by OnDeviceDocumentViewModel and
 *                      OnDeviceDocumentsScreen; never crosses module boundaries.
 *
 * Dependencies       : domain model (OnDeviceDocument, IngestionProgress)
 *
 * Requirements: 33.1, 33.2, 33.3, 33.6, 33.7, 33.9, 33.10
 * ============================================================
 */
package com.aiassistant.feature.ondevicerag

import com.aiassistant.domain.model.IngestionProgress
import com.aiassistant.domain.model.OnDeviceDocument

/**
 * All possible states of the OnDeviceDocumentsScreen.
 *
 * [OnDeviceDocumentViewModel] exposes a `StateFlow<OnDeviceDocumentUiState>`.
 */
sealed class OnDeviceDocumentUiState {

    /** Initial load in progress. */
    data object Loading : OnDeviceDocumentUiState()

    /**
     * Document list loaded — the primary steady state.
     *
     * @param documents            Current list of on-device documents.
     * @param ingestionInProgress  True while any document is actively being ingested.
     * @param lowStorageWarning    True when available storage < 100 MB; ingestion is paused.
     */
    data class DocumentList(
        val documents: List<OnDeviceDocument> = emptyList(),
        val ingestionInProgress: Boolean = false,
        val lowStorageWarning: Boolean = false,
    ) : OnDeviceDocumentUiState()

    /**
     * File rejected before any use case was called — size > 50 MB.
     *
     * @param fileName   Name of the rejected file for display.
     * @param sizeBytes  Actual file size for the error message.
     */
    data class FileSizeRejection(
        val fileName: String,
        val sizeBytes: Long,
    ) : OnDeviceDocumentUiState()

    /**
     * Ingestion is running for [documentId] and progress events are streaming in.
     *
     * @param documentId  ID of the document being ingested.
     * @param fileName    Display name for the progress indicator.
     * @param progress    The latest [IngestionProgress] event.
     * @param documents   Current full document list (shown behind the progress indicator).
     */
    data class IngestionRunning(
        val documentId: String,
        val fileName: String,
        val progress: IngestionProgress,
        val documents: List<OnDeviceDocument> = emptyList(),
    ) : OnDeviceDocumentUiState()

    /**
     * A non-recoverable error occurred.
     *
     * @param message Human-readable description.
     */
    data class Error(val message: String) : OnDeviceDocumentUiState()
}
