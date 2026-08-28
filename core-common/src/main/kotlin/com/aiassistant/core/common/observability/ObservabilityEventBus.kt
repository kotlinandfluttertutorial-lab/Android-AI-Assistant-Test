/**
 * ObservabilityEventBus.kt — core-common module
 *
 * Purpose: Process-wide event bus that decouples the capture layer (OkHttp interceptors,
 *          crash handlers, screen trackers) from the storage/upload layer (WorkManager).
 *
 * Architecture: core-common — pure Kotlin, zero Android/framework dependencies.
 *               Producers (interceptors) call [emit]; consumers (ObservabilityManager,
 *               WorkManager scheduling logic) collect [events].
 *
 * Design decisions:
 * - [MutableSharedFlow] with replay=0 means only live collectors receive events.
 *   Events emitted before the manager starts collecting are intentionally dropped —
 *   pre-session events have no session context and would be misleading in analysis.
 * - [extraBufferCapacity]=64 absorbs short bursts (e.g. a screen with many rapid
 *   API calls) without blocking the OkHttp thread.
 * - [onBufferOverflow]=DROP_OLDEST prevents the interceptor thread from ever
 *   suspending: when the buffer is full the oldest unprocessed event is discarded.
 *   Losing a few events under extreme load is better than stalling network threads.
 * - [tryEmit] is used instead of [emit] so producers that run on OkHttp's blocking
 *   threads never need to bridge to a coroutine context just to fire an event.
 *
 * Thread safety: [MutableSharedFlow] is thread-safe. [tryEmit] can be called from
 *                any thread without synchronization.
 *
 * Phase 2 — Android Observability
 */

package com.aiassistant.core.common.observability

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Singleton event bus for [ObservabilityEvent] instances.
 *
 * Producers (interceptors, crash handlers) call [emit] from any thread.
 * Consumers ([ObservabilityManager]) collect [events] on a background coroutine.
 *
 * This class has no Android dependencies so it can be instantiated in unit tests
 * without Robolectric.
 *
 * Hilt wiring: provided as a `@Singleton` by `ObservabilityModule` in `core-network`.
 * Direct instantiation is acceptable in tests.
 */
class ObservabilityEventBus {

    private val _events = MutableSharedFlow<ObservabilityEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    /**
     * Hot [SharedFlow] of [ObservabilityEvent] instances.
     *
     * Collect this flow on a long-lived background coroutine (e.g. in
     * [ObservabilityManager]'s init block) to receive all emitted events.
     */
    val events: SharedFlow<ObservabilityEvent> = _events.asSharedFlow()

    /**
     * Emits [event] to all active collectors.
     *
     * Safe to call from any thread, including OkHttp's background I/O threads.
     * If the internal buffer is full the oldest unprocessed event is dropped rather
     * than blocking the caller.
     *
     * @param event PII-free [ObservabilityEvent] to broadcast.
     */
    fun emit(event: ObservabilityEvent) {
        _events.tryEmit(event)
    }
}
