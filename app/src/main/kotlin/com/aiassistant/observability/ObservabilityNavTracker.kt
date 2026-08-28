/**
 * ObservabilityNavTracker.kt — app module
 *
 * Purpose: Composable that emits [EventType.SCREEN_VIEW] [ObservabilityEvent]s to
 *          [ObservabilityEventBus] on every NavController destination change.
 *
 * This is separate from the Firebase Analytics [screenViewTracker] in MainActivity —
 * that one logs to Firebase; this one logs to our own observability pipeline so the
 * AI analysis layer (Phase 10) can correlate errors with the screen the user was on.
 *
 * Both trackers are attached to the same NavController in MainActivity.
 *
 * Phase 8 — Observability
 */

package com.aiassistant.observability

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import com.aiassistant.core.common.observability.EventLevel
import com.aiassistant.core.common.observability.EventType
import com.aiassistant.core.common.observability.ObservabilityEvent
import com.aiassistant.core.common.observability.ObservabilityEventBus
import com.aiassistant.core.common.observability.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

// ─── ViewModel ───────────────────────────────────────────────────────────────

/**
 * Thin ViewModel that holds the Hilt-injected [ObservabilityEventBus] and
 * [SessionManager] so they survive recompositions.
 *
 * Using a ViewModel here is intentional — it survives configuration changes
 * (screen rotation) so we don't re-attach the NavController listener after
 * every rotation.
 */
@HiltViewModel
class ObservabilityNavTrackerViewModel @Inject constructor(
    val bus: ObservabilityEventBus,
    val sessionManager: SessionManager
) : ViewModel()

// ─── Composable ──────────────────────────────────────────────────────────────

/**
 * Attaches a destination-changed listener that emits a [EventType.SCREEN_VIEW]
 * event to [ObservabilityEventBus] on every navigation.
 *
 * Usage — add once in MainActivity alongside [screenViewTracker]:
 * ```kotlin
 * observabilityNavTracker(navController = navController)
 * ```
 *
 * @param navController The root [NavHostController] from [rememberNavController].
 * @param viewModel     Injected automatically via [hiltViewModel].
 */
@Composable
fun observabilityNavTracker(
    navController: NavHostController,
    viewModel: ObservabilityNavTrackerViewModel = hiltViewModel()
) {
    DisposableEffect(navController) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            val route = destination.route ?: return@OnDestinationChangedListener

            // Derive a clean screen name from the route
            val screenName = route
                .substringAfterLast("/")
                .substringBefore("?")
                .substringBefore("{")
                .trim('/')
                .replaceFirstChar { it.uppercaseChar() }
                .ifBlank { route }

            viewModel.bus.emit(
                ObservabilityEvent(
                    timestamp = System.currentTimeMillis(),
                    level = EventLevel.INFO,
                    eventType = EventType.SCREEN_VIEW,
                    message = "Screen: $screenName",
                    screen = screenName,
                    traceId = viewModel.sessionManager.currentTraceId,
                    sessionId = viewModel.sessionManager.sessionId,
                    metadata = mapOf("route" to route)
                )
            )
        }

        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
}
