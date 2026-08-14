/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-network
 * File       : LogoutEventBus.kt
 * Purpose    : LogoutEventBus — core-network module component
 *
 * Architecture Layer : Core-Network
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
 * Module     : core-network
 * File       : LogoutEventBus.kt
 * Purpose    : LogoutEventBus — core-network module component
 *
 * Architecture Layer : Core-Network
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
 * LogoutEventBus.kt â€” core-network module
 *
 * Purpose: SharedFlow-based event bus that signals a forced logout to the rest of the
 *          application. Used by [RefreshTokenInterceptor] when a token refresh fails so
 *          that the UI layer can navigate to the Login screen without introducing a
 *          direct Android / navigation dependency inside a network interceptor.
 *
 * Architecture: core-network â€” emitted from [RefreshTokenInterceptor], observed in the
 *               feature-auth ViewModel or app-level NavHost.
 * Dependencies: kotlinx.coroutines
 *
 * Design decisions:
 * - SharedFlow with replay=0 ensures observers only see events that arrive after they
 *   subscribe; stale logout signals are never replayed.
 * - extraBufferCapacity=1 + DROP_OLDEST prevents the interceptor from blocking if no
 *   observer is currently active (e.g. app is backgrounded).
 * - Internal MutableSharedFlow is kept private; only SharedFlow<Unit> is exposed so that
 *   only [RefreshTokenInterceptor] (within this module) can emit.
 * - No Android dependencies â€” this is a pure Kotlin / coroutines class so it can be
 *   unit-tested without Robolectric.
 *
 * Requirements: 1.3 â€” on refresh failure, navigate to Login.
 */
package com.aiassistant.core.network

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Application-wide event bus that broadcasts a forced-logout signal.
 *
 * Any component that needs to react to a session expiry (e.g. the root NavHost or
 * `AuthViewModel`) should collect [logoutEvents] and navigate the user to the Login screen.
 *
 * Example observer (in a ViewModel or NavHost):
 * ```kotlin
 * logoutEventBus.logoutEvents
 *     .onEach { navController.navigate(Route.Login) { popUpTo(0) } }
 *     .launchIn(viewModelScope)
 * ```
 */
@Singleton
class LogoutEventBus @Inject constructor() {

    private val _logoutEvents = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    /**
     * Hot stream of forced-logout events. Collect this in the UI layer to react to
     * session expiry triggered by [RefreshTokenInterceptor].
     */
    val logoutEvents: SharedFlow<Unit> = _logoutEvents.asSharedFlow()

    /**
     * Emits a logout event. Returns `true` if the event was successfully queued,
     * `false` if the buffer was full and the event was dropped (should not happen
     * given DROP_OLDEST policy).
     *
     * Called exclusively by [RefreshTokenInterceptor] on refresh failure.
     */
    internal fun tryEmit() {
        _logoutEvents.tryEmit(Unit)
    }
}
