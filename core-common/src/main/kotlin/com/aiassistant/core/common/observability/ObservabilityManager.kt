/**
 * ObservabilityManager.kt — core-common module
 *
 * Purpose: Central coordinator for the observability pipeline. Collects events
 *          from [ObservabilityEventBus], buffers them in memory, and exposes a
 *          [drain] function that the WorkManager upload task calls to flush the
 *          buffer to the backend.
 *
 * Architecture: core-common — pure Kotlin, zero Android/framework dependencies.
 *               Provided as a @Singleton by ObservabilityModule. Starts collecting
 *               on first access; stops when the provided [CoroutineScope] is cancelled.
 *
 * Pipeline:
 * ```
 * Interceptor / CrashHandler
 *       │  ObservabilityEventBus.emit(event)
 *       ▼
 * ObservabilityEventBus (SharedFlow, buffer=64)
 *       │  collect { event → _buffer.add(event) }
 *       ▼
 * ObservabilityManager._buffer  (in-memory ring buffer, max 500 events)
 *       │  drain() called by WorkManager every N minutes
 *       ▼
 * List<ObservabilityEvent>  →  backend POST /events
 * ```
 *
 * Design decisions:
 * - The in-memory buffer uses a [ArrayDeque] protected by a [Mutex]. A deque gives
 *   O(1) removal from the front (drain) and addition to the back (collect).
 * - Buffer is capped at [MAX_BUFFER_SIZE] (500) events. When the cap is reached the
 *   oldest event is dropped — disk persistence is intentionally out of scope for
 *   Phase 2 (added in Phase 8 when the full observability stack is wired up).
 * - [drain] returns a snapshot and clears the buffer atomically under the same lock
 *   so no events are lost between drain and upload. If the upload fails, the caller
 *   is responsible for re-enqueuing or discarding (WorkManager retry handles this).
 * - [DispatcherProvider] is injected for testability — tests substitute a
 *   TestCoroutineDispatcher so they can control timing precisely.
 *
 * Phase 2 — Android Observability
 */

package com.aiassistant.core.common.observability

import com.aiassistant.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** Maximum number of events held in the in-memory buffer before oldest are dropped. */
private const val MAX_BUFFER_SIZE = 500

/**
 * Coordinates the flow of [ObservabilityEvent] instances from capture to upload.
 *
 * ### Starting collection
 * Call [startCollecting] once after the singleton is created (done automatically
 * by `ObservabilityModule`'s provider function). Subsequent calls are no-ops.
 *
 * ### Draining the buffer
 * The WorkManager task calls [drain] periodically. [drain] returns all buffered
 * events and clears the buffer. If the upload fails, the events are lost — the
 * [WorkManager] task uses its own retry policy so a failed upload is retried.
 *
 * ### Thread safety
 * All mutations to [_buffer] are guarded by [_mutex] and executed on [Dispatchers.IO].
 * [drain] is a `suspend` function and must be called from a coroutine.
 *
 * @param bus               Source of [ObservabilityEvent] instances.
 * @param dispatcherProvider Dispatcher abstraction for testability.
 */
class ObservabilityManager(private val bus: ObservabilityEventBus, private val dispatcherProvider: DispatcherProvider) {

    /** Guards all access to [_buffer]. */
    private val _mutex = Mutex()

    /**
     * In-memory ring buffer. Oldest events at the front (index 0);
     * newest at the back (last index).
     */
    private val _buffer = ArrayDeque<ObservabilityEvent>(MAX_BUFFER_SIZE)

    /**
     * Long-lived scope used for the collection coroutine.
     * [SupervisorJob] ensures that a failure in the collection loop does not
     * propagate upward and cancel the host scope.
     */
    private val _scope = CoroutineScope(dispatcherProvider.io + SupervisorJob())

    /** Set to true after [startCollecting] is called so it is idempotent. */
    @Volatile
    private var _collecting = false

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Starts collecting [ObservabilityEvent] instances from [bus].
     *
     * Idempotent — safe to call multiple times; only the first call launches the
     * collection coroutine.
     *
     * Called once by `ObservabilityModule` immediately after constructing this instance.
     */
    fun startCollecting() {
        if (_collecting) return
        _collecting = true

        _scope.launch {
            bus.events.collect { event ->
                _mutex.withLock {
                    // Drop the oldest event if the buffer is at capacity.
                    if (_buffer.size >= MAX_BUFFER_SIZE) {
                        _buffer.removeFirst()
                        Timber.w(
                            "ObservabilityManager: buffer full — dropped oldest event " +
                                "(eventType=%s)",
                            _buffer.firstOrNull()?.eventType ?: "unknown"
                        )
                    }
                    _buffer.addLast(event)
                }
            }
        }
    }

    /**
     * Returns all buffered events and clears the buffer atomically.
     *
     * Designed to be called by the WorkManager upload task.
     * The returned list may be empty if no events have been captured since the
     * last drain.
     *
     * @return Snapshot of buffered [ObservabilityEvent] instances, oldest first.
     */
    suspend fun drain(): List<ObservabilityEvent> = _mutex.withLock {
        val snapshot = _buffer.toList()
        _buffer.clear()
        Timber.d("ObservabilityManager: drained %d events", snapshot.size)
        snapshot
    }

    /**
     * Returns the current number of buffered events without draining.
     *
     * Useful for monitoring and debug screens.
     */
    suspend fun bufferedCount(): Int = _mutex.withLock { _buffer.size }
}
