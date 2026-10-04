/*
 * Unit tests for OnDeviceEmbeddingProviderAdapter.
 *
 * OnDeviceEmbeddingModel is faked — no real MiniLM model loaded.
 * No production credentials, no Android framework, no network.
 */
package com.aiassistant.data.embedding

import com.aiassistant.core.common.ModelLoadEvent
import com.aiassistant.core.common.OnDeviceEmbeddingModel
import com.aiassistant.domain.agent.EmbeddingException
import com.aiassistant.domain.agent.EmbeddingProvider
import com.aiassistant.domain.agent.EmbeddingVector
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

// ── Fake OnDeviceEmbeddingModel ───────────────────────────────────────────────

private class FakeEmbeddingModel(
    override val embeddingDimension: Int = 4,
    private val throwOnEmbed: Boolean = false,
    private val initResult: ModelLoadEvent = ModelLoadEvent.Ready,
) : OnDeviceEmbeddingModel {

    var generateCallCount = 0

    override suspend fun initialize(modelPath: String, expectedChecksum: String) = initResult

    override suspend fun generateEmbedding(text: String): FloatArray {
        if (throwOnEmbed) throw RuntimeException("model failure")
        generateCallCount++
        // Deterministic fake: spread text.length across the vector
        val v = text.length.toFloat()
        return FloatArray(embeddingDimension) { i -> v + i }
    }

    override fun releaseMemory() {}
}

// ── Tests ─────────────────────────────────────────────────────────────────────

class OnDeviceEmbeddingProviderAdapterTest {

    private fun adapter(
        dim: Int = 4,
        throwOnEmbed: Boolean = false,
        modelId: String = "test-model",
    ) = OnDeviceEmbeddingProviderAdapter(
        model = FakeEmbeddingModel(embeddingDimension = dim, throwOnEmbed = throwOnEmbed),
        modelId = modelId,
    )

    // ── Properties ────────────────────────────────────────────────────────────

    @Test fun `modelName returns injected modelId`() {
        adapter(modelId = "all-MiniLM-L6-v2").modelName shouldBe "all-MiniLM-L6-v2"
    }

    @Test fun `embeddingDimension delegates to model`() {
        adapter(dim = 8).embeddingDimension shouldBe 8
    }

    @Test fun `isReady is false before initialise`() {
        adapter().isReady.shouldBeFalse()
    }

    @Test fun `isReady is true after successful initialise`() = runTest {
        val a = adapter()
        a.initialize("/path/model.bin", "abc123")
        a.isReady.shouldBeTrue()
    }

    @Test fun `isReady is false after failed initialise`() = runTest {
        val a = OnDeviceEmbeddingProviderAdapter(
            model = FakeEmbeddingModel(initResult = ModelLoadEvent.Failed("bad checksum")),
            modelId = "m",
        )
        a.initialize("/path/model.bin", "wrong")
        a.isReady.shouldBeFalse()
    }

    @Test fun `implements EmbeddingProvider interface`() {
        val p: EmbeddingProvider = adapter()
        p shouldNotBe null
    }

    // ── initialize ────────────────────────────────────────────────────────────

    @Test fun `initialize returns Ready on success`() = runTest {
        val result = adapter().initialize("/model.bin", "sha256")
        (result is ModelLoadEvent.Ready).shouldBeTrue()
    }

    @Test fun `initialize returns Failed when model says so`() = runTest {
        val a = OnDeviceEmbeddingProviderAdapter(
            model = FakeEmbeddingModel(initResult = ModelLoadEvent.Failed("missing")),
            modelId = "m",
        )
        val result = a.initialize("/bad.bin", "wrong")
        (result is ModelLoadEvent.Failed).shouldBeTrue()
    }

    // ── embed ─────────────────────────────────────────────────────────────────

    @Test fun `embed returns EmbeddingVector with correct dimension`() = runTest {
        val vec = adapter(dim = 4).embed("hello")
        vec.dimensions shouldBe 4
    }

    @Test fun `embed sets model name`() = runTest {
        val vec = adapter(modelId = "my-model").embed("hi")
        vec.modelName shouldBe "my-model"
    }

    @Test fun `embed sets source text`() = runTest {
        val vec = adapter().embed("test input")
        vec.sourceText shouldBe "test input"
    }

    @Test fun `embed values are non-empty`() = runTest {
        val vec = adapter(dim = 4).embed("hello")
        vec.values.isNotEmpty().shouldBeTrue()
    }

    @Test fun `embed blank text throws IllegalArgumentException`() = runTest {
        assertThrows<IllegalArgumentException> {
            adapter().embed("   ")
        }
    }

    @Test fun `embed empty string throws IllegalArgumentException`() = runTest {
        assertThrows<IllegalArgumentException> {
            adapter().embed("")
        }
    }

    @Test fun `embed throws EmbeddingException when model fails`() = runTest {
        val a = adapter(throwOnEmbed = true)
        assertThrows<EmbeddingException> {
            a.embed("text")
        }
    }

    @Test fun `EmbeddingException carries model name on failure`() = runTest {
        val a = adapter(throwOnEmbed = true, modelId = "bad-model")
        val ex = runCatching { a.embed("text") }.exceptionOrNull()
        (ex is EmbeddingException).shouldBeTrue()
        (ex as EmbeddingException).modelName shouldBe "bad-model"
    }

    // ── embedBatch ────────────────────────────────────────────────────────────

    @Test fun `embedBatch returns one vector per input`() = runTest {
        val results = adapter(dim = 4).embedBatch(listOf("a", "bb", "ccc"))
        results shouldHaveSize 3
    }

    @Test fun `embedBatch empty list throws IllegalArgumentException`() = runTest {
        assertThrows<IllegalArgumentException> {
            adapter().embedBatch(emptyList())
        }
    }

    @Test fun `embedBatch preserves order`() = runTest {
        val a = adapter(dim = 2)
        // FakeEmbeddingModel returns text.length-based vectors
        val results = a.embedBatch(listOf("a", "bb"))
        // "a".length=1 → [1f, 2f], "bb".length=2 → [2f, 3f]
        (results[0].values[0] < results[1].values[0]).shouldBeTrue()
    }

    @Test fun `embedBatch source text set per item`() = runTest {
        val results = adapter().embedBatch(listOf("foo", "bar"))
        results[0].sourceText shouldBe "foo"
        results[1].sourceText shouldBe "bar"
    }

    @Test fun `embedBatch single item works`() = runTest {
        val results = adapter(dim = 4).embedBatch(listOf("only"))
        results shouldHaveSize 1
    }

    // ── releaseMemory ─────────────────────────────────────────────────────────

    @Test fun `releaseMemory sets isReady to false`() = runTest {
        val a = adapter()
        a.initialize("/model.bin", "sha256")
        a.isReady.shouldBeTrue()
        a.releaseMemory()
        a.isReady.shouldBeFalse()
    }
}
