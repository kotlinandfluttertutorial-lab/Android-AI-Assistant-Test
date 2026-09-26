/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : ModelRouter.kt
 * Purpose    : Selects CLOUD, ON_DEVICE, or AUTO inference path for a request.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Interface + enum value type
 *
 * Key Concepts:
 *   - Pure Kotlin, zero Android/framework dependencies
 *   - Deterministic routing: AUTO falls back to CLOUD when on-device is
 *     unavailable; never makes an LLM call to decide routing
 *   - Does NOT rewrite existing GeminiProvider or OnDeviceInferenceClient
 *
 * Design Decision:
 *   ModelRouter is a pure function of (InferencePath preference,
 *   on-device availability, connectivity) → resolved InferencePath.
 *   The interface lives in domain so it can be referenced from domain use
 *   cases without pulling in Android runtime. The implementation lives in
 *   :data (ModelRouterImpl) where it can access OnDeviceCapabilityProvider
 *   and ConnectivityObserver from core-ai / core-network.
 *
 * Dependencies: domain agent models
 * ============================================================
 */

package com.aiassistant.domain.agent

/**
 * Requested or resolved inference path for an [AgentRequest].
 */
enum class InferencePath {
    /** Always use the cloud LLM provider (AIStreamClient → backend). */
    CLOUD,

    /** Always use the on-device GGUF engine (OnDeviceInferenceClient). */
    ON_DEVICE,

    /**
     * Let [ModelRouter] decide: prefer on-device when available and the
     * device is capable, fall back to cloud otherwise.
     */
    AUTO;

    /** True when this path requires a live network connection. */
    val requiresNetwork: Boolean get() = this == CLOUD
}

/**
 * Decision produced by [ModelRouter.route].
 *
 * @param path             The resolved [InferencePath] (never AUTO).
 * @param providerName     Canonical provider id (matches LlmProvider.id).
 * @param fallbackOccurred True when AUTO was requested but the preferred
 *                         path was unavailable and a fallback was applied.
 * @param reason           Short human-readable explanation for logging / UI.
 */
data class ModelRoutingDecision(
    val path: InferencePath,
    val providerName: String,
    val fallbackOccurred: Boolean = false,
    val reason: String = "",
) {
    init {
        require(path != InferencePath.AUTO) {
            "ModelRoutingDecision.path must be a resolved path (CLOUD or ON_DEVICE), not AUTO."
        }
        require(providerName.isNotBlank()) {
            "ModelRoutingDecision.providerName must not be blank."
        }
    }
}

/**
 * Selects the appropriate inference path for an [AgentRequest].
 *
 * The interface is intentionally simple — all routing logic is deterministic
 * and synchronous except for the on-device capability check which is cached
 * by the implementation.
 */
interface ModelRouter {

    /**
     * Resolve the inference path for [request].
     *
     * Rules (in priority order):
     * 1. If [request] explicitly names a provider via [AgentRequest.provider],
     *    honour it directly (map to CLOUD or ON_DEVICE).
     * 2. If [preference] is [InferencePath.CLOUD] → always return CLOUD.
     * 3. If [preference] is [InferencePath.ON_DEVICE] → return ON_DEVICE if
     *    the device is capable and the model is ready; otherwise return an
     *    error-flagged CLOUD decision with fallbackOccurred=true.
     * 4. If [preference] is [InferencePath.AUTO] → prefer ON_DEVICE when
     *    available, fall back to CLOUD silently.
     *
     * This is a **pure deterministic** function of the current state.
     * No network calls are made.
     *
     * @param request    The request to route.
     * @param preference The caller's preferred inference path.
     * @return           Resolved [ModelRoutingDecision].
     */
    suspend fun route(
        request: AgentRequest,
        preference: InferencePath = InferencePath.AUTO,
    ): ModelRoutingDecision
}
