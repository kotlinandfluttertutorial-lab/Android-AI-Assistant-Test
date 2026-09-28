/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ModelRouterImpl.kt
 * Purpose    : Wraps OnDeviceCapabilityProvider + ConnectivityObserver to
 *              resolve CLOUD / ON_DEVICE / AUTO inference path decisions.
 *              Phase 7: now also honours ModelRoutingMode from request metadata.
 *
 * Architecture Layer : Data — agent sub-package
 * Pattern Used       : Strategy implementation
 *
 * Key Concepts:
 *   - Does NOT rewrite OnDeviceInferenceClient or GeminiProvider
 *   - Uses existing OnDeviceCapabilityProvider (core-ai) for device check
 *   - Uses ConnectivityObserver (core-network) for network check
 *   - Capability result is cached after first evaluation
 *   - Phase 7: ModelRoutingMode.LOCAL_ONLY → never fall back to cloud silently
 *   - Hilt @Inject constructor; singleton scoped in AgentDataModule
 *
 * Dependencies: core-ai (LlmProvider, OnDeviceCapabilityProvider,
 *               OnDeviceCapabilityState), core-network (ConnectivityObserver),
 *               domain (ModelRouter, ModelRoutingDecision, InferencePath,
 *                       ModelRoutingMode)
 * ============================================================
 */

package com.aiassistant.data.agent

import com.aiassistant.core.ai.LlmProvider
import com.aiassistant.core.ai.OnDeviceCapabilityProvider
import com.aiassistant.core.ai.OnDeviceCapabilityState
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.domain.agent.AgentRequest
import com.aiassistant.domain.agent.InferencePath
import com.aiassistant.domain.agent.ModelRoutingDecision
import com.aiassistant.domain.agent.ModelRouter
import com.aiassistant.domain.agent.ModelRoutingMode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ModelRouter] implementation backed by the existing [OnDeviceCapabilityProvider]
 * (from core-ai) and [ConnectivityObserver] (from core-network).
 *
 * Neither underlying component is modified — this class is a pure adapter.
 *
 * ## Phase 7: ModelRoutingMode
 * If the request contains metadata key `"routing_mode"` with value `"LOCAL_ONLY"`,
 * the router enforces on-device routing.  If the device is not ready and the mode
 * is [ModelRoutingMode.LOCAL_ONLY], it returns an on-device decision anyway — the
 * [OnDeviceInferencePort] / [OnDeviceAgent] then emits a clear error rather than
 * routing to cloud silently.
 *
 * @param capabilityProvider Checks on-device hardware / model readiness.
 * @param connectivity       Checks current network availability.
 */
@Singleton
class ModelRouterImpl @Inject constructor(
    private val capabilityProvider: OnDeviceCapabilityProvider,
    private val connectivity: ConnectivityObserver,
) : ModelRouter {

    /** Cached capability state after first evaluation. Null means not yet evaluated. */
    @Volatile
    private var cachedCapabilityState: OnDeviceCapabilityState? = null

    override suspend fun route(
        request: AgentRequest,
        preference: InferencePath,
    ): ModelRoutingDecision {

        // ── Phase 7: ModelRoutingMode from request metadata ────────────────────
        val routingMode = ModelRoutingMode.fromName(request.metadata["routing_mode"])
        when (routingMode) {
            ModelRoutingMode.LOCAL_ONLY -> {
                // LOCAL_ONLY always routes to on-device — even when not ready.
                // OnDeviceAgent emits a clear LOCAL_ONLY_UNAVAILABLE error if needed.
                return onDeviceDecision("routing_mode:LOCAL_ONLY")
            }
            ModelRoutingMode.CLOUD -> {
                return cloudDecision("routing_mode:CLOUD")
            }
            ModelRoutingMode.AUTO -> { /* fall through to normal routing */ }
        }

        // ── Explicit provider hint in the request ──────────────────────────────
        val explicitProvider = request.provider
        if (!explicitProvider.isNullOrBlank()) {
            val lp = LlmProvider.fromId(explicitProvider)
            val path = if (lp == LlmProvider.ON_DEVICE) InferencePath.ON_DEVICE else InferencePath.CLOUD
            return ModelRoutingDecision(
                path = path,
                providerName = lp.id,
                reason = "explicit_provider:${lp.id}",
            )
        }

        // Evaluate (or use cached) on-device capability
        val capability = resolveCapability()

        return when (preference) {
            InferencePath.CLOUD -> cloudDecision("preference:CLOUD")

            InferencePath.ON_DEVICE -> {
                if (capability.isAvailable) {
                    onDeviceDecision("preference:ON_DEVICE")
                } else {
                    // ON_DEVICE requested but device not ready → fallback to cloud
                    cloudDecision("preference:ON_DEVICE but device not ready → fallback cloud").copy(
                        fallbackOccurred = true
                    )
                }
            }

            InferencePath.AUTO -> {
                val isOnline = connectivity.isConnected()
                when {
                    capability.isAvailable && !isOnline ->
                        onDeviceDecision("auto:offline,on_device_ready")
                    capability.isAvailable ->
                        onDeviceDecision("auto:online,on_device_preferred")
                    else ->
                        cloudDecision("auto:on_device_not_ready → cloud")
                }
            }
        }
    }

    /** Reset the cached capability state (e.g. after a model download completes). */
    fun clearCapabilityCache() {
        cachedCapabilityState = null
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private suspend fun resolveCapability(): OnDeviceCapabilityState {
        cachedCapabilityState?.let { return it }
        val state = capabilityProvider.evaluate()
        cachedCapabilityState = state
        return state
    }

    private fun cloudDecision(reason: String) = ModelRoutingDecision(
        path = InferencePath.CLOUD,
        providerName = LlmProvider.GEMINI_1_5_PRO.id,
        reason = reason,
    )

    private fun onDeviceDecision(reason: String) = ModelRoutingDecision(
        path = InferencePath.ON_DEVICE,
        providerName = LlmProvider.ON_DEVICE.id,
        reason = reason,
    )
}
