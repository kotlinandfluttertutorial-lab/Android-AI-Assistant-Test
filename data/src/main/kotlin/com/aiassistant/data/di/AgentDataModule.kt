/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : AgentDataModule.kt
 * Purpose    : Hilt DI module for the Agent layer data bindings.
 *
 * Architecture Layer : Data — DI
 * Pattern Used       : Hilt @Module @InstallIn(SingletonComponent)
 *
 * Key Concepts:
 *   - Binds AgentGatewayRepository → AgentGateway (singleton)
 *   - ChatAgent is @Singleton with @Inject constructor; no explicit @Provides needed
 *   - AgentGateway is @Singleton with @Inject constructor; @Binds wires the interface
 *   - Does NOT modify any existing DI module
 *
 * Dependencies: domain (AgentGatewayRepository), data (AgentGateway)
 * ============================================================
 */

package com.aiassistant.data.di

import com.aiassistant.data.agent.AgentGateway
import com.aiassistant.domain.agent.AgentGatewayRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that exposes the Agent layer to the rest of the application.
 *
 * Installed in [SingletonComponent] because both [AgentGateway] and
 * [com.aiassistant.data.agent.ChatAgent] are `@Singleton`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AgentDataModule {

    /**
     * Binds the data-layer [AgentGateway] to its domain interface
     * [AgentGatewayRepository] so that [com.aiassistant.feature.chat.ChatDetailViewModel]
     * can depend only on the domain interface without knowing about the data implementation.
     */
    @Binds
    @Singleton
    abstract fun bindAgentGatewayRepository(
        impl: AgentGateway,
    ): AgentGatewayRepository
}
