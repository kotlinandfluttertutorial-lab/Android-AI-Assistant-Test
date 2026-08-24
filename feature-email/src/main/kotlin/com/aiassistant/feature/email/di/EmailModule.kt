/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-email
 * File       : EmailModule.kt
 * Purpose    : Hilt module providing Email dependencies to the DI graph
 *
 * Architecture Layer : Feature (feature-email)
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
 * Module     : feature-email
 * File       : EmailModule.kt
 * Purpose    : Hilt module providing Email dependencies to the DI graph
 *
 * Architecture Layer : Feature (feature-email)
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
 * EmailModule.kt â€” feature-email module
 *
 * Purpose: Hilt module providing [CorrectGrammarUseCase] for [EmailViewModel].
 *          [GenerateEmailUseCase] is provided by the feature-resume module's ResumeModule
 *          at the SingletonComponent level and is shared across both features.
 * Architecture: feature-email â€” installs into SingletonComponent.
 * Requirements: 14.5
 */
package com.aiassistant.feature.email.di

import com.aiassistant.domain.repository.ResumeRepository
import com.aiassistant.domain.usecase.resume.CorrectGrammarUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EmailModule {

    /**
     * Provides [CorrectGrammarUseCase] backed by the application-level [ResumeRepository].
     */
    @Provides
    @Singleton
    fun provideCorrectGrammarUseCase(resumeRepository: ResumeRepository): CorrectGrammarUseCase =
        CorrectGrammarUseCase(resumeRepository)
}
