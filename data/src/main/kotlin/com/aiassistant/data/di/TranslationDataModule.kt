package com.aiassistant.data.di

import com.aiassistant.data.remote.translator.TranslationApiService
import com.aiassistant.data.repository.TranslationRepositoryImpl
import com.aiassistant.domain.repository.TranslationRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

/**
 * TranslationDataModule.kt — data module
 *
 * Purpose: Hilt [dagger.Module] that wires all translation-related bindings.
 *          Provides [TranslationApiService] via Retrofit factory and binds
 *          [TranslationRepositoryImpl] to the [TranslationRepository] domain interface.
 *
 * Architecture: data module — installs into [SingletonComponent] for process-wide singletons.
 *
 * Requirements: 10.5 (offline translation routing)
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TranslationDataModule {

    /**
     * Binds [TranslationRepositoryImpl] to the [TranslationRepository] domain interface.
     */
    @Binds
    @Singleton
    abstract fun bindTranslationRepository(impl: TranslationRepositoryImpl): TranslationRepository

    companion object {

        /**
         * Creates the [TranslationApiService] Retrofit implementation using the
         * application-level [Retrofit] singleton provided by core-network's NetworkModule.
         */
        @Provides
        @Singleton
        fun provideTranslationApiService(retrofit: Retrofit): TranslationApiService =
            retrofit.create(TranslationApiService::class.java)
    }
}
