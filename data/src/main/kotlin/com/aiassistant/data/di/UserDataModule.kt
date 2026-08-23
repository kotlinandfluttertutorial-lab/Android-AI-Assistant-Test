/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : UserDataModule.kt
 * Purpose    : Hilt module providing UserData dependencies to the DI graph
 *
 * Architecture Layer : Data
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
 * Module     : data
 * File       : UserDataModule.kt
 * Purpose    : Hilt module providing UserData dependencies to the DI graph
 *
 * Architecture Layer : Data
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
 * UserDataModule.kt â€” data module
 *
 * Purpose: Hilt [dagger.Module] that wires user profile-related bindings in the data module.
 *          Provides [UserApiService] Retrofit instance and binds [UserRepositoryImpl] to
 *          the [UserRepository] domain interface.
 *
 * Architecture: data module â€” installs into [dagger.hilt.components.SingletonComponent]
 *               so all bindings are process-wide singletons. Follows the same pattern as
 *               other data modules (AuthDataModule, ConversationDataModule).
 *
 * Design decisions:
 * - [UserApiService] is provided via `@Provides` because `Retrofit.create` is a factory call.
 * - [UserRepository] is bound via `@Binds` because [UserRepositoryImpl] is `@Inject`-annotated.
 * - Abstract class + companion object mixes `@Binds` and `@Provides` as required by Hilt.
 *
 * Requirements: 3.2 (provider preference sync), 24.2 (theme sync)
 */
package com.aiassistant.data.di

import com.aiassistant.data.remote.user.UserApiService
import com.aiassistant.data.repository.UserRepositoryImpl
import com.aiassistant.domain.repository.UserRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
abstract class UserDataModule {

    /**
     * Binds [UserRepositoryImpl] to the [UserRepository] domain interface.
     *
     * Any injection site that requests [UserRepository] will receive the singleton
     * [UserRepositoryImpl] constructed by Hilt (including [SettingsViewModel]).
     */
    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: UserRepositoryImpl): UserRepository

    companion object {

        /**
         * Creates the [UserApiService] Retrofit implementation.
         *
         * Uses the application-scoped [Retrofit] singleton from core-network's [NetworkModule],
         * ensuring the same [okhttp3.OkHttpClient] (with auth + pinning interceptors) is used.
         *
         * @param retrofit The application-level Retrofit singleton.
         * @return A Retrofit-generated implementation of [UserApiService].
         */
        @Provides
        @Singleton
        fun provideUserApiService(retrofit: Retrofit): UserApiService = retrofit.create(UserApiService::class.java)
    }
}
