/**
 * ObservabilityModule.kt — core-network module
 *
 * Purpose: Hilt [dagger.Module] that wires all Phase 2 Android Observability
 *          singletons into the DI graph.
 *
 * Architecture: core-network — installs into [dagger.hilt.components.SingletonComponent].
 *               Lives in core-network (not core-common) because it depends on Hilt and
 *               Android-framework types ([DispatcherProvider] from core-common is pure
 *               Kotlin but [ObservabilityManager] starts a coroutine using it, which
 *               is fine here).
 *
 * Provided singletons:
 *
 *   [SessionManager]                 — sessionId + traceId + requestId generation
 *   [ObservabilityEventBus]          — SharedFlow event bus (capture → manager)
 *   [ObservabilityManager]           — buffers events; exposes drain() for WorkManager
 *   [NetworkObservabilityInterceptor] — OkHttp interceptor (provided automatically by
 *                                       Hilt via @Inject; this module does NOT need an
 *                                       explicit @Provides for it)
 *
 * Note on [NetworkObservabilityInterceptor]: it carries @Singleton and @Inject
 * constructor annotations, so Hilt satisfies it without an explicit @Provides.
 * This module only needs to provide the types that lack @Inject constructors
 * (SessionManager, ObservabilityEventBus, ObservabilityManager) because they are
 * plain Kotlin classes without Android dependencies.
 *
 * Phase 2 — Android Observability
 */

package com.aiassistant.core.network.di

import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.observability.ObservabilityEventBus
import com.aiassistant.core.common.observability.ObservabilityManager
import com.aiassistant.core.common.observability.SessionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ObservabilityModule {

    // ─── SessionManager ───────────────────────────────────────────────────────

    /**
     * Provides the process-wide [SessionManager].
     *
     * A new [SessionManager] is constructed at app start, assigning a fresh [sessionId].
     * The singleton lifetime ensures that every interceptor, crash handler, and screen
     * tracker shares the same sessionId and traceId state.
     */
    @Provides
    @Singleton
    fun provideSessionManager(): SessionManager = SessionManager()

    // ─── ObservabilityEventBus ────────────────────────────────────────────────

    /**
     * Provides the process-wide [ObservabilityEventBus].
     *
     * Producers (interceptors, crash handlers) inject this and call [ObservabilityEventBus.emit].
     * The [ObservabilityManager] collects from it on a background coroutine.
     */
    @Provides
    @Singleton
    fun provideObservabilityEventBus(): ObservabilityEventBus = ObservabilityEventBus()

    // ─── ObservabilityManager ─────────────────────────────────────────────────

    /**
     * Provides the process-wide [ObservabilityManager] and immediately starts its
     * event-collection coroutine.
     *
     * [startCollecting] is called here (in the provider) rather than lazily because
     * the manager must be listening before the first network request fires — otherwise
     * early events are dropped. Calling it in the provider guarantees it runs as soon
     * as the DI graph is built (at Application.onCreate time via Hilt).
     *
     * @param bus               The [ObservabilityEventBus] to collect from.
     * @param dispatcherProvider Dispatcher abstraction so the IO dispatcher can be
     *                           swapped for a test dispatcher in unit tests.
     */
    @Provides
    @Singleton
    fun provideObservabilityManager(
        bus: ObservabilityEventBus,
        dispatcherProvider: DispatcherProvider,
    ): ObservabilityManager = ObservabilityManager(
        bus = bus,
        dispatcherProvider = dispatcherProvider,
    ).also { manager ->
        manager.startCollecting()
    }
}
