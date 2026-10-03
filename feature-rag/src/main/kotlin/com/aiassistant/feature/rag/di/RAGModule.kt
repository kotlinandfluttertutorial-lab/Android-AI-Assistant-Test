/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-rag
 * File       : RAGModule.kt
 * Purpose    : Hilt module providing RAG dependencies to the DI graph
 *
 * Architecture Layer : Feature (feature-rag)
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
 * Module     : feature-rag
 * File       : RAGModule.kt
 * Purpose    : Hilt module providing RAG dependencies to the DI graph
 *
 * Architecture Layer : Feature (feature-rag)
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
 * RAGModule.kt â€” feature-rag module
 *
 * Purpose: Hilt [dagger.Module] providing domain use case instances needed by
 *          [RAGViewModel] and [DocumentChatViewModel]. The use cases do not have
 *          `@Inject` constructor annotations in the domain module (pure-Kotlin,
 *          no DI framework dependency by design), so they are provided here via
 *          `@Provides` factory methods.
 *
 * Architecture: feature-rag â€” installs into [dagger.hilt.components.SingletonComponent];
 *               only feature-rag and the app module depend on these bindings.
 * Dependencies: domain (UploadDocumentUseCase, DeleteDocumentUseCase,
 *               QueryDocumentUseCase, DocumentRepository)
 *
 * Design decisions:
 * - Use `@Provides` instead of `@Binds` because the use cases are instantiated by
 *   factory (not by Hilt constructor injection in the domain layer).
 * - Scoped to [Singleton] so that the single [DocumentRepository] instance is shared
 *   across all ViewModel instances over the app lifecycle.
 *
 * Requirements: 4.1, 4.6, 4.7, 27.2, 27.5
 */
package com.aiassistant.feature.rag.di

import com.aiassistant.domain.repository.DocumentRepository
import com.aiassistant.domain.usecase.document.DeleteDocumentUseCase
import com.aiassistant.domain.usecase.document.QueryDocumentUseCase
import com.aiassistant.domain.usecase.document.QueryDocumentWithSourcesUseCase
import com.aiassistant.domain.usecase.document.UploadDocumentUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RAGModule {

    @Provides
    @Singleton
    fun provideUploadDocumentUseCase(documentRepository: DocumentRepository): UploadDocumentUseCase =
        UploadDocumentUseCase(documentRepository)

    @Provides
    @Singleton
    fun provideDeleteDocumentUseCase(documentRepository: DocumentRepository): DeleteDocumentUseCase =
        DeleteDocumentUseCase(documentRepository)

    /**
     * Provides the legacy [QueryDocumentUseCase] (returns raw answer string with
     * embedded citation markers). Retained for backward compatibility — existing
     * callers that rely on text-parsed citations continue to work unchanged.
     */
    @Provides
    @Singleton
    fun provideQueryDocumentUseCase(documentRepository: DocumentRepository): QueryDocumentUseCase =
        QueryDocumentUseCase(documentRepository)

    /**
     * Provides [QueryDocumentWithSourcesUseCase] which calls `POST /api/v1/rag/query`
     * and returns a structured [com.aiassistant.domain.model.RagAnswer] with per-source
     * excerpts and similarity scores (Requirements 4.6, 4.7).
     *
     * Used by [com.aiassistant.feature.rag.DocumentChatViewModel] so the UI can display
     * rich citations without client-side text parsing.
     */
    @Provides
    @Singleton
    fun provideQueryDocumentWithSourcesUseCase(
        documentRepository: DocumentRepository,
    ): QueryDocumentWithSourcesUseCase = QueryDocumentWithSourcesUseCase(documentRepository)
}
