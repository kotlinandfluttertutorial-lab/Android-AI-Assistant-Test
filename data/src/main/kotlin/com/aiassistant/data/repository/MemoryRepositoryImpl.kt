/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : MemoryRepositoryImpl.kt
 * Purpose    : Implements MemoryRepository with Room (local) and Retrofit (remote) data sources
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
 * File       : MemoryRepositoryImpl.kt
 * Purpose    : Implements MemoryRepository with Room (local) and Retrofit (remote) data sources
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
 * MemoryRepositoryImpl.kt — data module
 *
 * Purpose: Production implementation of [MemoryRepository].
 *          All operations go directly to the remote Memory Service via `/memory/...`
 *          endpoints. No local Room cache is used (memories contain sensitive user data).
 *
 * Architecture: data module — repository layer. Bridges domain contracts
 *               ([MemoryRepository]) with [MemoryRemoteDataSource]. Wired at runtime
 *               via [MemoryDataModule] Hilt bindings.
 *
 * Design:
 *   - [getMemories] returns a cold [Flow] that emits [ApiResult.Loading] then the
 *     remote result. There is no local cache; every collector triggers a fresh fetch.
 *   - All operations require connectivity; [ApiResult.NetworkUnavailable] is returned
 *     when offline.
 *
 * Requirements: 7.3 (view/edit/delete), 7.4 (embedding removal within 10 s)
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.data.mapper.toDomain
import com.aiassistant.data.remote.memory.MemoryRemoteDataSource
import com.aiassistant.domain.model.Memory
import com.aiassistant.domain.repository.MemoryRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Remote-only implementation of [MemoryRepository].
 *
 * Memories are never persisted locally. Every operation calls the backend directly.
 *
 * @param remoteSource         Retrofit-backed memory data source.
 * @param connectivityObserver Synchronous connectivity check.
 * @param dispatchers          Injectable dispatcher provider.
 */
@Singleton
class MemoryRepositoryImpl @Inject constructor(
    private val remoteSource: MemoryRemoteDataSource,
    private val connectivityObserver: ConnectivityObserver,
    private val dispatchers: DispatcherProvider
) : MemoryRepository {

    // ─── MemoryRepository ─────────────────────────────────────────────────────

    /**
     * Returns a cold [Flow] that fetches all memories directly from the Memory Service.
     *
     * Emits [ApiResult.Loading] first, then the remote result. Does not cache locally.
     * Returns [ApiResult.NetworkUnavailable] if the device is offline.
     */
    override fun getMemories(): Flow<ApiResult<List<Memory>>> = flow {
        emit(ApiResult.Loading)

        if (!connectivityObserver.isConnected()) {
            emit(ApiResult.NetworkUnavailable)
            return@flow
        }

        when (val result = remoteSource.getMemories()) {
            is ApiResult.Success -> emit(ApiResult.Success(result.data.map { it.toDomain() }))
            is ApiResult.Error -> emit(result)
            is ApiResult.NetworkUnavailable -> emit(ApiResult.NetworkUnavailable)
            is ApiResult.Loading -> Unit
        }
    }.flowOn(dispatchers.io)

    /**
     * Updates the content of a memory entry on the backend (Requirement 7.3).
     *
     * @param memoryId   The unique identifier of the memory to update.
     * @param newContent The replacement text content.
     */
    override suspend fun updateMemory(memoryId: String, newContent: String): ApiResult<Memory> =
        withContext(dispatchers.io) {
            if (!connectivityObserver.isConnected()) return@withContext ApiResult.NetworkUnavailable

            when (val result = remoteSource.updateMemory(memoryId, newContent)) {
                is ApiResult.Success -> ApiResult.Success(result.data.toDomain())
                is ApiResult.Error -> result
                is ApiResult.NetworkUnavailable -> ApiResult.NetworkUnavailable
                is ApiResult.Loading -> ApiResult.Loading
            }
        }

    /**
     * Deletes a memory entry and its ChromaDB embedding (Requirement 7.4).
     *
     * The backend is responsible for removing the vector embedding within 10 seconds.
     *
     * @param memoryId The unique identifier of the memory to delete.
     */
    override suspend fun deleteMemory(memoryId: String): ApiResult<Unit> = withContext(dispatchers.io) {
        if (!connectivityObserver.isConnected()) return@withContext ApiResult.NetworkUnavailable

        when (val result = remoteSource.deleteMemory(memoryId)) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Error -> result
            is ApiResult.NetworkUnavailable -> ApiResult.NetworkUnavailable
            is ApiResult.Loading -> ApiResult.Loading
        }
    }
}
