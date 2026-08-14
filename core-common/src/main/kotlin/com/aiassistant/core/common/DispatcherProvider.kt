/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-common
 * File       : DispatcherProvider.kt
 * Purpose    : DispatcherProvider — core-common module component
 *
 * Architecture Layer : Core-Common
 * Pattern Used       : Kotlin Class
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
 * Module     : core-common
 * File       : DispatcherProvider.kt
 * Purpose    : DispatcherProvider — core-common module component
 *
 * Architecture Layer : Core-Common
 * Pattern Used       : Kotlin Class
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
 * DispatcherProvider.kt
 *
 * Purpose: Coroutine dispatcher abstraction for dependency injection and testability.
 * Architecture: core-common â€” shared infrastructure, no Android/framework dependencies.
 * Dependencies: kotlinx.coroutines.core
 *
 * Design decisions:
 * - Interface-based to allow test doubles that inject TestCoroutineDispatcher
 * - DefaultDispatcherProvider maps to standard kotlinx dispatchers for production use
 * - All coroutine-using modules depend on DispatcherProvider, never on Dispatchers directly
 */

package com.aiassistant.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Abstraction over [CoroutineDispatcher] instances.
 *
 * Inject this interface wherever coroutines are launched so that tests can
 * substitute [TestCoroutineDispatcher] / [UnconfinedTestDispatcher] without
 * touching production code.
 *
 * Usage:
 * ```kotlin
 * class MyViewModel(private val dispatchers: DispatcherProvider) : ViewModel() {
 *     fun load() = viewModelScope.launch(dispatchers.io) { ... }
 * }
 * ```
 */
interface DispatcherProvider {
    /** Optimised for CPU-bound work (equivalent to [Dispatchers.Default]). */
    val default: CoroutineDispatcher

    /** Optimised for blocking I/O (equivalent to [Dispatchers.IO]). */
    val io: CoroutineDispatcher

    /** Confined to the main/UI thread (equivalent to [Dispatchers.Main]). */
    val main: CoroutineDispatcher

    /** Immediately (equivalent to [Dispatchers.Main.immediate]). */
    val mainImmediate: CoroutineDispatcher

    /** Unconfined dispatcher â€” runs in the caller's thread until first suspension. */
    val unconfined: CoroutineDispatcher
}

/**
 * Production implementation that delegates to standard [Dispatchers] singletons.
 *
 * Bind this in a Hilt module:
 * ```kotlin
 * @Provides @Singleton
 * fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()
 * ```
 */
class DefaultDispatcherProvider : DispatcherProvider {
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val mainImmediate: CoroutineDispatcher = Dispatchers.Main.immediate
    override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
}
