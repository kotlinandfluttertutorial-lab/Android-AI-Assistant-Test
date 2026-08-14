/**
 * SearchModule.kt — feature-search module
 *
 * Purpose: Hilt module providing [SemanticSearchUseCase] to the DI graph for the search feature.
 *          The [SemanticSearchRepository] binding is provided by [SemanticSearchDataModule] in
 *          the data module; this module wires the use case using the bound interface.
 *
 * Architecture: feature-search — installs into [SingletonComponent].
 *
 * Requirements: 36.1, 36.8
 */
package com.aiassistant.feature.search.di

import com.aiassistant.domain.repository.SemanticSearchRepository
import com.aiassistant.domain.usecase.search.SemanticSearchUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SearchModule {

    /**
     * Provides [SemanticSearchUseCase] for injection into [SemanticSearchViewModel].
     */
    @Provides
    @Singleton
    fun provideSemanticSearchUseCase(repository: SemanticSearchRepository): SemanticSearchUseCase =
        SemanticSearchUseCase(repository)
}
