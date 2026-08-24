/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ContextSuggestionRepositoryImpl.kt
 * Purpose    : Production implementation of ContextSuggestionRepository.
 *              Calls POST /api/v1/suggestions/context on the backend and maps
 *              the response to domain ContextSuggestion objects.
 *
 * Architecture Layer : Data
 * Pattern Used       : Repository Implementation
 *
 * Dependencies: SuggestionRemoteDataSource, ConnectivityObserver
 * Requirements: 33.1, 33.2, 33.3, 33.6, 33.7
 * ============================================================
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.data.remote.suggestion.SuggestionRemoteDataSource
import com.aiassistant.domain.model.ContextSuggestion
import com.aiassistant.domain.model.ScreenContext
import com.aiassistant.domain.repository.ContextSuggestionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Production implementation of [ContextSuggestionRepository].
 *
 * Delegates to [SuggestionRemoteDataSource] which calls
 * `POST /api/v1/suggestions/context`. The 3-second timeout is enforced here
 * at the repository layer (Requirement 33.6) so that the UI is never blocked
 * waiting for suggestions:
 * - Timeout → [ApiResult.Success] with empty list (silent degradation).
 * - Offline  → [ApiResult.NetworkUnavailable] (caller shows no suggestions).
 * - HTTP error → [ApiResult.Error] forwarded to the use case for logging.
 *
 * All rate-gating, privacy checks, and dismissal logic live in the domain
 * use cases ([GetContextSuggestionsUseCase], [DismissSuggestionUseCase]).
 *
 * @param remoteDataSource     Retrofit-backed data source for the suggestions endpoint.
 * @param connectivityObserver Synchronous connectivity state snapshot.
 */
@Singleton
class ContextSuggestionRepositoryImpl @Inject constructor(
    private val remoteDataSource: SuggestionRemoteDataSource,
    private val connectivityObserver: ConnectivityObserver
) : ContextSuggestionRepository {

    /**
     * Fetches 0–3 context-aware AI suggestions for the given [context].
     *
     * - **Online**: calls the backend; wraps in a 3-second timeout (Req 33.6).
     *   On timeout returns an empty [ApiResult.Success].
     * - **Offline**: returns [ApiResult.NetworkUnavailable] immediately without
     *   blocking the UI.
     *
     * @param context The screen-specific context carrying the content to analyse.
     * @return [ApiResult.Success] with 0–3 suggestions, or an error result.
     */
    override suspend fun getSuggestions(context: ScreenContext): ApiResult<List<ContextSuggestion>> {
        if (!connectivityObserver.isConnected()) {
            return ApiResult.NetworkUnavailable
        }

        // 3-second network timeout — silently return empty list on expiry (Req 33.6).
        val result = withTimeoutOrNull(3_000L) {
            remoteDataSource.getSuggestions(context)
        }

        return result ?: ApiResult.Success(emptyList())
    }
}
