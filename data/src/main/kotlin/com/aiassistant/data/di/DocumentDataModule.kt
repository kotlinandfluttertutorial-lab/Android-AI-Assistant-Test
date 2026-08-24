/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : DocumentDataModule.kt
 * Purpose    : Hilt module providing DocumentData dependencies to the DI graph
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
 * File       : DocumentDataModule.kt
 * Purpose    : Hilt module providing DocumentData dependencies to the DI graph
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
 * DocumentDataModule.kt â€” data module
 *
 * Purpose: Hilt [dagger.Module] that wires all document-related bindings in the data module.
 *
 *          Provides:
 *            - [DocumentApiService] Retrofit implementation
 *          Binds:
 *            - [DocumentRepositoryImpl] â†’ [DocumentRepository]
 *
 * Architecture: data module â€” installs into [SingletonComponent] for process-wide singletons.
 *
 * Requirements: 4.1, 4.6, 4.10
 */
package com.aiassistant.data.di

import com.aiassistant.data.remote.document.DocumentApiService
import com.aiassistant.data.repository.DocumentRepositoryImpl
import com.aiassistant.domain.repository.DocumentRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
abstract class DocumentDataModule {

    /**
     * Binds [DocumentRepositoryImpl] to the [DocumentRepository] domain interface.
     */
    @Binds
    @Singleton
    abstract fun bindDocumentRepository(impl: DocumentRepositoryImpl): DocumentRepository

    companion object {

        /**
         * Creates the [DocumentApiService] Retrofit implementation using the application-level
         * [Retrofit] singleton provided by `core-network`'s [NetworkModule].
         */
        @Provides
        @Singleton
        fun provideDocumentApiService(retrofit: Retrofit): DocumentApiService =
            retrofit.create(DocumentApiService::class.java)
    }
}
