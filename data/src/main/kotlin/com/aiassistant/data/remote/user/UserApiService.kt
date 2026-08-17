/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : UserApiService.kt
 * Purpose    : UserApiService — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Retrofit API Service Interface
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
 * File       : UserApiService.kt
 * Purpose    : UserApiService — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Retrofit API Service Interface
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
 * UserApiService.kt â€” data module
 *
 * Purpose: Retrofit service interface for user profile remote endpoints.
 * Architecture: data module â€” remote data source layer.
 * Dependencies: Retrofit, kotlinx.serialization
 *
 * Requirements: 3.2 (provider preference sync), 24.2 (theme sync)
 */
package com.aiassistant.data.remote.user

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT

/**
 * Remote DTO representing the user profile returned by the backend.
 */
@Serializable
data class UserResponse(
    val id: String,
    val email: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val role: String = "user",
    @SerialName("active_provider") val activeProvider: String = "openai_gpt4o",
    @SerialName("theme_mode") val themeMode: String = "system",
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("updated_at") val updatedAt: Long = 0L
)

/**
 * Request DTO for updating the user's display name.
 */
@Serializable
data class UpdateDisplayNameRequest(@SerialName("display_name") val displayName: String)

/**
 * Request DTO for updating the active LLM provider.
 */
@Serializable
data class UpdateProviderRequest(@SerialName("active_provider") val activeProvider: String)

/**
 * Request DTO for updating the theme mode preference.
 */
@Serializable
data class UpdateThemeModeRequest(@SerialName("theme_mode") val themeMode: String)

/**
 * Request DTO for registering the device's FCM push notification token.
 * Maps to the backend's DeviceTokenRequest: {"token": "..."}
 */
@Serializable
data class UpdateFcmTokenRequest(@SerialName("token") val fcmToken: String)

/**
 * Retrofit service interface for user profile endpoints.
 */
interface UserApiService {

    /**
     * Fetches the current user's profile from the backend.
     * GET /users/me
     */
    @GET("users/me")
    suspend fun getCurrentUser(): UserResponse

    /**
     * Updates the user's display name.
     * PATCH /users/me/display-name
     */
    @PATCH("users/me/display-name")
    suspend fun updateDisplayName(@Body request: UpdateDisplayNameRequest): UserResponse

    /**
     * Updates the user's active LLM provider preference.
     * PATCH /users/me/provider
     */
    @PATCH("users/me/provider")
    suspend fun updateActiveProvider(@Body request: UpdateProviderRequest): UserResponse

    /**
     * Updates the user's theme mode preference.
     * PATCH /users/me/theme
     */
    @PATCH("users/me/theme")
    suspend fun updateThemeMode(@Body request: UpdateThemeModeRequest): UserResponse

    /**
     * Registers or updates the device's FCM push notification token.
     * PUT /notifications/device-token
     */
    @PUT("notifications/device-token")
    suspend fun updateFcmToken(@Body request: UpdateFcmTokenRequest)

    /**
     * Requests an asynchronous export of all the user's data.
     * The backend prepares the archive and notifies the user when ready (up to 24 hours).
     * POST /users/me/export
     * Requirement 28.1
     */
    @POST("users/me/export")
    suspend fun requestDataExport()

    /**
     * Permanently deletes the authenticated user's account and all associated data.
     * The backend processes deletion within 72 hours.
     * DELETE /users/me
     * Requirement 28.2
     */
    @DELETE("users/me")
    suspend fun deleteAccount()
}
