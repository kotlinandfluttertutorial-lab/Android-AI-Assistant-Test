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
 *   - Phase 6: binds AgentGatewayWebExtension, AgentGatewayMediaExtension,
 *     ImageAnalysisRemoteDataSource, and WebSearchProvider → Stub
 *   - ChatAgent is @Singleton with @Inject constructor; no explicit @Provides needed
 *   - AgentGateway is @Singleton with @Inject constructor; @Binds wires interfaces
 *   - Does NOT modify any existing DI module
 *
 * Dependencies: domain (gateway interfaces), data (AgentGateway, impl classes)
 * ============================================================
 */

package com.aiassistant.data.di

import com.aiassistant.data.agent.AgentGateway
import com.aiassistant.data.agent.tools.CalculatorTool
import com.aiassistant.data.agent.tools.DateTimeTool
import com.aiassistant.data.agent.tools.DocumentSearchTool
import com.aiassistant.data.agent.tools.WebSearchTool
import com.aiassistant.data.agent.web.StubWebSearchProvider
import com.aiassistant.data.remote.image.ImageAnalysisRemoteDataSourceImpl
import com.aiassistant.domain.agent.AgentGatewayDocumentExtension
import com.aiassistant.domain.agent.AgentGatewayMediaExtension
import com.aiassistant.domain.agent.AgentGatewayRepository
import com.aiassistant.domain.agent.AgentGatewayWebExtension
import com.aiassistant.domain.agent.DefaultToolRegistry
import com.aiassistant.domain.agent.ToolRegistry
import com.aiassistant.domain.agent.WebSearchProvider
import com.aiassistant.domain.network.ImageAnalysisRemoteDataSource
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

    // ── Phase 6 bindings ──────────────────────────────────────────────────────

    @Binds
    @Singleton
    abstract fun bindAgentGatewayWebExtension(impl: AgentGateway): AgentGatewayWebExtension

    @Binds
    @Singleton
    abstract fun bindAgentGatewayMediaExtension(impl: AgentGateway): AgentGatewayMediaExtension

    /**
     * Bind [WebSearchProvider] to the safe stub by default.
     * Replace this binding when a real search API key is configured.
     */
    @Binds
    @Singleton
    abstract fun bindWebSearchProvider(impl: StubWebSearchProvider): WebSearchProvider

    /**
     * Bind [ImageAnalysisRemoteDataSource] to the OkHttp-backed implementation
     * that calls POST /images/analyze.
     */
    @Binds
    @Singleton
    abstract fun bindImageAnalysisRemoteDataSource(
        impl: ImageAnalysisRemoteDataSourceImpl,
    ): ImageAnalysisRemoteDataSource

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
