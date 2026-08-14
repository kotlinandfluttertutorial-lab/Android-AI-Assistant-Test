/**
 * StubOnDeviceEngine.kt
 *
 * Purpose: A no-op implementation of [OnDeviceEngine] used in production builds when
 *          the actual llama.cpp JNI bindings are not bundled. Provides a clear error
 *          message instead of silently failing.
 *
 * Architecture: feature-on-device-ai — default binding; replaced in full builds.
 * Dependencies: None beyond the [OnDeviceEngine] interface.
 *
 * Design decisions:
 * - Provided as the Hilt default binding so the module compiles and runs correctly
 *   without requiring native libraries to be present in every build variant.
 * - In a production build with llama.cpp JNI, replace this binding with a
 *   `LlamaCppEngine` implementation that calls into the native library.
 *
 * Requirements: 31.2, 31.5
 */
package com.aiassistant.feature.ondeviceai

import javax.inject.Inject

/**
 * Stub engine that immediately reports an error, indicating that no native inference
 * library is available in this build variant.
 *
 * Replace with a real JNI-backed engine (e.g. wrapping llama.cpp) in production builds.
 */
class StubOnDeviceEngine @Inject constructor() : OnDeviceEngine {

    override fun runInference(
        modelPath: String,
        prompt: String,
        onToken: (String) -> Unit,
        onComplete: (inputTokens: Int, outputTokens: Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        onError(
            "On-device inference engine is not available in this build. " +
                "Please integrate a native inference library (e.g. llama.cpp) " +
                "and bind it via the OnDeviceAiModule."
        )
    }

    override fun cancel() {
        // No-op for the stub; the real engine would cancel the JNI inference thread.
    }
}
