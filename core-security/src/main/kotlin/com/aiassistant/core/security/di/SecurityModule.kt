/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-security
 * File       : SecurityModule.kt
 * Purpose    : Hilt module providing Security dependencies to the DI graph
 *
 * Architecture Layer : Core-Security
 * Pattern Used       : Hilt DI Module
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
 * File       : SecurityModule.kt
 * Purpose    : Hilt module providing Security dependencies to the DI graph
 *
 * Architecture Layer : Core-Security
 * Pattern Used       : Hilt DI Module
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
 * SecurityModule.kt â€” core-security module
 *
 * Hilt dependency injection module that binds security component interfaces
 * to their production implementations.
 *
 * Scope: [SingletonComponent] â€” both [SecureStorage] and [BiometricAuthManager]
 * are singletons because:
 *   - [SecureStorage] wraps EncryptedSharedPreferences, which is heavyweight to
 *     construct (AES256 key generation on first access), so a single instance is
 *     preferable across the app lifetime.
 *   - [BiometricAuthManager] is stateless and safe to share across features.
 */
package com.aiassistant.core.security.di

import com.aiassistant.core.security.BiometricAuthManager
import com.aiassistant.core.security.BiometricAuthManagerImpl
import com.aiassistant.core.security.SecureStorage
import com.aiassistant.core.security.SecureStorageImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for the `core-security` module.
 *
 * Feature modules and the `data` module should inject [SecureStorage] and
 * [BiometricAuthManager] via their interface types â€” never the concrete
 * implementation classes â€” to stay decoupled from the crypto details.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    /**
     * Binds [SecureStorageImpl] as the [SecureStorage] singleton.
     *
     * Implementation uses EncryptedSharedPreferences backed by an AES256-GCM
     * MasterKey. Satisfies Requirement 9.4.
     */
    @Binds
    @Singleton
    abstract fun bindSecureStorage(impl: SecureStorageImpl): SecureStorage

    /**
     * Binds [BiometricAuthManagerImpl] as the [BiometricAuthManager] singleton.
     *
     * The implementation delegates entirely to the Android OS biometric subsystem.
     * No biometric data is captured or transmitted. Satisfies Requirement 1.7.
     */
    @Binds
    @Singleton
    abstract fun bindBiometricAuthManager(impl: BiometricAuthManagerImpl): BiometricAuthManager
}
