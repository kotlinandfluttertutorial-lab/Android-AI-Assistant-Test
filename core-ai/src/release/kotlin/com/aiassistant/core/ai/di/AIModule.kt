/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai
 * File       : AIModule.kt
 * Purpose    : Hilt module providing AI dependencies to the DI graph
 *
 * Architecture Layer : Core-AI
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
 * AIModule.kt — core-ai release source set
 *
 * Purpose: Hilt [dagger.Module] that binds [AIStreamClientImpl] as the
 *          singleton [AIStreamClient] implementation available in release builds.
 *
 * Architecture: core-ai release — installs into [dagger.hilt.components.SingletonComponent].
 */

package com.aiassistant.core.ai.di

import com.aiassistant.core.ai.AIStreamClient
import com.aiassistant.core.ai.AIStreamClientImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AIModule {

    /**
     * Binds [AIStreamClientImpl] to the [AIStreamClient] interface.
     */
    @Binds
    @Singleton
    abstract fun bindAIStreamClient(impl: AIStreamClientImpl): AIStreamClient
}
