/**
 * OnDeviceCapabilityState.kt
 *
 * Type alias — [OnDeviceCapabilityState] has moved to core-ai.
 * This file re-exports it so existing code in feature-on-device-ai
 * continues to compile without changes.
 */
package com.aiassistant.feature.ondeviceai

// Re-export the canonical type from core-ai so intra-module references still resolve.
typealias OnDeviceCapabilityState = com.aiassistant.core.ai.OnDeviceCapabilityState
