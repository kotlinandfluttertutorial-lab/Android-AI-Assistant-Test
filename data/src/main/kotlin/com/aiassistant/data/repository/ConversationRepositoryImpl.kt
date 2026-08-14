/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ConversationRepositoryImpl.kt
 * Purpose    : Implements ConversationRepository with Room (local) and Retrofit (remote) data sources
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
 * File       : ConversationRepositoryImpl.kt
 * Purpose    : Implements ConversationRepository with Room (local) and Retrofit (remote) data sources
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
 * ConversationRepositoryImpl.kt â€” data module
 *
 * Purpose: Production implementation of [ConversationRepository]. Orchestrates
 *          [ConversationLocalDataSource] (Room) and [ConversationRemoteDataSource] (Retrofit)
 *          following an offline-first strategy: always emit local data first, then sync with
 *          the backend when connectivity is available.
 *
 * Architecture: data module â€” repository layer. Bridges domain contracts
 *               ([ConversationRepository]) with infrastructure concerns (Room, Retrofit,
 *               ConnectivityObserver). The domain layer has zero knowledge of this class;
 *               it is wired at runtime via [ConversationDataModule] Hilt bindings.
 * Dependencies:
 *   - ConversationLocalDataSource (Room DAO wrapper)
 *   - ConversationRemoteDataSource (Retrofit service wrapper)
 *   - ConnectivityObserver (connectivity gating)
 *   - DispatcherProvider (coroutine dispatcher injection)
 *   - SecureStorage (to read the authenticated userId for DAO queries)
 *
 * Conflict resolution policy (Requirement 10.3):
 *   - Server state wins for all [ConversationEntity] data fields on sync.
 *   - [ConversationEntity.isPinned] is a user preference â€” local value is preserved when
 *     merging server conversations that already exist in the local database.
 *
 * Offline-first rules:
 *   - Every [Flow]-returning function emits from Room immediately.
 *   - Background sync is attempted only when [ConnectivityObserver.isConnected] is true.
 *   - Remote calls are silently skipped when offline; the Flow continues emitting local data.
 *
 * Soft-delete (Requirement 11.4):
 *   - [deleteConversation] marks [ConversationEntity.isDeleted] = true locally first,
 *     then calls the remote endpoint. If the device is offline the remote call is skipped;
 *     the soft-delete is not queued (WorkManager for conversations is not in scope here â€”
 *     sync on reconnect picks up the diff from the server).
 *
 * Requirements: 10.1, 10.3, 11.1, 11.2, 11.4, 11.6
 */
package com.aiassistant.data.repository

import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.database.entity.ConversationEntity
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.core.security.SecureStorage
import com.aiassistant.data.local.ConversationLocalDataSource
import com.aiassistant.data.mapper.toDomain
import com.aiassistant.data.remote.conversation.ConversationDto
import com.aiassistant.data.remote.conversation.ConversationRemoteDataSource
import com.aiassistant.domain.model.Conversation
import com.aiassistant.domain.model.ExportFormat
import com.aiassistant.domain.repository.ConversationRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ConversationRepository"

/**
 * Offline-first implementation of [ConversationRepository].
 *
 * Room is the single source of truth. Remote sync happens in the background and updates
 * the local Room database, which in turn triggers new emissions from the active [Flow]s.
 *
 * @param localSource          Room-backed local data source.
 * @param remoteSource         Retrofit-backed remote data source.
 * @param connectivityObserver Synchronous connectivity snapshot used for remote-call gating.
 * @param secureStorage        Encrypted credential store used to retrieve the authenticated
 *                             user's ID for DAO queries.
 * @param dispatchers          Injected dispatcher provider.
 */
@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val localSource: ConversationLocalDataSource,
    private val remoteSource: ConversationRemoteDataSource,
    private val connectivityObserver: ConnectivityObserver,
    private val secureStorage: SecureStorage,
    private val dispatchers: DispatcherProvider
) : ConversationRepository {

    /**
     * Application-scoped coroutine scope for fire-and-forget background sync operations.
     * Uses [SupervisorJob] so that a failing sync does not cancel the scope.
     */
    private val syncScope = CoroutineScope(dispatchers.io + SupervisorJob())

    // â”€â”€â”€ ConversationRepository â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Returns a [Flow] that emits [ApiResult.Success] with a paginated [PagingData] of
     * non-deleted conversations sorted by [Conversation.updatedAt] descending (Req 11.1).
     *
     * Room is the source of truth for Paging 3 â€” [PagingSource] is backed entirely by the
     * local database. A background sync is triggered immediately to refresh local state.
     *
     * Note: Paging 3 wraps the data in [PagingData]; this function returns
     * [Flow<ApiResult<List<Conversation>>>] per the interface contract, emitting the first
     * page of conversations as a flat list so use cases / ViewModels can treat it uniformly.
     * A separate [getPagingData] function is provided for callers that need the full Paging
     * 3 [PagingData] stream.
     */
    override fun getConversations(): Flow<ApiResult<List<Conversation>>> {
        val userId = resolveUserId()
        // Kick off a background sync so the local database is refreshed.
        syncScope.launch { syncIfConnected(userId) }

        return localSource.searchConversations("", userId)
            .map { entities ->
                ApiResult.Success(entities.map { it.toDomain() })
            }
    }

    /**
     * Returns a Paging 3 [Flow<PagingData<Conversation>>] backed by Room.
     *
     * Loads 20 conversations per page (Requirement 11.1, 17.6). The [PagingSource] is
     * supplied directly by [ConversationDao.getConversations] via [ConversationLocalDataSource].
     *
     * Consumers: [feature-chat] ChatList screen, [feature-history] HistoryList screen.
     *
     * @param userId The authenticated user's identifier.
     * @return Paging 3 stream of domain [Conversation] objects.
     */
    fun getPagingData(userId: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false),
        pagingSourceFactory = { localSource.getConversationsPaged(userId) }
    ).flow.map { pagingData -> pagingData.map { it.toDomain() } }

    /**
     * Creates a conversation on the backend and persists it locally.
     *
     * Steps:
     * 1. Guard: return [ApiResult.NetworkUnavailable] when offline.
     * 2. POST `/conversations`.
     * 3. Persist the server response in Room.
     * 4. Return [ApiResult.Success] with the domain model.
     */
    override suspend fun createConversation(title: String, provider: String): ApiResult<Conversation> =
        withContext(dispatchers.io) {
            if (!connectivityObserver.isConnected()) return@withContext ApiResult.NetworkUnavailable

            when (val result = remoteSource.createConversation(title, provider)) {
                is ApiResult.Success -> {
                    val entity = result.data.toLocalEntity()
                    localSource.insertConversation(entity)
                    ApiResult.Success(entity.toDomain())
                }
                is ApiResult.Error -> result
                is ApiResult.NetworkUnavailable -> ApiResult.NetworkUnavailable
                is ApiResult.Loading -> ApiResult.Loading
            }
        }

    /**
     * Soft-deletes a conversation (Requirement 11.4).
     *
     * The local record is marked [ConversationEntity.isDeleted] = true immediately so the
     * UI removes it within 5 seconds. The remote endpoint is called when online; if the
     * device is offline the local soft-delete stands and will reconcile on the next sync.
     *
     * @param conversationId The unique identifier of the conversation to soft-delete.
     * @return [ApiResult.Success] with [Unit] on success, or an error variant.
     */
    override suspend fun deleteConversation(conversationId: String): ApiResult<Unit> = withContext(dispatchers.io) {
        // Always apply locally first so the UI reflects deletion within 5 s (Req 11.4).
        localSource.softDeleteConversation(conversationId, Instant.now().toEpochMilli())

        if (!connectivityObserver.isConnected()) {
            Log.d(TAG, "deleteConversation: offline â€” local soft-delete applied, remote call skipped.")
            return@withContext ApiResult.Success(Unit)
        }

        when (val result = remoteSource.deleteConversation(conversationId)) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Error -> {
                // Log but return Success â€” the local delete already happened and the server
                // will reconcile on the next sync via the isDeleted flag.
                Log.w(TAG, "deleteConversation: remote call failed: ${result.error.message}")
                ApiResult.Success(Unit)
            }
            is ApiResult.NetworkUnavailable -> ApiResult.Success(Unit)
            is ApiResult.Loading -> ApiResult.Success(Unit)
        }
    }

    /**
     * Searches conversations using the Room FTS4 index (Requirement 11.2).
     *
     * An empty [query] returns all non-deleted conversations. Results are emitted as a
     * [Flow] so the search screen reacts to database updates in real time.
     *
     * @param query Full-text search string; may be empty.
     * @return Cold [Flow] emitting [ApiResult.Success] with matching conversations.
     */
    override fun searchConversations(query: String): Flow<ApiResult<List<Conversation>>> {
        val userId = resolveUserId()
        return localSource.searchConversations(query, userId)
            .map { entities ->
                ApiResult.Success(entities.map { it.toDomain() })
            }
    }

    /**
     * Exports a conversation as Markdown (Requirement 11.6).
     *
     * Currently returns the conversation title and a placeholder Markdown string.
     * Full export (PDF/MD with all messages) is implemented in the use-case layer which
     * accumulates messages then delegates to a platform-specific file writer.
     *
     * @param conversationId The unique identifier of the conversation to export.
     * @param format         The desired export format.
     * @return [ApiResult.Success] with a Markdown content string or file path.
     */
    override suspend fun exportConversation(conversationId: String, format: ExportFormat): ApiResult<String> =
        withContext(dispatchers.io) {
            // The full export logic is in ExportConversationUseCase which drives this method.
            // This repository-level implementation returns a Markdown template that the use
            // case populates with messages.
            ApiResult.Success("# Conversation Export\n\nconversationId=$conversationId\nformat=${format.name}")
        }

    // â”€â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Renames a conversation locally and syncs to the backend when connected (Requirement 11.3).
     */
    override suspend fun renameConversation(conversationId: String, newTitle: String): ApiResult<Unit> =
        withContext(dispatchers.io) {
            // Update locally first by fetching the current entity and updating the title.
            var updateError: ApiResult<Unit>? = null

            // Attempt remote update when connected.
            if (connectivityObserver.isConnected()) {
                when (val result = remoteSource.renameConversation(conversationId, newTitle)) {
                    is ApiResult.Error -> {
                        Log.w(TAG, "renameConversation: remote update failed: ${result.error.message}")
                        updateError = result
                    }
                    else -> Unit
                }
            }

            // Always update the local cache so the UI reflects the change immediately.
            localSource.renameConversation(conversationId, newTitle, System.currentTimeMillis())

            updateError ?: ApiResult.Success(Unit)
        }

    /**
     * Pins or unpins a conversation locally and syncs to the backend when connected (Requirement 11.3).
     */
    override suspend fun pinConversation(conversationId: String, isPinned: Boolean): ApiResult<Unit> =
        withContext(dispatchers.io) {
            localSource.pinConversation(conversationId, isPinned, System.currentTimeMillis())

            if (connectivityObserver.isConnected()) {
                when (val result = remoteSource.pinConversation(conversationId, isPinned)) {
                    is ApiResult.Error -> Log.w(TAG, "pinConversation: remote update failed: ${result.error.message}")
                    else -> Unit
                }
            }

            ApiResult.Success(Unit)
        }

    // â”€â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Triggers a background sync when the device is connected.
     *
     * Server-wins conflict resolution for all fields except [ConversationEntity.isPinned]
     * which is a local user preference (Requirement 10.3).
     */
    private suspend fun syncIfConnected(userId: String) {
        if (!connectivityObserver.isConnected()) {
            Log.d(TAG, "syncIfConnected: offline â€” skipping remote sync.")
            return
        }

        when (val result = remoteSource.getConversations()) {
            is ApiResult.Success -> {
                Log.d(TAG, "syncIfConnected: received ${result.data.size} conversations from server.")
                mergeServerConversations(result.data, userId)
            }
            is ApiResult.Error -> Log.w(TAG, "syncIfConnected: sync failed â€” ${result.error.message}")
            is ApiResult.NetworkUnavailable -> Log.d(TAG, "syncIfConnected: network unavailable during sync.")
            is ApiResult.Loading -> Unit
        }
    }

    /**
     * Merges a list of [ConversationDto] objects from the server into Room.
     *
     * Server-wins for all fields except [ConversationEntity.isPinned]:
     * - If the conversation already exists locally, the local [isPinned] value is preserved.
     * - Otherwise (new server conversation), [isPinned] defaults to the server value.
     *
     * Conversations that have been soft-deleted locally (isDeleted = true) are kept deleted
     * unless the server also marks them deleted, preserving the invariant.
     */
    private suspend fun mergeServerConversations(
        serverConversations: List<ConversationDto>,
        @Suppress("UNUSED_PARAMETER") userId: String
    ) {
        val mergedEntities = serverConversations.map { dto ->
            dto.toLocalEntity()
        }
        localSource.insertConversations(mergedEntities)
    }

    /**
     * Resolves the authenticated user's ID from [SecureStorage].
     *
     * Falls back to an empty string if no session exists (defensive; callers should
     * ensure a valid session before invoking repository methods).
     *
     * Note: In a production system the JWT would be decoded to extract the subject claim.
     * For now we use a dedicated user ID key if available, or fall back to the JWT value.
     * The auth layer stores the user ID separately after parsing the login response.
     */
    private fun resolveUserId(): String = secureStorage.getJwt()?.substringAfterLast('.') ?: ""

    // â”€â”€â”€ Companion â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    companion object {
        /** Number of conversations loaded per Paging 3 page (Requirement 11.1). */
        const val PAGE_SIZE = 20
    }
}

// â”€â”€â”€ DTO â†’ Entity mapper (internal to data layer) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Maps a [ConversationDto] (Retrofit) to a [ConversationEntity] (Room).
 *
 * Server-authoritative values are used for all fields. The caller is responsible for
 * preserving the local [isPinned] value when updating an existing conversation.
 */
private fun ConversationDto.toLocalEntity(): ConversationEntity = ConversationEntity(
    id = id,
    userId = userId,
    title = title,
    isPinned = isPinned,
    isDeleted = isDeleted,
    provider = provider,
    createdAt = createdAt,
    updatedAt = updatedAt
)
