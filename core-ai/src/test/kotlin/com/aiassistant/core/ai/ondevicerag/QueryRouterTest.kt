/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai (test)
 * File       : QueryRouterTest.kt
 * Purpose    : Unit tests for QueryRouter implementation.
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag

import com.aiassistant.core.common.CapabilityBit
import com.aiassistant.core.common.InferencePath
import com.aiassistant.core.common.PathPreference
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

class QueryRouterTest : DescribeSpec(
    {

    val router = QueryRouterImpl()

    describe("bitmask 15 (all signals present)") {
        it("routes ON_DEVICE when preference is null (auto)") {
            router.evaluate(15, null).path shouldBe InferencePath.ON_DEVICE
        }
        it("routes ON_DEVICE when preference is PREFER_ON_DEVICE") {
            router.evaluate(15, PathPreference.PREFER_ON_DEVICE).path shouldBe InferencePath.ON_DEVICE
        }
        it("routes CLOUD when preference is PREFER_CLOUD") {
            router.evaluate(15, PathPreference.PREFER_CLOUD).path shouldBe InferencePath.CLOUD
        }
    }

    describe("bitmask 7 (offline, on-device capable)") {
        it("routes ON_DEVICE regardless of null preference (offline rule)") {
            router.evaluate(7, null).path shouldBe InferencePath.ON_DEVICE
        }
        it("routes ON_DEVICE regardless of PREFER_ON_DEVICE (offline rule)") {
            router.evaluate(7, PathPreference.PREFER_ON_DEVICE).path shouldBe InferencePath.ON_DEVICE
        }
        it("routes ON_DEVICE even when PREFER_CLOUD — cloud is unreachable") {
            router.evaluate(7, PathPreference.PREFER_CLOUD).path shouldBe InferencePath.ON_DEVICE
        }
    }

    describe("bitmasks 0–14 except 7") {
        val bitmasksExpectingCloud = (0..14).filter { it != 7 }
        bitmasksExpectingCloud.forEach { mask ->
            it("bitmask $mask with null preference → CLOUD") {
                router.evaluate(mask, null).path shouldBe InferencePath.CLOUD
            }
            it("bitmask $mask with PREFER_ON_DEVICE → CLOUD") {
                router.evaluate(mask, PathPreference.PREFER_ON_DEVICE).path shouldBe InferencePath.CLOUD
            }
            it("bitmask $mask with PREFER_CLOUD → CLOUD") {
                router.evaluate(mask, PathPreference.PREFER_CLOUD).path shouldBe InferencePath.CLOUD
            }
        }
    }

    describe("RoutingDecision fields") {
        it("capabilityBitmask is preserved in the decision") {
            router.evaluate(15, null).capabilityBitmask shouldBe 15
        }
        it("reason string is non-blank for every combination") {
            for (mask in 0..15) {
                for (pref in listOf(null, PathPreference.PREFER_ON_DEVICE, PathPreference.PREFER_CLOUD)) {
                    router.evaluate(mask, pref).reason.shouldNotBeBlank()
                }
            }
        }
        it("fallbackOccurred defaults to false") {
            router.evaluate(15, null).fallbackOccurred shouldBe false
        }
    }

    describe("CapabilityBit constants") {
        it("FULLY_CAPABLE == 15") {
            CapabilityBit.FULLY_CAPABLE shouldBe 15
        }
        it("ALL_ON_DEVICE_CAPABLE == 7 (bits 0-2)") {
            CapabilityBit.ALL_ON_DEVICE_CAPABLE shouldBe 7
        }
        it("individual bit constants are powers of 2") {
            CapabilityBit.GEMMA_READY shouldBe 1
            CapabilityBit.EMBEDDING_READY shouldBe 2
            CapabilityBit.CHUNKS_EXIST shouldBe 4
            CapabilityBit.NETWORK_REACHABLE shouldBe 8
        }
    }
})
