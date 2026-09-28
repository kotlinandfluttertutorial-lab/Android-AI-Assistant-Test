/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentGatewayOnDeviceExtension.kt
 * Purpose    : Domain interface for routing on-device inference requests
 *              through the Agent Orchestrator layer from feature modules.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Interface (Gateway extension)
 *
 * Key Concepts:
 *   - Feature modules depend only on this domain interface, not on :data
 *   - Implemented by AgentGateway in :data (Phase 7)
 *   - routingMode=LOCAL_ONLY never silently falls back to cloud
 *
 * Phase 7 requirement: "The OnDeviceAgent path MUST NOT make network calls."
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.coroutines.flow.Flow

/**
 * Domain contract for executing on-device inference requests through the
 * Agent Orchestrator stack.
 *
 * The implementation ([com.aiassistant.data.agent.AgentGateway]) lives in `:data`
 * and is bound via Hilt.  Feature modules inject this interface.
 */
interface AgentGatewayOnDeviceExtension {

    /**
     * Execute an on-device inference request and stream [AgentEvent] values.
     *
     * ## Critical guarantee
     * When [routingMode] is [ModelRoutingMode.LOCAL_ONLY], the implementation MUST
     * NOT make any network calls.  If on-device inference is unavailable, it emits
     * [AgentEvent.Failed] with code `LOCAL_ONLY_UNAVAILABLE`.
     *
     * @param prompt        The user's input or assembled prompt.
     * @param conversationId Optional conversation identifier for logging.
     * @param routingMode   Controls on-device vs. cloud routing.
     * @param context       Optional [AgentContext].
     * @return Cold [Flow] of [AgentEvent] ending with [AgentEvent.Completed] or
     *         [AgentEvent.Failed].
     */
    fun executeOnDevice(
        prompt: String,
        conversationId: String? = null,
        routingMode: ModelRoutingMode = ModelRoutingMode.AUTO,
        context: AgentContext? = null,
    ): Flow<AgentEvent>

    /**
     * Returns `true` when the on-device model is downloaded, verified, and ready.
     *
     * Feature modules can use this to decide whether to show a
     * "Run locally" option in the UI.
     */
    val isOnDeviceReady: Boolean
}
