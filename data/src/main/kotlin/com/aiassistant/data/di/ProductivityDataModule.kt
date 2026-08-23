/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ProductivityDataModule.kt
 * Purpose    : Hilt module providing ProductivityData dependencies to the DI graph
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
 * File       : ProductivityDataModule.kt
 * Purpose    : Hilt module providing ProductivityData dependencies to the DI graph
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
 * ProductivityDataModule.kt â€” data module
 *
 * Purpose: Hilt [dagger.Module] that wires all Productivity Suite bindings in the data module.
 *
 *          Provides:
 *            - [ProductivityApiService] Retrofit implementation
 *          Binds:
 *            - [ProductivityRepositoryImpl] â†’ [ProductivityRepository]
 *
 * Architecture: data module â€” installs into [SingletonComponent] for process-wide singletons.
 *
 * Requirements: 13.1, 16.3, 16.4
 */
package com.aiassistant.data.di

import com.aiassistant.data.remote.productivity.ProductivityApiService
import com.aiassistant.data.repository.ProductivityRepositoryImpl
import com.aiassistant.domain.repository.ProductivityRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
abstract class ProductivityDataModule {

    /**
     * Binds [ProductivityRepositoryImpl] to the [ProductivityRepository] domain interface.
     *
     * Any injection site requesting [ProductivityRepository] receives the singleton
     * [ProductivityRepositoryImpl] constructed by Hilt.
     */
    @Binds
    @Singleton
    abstract fun bindProductivityRepository(impl: ProductivityRepositoryImpl): ProductivityRepository

    companion object {

        /**
         * Creates the [ProductivityApiService] Retrofit implementation using the
         * application-level [Retrofit] singleton provided by `core-network`'s NetworkModule.
         *
         * @param retrofit The application-level Retrofit singleton.
         * @return A Retrofit-generated implementation of [ProductivityApiService].
         */
        @Provides
        @Singleton
        fun provideProductivityApiService(retrofit: Retrofit): ProductivityApiService =
            retrofit.create(ProductivityApiService::class.java)
    }
}
