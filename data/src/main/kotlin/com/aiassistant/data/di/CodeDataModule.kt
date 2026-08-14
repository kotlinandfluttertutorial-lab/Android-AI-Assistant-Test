package com.aiassistant.data.di

import com.aiassistant.data.repository.CodeRepositoryImpl
import com.aiassistant.domain.repository.CodeRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * CodeDataModule.kt — data module
 *
 * Purpose: Hilt [dagger.Module] that binds [CodeRepositoryImpl] to the
 *          [CodeRepository] domain interface.
 *
 * Architecture: data module — installs into [SingletonComponent] for process-wide singletons.
 *
 * Requirements: 12.1, 12.2, 12.3, 12.4, 12.6
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CodeDataModule {

    /**
     * Binds [CodeRepositoryImpl] to the [CodeRepository] domain interface.
     */
    @Binds
    @Singleton
    abstract fun bindCodeRepository(impl: CodeRepositoryImpl): CodeRepository
}
