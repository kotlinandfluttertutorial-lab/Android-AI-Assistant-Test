package com.aiassistant.feature.profile.di

import com.aiassistant.domain.repository.MemoryRepository
import com.aiassistant.domain.usecase.memory.DeleteMemoryUseCase
import com.aiassistant.domain.usecase.memory.GetMemoriesUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/**
 * ProfileModule.kt — feature-profile module
 *
 * Provides domain use cases for [ProfileViewModel]. These use cases live in the
 * domain module which has no javax.inject dependency (pure Kotlin), so they are
 * provided here via @Provides factory methods rather than @Inject constructors.
 *
 * Installed in [ViewModelComponent] to scope instances to each ViewModel lifecycle.
 *
 * Requirements: 7.3, 7.4
 */
@Module
@InstallIn(ViewModelComponent::class)
object ProfileModule {

    @Provides
    fun provideGetMemoriesUseCase(memoryRepository: MemoryRepository): GetMemoriesUseCase =
        GetMemoriesUseCase(memoryRepository)

    @Provides
    fun provideDeleteMemoryUseCase(memoryRepository: MemoryRepository): DeleteMemoryUseCase =
        DeleteMemoryUseCase(memoryRepository)
}
