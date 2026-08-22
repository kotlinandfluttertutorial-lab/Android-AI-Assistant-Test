/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : DocumentRepositoryImpl.kt
 * Purpose    : Implements DocumentRepository with Room (local) and Retrofit (remote) data sources
 *
 * Architecture Layer : Data
 * Pattern Used       : Repository Implementation (offline-first)
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
 * Module     : data
 * File       : DocumentRepositoryImpl.kt
 * Purpose    : Implements DocumentRepository with Room (local) and Retrofit (remote) data sources
 *
 * Architecture Layer : Data
 * Pattern Used       : Repository Implementation (offline-first)
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
 * DocumentRepositoryImpl.kt â€” data module
 *
 * Purpose: Production implementation of [DocumentRepository]. Orchestrates
 *          [DocumentDao] (Room local cache) and [DocumentRemoteDataSource] (Retrofit)
 *          for the RAG document pipeline.
 *
 * Architecture: data module â€” repository layer. Bridges domain contracts
 *               ([DocumentRepository]) with infrastructure concerns (Room, Retrofit,
 *               Android ContentResolver). The domain layer has zero knowledge of this
 *               class; it is wired at runtime via [DocumentDataModule] Hilt bindings.
 *
 * Offline strategy:
 *   - [getDocuments] emits from Room immediately (local cache as source of truth).
 *   - [uploadDocument] requires connectivity â€” returns [ApiResult.NetworkUnavailable] offline.
 *   - [getIngestionStatus] is remote-only â€” requires connectivity.
 *   - [deleteDocument] removes local cache immediately, then calls remote if connected.
 *
 * syncStatus transitions (Requirement 4.1):
 *   PENDING â†’ PROCESSING â†’ READY | FAILED
 *
 * Requirements: 4.1, 4.6, 4.10
 */
package com.aiassistant.data.repository

import android.content.Context
import android.util.Log
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.database.dao.DocumentDao
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.core.security.SecureStorage
import com.aiassistant.data.mapper.toDomain
import com.aiassistant.data.mapper.toEntity
import com.aiassistant.data.remote.document.DocumentRemoteDataSource
import com.aiassistant.domain.model.Document
import com.aiassistant.domain.model.IngestionStatus
import com.aiassistant.domain.repository.DocumentRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "DocumentRepository"

/**
 * Offline-first implementation of [DocumentRepository].
 *
 * Room is the source of truth for the document list. Remote calls are gated by
 * [ConnectivityObserver].
 *
 * @param documentDao          Room DAO for document entity persistence.
 * @param remoteSource         Retrofit-backed remote data source.
 * @param connectivityObserver Synchronous connectivity check.
 * @param secureStorage        Credential store used to resolve the authenticated user ID.
 * @param dispatchers          Injectable dispatcher provider.
 * @param context              Application context for ContentResolver file reading.
 */
@Singleton
class DocumentRepositoryImpl @Inject constructor(
    private val documentDao: DocumentDao,
    private val remoteSource: DocumentRemoteDataSource,
    private val connectivityObserver: ConnectivityObserver,
    private val secureStorage: SecureStorage,
    private val dispatchers: DispatcherProvider,
    @ApplicationContext private val context: Context
) : DocumentRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    // â”€â”€â”€ DocumentRepository â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Returns a [Flow] of documents owned by the authenticated user, sorted by upload date.
     *
     * Room is the source of truth; the flow emits immediately from the local cache.
     * Triggers a background sync to refresh the local cache from the remote source.
     */
    override fun getDocuments(): Flow<ApiResult<List<Document>>> {
        val userId = resolveUserId()

        // Background sync
        repositoryScope.launch {
            syncDocuments()
        }

        return documentDao.getDocumentsByUser(userId)
            .map { entities -> ApiResult.Success(entities.map { it.toDomain() }) }
    }

    /**
     * Synchronizes the local document cache with the remote backend.
     */
    private suspend fun syncDocuments() {
        if (!connectivityObserver.isConnected()) return

        val userId = resolveUserId()
        when (val result = remoteSource.getDocuments()) {
            is ApiResult.Success -> {
                val entities = result.data.map { it.toEntity(userId) }
                documentDao.insertDocuments(entities)
            }
            else -> { /* Log or handle error */ }
        }
    }

    /**
     * Uploads a document as a multipart POST to `/documents` (Requirement 4.1).
     *
     * Requires connectivity. On success, persists the returned [Document] locally with
     * [IngestionStatus.PENDING] so the UI shows the document immediately.
     *
     * @param fileUri   Android content URI pointing to the file.
     * @param fileName  Original file name.
     * @param mimeType  MIME type of the file.
     */
    override suspend fun uploadDocument(fileUri: String, fileName: String, mimeType: String): ApiResult<Document> =
        withContext(dispatchers.io) {
            if (!connectivityObserver.isConnected()) return@withContext ApiResult.NetworkUnavailable

            val bytes = readBytesFromUri(fileUri) ?: return@withContext ApiResult.Error(
                DomainError.ValidationError(message = "Could not read file at URI: $fileUri")
            )

            val requestBody = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("file", fileName, requestBody)

            val userId = resolveUserId()
            when (val result = remoteSource.uploadDocument(part)) {
                is ApiResult.Success -> {
                    val entity = result.data.toEntity(
                        userId = userId,
                        fileName = fileName,
                        mimeType = mimeType,
                        sizeBytes = bytes.size.toLong()
                    )
                    documentDao.insertDocument(entity)
                    ApiResult.Success(entity.toDomain())
                }
                is ApiResult.Error -> result
                is ApiResult.NetworkUnavailable -> ApiResult.NetworkUnavailable
                is ApiResult.Loading -> ApiResult.Loading
            }
        }

    /**
     * Polls `GET /jobs/{jobId}` for the ingestion status of a document (Requirement 4.1).
     *
     * Updates the local [DocumentDao] entry with the latest status so the UI reflects
     * the current state.
     *
     * @param documentId The unique identifier of the document to check.
     */
    override suspend fun getIngestionStatus(documentId: String): ApiResult<IngestionStatus> =
        withContext(dispatchers.io) {
            if (!connectivityObserver.isConnected()) return@withContext ApiResult.NetworkUnavailable

            // Resolve jobId from Room; fall back to documentId itself as job identifier.
            val jobId = resolveJobId(documentId) ?: documentId

            when (val result = remoteSource.getJobStatus(jobId)) {
                is ApiResult.Success -> {
                    val status = IngestionStatus.fromValue(result.data.status)
                    // Persist the error message alongside the status so the UI can show
                    // a human-readable failure reason without an extra network call.
                    updateLocalStatus(documentId, status, result.data.errorMessage)
                    ApiResult.Success(status)
                }
                is ApiResult.Error -> result
                is ApiResult.NetworkUnavailable -> ApiResult.NetworkUnavailable
                is ApiResult.Loading -> ApiResult.Loading
            }
        }

    /**
     * Submits a natural language query against the document's RAG index (Requirement 4.6).
     *
     * @param documentId The document to query against.
     * @param query      The user's question.
     */
    override suspend fun queryDocument(documentId: String, query: String): ApiResult<String> =
        withContext(dispatchers.io) {
            if (!connectivityObserver.isConnected()) return@withContext ApiResult.NetworkUnavailable

            when (val result = remoteSource.queryDocument(documentId, query)) {
                is ApiResult.Success -> ApiResult.Success(result.data.answer)
                is ApiResult.Error -> result
                is ApiResult.NetworkUnavailable -> ApiResult.NetworkUnavailable
                is ApiResult.Loading -> ApiResult.Loading
            }
        }

    /**
     * Deletes a document from both local Room and the backend (Requirement 4.10).
     *
     * Local delete happens immediately. Remote delete is attempted when connected.
     *
     * @param documentId The unique identifier of the document to delete.
     */
    override suspend fun deleteDocument(documentId: String): ApiResult<Unit> = withContext(dispatchers.io) {
        documentDao.deleteDocument(documentId)

        if (!connectivityObserver.isConnected()) {
            Log.d(TAG, "deleteDocument: offline â€” local delete applied, remote call skipped.")
            return@withContext ApiResult.Success(Unit)
        }

        when (val result = remoteSource.deleteDocument(documentId)) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Error -> {
                Log.w(TAG, "deleteDocument: remote call failed: ${result.error.message}")
                ApiResult.Success(Unit)
            }
            is ApiResult.NetworkUnavailable -> ApiResult.Success(Unit)
            is ApiResult.Loading -> ApiResult.Success(Unit)
        }
    }

    // â”€â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** Reads the raw bytes from an Android content URI using [ContentResolver]. */
    private fun readBytesFromUri(uriString: String): ByteArray? = try {
        val uri = android.net.Uri.parse(uriString)
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        Log.e(TAG, "readBytesFromUri: failed to read $uriString", e)
        null
    }

    /** Resolves the Celery job ID stored in the local Room record for a document. */
    private suspend fun resolveJobId(documentId: String): String? =
        // firstOrNull() terminates the flow after the first emission, avoiding
        // the infinite collect bug where collect {} never returns on a Room flow.
        documentDao.getDocumentById(documentId).firstOrNull()?.jobId

    /** Updates the [ingestionStatus] (and optionally [errorMessage]) of a document in Room. */
    private suspend fun updateLocalStatus(documentId: String, status: IngestionStatus, errorMessage: String? = null) {
        val entity = documentDao.getDocumentById(documentId).firstOrNull() ?: return
        documentDao.updateDocument(
            entity.copy(
                ingestionStatus = status.value,
                errorMessage = if (status == IngestionStatus.FAILED) errorMessage else null
            )
        )
    }

    /** Resolves the authenticated user's ID from [SecureStorage]. */
    private fun resolveUserId(): String = secureStorage.getJwt()?.substringAfterLast('.') ?: ""
}
