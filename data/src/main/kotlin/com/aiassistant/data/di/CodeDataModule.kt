/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : CodeDataModule.kt
 * Purpose    : Hilt module providing CodeData dependencies to the DI graph.
 *              Provides CodeApiService (Retrofit) and binds CodeRepositoryImpl
 *              to the CodeRepository domain interface.
 *
 * Architecture Layer : Data
 * Pattern Used       : Hilt DI Module
 *
 * Requirements: 12.1, 12.2, 12.3, 12.4, 12.6
 * ============================================================
 */
package com.aiassistant.data.di

import com.aiassistant.data.remote.code.CodeApiService
import com.aiassistant.data.repository.CodeRepositoryImpl
import com.aiassistant.domain.repository.CodeRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

/**
 * Hilt [dagger.Module] that wires all code-analysis–related bindings.
 *
 * Provides:
 *   - [CodeApiService] Retrofit implementation
 * Binds:
 *   - [CodeRepositoryImpl] → [CodeRepository]
 *
 * Architecture: installs into [SingletonComponent] for process-wide singletons.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CodeDataModule {

    /**
     * Binds [CodeRepositoryImpl] to the [CodeRepository] domain interface.
     */
    @Binds
    @Singleton
    abstract fun bindCodeRepository(impl: CodeRepositoryImpl): CodeRepository

    companion object {

        /**
         * Creates the [CodeApiService] Retrofit implementation using the application-level
         * [Retrofit] singleton provided by core-network's NetworkModule.
         */
        @Provides
        @Singleton
        fun provideCodeApiService(retrofit: Retrofit): CodeApiService =
            retrofit.create(CodeApiService::class.java)
    }
}
