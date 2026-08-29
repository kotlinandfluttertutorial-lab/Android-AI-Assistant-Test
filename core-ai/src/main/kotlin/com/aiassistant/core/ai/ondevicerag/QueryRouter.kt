/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai
 * File       : QueryRouter.kt
 * Purpose    : Decides whether a query should be answered on-device (Gemma) or
 *              routed to the Backend cloud LLM, based on four hardware/software
 *              capability signals combined into a 4-bit bitmask and the user's
 *              explicit path preference.
 *
 * Architecture Layer : Core-AI — on-device RAG pipeline (routing gate before
 *                      query execution).
 *                      Called by RouteQueryUseCase (domain module) which logs
 *                      every decision via QueryRoutingLogRepository.
 *
 * Dependencies       : Pure Kotlin — no Android framework imports.
 *                      Hilt @Inject constructor for production binding.
 *
 * Design Decision    : The routing decision is a pure function of (bitmask,
 *                      preference) with no side effects beyond the log write
 *                      delegated to the caller (RouteQueryUseCase).  This makes
 *                      the router fully unit-testable with all 16 × 3 = 48
 *                      combinations (Property 40) without any mocking.
 *
 *                      Bitmask bit layout (LSB = bit 0):
 *                        bit 0 — Gemma model files present + checksum valid
 *                        bit 1 — EmbeddingModel.isReady
 *                        bit 2 — LocalVectorIndex has ≥1 chunk for userId
 *                        bit 3 — network reachable to Backend
 *
 *                      Rule (spec §45.5):
 *                        ON_DEVICE  iff  bitmask == 0b1111 AND preference != PREFER_CLOUD
 *                        CLOUD      in all other cases
 *
 *                      Offline + on-device capable (bits 0-2 set, bit 3 unset):
 *                        bitmask = 0b0111 → still routes ON_DEVICE (never queues).
 *                        This is the same rule — bitmask 7 ≠ 15 so it goes CLOUD
 *                        by the base rule... BUT the spec says "offline + on-device
 *                        capable: always ON_DEVICE, never queue".  We implement this
 *                        as a secondary rule checked before the primary rule:
 *                        if (bits 0-2 set) AND (bit 3 unset) → ON_DEVICE regardless
 *                        of preference (because cloud is unreachable anyway).
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag

import javax.inject.Inject
import javax.inject.Singleton

/** Which inference path the router selected. */
enum class InferencePath {
    ON_DEVICE,
    CLOUD,
}

/**
 * The user's explicit routing preference, set in Settings.
 * Null means "let the router decide" (auto mode).
 */
enum class PathPreference {
    PREFER_ON_DEVICE,
    PREFER_CLOUD,
}

/**
 * The result of one routing evaluation.
 *
 * @param path               Selected inference path.
 * @param capabilityBitmask  4-bit integer snapshot of the four signals at decision time.
 * @param reason             Human-readable explanation for debug / BenchmarkScreen.
 * @param fallbackOccurred   True when the router switched path at runtime (set by caller
 *                           if inference fails and retries the other path).
 */
data class RoutingDecision(
    val path: InferencePath,
    val capabilityBitmask: Int,
    val reason: String,
    val fallbackOccurred: Boolean = false,
)

/**
 * Bitmask constants — each bit represents one capability signal.
 *
 * Example: all four signals present → `GEMMA_READY or EMBEDDING_READY or CHUNKS_EXIST or NETWORK_REACHABLE` = 0b1111 = 15
 */
object CapabilityBit {
    /** bit 0 — Gemma model files present and SHA-256 checksum valid. */
    const val GEMMA_READY: Int = 0b0001

    /** bit 1 — EmbeddingModel.isReady == true. */
    const val EMBEDDING_READY: Int = 0b0010

    /** bit 2 — LocalVectorIndex has ≥1 chunk for the querying userId. */
    const val CHUNKS_EXIST: Int = 0b0100

    /** bit 3 — network is reachable to the Backend API. */
    const val NETWORK_REACHABLE: Int = 0b1000

    /** Bitmask value when all four signals are active. */
    const val ALL_ON_DEVICE_CAPABLE: Int = GEMMA_READY or EMBEDDING_READY or CHUNKS_EXIST

    /** Bitmask value when all four signals including network are active. */
    const val FULLY_CAPABLE: Int = ALL_ON_DEVICE_CAPABLE or NETWORK_REACHABLE
}

/**
 * Pure routing decision engine.
 *
 * [evaluate] is a deterministic function of ([capabilityBitmask], [userPreference])
 * — no coroutines, no I/O.  The caller (RouteQueryUseCase) is responsible for
 * persisting the returned [RoutingDecision] to the audit log.
 */
@Singleton
class QueryRouter @Inject constructor() {

    /**
     * Evaluates the routing decision for a single query.
     *
     * Routing rules (evaluated in order):
     *
     * 1. **Offline + on-device capable** (bits 0-2 set, bit 3 unset):
     *    → `ON_DEVICE` always — cloud is unreachable, so user preference is irrelevant.
     *
     * 2. **Fully capable + user prefers cloud** (bitmask == 15, preference == PREFER_CLOUD):
     *    → `CLOUD` — honour explicit preference.
     *
     * 3. **Fully capable** (bitmask == 15, preference != PREFER_CLOUD):
     *    → `ON_DEVICE`.
     *
     * 4. **Any other combination**:
     *    → `CLOUD`.
     *
     * @param capabilityBitmask 4-bit integer built by the caller from live signal checks.
     * @param userPreference    Explicit user preference from Settings; null = auto.
     * @return [RoutingDecision] with the selected path, bitmask snapshot, and reason string.
     */
    fun evaluate(
        capabilityBitmask: Int,
        userPreference: PathPreference?,
    ): RoutingDecision {
        val offlineOnDeviceCapable =
            (capabilityBitmask and CapabilityBit.ALL_ON_DEVICE_CAPABLE) == CapabilityBit.ALL_ON_DEVICE_CAPABLE &&
                (capabilityBitmask and CapabilityBit.NETWORK_REACHABLE) == 0

        // Rule 1 — offline and on-device capable: must go on-device (no network)
        if (offlineOnDeviceCapable) {
            return RoutingDecision(
                path = InferencePath.ON_DEVICE,
                capabilityBitmask = capabilityBitmask,
                reason = "Device is offline and on-device RAG is fully capable. " +
                    "Routing on-device; never queuing for later.",
            )
        }

        val fullyCapable = capabilityBitmask == CapabilityBit.FULLY_CAPABLE

        return when {
            // Rule 2 — fully capable but user explicitly prefers cloud
            fullyCapable && userPreference == PathPreference.PREFER_CLOUD -> RoutingDecision(
                path = InferencePath.CLOUD,
                capabilityBitmask = capabilityBitmask,
                reason = "All on-device signals ready but user preference is PREFER_CLOUD.",
            )

            // Rule 3 — fully capable, no override or prefers on-device
            fullyCapable -> RoutingDecision(
                path = InferencePath.ON_DEVICE,
                capabilityBitmask = capabilityBitmask,
                reason = buildOnDeviceReason(capabilityBitmask, userPreference),
            )

            // Rule 4 — one or more signals missing; fall back to cloud
            else -> RoutingDecision(
                path = InferencePath.CLOUD,
                capabilityBitmask = capabilityBitmask,
                reason = buildCloudReason(capabilityBitmask),
            )
        }
    }

    // ── Reason string helpers (used by BenchmarkScreen) ───────────────────────

    private fun buildOnDeviceReason(bitmask: Int, preference: PathPreference?): String {
        val prefNote = if (preference == PathPreference.PREFER_ON_DEVICE) {
            " User preference: PREFER_ON_DEVICE."
        } else {
            " No preference override (auto)."
        }
        return "All four capability signals active (bitmask=0b${bitmask.toString(2).padStart(4, '0')}).$prefNote"
    }

    private fun buildCloudReason(bitmask: Int): String {
        val missing = buildList {
            if (bitmask and CapabilityBit.GEMMA_READY == 0) add("Gemma model not ready")
            if (bitmask and CapabilityBit.EMBEDDING_READY == 0) add("EmbeddingModel not ready")
            if (bitmask and CapabilityBit.CHUNKS_EXIST == 0) add("no indexed chunks for user")
            if (bitmask and CapabilityBit.NETWORK_REACHABLE == 0) add("network unreachable")
        }
        return "Routing to cloud. Missing signals: ${missing.joinToString(", ")}."
    }
}
