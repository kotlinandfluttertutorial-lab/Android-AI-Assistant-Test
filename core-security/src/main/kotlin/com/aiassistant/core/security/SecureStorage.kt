/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-security
 * File       : SecureStorage.kt
 * Purpose    : SecureStorage — core-security module component
 *
 * Architecture Layer : Core-Security
 * Pattern Used       : Kotlin Class
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
 * Module     : core-security
 * File       : SecureStorage.kt
 * Purpose    : SecureStorage — core-security module component
 *
 * Architecture Layer : Core-Security
 * Pattern Used       : Kotlin Class
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
 * SecureStorage.kt â€” core-security module
 *
 * Provides encrypted local storage for JWT and refresh tokens using
 * AndroidX Security Crypto's EncryptedSharedPreferences. Raw key
 * material is never exposed through this interface.
 *
 * Requirements: 9.4 â€” AI_Assistant SHALL use Android EncryptedSharedPreferences
 * for all locally stored credentials and tokens.
 */
package com.aiassistant.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface for encrypted credential storage.
 *
 * All stored values are encrypted at rest via EncryptedSharedPreferences.
 * No raw key material is ever returned; only the stored opaque token strings.
 */
interface SecureStorage {

    /**
     * Persists the JWT access token in encrypted storage.
     * @param token the signed JWT string to store
     */
    fun saveJwt(token: String)

    /**
     * Retrieves the stored JWT access token.
     * @return the JWT string, or null if none has been saved or storage is cleared
     */
    fun getJwt(): String?

    /**
     * Persists the refresh token in encrypted storage.
     * @param token the refresh token string to store
     */
    fun saveRefreshToken(token: String)

    /**
     * Retrieves the stored refresh token.
     * @return the refresh token string, or null if none has been saved or storage is cleared
     */
    fun getRefreshToken(): String?

    /**
     * Wipes all credentials from encrypted storage.
     * Call on logout or account removal.
     */
    fun clearAll()

    /**
     * Persists a flag indicating the user has completed the onboarding flow.
     * Used by [AuthViewModel.checkInitialState] to decide whether to show Onboarding.
     */
    fun saveOnboardingComplete()

    /**
     * Returns `true` if the user has previously completed onboarding.
     * Defaults to `false` on a fresh install.
     */
    fun isOnboardingComplete(): Boolean

    /**
     * Persists the FCM push notification token and marks it as pending sync.
     *
     * Call this from [com.google.firebase.messaging.FirebaseMessagingService.onNewToken]
     * whenever the Firebase SDK issues a new token. The token must be uploaded to the
     * backend before push notifications can be delivered; use [isFcmTokenPendingSync] to
     * check whether an upload is needed.
     *
     * @param token the FCM registration token string
     */
    fun saveFcmToken(token: String)

    /**
     * Retrieves the stored FCM push notification token.
     *
     * @return the FCM token string, or null if none has been saved yet
     */
    fun getFcmToken(): String?

    /**
     * Marks the stored FCM token as successfully synced to the backend.
     *
     * Call this after a successful PATCH /users/me/fcm-token response so that subsequent
     * API calls no longer piggyback a token update.
     */
    fun saveFcmTokenSynced()

    /**
     * Clears the stored FCM token and its pending-sync flag.
     *
     * Call on logout so a fresh token cycle begins on the next login.
     */
    fun clearFcmToken()

    /**
     * Returns `true` if a FCM token has been stored but not yet uploaded to the backend.
     *
     * The flag is set by [saveFcmToken] and cleared by [saveFcmTokenSynced] or [clearFcmToken].
     */
    fun isFcmTokenPendingSync(): Boolean
}

/**
 * Production implementation backed by EncryptedSharedPreferences.
 *
 * Uses a AES256_GCM-encrypted MasterKey to protect the underlying
 * SharedPreferences file. Keys themselves are stored with AES256_SIV
 * and values with AES256_GCM.
 *
 * The secondary constructor accepting a [SharedPreferences] instance is
 * intentionally internal and intended only for Robolectric unit tests
 * that verify the read/write/clear contract without requiring the Android
 * Keystore hardware provider (which is unavailable in a JVM-only test environment).
 */
@Singleton
class SecureStorageImpl @Inject constructor(@ApplicationContext private val context: Context) : SecureStorage {

    /**
     * Testing-only constructor: accepts a pre-built [SharedPreferences] instance.
     *
     * Allows Robolectric unit tests to exercise the full read/write/clear
     * contract using a plain SharedPreferences without needing the AndroidKeyStore
     * JCE provider. The [SharedPreferences] instance is captured directly;
     * no AndroidKeyStore or MasterKey is created.
     *
     * Internal visibility limits use to the same module (tests reside in the same module).
     * Never use this constructor in production code.
     */
    @Suppress("DEPRECATION")
    internal constructor(prefsOverride: SharedPreferences) : this(
        context = android.app.Application()
    ) {
        prefsOverrideInternal = prefsOverride
    }

    @Volatile
    private var prefsOverrideInternal: SharedPreferences? = null

    private val prefs: SharedPreferences by lazy {
        prefsOverrideInternal ?: run {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    override fun saveJwt(token: String) {
        prefs.edit().putString(KEY_JWT, token).apply()
    }

    override fun getJwt(): String? = prefs.getString(KEY_JWT, null)

    override fun saveRefreshToken(token: String) {
        prefs.edit().putString(KEY_REFRESH_TOKEN, token).apply()
    }

    override fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    override fun clearAll() {
        prefs.edit().clear().apply()
    }

    override fun saveOnboardingComplete() {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
    }

    override fun isOnboardingComplete(): Boolean = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)

    override fun saveFcmToken(token: String) {
        prefs.edit()
            .putString(KEY_FCM_TOKEN, token)
            .putBoolean(KEY_FCM_TOKEN_PENDING_SYNC, true)
            .apply()
    }

    override fun getFcmToken(): String? = prefs.getString(KEY_FCM_TOKEN, null)

    override fun saveFcmTokenSynced() {
        prefs.edit().putBoolean(KEY_FCM_TOKEN_PENDING_SYNC, false).apply()
    }

    override fun clearFcmToken() {
        prefs.edit()
            .remove(KEY_FCM_TOKEN)
            .putBoolean(KEY_FCM_TOKEN_PENDING_SYNC, false)
            .apply()
    }

    override fun isFcmTokenPendingSync(): Boolean = prefs.getBoolean(KEY_FCM_TOKEN_PENDING_SYNC, false)

    internal companion object {
        const val PREFS_FILE_NAME = "ai_assistant_secure_prefs"

        // These are SharedPreferences keys â€” they identify which value to
        // retrieve, not the cryptographic key material. The underlying crypto
        // keys are managed by the Android Keystore via MasterKey.
        const val KEY_JWT = "jwt_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        const val KEY_FCM_TOKEN = "fcm_token"
        const val KEY_FCM_TOKEN_PENDING_SYNC = "fcm_token_pending_sync"
    }
}
