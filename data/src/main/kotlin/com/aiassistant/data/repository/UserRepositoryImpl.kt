/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : UserRepositoryImpl.kt
 * Purpose    : Implements UserRepository with Room (local) and Retrofit (remote) data sources
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
 * File       : UserRepositoryImpl.kt
 * Purpose    : Implements UserRepository with Room (local) and Retrofit (remote) data sources
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
 * UserRepositoryImpl.kt â€” data module
 *
 * Purpose: Production implementation of [UserRepository]. Uses Room as the local cache
 *          (single source of truth) and the [UserApiService] for remote profile sync.
 *          Always emits the local Room value first, then re-fetches remotely when connected.
 *
 * Architecture: data module â€” repository layer. Bridges domain contracts ([UserRepository])
 *               with infrastructure concerns (Room UserDao, Retrofit UserApiService).
 *               The domain layer has zero knowledge of this class; it is wired at runtime
 *               via [UserDataModule].
 *
 * Design decisions:
 * - The single active user is the last user inserted into Room by AuthRepositoryImpl after login.
 *   [UserDao.getAllUsers] returns a Flow that emits on any change.
 * - Remote write operations (updateActiveProvider, updateThemeMode, updateDisplayName) use
 *   optimistic local updates: Room is written first so the UI responds immediately, then the
 *   backend is called to persist the change. On remote failure, the local state remains (the
 *   spec says "local-wins for preferences").
 * - When offline, remote calls return [ApiResult.NetworkUnavailable]; the local value
 *   returned via [getCurrentUser] still reflects the most recent persisted state.
 *
 * Requirements: 3.2 (provider selector), 24.2 (theme persistence)
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.database.dao.UserDao
import com.aiassistant.core.database.entity.UserEntity
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.core.security.SecureStorage
import com.aiassistant.data.remote.user.UpdateDisplayNameRequest
import com.aiassistant.data.remote.user.UpdateFcmTokenRequest
import com.aiassistant.data.remote.user.UpdateProviderRequest
import com.aiassistant.data.remote.user.UpdateThemeModeRequest
import com.aiassistant.data.remote.user.UserApiService
import com.aiassistant.data.remote.user.UserResponse
import com.aiassistant.domain.model.ThemeMode
import com.aiassistant.domain.model.User
import com.aiassistant.domain.model.UserRole
import com.aiassistant.domain.repository.UserRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Concrete implementation of [UserRepository].
 *
 * @param userDao             Room DAO for local user profile cache.
 * @param userApiService      Retrofit service for remote user profile endpoints.
 * @param connectivityObserver Connectivity state snapshot for early-exit checks.
 * @param dispatchers         Injectable dispatcher provider.
 * @param secureStorage       Encrypted local store for FCM token pending-sync state.
 */
@Singleton
class UserRepositoryImpl @Inject constructor(
    private val userDao: UserDao,
    private val userApiService: UserApiService,
    private val connectivityObserver: ConnectivityObserver,
    private val dispatchers: DispatcherProvider,
    private val secureStorage: SecureStorage
) : UserRepository {

    /**
     * Background scope used for fire-and-forget FCM token sync calls.
     * A [SupervisorJob] ensures a failed sync does not cancel the parent scope.
     */
    private val syncScope = CoroutineScope(dispatchers.io + SupervisorJob())

    /**
     * Returns a [Flow] emitting the current user profile from the local Room cache.
     *
     * Emits [ApiResult.Loading] initially, then [ApiResult.Success] with the [User] or
     * `null` when no user is stored (e.g. logged out). The Room flow re-emits on any
     * profile change so the UI always stays up to date.
     *
     * This implementation queries the first user in the table; since this is a
     * single-user mobile app, there is always at most one active user record.
     */
    override fun getCurrentUser(): Flow<ApiResult<User?>> = userDao.getAllUsers().map { entity ->
        ApiResult.Success(entity?.toDomain())
    }

    /**
     * Updates the display name locally and syncs to the backend if connected.
     */
    override suspend fun updateDisplayName(displayName: String): ApiResult<User> = withContext(dispatchers.io) {
        val current = userDao.getFirstUser() ?: return@withContext ApiResult.Error(
            DomainError.ValidationError(message = "No active user found.")
        )

        // Optimistic local update
        val updated = current.copy(displayName = displayName)
        userDao.updateUser(updated)

        // Remote sync
        if (!connectivityObserver.isConnected()) {
            return@withContext ApiResult.Success(updated.toDomain())
        }

        safeApiCall {
            val response = userApiService.updateDisplayName(UpdateDisplayNameRequest(displayName))
            val synced = response.toEntity()
            userDao.updateUser(synced)
            synced.toDomain()
        }.also { result ->
            if (result is ApiResult.Success) maybeSyncFcmToken()
        }
    }

    /**
     * Updates the active LLM provider locally and syncs to the backend if connected (Requirement 3.2).
     *
     * The local Room update takes effect immediately so the app uses the new provider
     * without waiting for the network round-trip. This satisfies "switch without application restart".
     */
    override suspend fun updateActiveProvider(provider: String): ApiResult<User> = withContext(dispatchers.io) {
        val current = userDao.getFirstUser() ?: return@withContext ApiResult.Error(
            DomainError.ValidationError(message = "No active user found.")
        )

        // Optimistic local update â€” UI is immediately consistent
        val updated = current.copy(activeProvider = provider)
        userDao.updateUser(updated)

        // Remote sync â€” failure is non-blocking (local-wins for preferences)
        if (!connectivityObserver.isConnected()) {
            return@withContext ApiResult.Success(updated.toDomain())
        }

        safeApiCall {
            val response = userApiService.updateActiveProvider(UpdateProviderRequest(provider))
            val synced = response.toEntity()
            userDao.updateUser(synced)
            synced.toDomain()
        }.also { result ->
            if (result is ApiResult.Success) maybeSyncFcmToken()
        }
    }

    /**
     * Updates the theme mode locally and syncs to the backend if connected (Requirement 24.2).
     *
     * Like [updateActiveProvider], the local update is applied first for immediate UI response.
     */
    override suspend fun updateThemeMode(themeMode: ThemeMode): ApiResult<User> = withContext(dispatchers.io) {
        val current = userDao.getFirstUser() ?: return@withContext ApiResult.Error(
            DomainError.ValidationError(message = "No active user found.")
        )

        // Optimistic local update
        val updated = current.copy(themeMode = themeMode.value)
        userDao.updateUser(updated)

        // Remote sync
        if (!connectivityObserver.isConnected()) {
            return@withContext ApiResult.Success(updated.toDomain())
        }

        safeApiCall {
            val response = userApiService.updateThemeMode(UpdateThemeModeRequest(themeMode.value))
            val synced = response.toEntity()
            userDao.updateUser(synced)
            synced.toDomain()
        }.also { result ->
            if (result is ApiResult.Success) maybeSyncFcmToken()
        }
    }

    /**
     * Uploads the FCM push notification token to the backend (Requirement 16.3, 16.5).
     *
     * On success, marks the token as synced in [SecureStorage] so subsequent calls no
     * longer piggyback a token update. On failure, leaves the pending-sync flag set so
     * the next successful API call retries.
     */
    override suspend fun updateFcmToken(token: String): ApiResult<Unit> = withContext(dispatchers.io) {
        safeApiCall {
            userApiService.updateFcmToken(UpdateFcmTokenRequest(token))
            secureStorage.saveFcmTokenSynced()
        }
    }

    /**
     * Sends a fire-and-forget data export request to the backend (Requirement 28.1).
     *
     * The backend prepares a JSON archive of all the user's data asynchronously and
     * notifies the user when ready (up to 24 hours). This call returns success as soon
     * as the server accepts the request.
     */
    override suspend fun requestDataExport(): ApiResult<Unit> = withContext(dispatchers.io) {
        safeApiCall {
            userApiService.requestDataExport()
        }
    }

    /**
     * Requests permanent account and data deletion from the backend (Requirement 28.2).
     *
     * On success the caller is responsible for clearing all local data (Room, SecureStorage)
     * and navigating to the authentication screen.
     */
    override suspend fun deleteAccount(): ApiResult<Unit> = withContext(dispatchers.io) {
        safeApiCall {
            userApiService.deleteAccount()
        }
    }

    // â”€â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Fire-and-forget FCM token sync. Checks [SecureStorage.isFcmTokenPendingSync] and,
     * if a token is pending, launches a background coroutine to push it to the backend.
     *
     * The coroutine uses [syncScope] (backed by [SupervisorJob]) so a sync failure never
     * propagates to or cancels the calling coroutine. The main API result is unaffected
     * regardless of whether the sync succeeds (Requirement 16.5).
     */
    private fun maybeSyncFcmToken() {
        if (!secureStorage.isFcmTokenPendingSync()) return
        val token = secureStorage.getFcmToken() ?: return
        syncScope.launch {
            // Failure is silently swallowed; the pending flag remains set so the
            // next successful call will retry.
            safeApiCall {
                userApiService.updateFcmToken(UpdateFcmTokenRequest(token))
                secureStorage.saveFcmTokenSynced()
            }
        }
    }

    /**
     * Maps [UserEntity] to the domain [User] model.
     */
    private fun UserEntity.toDomain(): User = User(
        id = id,
        email = email,
        displayName = displayName,
        avatarUrl = avatarUrl,
        role = UserRole.fromValue(role),
        activeProvider = activeProvider,
        themeMode = ThemeMode.fromValue(themeMode),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    /**
     * Maps a remote [UserResponse] DTO to a [UserEntity] for Room storage.
     */
    private fun UserResponse.toEntity(): UserEntity = UserEntity(
        id = id,
        email = email,
        displayName = displayName,
        avatarUrl = avatarUrl,
        role = role,
        activeProvider = activeProvider,
        themeMode = themeMode,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    /**
     * Wraps a suspending Retrofit call and maps any exception to a typed [ApiResult].
     */
    private suspend fun <T> safeApiCall(block: suspend () -> T): ApiResult<T> = try {
        ApiResult.Success(block())
    } catch (e: HttpException) {
        ApiResult.Error(
            when (e.code()) {
                401 -> DomainError.Unauthorized(cause = e)
                403 -> DomainError.Forbidden(cause = e)
                in 400..499 -> DomainError.ValidationError(
                    message = "Invalid request (HTTP ${e.code()}).",
                    cause = e
                )
                in 500..599 -> DomainError.ServerError(
                    httpStatusCode = e.code(),
                    cause = e
                )
                else -> DomainError.NetworkError(
                    message = "Unexpected HTTP ${e.code()}.",
                    cause = e
                )
            }
        )
    } catch (e: IOException) {
        ApiResult.Error(
            DomainError.NetworkError(
                message = e.message ?: "Network I/O error.",
                cause = e
            )
        )
    }
}
