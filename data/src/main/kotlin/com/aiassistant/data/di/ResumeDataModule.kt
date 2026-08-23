/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ResumeDataModule.kt
 * Purpose    : Hilt module providing ResumeData dependencies to the DI graph
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
 * File       : ResumeDataModule.kt
 * Purpose    : Hilt module providing ResumeData dependencies to the DI graph
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
 * ResumeDataModule.kt â€” data module
 *
 * Purpose: Hilt [dagger.Module] that wires all resume and email-related bindings in the
 *          data module.
 *
 *          Provides:
 *            - [ResumeApiService] Retrofit implementation
 *          Binds:
 *            - [ResumeRepositoryImpl] â†’ [ResumeRepository]
 *
 * Architecture: data module â€” installs into [SingletonComponent] for process-wide singletons.
 *
 * Requirements: 14.1, 14.2, 14.4, 14.5
 */
package com.aiassistant.data.di

import com.aiassistant.data.remote.resume.ResumeApiService
import com.aiassistant.data.repository.ResumeRepositoryImpl
import com.aiassistant.domain.repository.ResumeRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
abstract class ResumeDataModule {

    /**
     * Binds [ResumeRepositoryImpl] to the [ResumeRepository] domain interface.
     *
     * Any injection site requesting [ResumeRepository] receives the singleton
     * [ResumeRepositoryImpl] constructed by Hilt.
     */
    @Binds
    @Singleton
    abstract fun bindResumeRepository(impl: ResumeRepositoryImpl): ResumeRepository

    companion object {

        /**
         * Creates the [ResumeApiService] Retrofit implementation using the application-level
         * [Retrofit] singleton provided by `core-network`'s NetworkModule.
         *
         * @param retrofit The application-level Retrofit singleton.
         * @return A Retrofit-generated implementation of [ResumeApiService].
         */
        @Provides
        @Singleton
        fun provideResumeApiService(retrofit: Retrofit): ResumeApiService =
            retrofit.create(ResumeApiService::class.java)
    }
}
