/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai (test)
 * File       : EmbeddingDeterminismPropertyTest.kt
 *
 * Property 38: Embedding Determinism
 * Validates  : Requirement 34.5
 *
 * Specification:
 *   Generate random strings (1–512 chars); call
 *   embeddingModel.generateEmbedding(text) twice on the same instance;
 *   assert assertContentEquals(result1, result2) for all inputs.
 *
 * Architecture Layer : Core-AI test — pure JVM, no Android deps.
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

class EmbeddingDeterminismPropertyTest : DescribeSpec(
    {

    /**
     * Property 38: For any text of 1–512 chars, calling generateEmbedding
     * twice on the same model instance produces identical FloatArray values.
     *
     * This verifies the determinism invariant required by the spec so that
     * re-ingesting a document yields the same vectors (enabling the
     * "overwrite on same chunk.id" behaviour in LocalVectorIndex).
     */
    describe("Property 38 — Embedding Determinism") {

        val model = readyModel()

        it("generateEmbedding returns identical FloatArray on repeated calls for any input") {
            checkAll(
                iterations = 200,
                Arb.string(minSize = 1, maxSize = 512)
            ) { text ->
                val result1 = model.generateEmbedding(text)
                val result2 = model.generateEmbedding(text)

                // assertContentEquals is the spec's prescribed assertion
                result1.contentEquals(result2) shouldBe true
            }
        }

        it("identical inputs always produce identical output (empty string edge case)") {
            val e1 = model.generateEmbedding("")
            val e2 = model.generateEmbedding("")
            e1.contentEquals(e2) shouldBe true
        }

        it("different inputs produce different embeddings in the vast majority of cases") {
            // Not a strict requirement of Property 38, but validates the model
            // is actually differentiating inputs and not returning a constant vector.
            var collisions = 0
            checkAll(
                iterations = 50,
                Arb.string(minSize = 5, maxSize = 100),
                Arb.string(minSize = 5, maxSize = 100)
            ) { a, b ->
                if (a != b) {
                    val ea = model.generateEmbedding(a)
                    val eb = model.generateEmbedding(b)
                    if (ea.contentEquals(eb)) collisions++
                }
            }
            // Allow ≤2 collisions out of 50 iterations as a generous threshold
            (collisions <= 2) shouldBe true
        }
    }
})

// ── Helper ────────────────────────────────────────────────────────────────────

/** Returns a MiniLmEmbeddingModel (determinism test uses real implementation). */
private fun readyModel(): MiniLmEmbeddingModel = MiniLmEmbeddingModel()
