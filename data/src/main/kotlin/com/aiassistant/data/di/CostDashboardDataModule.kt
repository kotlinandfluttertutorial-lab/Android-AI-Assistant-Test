/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : CostDashboardDataModule.kt
 * Purpose    : Hilt module wiring CostDashboard dependencies
 *
 * Architecture Layer : Data — Hilt DI Module
 * Pattern Used       : Hilt Module with @Binds + @Provides
 *
 * Requirements: 34.1, 34.2, 34.4, 34.7
 * ============================================================
 */

package com.aiassistant.data.di

import com.aiassistant.data.remote.usage.CostDashboardApiService
import com.aiassistant.data.repository.CostDashboardRepositoryImpl
import com.aiassistant.domain.repository.CostDashboardRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

/**
 * Hilt module providing Cost Dashboard dependencies.
 *
 * Wires:
 * - [CostDashboardApiService] Retrofit instance
 * - [CostDashboardRepository] bound to [CostDashboardRepositoryImpl]
 *
 * Requirements: 34.1, 34.2, 34.4
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CostDashboardDataModule {

    /**
     * Binds [CostDashboardRepositoryImpl] to the [CostDashboardRepository] domain interface.
     */
    @Binds
    @Singleton
    abstract fun bindCostDashboardRepository(impl: CostDashboardRepositoryImpl): CostDashboardRepository

    companion object {

        /**
         * Creates the [CostDashboardApiService] Retrofit implementation.
         */
        @Provides
        @Singleton
        fun provideCostDashboardApiService(retrofit: Retrofit): CostDashboardApiService =
            retrofit.create(CostDashboardApiService::class.java)
    }
}
