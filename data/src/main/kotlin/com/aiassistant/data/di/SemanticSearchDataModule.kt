/**
 * SemanticSearchDataModule.kt — data module
 *
 * Purpose: Hilt [dagger.Module] that wires all semantic-search-related bindings in the data module.
 *
 *          Binds:
 *            - [SemanticSearchRepositoryImpl] → [SemanticSearchRepository]
 *
 *          Provides:
 *            - [SemanticSearchApiService] via Retrofit
 *
 * Architecture: data module — installs into [SingletonComponent] for process-wide singletons.
 *
 * Requirements: 36.1, 36.3
 */
package com.aiassistant.data.di

import com.aiassistant.data.remote.search.SemanticSearchApiService
import com.aiassistant.data.repository.SemanticSearchRepositoryImpl
import com.aiassistant.domain.repository.SemanticSearchRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
abstract class SemanticSearchDataModule {

    /**
     * Binds [SemanticSearchRepositoryImpl] to the [SemanticSearchRepository] domain interface.
     */
    @Binds
    @Singleton
    abstract fun bindSemanticSearchRepository(impl: SemanticSearchRepositoryImpl): SemanticSearchRepository

    companion object {

        /**
         * Creates the [SemanticSearchApiService] Retrofit implementation.
         */
        @Provides
        @Singleton
        fun provideSemanticSearchApiService(retrofit: Retrofit): SemanticSearchApiService =
            retrofit.create(SemanticSearchApiService::class.java)
    }
}
