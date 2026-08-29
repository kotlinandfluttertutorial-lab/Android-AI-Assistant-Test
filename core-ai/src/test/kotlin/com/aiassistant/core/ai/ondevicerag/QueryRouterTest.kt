/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai (test)
 * File       : QueryRouterTest.kt
 * Purpose    : Unit tests for QueryRouter covering all 16 bitmask values × 3
 *              preference options (null, PREFER_ON_DEVICE, PREFER_CLOUD).
 *
 *              This is a subset of Property 40 (full exhaustive coverage is in
 *              the property-test file in task 48); here we cover all 16×3 = 48
 *              combinations with explicit assertions and descriptive test names.
 *
 * Architecture Layer : Core-AI test — verifies routing decision logic.
 *
 * Requirements: 36.1, 36.2, 36.3, 36.4
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

class QueryRouterTest : DescribeSpec({

    val router = QueryRouter()

    // ── Fully capable (bitmask = 15 = 0b1111) ─────────────────────────────

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

    // ── Offline + on-device capable (bits 0-2 set, bit 3 unset = bitmask 7) ─

    describe("bitmask 7 (offline, on-device capable)") {

        it("routes ON_DEVICE regardless of null preference (offline rule)") {
            router.evaluate(7, null).path shouldBe InferencePath.ON_DEVICE
        }

        it("routes ON_DEVICE regardless of PREFER_ON_DEVICE (offline rule)") {
            router.evaluate(7, PathPreference.PREFER_ON_DEVICE).path shouldBe InferencePath.ON_DEVICE
        }

        it("routes ON_DEVICE even when PREFER_CLOUD — cloud is unreachable") {
            // Key behaviour: offline overrides even explicit PREFER_CLOUD
            router.evaluate(7, PathPreference.PREFER_CLOUD).path shouldBe InferencePath.ON_DEVICE
        }
    }

    // ── All bitmask values 0–14 (any missing signal except bitmask 7) ────────

    describe("bitmasks 0–14 except 7 (some signal missing, network may be present)") {
        val bitmasksExpectingCloud = (0..14).filter { it != 7 }

        bitmasksExpectingCloud.forEach { mask ->
            it("bitmask $mask (${mask.toString(2).padStart(4, '0')}) with null preference → CLOUD") {
                router.evaluate(mask, null).path shouldBe InferencePath.CLOUD
            }

            it("bitmask $mask with PREFER_ON_DEVICE → CLOUD (missing signals override preference)") {
                router.evaluate(mask, PathPreference.PREFER_ON_DEVICE).path shouldBe InferencePath.CLOUD
            }

            it("bitmask $mask with PREFER_CLOUD → CLOUD") {
                router.evaluate(mask, PathPreference.PREFER_CLOUD).path shouldBe InferencePath.CLOUD
            }
        }
    }

    // ── RoutingDecision fields ─────────────────────────────────────────────

    describe("RoutingDecision fields") {

        it("capabilityBitmask is preserved in the decision") {
            val decision = router.evaluate(15, null)
            decision.capabilityBitmask shouldBe 15
        }

        it("reason string is non-blank for every combination") {
            for (mask in 0..15) {
                for (pref in listOf(null, PathPreference.PREFER_ON_DEVICE, PathPreference.PREFER_CLOUD)) {
                    router.evaluate(mask, pref).reason.shouldNotBeBlank()
                }
            }
        }

        it("fallbackOccurred defaults to false on initial evaluate()") {
            router.evaluate(15, null).fallbackOccurred shouldBe false
        }
    }

    // ── CapabilityBit constants ────────────────────────────────────────────

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
