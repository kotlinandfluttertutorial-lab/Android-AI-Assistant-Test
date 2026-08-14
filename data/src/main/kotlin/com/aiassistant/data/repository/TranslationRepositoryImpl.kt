/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : TranslationRepositoryImpl.kt
 * Purpose    : Implements TranslationRepository with Room (local) and Retrofit (remote) data sources
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
 * File       : TranslationRepositoryImpl.kt
 * Purpose    : Implements TranslationRepository with Room (local) and Retrofit (remote) data sources
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
 * TranslationRepositoryImpl.kt â€” data module
 *
 * Purpose: Production implementation of [TranslationRepository]. Routes translation
 *          requests to the online AI Orchestrator when the device has connectivity,
 *          or returns [ApiResult.NetworkUnavailable] as the offline stub signal when
 *          there is no network.
 *
 * Architecture: data module â€” repository layer. Bridges domain contracts
 *               ([TranslationRepository]) with infrastructure concerns (Retrofit,
 *               ConnectivityObserver). Wired at runtime via [TranslationDataModule].
 *
 * Dependencies: TranslationRemoteDataSource, ConnectivityObserver (core-network)
 *
 * Requirements: 10.5, 19.1
 *
 * Design decisions:
 * - Online/offline routing is handled here, not in the use case, keeping the domain
 *   layer free of connectivity concerns (clean architecture).
 * - When offline, returns [ApiResult.NetworkUnavailable] rather than a stub result.
 *   The ViewModel interprets this to show the appropriate offline message (Requirement 10.5).
 *   Real on-device ML model integration is out of scope for this task.
 * - [ConnectivityObserver.isConnected] is a synchronous snapshot check, suitable for
 *   a per-request routing decision in a suspend function.
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.data.remote.translator.TranslationRemoteDataSource
import com.aiassistant.domain.repository.TranslationRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes translation requests to the online AI Orchestrator when connected, or returns
 * [ApiResult.NetworkUnavailable] when offline (offline ML model stub).
 *
 * @param remoteDataSource  Retrofit-backed data source for the translation endpoint.
 * @param connectivityObserver Synchronous connectivity state snapshot.
 */
@Singleton
class TranslationRepositoryImpl @Inject constructor(
    private val remoteDataSource: TranslationRemoteDataSource,
    private val connectivityObserver: ConnectivityObserver
) : TranslationRepository {

    /**
     * Translates [text] from [sourceLanguage] to [targetLanguage].
     *
     * - **Online**: delegates to [TranslationRemoteDataSource] â†’ AI Orchestrator.
     * - **Offline**: returns [ApiResult.NetworkUnavailable] â€” the ViewModel shows an
     *   offline message; real on-device ML model integration is out of scope.
     *
     * Language codes should follow BCP 47 format (e.g. "en", "fr", "zh-Hans").
     *
     * Requirement 10.5 / 19.1.
     */
    override suspend fun translateText(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): ApiResult<String> = if (connectivityObserver.isConnected()) {
        remoteDataSource.translateText(
            text = text,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage
        )
    } else {
        ApiResult.NetworkUnavailable
    }
}
