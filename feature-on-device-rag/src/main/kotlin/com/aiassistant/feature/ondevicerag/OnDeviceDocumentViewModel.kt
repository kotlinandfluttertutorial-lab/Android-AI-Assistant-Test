/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-rag
 * File       : OnDeviceDocumentViewModel.kt
 * Purpose    : Manages UI state for OnDeviceDocumentsScreen: file picker,
 *              ingestion progress, document list, low-storage warning, and delete.
 *
 * Architecture Layer : Feature (feature-on-device-rag) — MVVM ViewModel.
 *                      Delegates all business logic to domain use cases.
 *                      Never imports from data module or core-database directly.
 *
 * Dependencies       : domain use cases (OnDeviceIngestDocumentUseCase,
 *                      DeleteOnDeviceDocumentUseCase, GetOnDeviceDocumentsUseCase),
 *                      core-common (DispatcherProvider)
 *
 * Requirements: 33.1, 33.2, 33.3, 33.6, 33.7, 33.9, 33.10
 * ============================================================
 */
package com.aiassistant.feature.ondevicerag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.model.IngestionProgress
import com.aiassistant.domain.model.OnDeviceDocument
import com.aiassistant.domain.model.OnDeviceIngestionStatus
import com.aiassistant.domain.usecase.ondevicerag.DeleteOnDeviceDocumentUseCase
import com.aiassistant.domain.usecase.ondevicerag.GetOnDeviceDocumentsUseCase
import com.aiassistant.domain.usecase.ondevicerag.OnDeviceIngestDocumentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Maximum on-device document size: 50 MB. */
private const val MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024


@HiltViewModel
open class OnDeviceDocumentViewModel @Inject constructor(
    private val getDocumentsUseCase: GetOnDeviceDocumentsUseCase,
    private val ingestDocumentUseCase: OnDeviceIngestDocumentUseCase,
    private val deleteDocumentUseCase: DeleteOnDeviceDocumentUseCase,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    // Authenticated user ID — in production resolved from SecureStorage via the
    // repository layer; here defaulted so tests can inject a fixed value.
    var userId: String = "default_user"

    private val _uiState = MutableStateFlow<OnDeviceDocumentUiState>(OnDeviceDocumentUiState.Loading)
    val uiState: StateFlow<OnDeviceDocumentUiState> = _uiState.asStateFlow()

    // Live document list from Room
    private val _documents = MutableStateFlow<List<OnDeviceDocument>>(emptyList())

    init {
        observeDocuments()
    }

    // ── Public actions ────────────────────────────────────────────────────

    /**
     * Called when the user picks a file.  Validates size before calling use case.
     *
     * @param document   Pre-constructed [OnDeviceDocument] (id, name, mime, size).
     * @param rawText    Extracted text (caller or data layer extracts text before handing off).
     * @param sizeBytes  File size for the 50 MB guard.
     */
    fun ingestDocument(document: OnDeviceDocument, rawText: String, sizeBytes: Long) {
        // ── 50 MB guard (Requirement 33.2) ────────────────────────────────
        if (sizeBytes > MAX_FILE_SIZE_BYTES) {
            _uiState.value = OnDeviceDocumentUiState.FileSizeRejection(
                fileName = document.fileName,
                sizeBytes = sizeBytes,
            )
            return
        }

        // ── Low-storage check (Requirement 33.9) ─────────────────────────
        if (isLowStorage()) {
            _uiState.update { current ->
                val docs = (current as? OnDeviceDocumentUiState.DocumentList)?.documents
                    ?: _documents.value
                OnDeviceDocumentUiState.DocumentList(
                    documents = docs,
                    ingestionInProgress = false,
                    lowStorageWarning = true,
                )
            }
            return
        }

        viewModelScope.launch {
            ingestDocumentUseCase(document, rawText)
                .catch { e ->
                    _uiState.value = OnDeviceDocumentUiState.Error(
                        "Ingestion failed: ${e.message}"
                    )
                }
                .collect { progress ->
                    handleIngestionProgress(document, progress)
                }
        }
    }

    /**
     * Deletes [documentId] and all its embedding chunks.
     * Removes the entry from the list in [UiState] immediately.
     */
    fun deleteDocument(documentId: String) {
        viewModelScope.launch(dispatchers.io) {
            deleteDocumentUseCase(documentId, userId)
            // Room Flow update will refresh the list automatically.
        }
    }

    /**
     * Clears a [OnDeviceDocumentUiState.FileSizeRejection] state and returns to the
     * document list.
     */
    fun clearFileSizeRejection() {
        _uiState.value = OnDeviceDocumentUiState.DocumentList(
            documents = _documents.value,
            lowStorageWarning = false,
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun observeDocuments() {
        getDocumentsUseCase(userId)
            .onEach { docs ->
                _documents.value = docs
                // Only update to DocumentList when not currently ingesting
                if (_uiState.value !is OnDeviceDocumentUiState.IngestionRunning) {
                    _uiState.value = OnDeviceDocumentUiState.DocumentList(
                        documents = docs,
                        ingestionInProgress = docs.any {
                            it.ingestionStatus == OnDeviceIngestionStatus.PROCESSING
                        },
                        lowStorageWarning = isLowStorage(),
                    )
                }
            }
            .catch { e ->
                _uiState.value = OnDeviceDocumentUiState.Error("Failed to load documents: ${e.message}")
            }
            .launchIn(viewModelScope)
    }

    private fun handleIngestionProgress(document: OnDeviceDocument, progress: IngestionProgress) {
        when (progress) {
            is IngestionProgress.Complete -> {
                // Let the Room Flow update carry the final READY state
                _uiState.value = OnDeviceDocumentUiState.DocumentList(
                    documents = _documents.value,
                    ingestionInProgress = false,
                    lowStorageWarning = isLowStorage(),
                )
            }
            is IngestionProgress.Error -> {
                _uiState.value = OnDeviceDocumentUiState.DocumentList(
                    documents = _documents.value,
                    ingestionInProgress = false,
                    lowStorageWarning = isLowStorage(),
                )
            }
            else -> {
                _uiState.value = OnDeviceDocumentUiState.IngestionRunning(
                    documentId = document.id,
                    fileName = document.fileName,
                    progress = progress,
                    documents = _documents.value,
                )
            }
        }
    }

    /**
     * Stub storage check — in production reads from StatFs(context.filesDir).
     * Overrideable in tests by subclassing or using a production-injected provider.
     */
    internal open fun isLowStorage(): Boolean = false
}
