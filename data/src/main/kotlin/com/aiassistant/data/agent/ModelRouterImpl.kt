/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ModelRouterImpl.kt
 * Purpose    : Wraps OnDeviceCapabilityProvider + ConnectivityObserver to
 *              resolve CLOUD / ON_DEVICE / AUTO inference path decisions.
 *
 * Architecture Layer : Data — agent sub-package
 * Pattern Used       : Strategy implementation
 *
 * Key Concepts:
 *   - Does NOT rewrite OnDeviceInferenceClient or GeminiProvider
 *   - Uses existing OnDeviceCapabilityProvider (core-ai) for device check
 *   - Uses ConnectivityObserver (core-network) for network check
 *   - Capability result is cached after first evaluation
 *   - Hilt @Inject constructor; singleton scoped in AgentDataModule
 *
 * Dependencies: core-ai (LlmProvider, OnDeviceCapabilityProvider,
 *               OnDeviceCapabilityState), core-network (ConnectivityObserver),
 *               domain (ModelRouter, ModelRoutingDecision, InferencePath)
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ModelRouter] implementation backed by the existing [OnDeviceCapabilityProvider]
 * (from core-ai) and [ConnectivityObserver] (from core-network).
 *
 * Neither underlying component is modified — this class is a pure adapter.
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
        // ── Explicit provider hint in the request ──────────────────────────
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
