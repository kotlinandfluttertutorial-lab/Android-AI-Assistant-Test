/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : NoteDataModule.kt
 * Purpose    : Hilt module providing NoteData dependencies to the DI graph
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
 * File       : NoteDataModule.kt
 * Purpose    : Hilt module providing NoteData dependencies to the DI graph
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
 * NoteDataModule.kt â€” data module
 *
 * Purpose: Hilt [dagger.Module] that wires all note-related bindings in the data module.
 *
 *          Provides:
 *            - [NoteApiService] Retrofit implementation
 *          Binds:
 *            - [NoteRepositoryImpl] â†’ [NoteRepository]
 *
 * Architecture: data module â€” installs into [SingletonComponent] for process-wide singletons.
 *
 * Requirements: 13.1, 13.2, 13.3, 13.4, 13.5
 */
package com.aiassistant.data.di

import com.aiassistant.data.remote.note.NoteApiService
import com.aiassistant.data.repository.NoteRepositoryImpl
import com.aiassistant.domain.repository.NoteRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
abstract class NoteDataModule {

    /**
     * Binds [NoteRepositoryImpl] to the [NoteRepository] domain interface.
     *
     * Any injection site requesting [NoteRepository] receives the singleton
     * [NoteRepositoryImpl] constructed by Hilt.
     */
    @Binds
    @Singleton
    abstract fun bindNoteRepository(impl: NoteRepositoryImpl): NoteRepository

    companion object {

        /**
         * Creates the [NoteApiService] Retrofit implementation using the application-level
         * [Retrofit] singleton provided by `core-network`'s NetworkModule.
         *
         * @param retrofit The application-level Retrofit singleton.
         * @return A Retrofit-generated implementation of [NoteApiService].
         */
        @Provides
        @Singleton
        fun provideNoteApiService(retrofit: Retrofit): NoteApiService = retrofit.create(NoteApiService::class.java)
    }
}
