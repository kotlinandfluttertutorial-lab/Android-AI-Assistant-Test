/**
 * SemanticSearchRepositoryImpl.kt — data module
 *
 * Purpose: Production implementation of [SemanticSearchRepository].
 *          Submits queries to the backend `/search/semantic` endpoint via Retrofit.
 *          Requires connectivity; emits [ApiResult.NetworkUnavailable] if offline.
 *
 * Architecture: data module — repository layer. Bridges domain contracts
 *               ([SemanticSearchRepository]) with [SemanticSearchRemoteDataSource].
 *               Wired at runtime via [SemanticSearchDataModule] Hilt bindings.
 *
 * Requirements: 36.1, 36.3
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.data.mapper.toDomain
import com.aiassistant.data.remote.search.SemanticSearchRemoteDataSource
import com.aiassistant.domain.model.SemanticSearchResult
import com.aiassistant.domain.repository.SemanticSearchRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * Remote-only implementation of [SemanticSearchRepository].
 *
 * Semantic search results are never cached locally — every query goes directly to the backend.
 *
 * @param remoteSource         Retrofit-backed semantic search data source.
 * @param connectivityObserver Synchronous connectivity check.
 * @param dispatchers          Injectable dispatcher provider.
 */
@Singleton
class SemanticSearchRepositoryImpl @Inject constructor(
    private val remoteSource: SemanticSearchRemoteDataSource,
    private val connectivityObserver: ConnectivityObserver,
    private val dispatchers: DispatcherProvider
) : SemanticSearchRepository {

    /**
     * Perform semantic search by calling the backend API.
     *
     * Returns [ApiResult.NetworkUnavailable] if the device is offline.
     * Maps [SemanticSearchResultDto] objects to [SemanticSearchResult] domain entities.
     *
     * @param query Natural language search string.
     */
    override suspend fun search(query: String): ApiResult<List<SemanticSearchResult>> = withContext(dispatchers.io) {
        if (!connectivityObserver.isConnected()) return@withContext ApiResult.NetworkUnavailable

        when (val result = remoteSource.search(query)) {
            is ApiResult.Success -> ApiResult.Success(
                result.data.results.map { it.toDomain() }
            )
            is ApiResult.Error -> result
            is ApiResult.NetworkUnavailable -> ApiResult.NetworkUnavailable
            is ApiResult.Loading -> ApiResult.Loading
        }
    }
}
