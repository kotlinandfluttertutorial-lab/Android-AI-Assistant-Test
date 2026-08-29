/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : RouteQueryUseCase.kt
 * Purpose    : Evaluates the 4-bit capability bitmask + user preference and
 *              returns an OnDeviceRoutingDecision, then persists a log entry
 *              via QueryRoutingLogRepository.
 *
 * Architecture Layer : Domain — pure Kotlin use case.
 *
 * Design Decision    : The domain use case wraps core-ai's QueryRouter and
 *                      translates its result into the domain mirror types
 *                      (OnDeviceRoutingDecision, OnDeviceInferencePath) so
 *                      feature modules never depend on core-ai directly.
 *
 * Requirements: 36.1, 36.2, 36.3, 36.4, 36.5, 36.8, 36.9, 36.10
 * ============================================================
 */
package com.aiassistant.domain.usecase.ondevicerag

import com.aiassistant.core.ai.ondevicerag.InferencePath
import com.aiassistant.core.ai.ondevicerag.PathPreference
import com.aiassistant.core.ai.ondevicerag.QueryRouter
import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.OnDeviceInferencePath
import com.aiassistant.domain.model.OnDevicePathPreference
import com.aiassistant.domain.model.OnDeviceRoutingDecision
import com.aiassistant.domain.repository.QueryRoutingLogRepository
import javax.inject.Inject

/**
 * Determines the inference path for a single query and logs the decision.
 *
 * @param queryRouter        Core-ai pure routing function (bitmask → decision).
 * @param routingLogRepository Persists each routing decision for the audit trail.
 */
class RouteQueryUseCase @Inject constructor(
    private val queryRouter: QueryRouter,
    private val routingLogRepository: QueryRoutingLogRepository,
) {

    /**
     * Evaluates routing signals and returns the [OnDeviceRoutingDecision].
     *
     * Also persists a [QueryRoutingLogEntity] row via [routingLogRepository].
     * Log write failures are swallowed so a DB error never blocks the user query.
     *
     * @param userId             Owner of the query session.
     * @param capabilityBitmask  4-bit integer built by the caller from live signals.
     * @param userPreference     Explicit user preference from Settings, or null (auto).
     */
    suspend operator fun invoke(
        userId: String,
        capabilityBitmask: Int,
        userPreference: OnDevicePathPreference?,
    ): ApiResult<OnDeviceRoutingDecision> {
        // Translate domain preference → core-ai preference
        val corePreference = userPreference?.toCoreAi()

        // Evaluate routing decision (pure, no side effects)
        val coreDecision = queryRouter.evaluate(capabilityBitmask, corePreference)

        // Map to domain decision
        val domainDecision = OnDeviceRoutingDecision(
            path = coreDecision.path.toDomain(),
            capabilityBitmask = coreDecision.capabilityBitmask,
            reason = coreDecision.reason,
            fallbackOccurred = coreDecision.fallbackOccurred,
        )

        // Persist log entry — fire-and-forget; log failures do not block the query
        runCatching {
            routingLogRepository.logDecision(userId, domainDecision)
        }

        return ApiResult.Success(domainDecision)
    }

    // ── Extension mappers ─────────────────────────────────────────────────

    private fun OnDevicePathPreference.toCoreAi(): PathPreference = when (this) {
        OnDevicePathPreference.PREFER_ON_DEVICE -> PathPreference.PREFER_ON_DEVICE
        OnDevicePathPreference.PREFER_CLOUD -> PathPreference.PREFER_CLOUD
    }

    private fun InferencePath.toDomain(): OnDeviceInferencePath = when (this) {
        InferencePath.ON_DEVICE -> OnDeviceInferencePath.ON_DEVICE
        InferencePath.CLOUD -> OnDeviceInferencePath.CLOUD
    }
}
