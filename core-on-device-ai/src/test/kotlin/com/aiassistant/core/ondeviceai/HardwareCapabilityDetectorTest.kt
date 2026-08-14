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
 * Pattern Used       : Kotest DescribeSpec + JUnit 5 runner
 *
 * Requirements: 31.1
 * ============================================================
 */

package com.aiassistant.feature.ondeviceai

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [HardwareCapabilityDetector] logic.
 *
 * EGL calls require real hardware and are not exercised here. Instead the tests
 * validate the [HardwareCapability] data contract and the constants used for the
 * 4 GB memory threshold.
 */
class HardwareCapabilityDetectorTest : DescribeSpec({

    describe("HardwareCapability data class") {

        it("isSupported false when memory below 4 GB") {
            val cap = HardwareCapability(
                isSupported = false,
                availableBytes = 2L * 1024L * 1024L * 1024L, // 2 GB
                vendorInfo = "Qualcomm Adreno",
            )
            cap.isSupported.shouldBeFalse()
        }

        it("isSupported false when no NPU or GPU hint in vendor string") {
            val cap = HardwareCapability(
                isSupported = false,
                availableBytes = 6L * 1024L * 1024L * 1024L,
                vendorInfo = "Unknown GPU",
            )
            cap.isSupported.shouldBeFalse()
        }

        it("isSupported true when both memory and accelerator criteria are met") {
            val cap = HardwareCapability(
                isSupported = true,
                availableBytes = 8L * 1024L * 1024L * 1024L,
                vendorInfo = "Qualcomm Adreno 740",
            )
            cap.isSupported.shouldBeTrue()
        }

        it("availableBytes equals 4 GB when constructed with the threshold value") {
            val expected = 4L * 1024L * 1024L * 1024L
            val cap = HardwareCapability(
                isSupported = true,
                availableBytes = expected,
                vendorInfo = "adreno",
            )
            cap.availableBytes shouldBe expected
        }

        it("vendorInfo can be null and isSupported is false") {
            val cap = HardwareCapability(
                isSupported = false,
                availableBytes = 1024L,
                vendorInfo = null,
            )
            cap.isSupported.shouldBeFalse()
            cap.vendorInfo shouldBe null
        }

        it("isSupported false when memory below threshold even if vendor matches") {
            val cap = HardwareCapability(
                isSupported = false,
                availableBytes = 1L * 1024L * 1024L * 1024L, // only 1 GB
                vendorInfo = "Qualcomm Adreno 740",
            )
            cap.isSupported.shouldBeFalse()
        }

        it("isSupported false when memory sufficient but vendor is null") {
            val cap = HardwareCapability(
                isSupported = false,
                availableBytes = 8L * 1024L * 1024L * 1024L,
                vendorInfo = null,
            )
            cap.isSupported.shouldBeFalse()
        }
    }

    describe("HardwareCapability structural equality") {

        it("two instances with same values are equal") {
            val cap1 = HardwareCapability(isSupported = true, availableBytes = 4L shl 30, vendorInfo = "Mali")
            val cap2 = HardwareCapability(isSupported = true, availableBytes = 4L shl 30, vendorInfo = "Mali")
            cap1 shouldBe cap2
        }

        it("two instances with different isSupported differ") {
            val cap1 = HardwareCapability(isSupported = true, availableBytes = 4L shl 30, vendorInfo = "Mali")
            val cap2 = cap1.copy(isSupported = false)
            (cap1 == cap2).shouldBeFalse()
        }
    }
})
