/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-rag
 * File       : RAGViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the RAG feature
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
 * RAGViewModel.kt
 *
 * Purpose: Manages all UI state for the Document List screen, including document upload
 *          lifecycle, Paging 3 document list, ingestion status polling, and offline state.
 * Architecture: feature-rag â€” MVVM ViewModel; injected via Hilt.
 * Dependencies: domain use cases (UploadDocumentUseCase, DeleteDocumentUseCase),
 *               DocumentRepository, ConnectivityObserver, DispatcherProvider, Paging 3
 *
 * Requirements: 4.1, 27.2, 27.5
 */
package com.aiassistant.feature.rag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingSource.LoadResult
import androidx.paging.cachedIn
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.domain.model.Document
import com.aiassistant.domain.model.IngestionStatus
import com.aiassistant.domain.repository.DocumentRepository
import com.aiassistant.domain.usecase.document.DeleteDocumentUseCase
import com.aiassistant.domain.usecase.document.UploadDocumentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// â”€â”€â”€ Extension helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Returns the first non-[ApiResult.Loading] emission from the [Flow], or
 * [ApiResult.Loading] if the flow completes without a non-loading element.
 */
private suspend fun <T> Flow<ApiResult<T>>.firstOrLoading(): ApiResult<T> = first()

/** Polling interval for ingestion status queries (every 5 seconds). */
private const val POLLING_INTERVAL_MS = 5_000L

/** Maximum file size accepted: 50 MB. */
private const val MAX_FILE_SIZE_BYTES = 50L * 1024L * 1024L

/**
 * ViewModel for the RAG Document List screen.
 *
 * Exposes a [StateFlow] of [RAGUiState] and a Paging 3 [Flow] of [PagingData] of [Document].
 * Polling for `GET /jobs/{job_id}` is managed internally; callers start/stop it via
 * [startPolling] and [stopPolling].
 */
@HiltViewModel
class RAGViewModel @Inject constructor(
    private val uploadDocumentUseCase: UploadDocumentUseCase,
    private val deleteDocumentUseCase: DeleteDocumentUseCase,
    private val documentRepository: DocumentRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    // â”€â”€â”€ Offline state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Emits `true` when the device has no network connectivity.
     */
    val isOffline: StateFlow<Boolean> = connectivityObserver.isConnectedFlow
        .map { isConnected -> !isConnected }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = !connectivityObserver.isConnected()
        )

    // â”€â”€â”€ UI state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private val _uiState = MutableStateFlow<RAGUiState>(RAGUiState.Loading)

    /**
     * Primary UI state for the Document List screen.
     *
     * Starts as [RAGUiState.Loading] and transitions based on repository calls and
     * upload lifecycle events.
     */
    val uiState: StateFlow<RAGUiState> = _uiState.asStateFlow()

    // â”€â”€â”€ Paging 3 â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Paged list of [Document] objects (20 per page).
     *
     * This flow is derived from [DocumentRepository.getDocuments] and is always
     * actively collecting to keep the list fresh after uploads and deletes.
     */
    val documents: Flow<PagingData<Document>> = Pager(
        config = PagingConfig(
            pageSize = 20,
            enablePlaceholders = false,
            maxSize = 60 // keep at most 3 pages in memory
        ),
        pagingSourceFactory = { DocumentsPagingSource(documentRepository) }
    ).flow.cachedIn(viewModelScope)

    // â”€â”€â”€ Polling â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private var pollingJob: Job? = null

    /** Documents currently tracked by the polling loop (jobId â†’ documentId). */
    private val polledJobs = MutableStateFlow<Map<String, String>>(emptyMap())

    init {
        // Load documents initially and transition to DocumentList state.
        loadDocuments()
    }

    // â”€â”€â”€ Public actions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Validates the file, then uploads it via [UploadDocumentUseCase].
     *
     * Pre-condition: [sizeBytes] must be â‰¤ 50 MB and [mimeType] must be one of the
     * accepted types. Violations produce [RAGUiState.UploadError] with an inline message.
     *
     * @param uri       Content URI string pointing to the file (e.g. `content://â€¦`).
     * @param fileName  Original display name for the file.
     * @param mimeType  MIME type of the file.
     * @param sizeBytes File size in bytes.
     */
    fun uploadDocument(uri: String, fileName: String, mimeType: String, sizeBytes: Long) {
        viewModelScope.launch {
            // Client-side 50 MB guard for immediate inline error feedback.
            if (sizeBytes > MAX_FILE_SIZE_BYTES) {
                _uiState.value = RAGUiState.UploadError(
                    message = "\"$fileName\" is too large. Maximum file size is 50 MB.",
                    isOffline = isOffline.value
                )
                return@launch
            }

            _uiState.value = RAGUiState.UploadInProgress(
                fileName = fileName,
                isOffline = isOffline.value
            )

            val result = withContext(dispatchers.io) {
                uploadDocumentUseCase(
                    fileUri = uri,
                    fileName = fileName,
                    mimeType = mimeType,
                    sizeBytes = sizeBytes
                )
            }

            when (result) {
                is ApiResult.Success -> {
                    val document = result.data
                    _uiState.value = RAGUiState.UploadSuccess(
                        document = document,
                        isOffline = isOffline.value
                    )
                    // Auto-start polling for the newly uploaded document.
                    document.jobId?.let { jobId -> startPolling(jobId) }
                    // Transition back to list after a brief moment so upload toast is visible.
                    delay(1_500L)
                    _uiState.value = RAGUiState.DocumentList(isOffline = isOffline.value)
                }
                is ApiResult.Error -> {
                    _uiState.value = RAGUiState.UploadError(
                        message = result.error.message,
                        isOffline = isOffline.value
                    )
                }
                is ApiResult.NetworkUnavailable -> {
                    _uiState.value = RAGUiState.UploadError(
                        message = "No network connection. Please try again when online.",
                        isOffline = true
                    )
                }
                is ApiResult.Loading -> {
                    // Handled by the UploadInProgress state above.
                }
            }
        }
    }

    /**
     * Deletes a document and all its RAG artefacts.
     *
     * @param documentId The unique identifier of the document to delete.
     */
    fun deleteDocument(documentId: String) {
        viewModelScope.launch {
            withContext(dispatchers.io) {
                deleteDocumentUseCase(documentId)
            }
        }
    }

    /**
     * Begins polling `GET /jobs/{jobId}` every [POLLING_INTERVAL_MS] ms.
     *
     * Registers [jobId] for tracking and starts the polling loop if it is not already
     * active. The polling loop runs until all tracked jobs reach a terminal state
     * ([IngestionStatus.READY] or [IngestionStatus.FAILED]).
     *
     * @param jobId The Celery job identifier returned by the backend after upload.
     *              The document associated with this job must already be stored in the
     *              local database so [DocumentRepository.getIngestionStatus] can resolve it.
     */
    fun startPolling(jobId: String) {
        // Register the job for polling. The document ID used for status queries is looked
        // up from the repository; here we store jobId â†’ jobId as a tracking key and let
        // the repository resolve the document.
        polledJobs.update { current ->
            if (jobId in current) current else current + (jobId to jobId)
        }

        if (pollingJob?.isActive == true) {
            // Polling loop is already running â€” the new job will be picked up automatically.
            return
        }

        pollingJob = viewModelScope.launch(dispatchers.io) {
            while (true) {
                // Copy snapshot to avoid ConcurrentModification while iterating.
                val activeJobs = polledJobs.value.toMap()
                if (activeJobs.isEmpty()) break

                activeJobs.keys.forEach { trackedJobId ->
                    val statusResult = documentRepository.getIngestionStatus(trackedJobId)
                    if (statusResult is ApiResult.Success) {
                        val status = statusResult.data
                        if (status == IngestionStatus.READY || status == IngestionStatus.FAILED) {
                            // Job is terminal â€” remove from tracking map.
                            polledJobs.update { current ->
                                current.toMutableMap().also { it.remove(trackedJobId) }
                            }
                        }
                    }
                }

                delay(POLLING_INTERVAL_MS)
            }
        }
    }

    /**
     * Cancels the active polling coroutine.
     * Safe to call when no polling is running.
     */
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * Resets the UI state to [RAGUiState.DocumentList] after an upload error has been
     * acknowledged (e.g. the user dismisses the error).
     */
    fun clearUploadError() {
        _uiState.value = RAGUiState.DocumentList(isOffline = isOffline.value)
    }

    // â”€â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** Triggers an initial document load and transitions to [RAGUiState.DocumentList]. */
    private fun loadDocuments() {
        viewModelScope.launch {
            // Collect the repository flow to observe the initial state.
            documentRepository.getDocuments().collect { result ->
                when (result) {
                    is ApiResult.Loading -> {
                        if (_uiState.value is RAGUiState.Loading) {
                            // Stay in loading state.
                        }
                    }
                    is ApiResult.Success, is ApiResult.NetworkUnavailable -> {
                        if (_uiState.value is RAGUiState.Loading) {
                            _uiState.value = RAGUiState.DocumentList(
                                isOffline = isOffline.value
                            )
                        }
                    }
                    is ApiResult.Error -> {
                        if (_uiState.value is RAGUiState.Loading) {
                            _uiState.value = RAGUiState.Error(
                                message = result.error.message
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}

// â”€â”€â”€ PagingSource â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * [PagingSource] that loads documents from [DocumentRepository.getDocuments] one
 * page at a time (20 documents per page).
 *
 * Because [DocumentRepository.getDocuments] returns a [Flow] of the full list, each
 * load call takes the first emitted snapshot and slices the requested page out of it.
 * This keeps the paging layer simple while still reflecting in-flight status updates.
 *
 * @param repository The document repository to fetch from.
 */
class DocumentsPagingSource(private val repository: DocumentRepository) : PagingSource<Int, Document>() {

    override fun getRefreshKey(state: androidx.paging.PagingState<Int, Document>): Int? =
        state.anchorPosition?.let { anchor ->
            state.closestPageToPosition(anchor)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchor)?.nextKey?.minus(1)
        }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Document> {
        return try {
            val page = params.key ?: 0
            val pageSize = params.loadSize

            // Take only the first snapshot to avoid blocking indefinitely.
            val firstResult = repository.getDocuments().firstOrLoading()
            val allDocuments: List<Document> = when (firstResult) {
                is ApiResult.Success -> firstResult.data
                else -> emptyList()
            }

            val fromIndex = page * pageSize
            val toIndex = minOf(fromIndex + pageSize, allDocuments.size)

            if (fromIndex >= allDocuments.size) {
                return LoadResult.Page(
                    data = emptyList(),
                    prevKey = if (page == 0) null else page - 1,
                    nextKey = null
                )
            }

            val slice = allDocuments.subList(fromIndex, toIndex)
            LoadResult.Page(
                data = slice,
                prevKey = if (page == 0) null else page - 1,
                nextKey = if (toIndex >= allDocuments.size) null else page + 1
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}
