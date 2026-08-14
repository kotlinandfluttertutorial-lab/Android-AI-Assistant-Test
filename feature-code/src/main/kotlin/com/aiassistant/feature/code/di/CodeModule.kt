/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-code
 * File       : CodeModule.kt
 * Purpose    : Hilt module providing Code dependencies to the DI graph
 *
 * Architecture Layer : Feature (feature-code)
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
 * Module     : feature-code
 * File       : CodeModule.kt
 * Purpose    : Hilt module providing Code dependencies to the DI graph
 *
 * Architecture Layer : Feature (feature-code)
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
 * CodeModule.kt
 *
 * Purpose: Hilt [dagger.Module] providing domain use case instances needed by
 *          [CodeViewModel]. The use cases do not carry @Inject constructor annotations
 *          in the domain module (pure-Kotlin, no DI framework dependency by design),
 *          so they are provided here via @Provides factory methods.
 *
 * Architecture: feature-code â€” installs into [dagger.hilt.android.components.ViewModelComponent].
 * Dependencies: domain (AnalyzeCodeUseCase, CodeRepository)
 *
 * Design decisions:
 * - @Provides rather than @Binds because use cases are instantiated by factory.
 * - Scoped to ViewModelComponent so each ViewModel instance gets its own use-case
 *   instances, while the CodeRepository (bound at SingletonComponent) is shared.
 *
 * Requirements: 12.1, 12.2, 12.3, 12.4
 */
package com.aiassistant.feature.code.di

import com.aiassistant.domain.repository.CodeRepository
import com.aiassistant.domain.usecase.code.AnalyzeCodeUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/**
 * Hilt module providing use-case instances for the code ViewModel.
 */
@Module
@InstallIn(ViewModelComponent::class)
object CodeModule {

    /**
     * Provides [AnalyzeCodeUseCase] backed by the injected [CodeRepository].
     *
     * [CodeRepository] is bound in the data module's Hilt module at [SingletonComponent]
     * scope and flows down to [ViewModelComponent] automatically.
     */
    @Provides
    fun provideAnalyzeCodeUseCase(repo: CodeRepository): AnalyzeCodeUseCase = AnalyzeCodeUseCase(repo)
}
