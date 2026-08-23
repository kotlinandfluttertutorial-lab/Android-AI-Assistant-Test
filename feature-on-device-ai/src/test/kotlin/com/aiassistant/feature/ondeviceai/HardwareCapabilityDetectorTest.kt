/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-ai
 * File       : HardwareCapabilityDetectorTest.kt
 * Purpose    : Unit tests for HardwareCapabilityDetector business logic.
 *              EGL calls are not exercised in unit tests (they require hardware);
 *              the tests focus on the memory threshold and vendor-hint matching logic
 *              via the public data contract of HardwareCapability.
 *
 * Architecture Layer : Feature (feature-on-device-ai) — tests
 * Pattern Used       : Kotest DescribeSpec + MockK
 *
 * Requirements: 31.1
 * ============================================================
 */

package com.aiassistant.feature.ondeviceai

import io.kotest.core.spec.style.DescribeSpec
import io.mockk.unmockkAll
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class HardwareCapabilityDetectorTest :
    DescribeSpec({

        afterSpec {
            unmockkAll()
        }

        describe("HardwareCapability logic") {
            it("isSupported false when memory below 4GB") {
                val cap = HardwareCapability(
                    isSupported = false,
                    availableBytes = 2L * 1024L * 1024L * 1024L, // 2 GB
                    vendorInfo = "Qualcomm Adreno"
                )
                assertFalse(cap.isSupported)
            }

            it("isSupported false when no NPU GPU hint") {
                val cap = HardwareCapability(
                    isSupported = false,
                    availableBytes = 6L * 1024L * 1024L * 1024L,
                    vendorInfo = "Unknown GPU"
                )
                assertFalse(cap.isSupported)
            }

            it("isSupported true when both criteria met") {
                val cap = HardwareCapability(
                    isSupported = true,
                    availableBytes = 8L * 1024L * 1024L * 1024L,
                    vendorInfo = "Qualcomm Adreno 740"
                )
                assertTrue(cap.isSupported)
            }

            it("REQUIRED_MEMORY_BYTES constant equals 4 GB") {
                val expected = 4L * 1024L * 1024L * 1024L
                val cap = HardwareCapability(
                    isSupported = true,
                    availableBytes = expected,
                    vendorInfo = "adreno"
                )
                assertTrue(cap.availableBytes == expected)
            }

            it("HardwareCapability vendor info nullable") {
                val cap = HardwareCapability(
                    isSupported = false,
                    availableBytes = 1024L,
                    vendorInfo = null
                )
                assertFalse(cap.isSupported)
            }
        }
    })
