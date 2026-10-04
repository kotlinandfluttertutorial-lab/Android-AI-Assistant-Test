/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : OnDeviceEmbeddingProviderAdapter.kt
 * Purpose    : Adapts [OnDeviceEmbeddingModel] (core-common) to the
 *              domain [EmbeddingProvider] interface so agents and the
 *              on-device RAG pipeline can embed text without knowing
 *              whether the underlying engine is MiniLM, MediaPipe, or
 *              any future model.
 *
 * Architecture Layer : Data — embedding
 * Pattern Used       : Adapter
 *
 * Design rules:
 *   - Pure Kotlin, zero Android framework in the adapter logic.
 *   - [OnDeviceEmbeddingModel] (core-common/RagContracts.kt) is the
 *     infrastructure contract; [EmbeddingProvider] is the domain contract.
 *   - The adapter converts FloatArray → List<Float> so domain types
 *     remain free of array primitives.
 *   - [isReady] reflects the model's initialization state tracked via
 *     [ModelLoadEvent].  Callers should check before calling [embed].
 *   - All work happens on the caller's coroutine context; callers using
 *     the IO dispatcher are responsible for that dispatch.
 *
 * NOTE: The current [MiniLmEmbeddingModel] implementation returns
 * hash-based stub vectors (not real MiniLM inference).  This adapter
 * is correct and will automatically benefit when real inference is
 * wired in — no changes to the adapter are required.
 * ============================================================
 */

package com.aiassistant.data.embedding

import com.aiassistant.core.common.ModelLoadEvent
import com.aiassistant.core.common.OnDeviceEmbeddingModel
import com.aiassistant.domain.agent.EmbeddingException
import com.aiassistant.domain.agent.EmbeddingProvider
import com.aiassistant.domain.agent.EmbeddingVector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapts [OnDeviceEmbeddingModel] to the domain [EmbeddingProvider] port.
 *
 * The adapter is configured by injecting a concrete [OnDeviceEmbeddingModel]
 * (e.g. [com.aiassistant.core.ai.ondevicerag.MiniLmEmbeddingModel]) via
 * Hilt.  The model is expected to be already initialised before [embed] or
 * [embedBatch] are called; check [isReady] first.
 *
 * @param model   The on-device embedding model (injected).
 * @param modelId Human-readable model identifier for logging and metadata.
 */
@Singleton
class OnDeviceEmbeddingProviderAdapter @Inject constructor(
    private val model: OnDeviceEmbeddingModel,
    private val modelId: String = "all-MiniLM-L6-v2",
) : EmbeddingProvider {

    /** Tracks whether [initialize] succeeded. */
    @Volatile
    private var _ready: Boolean = false

    // ── EmbeddingProvider interface ───────────────────────────────────────────

    override val modelName: String
        get() = modelId

    override val embeddingDimension: Int
        get() = model.embeddingDimension

    override val isReady: Boolean
        get() = _ready

    /**
     * Embed a single [text] string.
     *
     * @param text Input text.  Must not be blank.
     * @return [EmbeddingVector] with [embeddingDimension] floats.
     * @throws EmbeddingException On model failure or if the model is not ready.
     * @throws IllegalArgumentException If [text] is blank.
     */
    override suspend fun embed(text: String): EmbeddingVector {
        require(text.isNotBlank()) { "EmbeddingProvider.embed() received blank text." }
        return embedBatch(listOf(text)).first()
    }

    /**
     * Embed a batch of [texts].
     *
     * Each text is embedded independently (the on-device model does not
     * support true batching).  Callers needing high throughput should
     * prefer parallel coroutines.
     *
     * @param texts Non-empty list.
     * @return List of [EmbeddingVector] in the same order as [texts].
     * @throws EmbeddingException On model failure.
     * @throws IllegalArgumentException If [texts] is empty.
     */
    override suspend fun embedBatch(texts: List<String>): List<EmbeddingVector> {
        require(texts.isNotEmpty()) { "EmbeddingProvider.embedBatch() received empty list." }

        return texts.map { text ->
            try {
                val raw: FloatArray = model.generateEmbedding(text)
                EmbeddingVector(
                    values = raw.asList(),
                    modelName = modelId,
                    sourceText = text,
                )
            } catch (e: Exception) {
                throw EmbeddingException(
                    message = "On-device embedding failed for model $modelId: ${e.message}",
                    modelName = modelId,
                    retryable = false,
                    cause = e,
                )
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Initialise the underlying model from [modelPath] and verify its
     * checksum.  Sets [isReady] to `true` on success.
     *
     * @param modelPath        Path to the GGUF / TFLite / ONNX model file.
     * @param expectedChecksum SHA-256 hex digest of the model file.
     * @return [ModelLoadEvent.Ready] on success; [ModelLoadEvent.Failed] on error.
     */
    suspend fun initialize(modelPath: String, expectedChecksum: String): ModelLoadEvent {
        val event = model.initialize(modelPath, expectedChecksum)
        _ready = event is ModelLoadEvent.Ready
        return event
    }

    /** Release any native memory held by the model. */
    fun releaseMemory() {
        _ready = false
        model.releaseMemory()
    }
}
