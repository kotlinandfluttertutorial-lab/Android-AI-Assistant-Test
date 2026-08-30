/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai
 * File       : QueryRouter.kt
 * Purpose    : Pure routing decision engine implementation.
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag

import com.aiassistant.core.common.CapabilityBit
import com.aiassistant.core.common.InferencePath
import com.aiassistant.core.common.PathPreference
import com.aiassistant.core.common.QueryRouter
import com.aiassistant.core.common.RoutingDecision
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QueryRouterImpl @Inject constructor() : QueryRouter {

    override fun evaluate(capabilityBitmask: Int, userPreference: PathPreference?): RoutingDecision {
        val offlineOnDeviceCapable =
            (capabilityBitmask and CapabilityBit.ALL_ON_DEVICE_CAPABLE) == CapabilityBit.ALL_ON_DEVICE_CAPABLE &&
                (capabilityBitmask and CapabilityBit.NETWORK_REACHABLE) == 0

        if (offlineOnDeviceCapable) {
            return RoutingDecision(
                path = InferencePath.ON_DEVICE,
                capabilityBitmask = capabilityBitmask,
                reason = "Offline + capable."
            )
        }

        val fullyCapable = capabilityBitmask == CapabilityBit.FULLY_CAPABLE

        return when {
            fullyCapable && userPreference == PathPreference.PREFER_CLOUD -> RoutingDecision(
                path = InferencePath.CLOUD,
                capabilityBitmask = capabilityBitmask,
                reason = "User prefers cloud."
            )
            fullyCapable -> RoutingDecision(
                path = InferencePath.ON_DEVICE,
                capabilityBitmask = capabilityBitmask,
                reason = "Fully capable."
            )
            else -> RoutingDecision(
                path = InferencePath.CLOUD,
                capabilityBitmask = capabilityBitmask,
                reason = "Missing signals."
            )
        }
    }
}
