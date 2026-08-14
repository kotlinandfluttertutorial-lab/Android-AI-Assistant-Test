/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : UserRepository.kt
 * Purpose    : Domain contract defining data access operations for User entities
 *
 * Architecture Layer : Domain
 * Pattern Used       : Repository Interface
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
 * Module     : domain
 * File       : UserRepository.kt
 * Purpose    : Domain contract defining data access operations for User entities
 *
 * Architecture Layer : Domain
 * Pattern Used       : Repository Interface
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
 * UserRepository.kt
 *
 * Purpose: Domain-layer repository interface for user profile operations.
 *          Implemented in the data module; injected into use cases at runtime.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), domain model (User)
 *
 * Requirements: 19.2
 */

package com.aiassistant.domain.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.ThemeMode
import com.aiassistant.domain.model.User
import kotlinx.coroutines.flow.Flow

/**
 * Contract for user profile operations between the domain and data layers.
 *
 * The data module provides a concrete implementation backed by Room (local cache) and
 * Retrofit (remote). Profile data is fetched from the remote on login and cached locally
 * for offline access.
 */
interface UserRepository {

    /**
     * Returns a [Flow] that emits the current authenticated user's profile.
     *
     * Emits `null` when no user is signed in. The data layer emits from the local Room
     * cache first, then re-fetches from the backend when connectivity is available.
     *
     * @return Cold [Flow] emitting [ApiResult.Success] with the [User] or
     *         [ApiResult.Error] if the profile cannot be loaded.
     */
    fun getCurrentUser(): Flow<ApiResult<User?>>

    /**
     * Updates the user's display name on the backend and in the local cache.
     *
     * @param displayName The new display name.
     * @return [ApiResult.Success] with the updated [User] on success.
     */
    suspend fun updateDisplayName(displayName: String): ApiResult<User>

    /**
     * Updates the user's active LLM provider preference.
     *
     * @param provider The provider identifier to set as active.
     * @return [ApiResult.Success] with the updated [User] on success.
     */
    suspend fun updateActiveProvider(provider: String): ApiResult<User>

    /**
     * Updates the user's theme mode preference and persists it to DataStore.
     *
     * @param themeMode The new theme mode preference.
     * @return [ApiResult.Success] with the updated [User] on success.
     */
    suspend fun updateThemeMode(themeMode: ThemeMode): ApiResult<User>

    /**
     * Uploads the FCM push notification token to the backend.
     *
     * Called automatically by the repository layer after any successful API call when a
     * new token is pending sync (Requirement 16.5). Direct callers should generally prefer
     * letting the repository piggyback this on the next success rather than calling it
     * explicitly.
     *
     * @param token The FCM registration token to register with the backend.
     * @return [ApiResult.Success] with [Unit] on success, or [ApiResult.Error] on failure.
     *         A failure here does NOT affect the main API call result.
     */
    suspend fun updateFcmToken(token: String): ApiResult<Unit>

    /**
     * Requests an export of all the user's data (conversations, messages, documents,
     * memories, notes) from the backend (Requirement 28.1).
     *
     * This is a fire-and-forget POST to the backend export endpoint. The backend prepares
     * the archive asynchronously and notifies the user when ready (up to 24 hours).
     *
     * @return [ApiResult.Success] with [Unit] when the export request is accepted,
     *         or [ApiResult.Error] on failure.
     */
    suspend fun requestDataExport(): ApiResult<Unit>

    /**
     * Requests permanent deletion of the authenticated user's account and all associated
     * data (conversations, messages, documents, memories, notes, vector embeddings)
     * (Requirement 28.2).
     *
     * After a successful call the caller must clear all local data and navigate to the
     * authentication screen. The backend processes deletion within 72 hours.
     *
     * @return [ApiResult.Success] with [Unit] when the deletion request is accepted,
     *         or [ApiResult.Error] on failure.
     */
    suspend fun deleteAccount(): ApiResult<Unit>
}
