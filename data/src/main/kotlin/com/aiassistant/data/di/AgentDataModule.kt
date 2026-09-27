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
import com.aiassistant.data.agent.tools.CalculatorTool
import com.aiassistant.data.agent.tools.DateTimeTool
import com.aiassistant.data.agent.tools.DocumentSearchTool
import com.aiassistant.data.agent.tools.WebSearchTool
import com.aiassistant.domain.agent.AgentGatewayDocumentExtension
import com.aiassistant.domain.agent.AgentGatewayRepository
import com.aiassistant.domain.agent.DefaultToolRegistry
import com.aiassistant.domain.agent.ToolRegistry
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AgentDataModule {

    @Binds
    @Singleton
    abstract fun bindAgentGatewayRepository(impl: AgentGateway): AgentGatewayRepository

    @Binds
    @Singleton
    abstract fun bindAgentGatewayCodeExtension(
        impl: AgentGateway,
    ): com.aiassistant.domain.agent.AgentGatewayCodeExtension

    @Binds
    @Singleton
    abstract fun bindAgentGatewayDocumentExtension(impl: AgentGateway): AgentGatewayDocumentExtension

    companion object {
        /**
         * Provides a pre-populated [ToolRegistry] singleton.
         *
         * Each tool is `@Singleton` and `@Inject`-constructable, so Hilt injects
         * them here without additional `@Provides` methods.
         */
        @Provides
        @Singleton
        fun provideToolRegistry(
            calculatorTool: CalculatorTool,
            dateTimeTool: DateTimeTool,
            documentSearchTool: DocumentSearchTool,
            webSearchTool: WebSearchTool,
        ): ToolRegistry = DefaultToolRegistry().also { reg ->
            reg.register(calculatorTool)
            reg.register(dateTimeTool)
            reg.register(documentSearchTool)
            reg.register(webSearchTool)
        }
    }
}
