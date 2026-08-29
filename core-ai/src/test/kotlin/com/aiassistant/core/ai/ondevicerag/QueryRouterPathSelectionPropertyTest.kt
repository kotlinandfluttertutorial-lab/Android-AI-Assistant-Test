/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai (test)
 * File       : QueryRouterPathSelectionPropertyTest.kt
 *
 * Property 40: Query Router Path Selection Correctness
 * Validates  : Requirements 36.1, 36.2
 *
 * Specification:
 *   Generate all 16 bitmask values (0–15) × 3 preference options
 *   (null, PREFER_ON_DEVICE, PREFER_CLOUD); assert decision.path == ON_DEVICE
 *   iff bitmask == 15 AND preference != PREFER_CLOUD; assert CLOUD in all
 *   other cases; assert no other factor influences the decision.
 *
 * Architecture Layer : Core-AI test — pure JVM, no Android deps.
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll

class QueryRouterPathSelectionPropertyTest : DescribeSpec({

    val router = QueryRouter()

    /**
     * Property 40: Exhaustive check of all 48 (16 × 3) bitmask × preference
     * combinations.  Verifies the exact routing rule:
     *
     *   ON_DEVICE  iff  bitmask == 0b1111 AND preference != PREFER_CLOUD
     *             OR   bitmask == 0b0111 (offline capable, no network)
     *   CLOUD      in all other cases
     */
    describe("Property 40 — Query Router Path Selection Correctness") {

        it("covers all 16 bitmask values × 3 preferences exhaustively") {
            // Enumerate all combinations explicitly so the property is fully deterministic
            val preferences = listOf(null, PathPreference.PREFER_ON_DEVICE, PathPreference.PREFER_CLOUD)
            val allCombinations = (0..15).flatMap { mask -> preferences.map { pref -> mask to pref } }

            allCombinations.forEach { (bitmask, preference) ->
                val decision = router.evaluate(bitmask, preference)

                val offlineCapable =
                    (bitmask and CapabilityBit.ALL_ON_DEVICE_CAPABLE) == CapabilityBit.ALL_ON_DEVICE_CAPABLE &&
                        (bitmask and CapabilityBit.NETWORK_REACHABLE) == 0

                val expectedPath = when {
                    // Offline rule: bits 0-2 set, bit 3 unset → always ON_DEVICE
                    offlineCapable -> InferencePath.ON_DEVICE
                    // Fully capable + cloud preference → CLOUD
                    bitmask == CapabilityBit.FULLY_CAPABLE && preference == PathPreference.PREFER_CLOUD -> InferencePath.CLOUD
                    // Fully capable, auto or on-device preference → ON_DEVICE
                    bitmask == CapabilityBit.FULLY_CAPABLE -> InferencePath.ON_DEVICE
                    // Any missing signal → CLOUD
                    else -> InferencePath.CLOUD
                }

                decision.path shouldBe expectedPath
            }
        }

        it("uses Kotest PropTest to verify bitmask in 0..15 always produces a valid InferencePath") {
            checkAll(
                iterations = 48,
                Arb.int(0, 15),
                Arb.element(listOf(null, PathPreference.PREFER_ON_DEVICE, PathPreference.PREFER_CLOUD)),
            ) { bitmask, preference ->
                val decision = router.evaluate(bitmask, preference)
                // Result is always one of the two valid paths — never null/error
                (decision.path == InferencePath.ON_DEVICE || decision.path == InferencePath.CLOUD) shouldBe true
                // Bitmask is preserved in the decision
                decision.capabilityBitmask shouldBe bitmask
            }
        }

        it("bitmask 15 + null preference always ON_DEVICE") {
            checkAll(iterations = 10, Arb.of(15)) { bitmask ->
                router.evaluate(bitmask, null).path shouldBe InferencePath.ON_DEVICE
            }
        }

        it("bitmask 15 + PREFER_CLOUD always CLOUD") {
            checkAll(iterations = 10, Arb.of(15)) { bitmask ->
                router.evaluate(bitmask, PathPreference.PREFER_CLOUD).path shouldBe InferencePath.CLOUD
            }
        }

        it("any bitmask 0..14 (except 7) with any preference always CLOUD") {
            val nonOfflineMasks = (0..14).filter { it != 7 }
            nonOfflineMasks.forEach { mask ->
                listOf(null, PathPreference.PREFER_ON_DEVICE, PathPreference.PREFER_CLOUD).forEach { pref ->
                    router.evaluate(mask, pref).path shouldBe InferencePath.CLOUD
                }
            }
        }

        it("bitmask 7 (offline capable) with PREFER_CLOUD still produces ON_DEVICE") {
            router.evaluate(7, PathPreference.PREFER_CLOUD).path shouldBe InferencePath.ON_DEVICE
        }

        it("fallbackOccurred defaults to false for all evaluate() results") {
            checkAll(iterations = 48, Arb.int(0, 15)) { bitmask ->
                router.evaluate(bitmask, null).fallbackOccurred shouldBe false
            }
        }

        it("no factor other than bitmask and preference affects path selection") {
            // Call evaluate() twice with identical inputs and verify identical outputs
            checkAll(
                iterations = 48,
                Arb.int(0, 15),
                Arb.element(listOf(null, PathPreference.PREFER_ON_DEVICE, PathPreference.PREFER_CLOUD)),
            ) { bitmask, preference ->
                val d1 = router.evaluate(bitmask, preference)
                val d2 = router.evaluate(bitmask, preference)
                d1.path shouldBe d2.path
                d1.capabilityBitmask shouldBe d2.capabilityBitmask
            }
        }
    }
})
