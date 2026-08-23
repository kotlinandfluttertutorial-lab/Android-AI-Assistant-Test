package com.aiassistant.feature.ondeviceai

import io.kotest.core.spec.style.DescribeSpec
import io.mockk.mockk
import io.mockk.unmockkAll

class OnDeviceAiInitializerTest : DescribeSpec({
    val capabilityDetector = mockk<HardwareCapabilityDetector>(relaxed = true)
    val modelManager = mockk<OnDeviceModelManager>(relaxed = true)
    val inferenceClient = mockk<OnDeviceInferenceClient>(relaxed = true)

    val initializer = OnDeviceAiInitializer(
        capabilityDetector = capabilityDetector,
        modelManager = modelManager,
        inferenceClient = inferenceClient
    )

    afterEach {
        initializer.cancelScope()
    }

    afterSpec {
        unmockkAll()
    }

    describe("OnDeviceAiInitializer cleanup") {
        it("cancelScope cancels the internal coroutine scope") {
            // This test just ensures the method can be called without error
            // as requested by the memory management consolidation task.
            initializer.cancelScope()
        }
    }
})
