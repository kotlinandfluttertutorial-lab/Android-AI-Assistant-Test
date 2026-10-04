/*
 * Domain unit tests for EmbeddingProvider interface and EmbeddingVector value object.
 */
package com.aiassistant.domain.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

// ── Minimal stub ──────────────────────────────────────────────────────────────

private class FakeEmbeddingProvider(
    override val modelName: String = "test-model",
    override val embeddingDimension: Int = 4,
    override val isReady: Boolean = true,
) : EmbeddingProvider {

    override suspend fun embed(text: String): EmbeddingVector {
        require(text.isNotBlank()) { "text must not be blank" }
        // Simple deterministic fake: fill with text.length normalised
        val v = List(embeddingDimension) { text.length.toFloat() / 100f }
        return EmbeddingVector(values = v, modelName = modelName, sourceText = text)
    }

    override suspend fun embedBatch(texts: List<String>): List<EmbeddingVector> {
        require(texts.isNotEmpty()) { "texts must not be empty" }
        return texts.map { embed(it) }
    }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

class EmbeddingProviderTest {

    private fun provider(dim: Int = 4) = FakeEmbeddingProvider(embeddingDimension = dim)

    // ── EmbeddingVector ───────────────────────────────────────────────────────

    @Test fun `EmbeddingVector valid construction`() {
        val ev = EmbeddingVector(values = listOf(0.1f, 0.2f, 0.3f))
        ev.dimensions shouldBe 3
        ev.modelName shouldBe ""
    }

    @Test fun `EmbeddingVector empty values raises`() {
        shouldThrow<IllegalArgumentException> {
            EmbeddingVector(values = emptyList())
        }
    }

    @Test fun `EmbeddingVector dimensions matches values size`() {
        val ev = EmbeddingVector(values = List(384) { 0f })
        ev.dimensions shouldBe 384
    }

    @Test fun `EmbeddingVector stores all fields`() {
        val ev = EmbeddingVector(
            values = listOf(1f, 0f),
            modelName = "all-MiniLM-L6-v2",
            sourceText = "hello",
        )
        ev.modelName shouldBe "all-MiniLM-L6-v2"
        ev.sourceText shouldBe "hello"
    }

    @Test fun `cosineSimilarity of identical vectors is 1`() {
        val ev = EmbeddingVector(values = listOf(1f, 0f, 0f))
        ev.cosineSimilarity(ev) shouldBe (1f plusOrMinus 0.001f)
    }

    @Test fun `cosineSimilarity of orthogonal vectors is 0`() {
        val a = EmbeddingVector(values = listOf(1f, 0f))
        val b = EmbeddingVector(values = listOf(0f, 1f))
        a.cosineSimilarity(b) shouldBe (0f plusOrMinus 0.001f)
    }

    @Test fun `cosineSimilarity of opposite vectors is -1`() {
        val a = EmbeddingVector(values = listOf(1f, 0f))
        val b = EmbeddingVector(values = listOf(-1f, 0f))
        a.cosineSimilarity(b) shouldBe (-1f plusOrMinus 0.001f)
    }

    @Test fun `cosineSimilarity of zero vector returns 0`() {
        val a = EmbeddingVector(values = listOf(0f, 0f))
        val b = EmbeddingVector(values = listOf(1f, 1f))
        a.cosineSimilarity(b) shouldBe (0f plusOrMinus 0.001f)
    }

    @Test fun `cosineSimilarity dimension mismatch raises`() {
        val a = EmbeddingVector(values = listOf(1f, 0f))
        val b = EmbeddingVector(values = listOf(1f, 0f, 0f))
        shouldThrow<IllegalArgumentException> { a.cosineSimilarity(b) }
    }

    @Test fun `cosineSimilarity known value`() {
        // [1,1] and [1,0]: cos θ = 1/sqrt(2) ≈ 0.7071
        val a = EmbeddingVector(values = listOf(1f, 1f))
        val b = EmbeddingVector(values = listOf(1f, 0f))
        val expected = 1f / sqrt(2f)
        a.cosineSimilarity(b) shouldBe (expected plusOrMinus 0.001f)
    }

    // ── EmbeddingException ────────────────────────────────────────────────────

    @Test fun `EmbeddingException carries model name`() {
        val e = EmbeddingException("crash", modelName = "all-MiniLM", retryable = true)
        e.modelName shouldBe "all-MiniLM"
        e.retryable.shouldBeTrue()
    }

    @Test fun `EmbeddingException not retryable by default`() {
        EmbeddingException("crash").retryable.shouldBeFalse()
    }

    // ── Interface contract ────────────────────────────────────────────────────

    @Test fun `provider is ready`() {
        provider().isReady.shouldBeTrue()
    }

    @Test fun `provider reports correct modelName and dimension`() {
        val p = FakeEmbeddingProvider(modelName = "m1", embeddingDimension = 128)
        p.modelName shouldBe "m1"
        p.embeddingDimension shouldBe 128
    }

    @Test fun `embed returns vector with correct dimension`() = runTest {
        val ev = provider(dim = 4).embed("hello")
        ev.dimensions shouldBe 4
        ev.sourceText shouldBe "hello"
        ev.modelName shouldBe "test-model"
    }

    @Test fun `embed rejects blank text`() = runTest {
        shouldThrow<IllegalArgumentException> { provider().embed("  ") }
    }

    @Test fun `embedBatch returns one vector per input`() = runTest {
        val results = provider().embedBatch(listOf("a", "bb", "ccc"))
        results.size shouldBe 3
        results.all { it.dimensions == 4 }.shouldBeTrue()
    }

    @Test fun `embedBatch rejects empty list`() = runTest {
        shouldThrow<IllegalArgumentException> { provider().embedBatch(emptyList()) }
    }

    @Test fun `embedBatch vectors differ for different-length inputs`() = runTest {
        val results = provider().embedBatch(listOf("a", "longer text"))
        results[0].values shouldNotBe results[1].values
    }

    @Test fun `stub satisfies interface at compile time`() {
        val p: EmbeddingProvider = FakeEmbeddingProvider()
        p.modelName shouldBe "test-model"
    }

    @Test fun `not-ready provider reflects state`() {
        FakeEmbeddingProvider(isReady = false).isReady.shouldBeFalse()
    }
}
