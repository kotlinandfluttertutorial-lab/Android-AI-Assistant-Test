/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai
 * File       : MockAIModule.kt
 * Purpose    : Hilt module providing MockAI dependencies to the DI graph
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

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai
 * File       : MockAIModule.kt
 * Purpose    : Hilt module providing MockAI dependencies to the DI graph
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
 * MockAIModule.kt â€” core-ai debug source set
 *
 * Purpose: Hilt [dagger.Module] that replaces the production [AIModule] binding in
 *          debug builds, providing [MockAIStreamClient] as the [AIStreamClient]
 *          singleton. This eliminates all network calls and API-key requirements
 *          from CI pipelines and local debug runs.
 *
 * Architecture: core-ai debug â€” installs into [dagger.hilt.components.SingletonComponent].
 *               Marked with [dagger.hilt.testing.UninstallModules] if needed in tests.
 *
 * Design decisions:
 * - Uses `@Binds` to let Hilt construct [MockAIStreamClient] via its @Inject constructor.
 * - The production [AIModule] is excluded at compile time for the debug variant because
 *   only the debug source set participates in the debug build; the release build still
 *   uses [AIModule] from main.
 * - If the production [AIModule] were also included (e.g., in a combined variant), you
 *   would need `replaces = [AIModule::class]`. For a clean debug source set this is
 *   not required.
 *
 * Requirements: 21.4
 */
package com.aiassistant.core.ai.di

import com.aiassistant.core.ai.AIStreamClient
import com.aiassistant.core.ai.MockAIStreamClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MockAIModule {

    /**
     * Binds [MockAIStreamClient] to the [AIStreamClient] interface for debug builds.
     *
     * Any injection site that requests [AIStreamClient] will receive the
     * [MockAIStreamClient] singleton during debug/test runs.
     */
    @Binds
    @Singleton
    abstract fun bindAIStreamClient(impl: MockAIStreamClient): AIStreamClient
}
