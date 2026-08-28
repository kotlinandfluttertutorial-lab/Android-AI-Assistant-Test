/**
 * AppLifecycleObserver.kt — app module
 *
 * Purpose: [DefaultLifecycleObserver] that emits [EventType.APP_FOREGROUND] and
 *          [EventType.APP_BACKGROUND] [ObservabilityEvent]s when the application
 *          moves between foreground and background.
 *
 * WHY APP-LEVEL (not Activity-level):
 *   A single Activity's onStart/onStop fires on every screen rotation.
 *   ProcessLifecycleOwner tracks the *process* lifecycle — it only fires
 *   when the app truly enters or leaves the foreground, not on config changes.
 *
 * REGISTRATION:
 *   Called once in [AIAssistantApplication.onCreate] via:
 *     ProcessLifecycleOwner.get().lifecycle.addObserver(
 *         AppLifecycleObserver(bus, sessionManager)
 *     )
 *
 * Phase 8 — Observability
 */

package com.aiassistant.observability

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.aiassistant.core.common.observability.EventLevel
import com.aiassistant.core.common.observability.EventType
import com.aiassistant.core.common.observability.ObservabilityEvent
import com.aiassistant.core.common.observability.ObservabilityEventBus
import com.aiassistant.core.common.observability.SessionManager
import timber.log.Timber

/**
 * Emits foreground/background [ObservabilityEvent]s when the app process lifecycle changes.
 *
 * @param bus            Target event bus — events are emitted via [ObservabilityEventBus.emit].
 * @param sessionManager Source of sessionId and currentTraceId.
 */
class AppLifecycleObserver(private val bus: ObservabilityEventBus, private val sessionManager: SessionManager) :
    DefaultLifecycleObserver {

    /** Called when the app comes to the foreground (any Activity becomes visible). */
    override fun onStart(owner: LifecycleOwner) {
        Timber.d("AppLifecycleObserver: app foregrounded")
        bus.emit(
            ObservabilityEvent(
                timestamp = System.currentTimeMillis(),
                level = EventLevel.INFO,
                eventType = EventType.APP_FOREGROUND,
                message = "App entered foreground",
                traceId = sessionManager.currentTraceId,
                sessionId = sessionManager.sessionId
            )
        )
    }

    /** Called when all Activities are stopped (app goes to background or is killed). */
    override fun onStop(owner: LifecycleOwner) {
        Timber.d("AppLifecycleObserver: app backgrounded")
        bus.emit(
            ObservabilityEvent(
                timestamp = System.currentTimeMillis(),
                level = EventLevel.INFO,
                eventType = EventType.APP_BACKGROUND,
                message = "App entered background",
                traceId = sessionManager.currentTraceId,
                sessionId = sessionManager.sessionId
            )
        )
    }
}
