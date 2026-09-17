package com.aiassistant

/**
 * Release stub — LeakCanary is not included in release builds.
 * This object exists only so [AIAssistantApplication] can call
 * [configure] without a conditional compile guard.
 */
object LeakCanaryConfig {
    fun configure() {
        // No-op in release builds. LeakCanary is debugImplementation only.
    }
}
