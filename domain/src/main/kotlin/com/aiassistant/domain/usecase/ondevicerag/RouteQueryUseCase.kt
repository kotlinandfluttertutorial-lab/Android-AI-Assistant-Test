/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : RouteQueryUseCase.kt
 * Purpose    : Determines the inference path for a single query and logs the decision.
 * ============================================================
 */
package com.aiassistant.domain.usecase.ondevicerag

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.InferencePath
import com.aiassistant.core.common.PathPreference
import com.aiassistant.core.common.QueryRouter
import com.aiassistant.domain.model.OnDeviceInferencePath
import com.aiassistant.domain.model.OnDevicePathPreference
import com.aiassistant.domain.model.OnDeviceRoutingDecision
import com.aiassistant.domain.repository.QueryRoutingLogRepository
import javax.inject.Inject

class RouteQueryUseCase @Inject constructor(
    private val queryRouter: QueryRouter,
    private val routingLogRepository: QueryRoutingLogRepository
) {

    suspend operator fun invoke(
        userId: String,
        capabilityBitmask: Int,
        userPreference: OnDevicePathPreference?
    ): ApiResult<OnDeviceRoutingDecision> {
        val corePreference = userPreference?.toCoreAi()
        val coreDecision = queryRouter.evaluate(capabilityBitmask, corePreference)

        val domainDecision = OnDeviceRoutingDecision(
            path = coreDecision.path.toDomain(),
            capabilityBitmask = coreDecision.capabilityBitmask,
            reason = coreDecision.reason,
            fallbackOccurred = coreDecision.fallbackOccurred
        )

        runCatching {
            routingLogRepository.logDecision(userId, domainDecision)
        }

        return ApiResult.Success(domainDecision)
    }

    private fun OnDevicePathPreference.toCoreAi(): PathPreference = when (this) {
        OnDevicePathPreference.PREFER_ON_DEVICE -> PathPreference.PREFER_ON_DEVICE
        OnDevicePathPreference.PREFER_CLOUD -> PathPreference.PREFER_CLOUD
    }

    private fun InferencePath.toDomain(): OnDeviceInferencePath = when (this) {
        InferencePath.ON_DEVICE -> OnDeviceInferencePath.ON_DEVICE
        InferencePath.CLOUD -> OnDeviceInferencePath.CLOUD
    }
}
