/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : MemoryDataModule.kt
 * Purpose    : Hilt module providing MemoryData dependencies to the DI graph
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
 * File       : MemoryDataModule.kt
 * Purpose    : Hilt module providing MemoryData dependencies to the DI graph
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
 * MemoryDataModule.kt â€” data module
 *
 * Purpose: Hilt [dagger.Module] that wires all memory-related bindings in the data module.
 *
 *          Binds:
 *            - [MemoryRepositoryImpl] â†’ [MemoryRepository]
 *
 * Note: [MemoryApiService] and [MemoryRemoteDataSource] already exist and use
 *       constructor injection with @Inject; they do not need @Provides here.
 *       However, [MemoryApiService] requires a Retrofit @Provides binding since
 *       Retrofit implementations cannot use @Inject constructors.
 *
 * Architecture: data module â€” installs into [SingletonComponent] for process-wide singletons.
 *
 * Requirements: 7.3, 7.4
 */
package com.aiassistant.data.di

import com.aiassistant.data.remote.memory.MemoryApiService
import com.aiassistant.data.repository.MemoryRepositoryImpl
import com.aiassistant.domain.repository.MemoryRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
abstract class MemoryDataModule {

    /**
     * Binds [MemoryRepositoryImpl] to the [MemoryRepository] domain interface.
     */
    @Binds
    @Singleton
    abstract fun bindMemoryRepository(impl: MemoryRepositoryImpl): MemoryRepository

    companion object {

        /**
         * Creates the [MemoryApiService] Retrofit implementation.
         */
        @Provides
        @Singleton
        fun provideMemoryApiService(retrofit: Retrofit): MemoryApiService =
            retrofit.create(MemoryApiService::class.java)
    }
}
