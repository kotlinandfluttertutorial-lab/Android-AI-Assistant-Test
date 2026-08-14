/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-resume
 * File       : ResumeModule.kt
 * Purpose    : Hilt module providing Resume dependencies to the DI graph
 *
 * Architecture Layer : Feature (feature-resume)
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
 * Module     : feature-resume
 * File       : ResumeModule.kt
 * Purpose    : Hilt module providing Resume dependencies to the DI graph
 *
 * Architecture Layer : Feature (feature-resume)
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
 * ResumeModule.kt â€” feature-resume module
 *
 * Purpose: Hilt [dagger.Module] providing domain use case instances needed by
 *          [ResumeViewModel]. The use cases do not carry @Inject constructor
 *          annotations in the domain module (pure-Kotlin, no DI framework dependency
 *          by design), so they are provided here via @Provides factory methods.
 *
 * Architecture: feature-resume â€” installs into [dagger.hilt.components.SingletonComponent].
 * Dependencies: domain (GenerateResumeUseCase, GenerateCoverLetterUseCase, ResumeRepository)
 *
 * Design decisions:
 * - @Provides rather than @Binds because use cases are instantiated by factory.
 * - Scoped to @Singleton so the single ResumeRepository instance is shared across all
 *   ViewModel instances over the app lifecycle.
 *
 * Requirements: 14.1, 14.2, 14.3
 */
package com.aiassistant.feature.resume.di

import com.aiassistant.domain.repository.ResumeRepository
import com.aiassistant.domain.usecase.resume.GenerateCoverLetterUseCase
import com.aiassistant.domain.usecase.resume.GenerateEmailUseCase
import com.aiassistant.domain.usecase.resume.GenerateResumeUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ResumeModule {

    /**
     * Provides [GenerateResumeUseCase] backed by the application-level [ResumeRepository].
     */
    @Provides
    @Singleton
    fun provideGenerateResumeUseCase(resumeRepository: ResumeRepository): GenerateResumeUseCase =
        GenerateResumeUseCase(resumeRepository)

    /**
     * Provides [GenerateCoverLetterUseCase] backed by the application-level [ResumeRepository].
     */
    @Provides
    @Singleton
    fun provideGenerateCoverLetterUseCase(resumeRepository: ResumeRepository): GenerateCoverLetterUseCase =
        GenerateCoverLetterUseCase(resumeRepository)

    /**
     * Provides [GenerateEmailUseCase] backed by the application-level [ResumeRepository].
     * Shared singleton accessible from both feature-resume and feature-email modules.
     */
    @Provides
    @Singleton
    fun provideGenerateEmailUseCase(resumeRepository: ResumeRepository): GenerateEmailUseCase =
        GenerateEmailUseCase(resumeRepository)
}
