/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-network
 * File       : FailoverBannerStateProvider.kt
 * Purpose    : Converts raw FailoverEvent stream into a persistent
 *              StateFlow<FailoverBannerState> that UI-layer ViewModels
 *              can observe to show or dismiss the informational banner.
 *
 * Architecture Layer : Core-Network
 * Pattern Used       : StateFlow wrapper over SharedFlow
 *
 * Key Concepts:
 *   - FailoverBannerState holds isVisible, activeBackendName, failoverReason
 *   - Banner becomes visible on SwitchedToEndpoint events (Req 35.6)
 *   - Banner auto-dismisses on PrimaryEndpointRecovered events (Req 35.6)
 *   - AllEndpointsExhausted hides the banner (error state handled separately)
 *   - StateFlow is used (not SharedFlow) so late subscribers see current state
 *
 * Dependencies:
 *   - core-network (FailoverEventBus, FailoverEvent)
 *   - kotlinx.coroutines
 * ============================================================
 */
/**
 * FailoverBannerStateProvider.kt — core-network module
 *
 * Purpose: Aggregates [FailoverEvent] emissions from [FailoverEventBus] into a single
 *          [kotlinx.coroutines.flow.StateFlow]<[FailoverBannerState]> so that any UI
 *          observer (ViewModel, Composable) gets the current banner state immediately
 *          on subscription without having to miss historic events.
 *
 * State transitions driven by [FailoverEvent]:
 * - [FailoverEvent.SwitchedToEndpoint]       → `isVisible = true`, populate name + reason
 * - [FailoverEvent.PrimaryEndpointRecovered] → `isVisible = false` (auto-dismiss, Req 35.6)
 * - [FailoverEvent.AllEndpointsExhausted]    → `isVisible = false` (structured error shown instead)
 *
 * Architecture: core-network — injected by [FederationModule].
 *               MUST NOT import any feature, data, or domain use-case classes.
 *
 * Requirements: 35.6
 */

package com.aiassistant.core.network.federation

import androidx.annotation.VisibleForTesting
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ─── State model ─────────────────────────────────────────────────────────────

/**
 * Snapshot of the failover banner's current display state.
 *
 * @param isVisible         Whether the non-blocking informational banner should be shown.
 * @param activeBackendName Human-readable name of the backend endpoint currently serving
 *                          requests (displayed in the banner when [isVisible] is `true`).
 * @param failoverReason    Short description of why the failover occurred (e.g.
 *                          "Connection error", "HTTP 503"). Empty when [isVisible] is `false`.
 */
data class FailoverBannerState(
    val isVisible: Boolean = false,
    val activeBackendName: String = "",
    val failoverReason: String = ""
) {
    companion object {
        /** Initial / dismissed state — banner is hidden. */
        val Hidden: FailoverBannerState = FailoverBannerState(isVisible = false)
    }
}

// ─── Provider ────────────────────────────────────────────────────────────────

/**
 * Process-wide provider that maintains a [StateFlow] of [FailoverBannerState].
 *
 * Lifecycle: the internal coroutine that bridges [FailoverEventBus.events] into the
 * [StateFlow] is launched with a [SupervisorJob] on [Dispatchers.Default] so it
 * survives individual coroutine cancellations and runs for the lifetime of the
 * process (matching the [Singleton] scope).
 *
 * @param eventBus      The [FailoverEventBus] that publishes raw [FailoverEvent] emissions.
 * @param providerScope The [CoroutineScope] on which the internal bridging coroutine runs.
 *                      Defaults to a new scope backed by [Dispatchers.Default] in
 *                      production. Supply a test-controlled scope in unit tests.
 */
@Singleton
class FailoverBannerStateProvider @Inject constructor(
    private val eventBus: FailoverEventBus,
    private val providerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private val _bannerState = MutableStateFlow(FailoverBannerState.Hidden)

    /**
     * Hot [StateFlow] of [FailoverBannerState].
     *
     * - New collectors immediately receive the current state (replay = 1 semantics
     *   from [StateFlow]).
     * - `isVisible = true` while a secondary endpoint is active.
     * - Automatically resets to [FailoverBannerState.Hidden] when the primary endpoint
     *   recovers (Requirement 35.6).
     */
    val bannerState: StateFlow<FailoverBannerState> = _bannerState.asStateFlow()

    init {
        // Bridge raw FailoverEvent emissions into the StateFlow.
        providerScope.launch {
            eventBus.events.collect { event ->
                _bannerState.value = when (event) {
                    is FailoverEvent.SwitchedToEndpoint -> FailoverBannerState(
                        isVisible = true,
                        activeBackendName = event.activeEndpointName,
                        failoverReason = event.failoverReason
                    )

                    is FailoverEvent.PrimaryEndpointRecovered -> FailoverBannerState.Hidden

                    is FailoverEvent.AllEndpointsExhausted -> FailoverBannerState.Hidden
                }
            }
        }
    }

    /**
     * Cancels the internal coroutine scope. Used for testing to prevent leaks.
     */
    @VisibleForTesting
    internal fun cancelScope() {
        providerScope.cancel()
    }
}
