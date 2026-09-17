package com.aiassistant

import leakcanary.LeakCanary
import shark.IgnoredReferenceMatcher
import shark.InstanceFieldPattern

/**
 * LeakCanaryConfig.kt — debug-only LeakCanary customisation.
 *
 * Suppresses known Android framework leaks so they are categorised as
 * "Library Leaks" rather than "Application Leaks" in the LeakCanary UI,
 * preventing false-positive alerts cluttering the leak report.
 *
 * Call [configure] from [AIAssistantApplication.onCreate] in debug builds.
 */
object LeakCanaryConfig {

    fun configure() {
        LeakCanary.config = LeakCanary.config.copy(
            referenceMatchers = LeakCanary.config.referenceMatchers +
                // android.credentials.CredentialManager$GetCredentialTransport retains
                // the calling Activity's Context after the activity is destroyed.
                // Known Android framework bug on API 34/35+:
                // https://github.com/android/identity-samples/issues/56
                // Not caused by app code — suppress to avoid noise.
                IgnoredReferenceMatcher(
                    pattern = InstanceFieldPattern(
                        className = "android.credentials.CredentialManager\$GetCredentialTransport",
                        fieldName = "mContext"
                    )
                )
        )
    }
}
