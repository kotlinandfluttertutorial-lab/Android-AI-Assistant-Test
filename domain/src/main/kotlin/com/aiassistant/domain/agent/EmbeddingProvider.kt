/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : EmbeddingProvider.kt
 * Purpose    : Domain port for converting text to dense float embedding
 *              vectors.  Covers both cloud (Gemini Embeddings, OpenAI
 *              Embeddings) and on-device (MiniLM) backends without
 *              exposing any SDK dependency at the domain layer.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Interface (Port — Hexagonal Architecture)
 *
 * Relationship to existing interfaces:
 *   - [OnDeviceEmbeddingModel] (core-common/RagContracts.kt) is the
 *     on-device-specific interface that includes model lifecycle
 *     (initialize, releaseMemory).  It is intentionally NOT extended here
 *     because cloud providers do not have a lifecycle.
 *   - [EmbeddingProvider] is the unified domain contract used by
 *     agents and retrieval pipelines regardless of backend.
 *   - The data layer may implement [EmbeddingProvider] by delegating to
 *     [OnDeviceEmbeddingModel] for the on-device case, or to a Retrofit
 *     service for the cloud case.
 *
 * Design Rules:
 *   - Pure Kotlin, zero Android/framework/SDK dependencies.
 *   - [EmbeddingVector] is defined here as a value object so callers
 *     depend only on this module.
 *
 * Dependencies: kotlinx.serialization
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.serialization.Serializable

// ── Value object ─────────────────────────────────────────────────────────────

/**
 * A dense float embedding vector produced by an [EmbeddingProvider].
 *
 * @param values      The embedding values.
 * @param modelName   Name of the model that produced this vector
 *                    (e.g. `"all-MiniLM-L6-v2"`, `"text-embedding-3-small"`).
 * @param sourceText  The text that was embedded (empty if not retained).
 */
@Serializable
data class EmbeddingVector(
    val values: List<Float>,
    val modelName: String = "",
    val sourceText: String = "",
) {
    init {
        require(values.isNotEmpty()) { "EmbeddingVector.values must not be empty." }
    }

    /** Dimensionality of this vector. */
    val dimensions: Int get() = values.size

    /**
     * Compute the cosine similarity between this vector and [other].
     *
     * Returns a value in [-1.0, 1.0].  Returns 0.0 when either vector is
     * the zero vector.
     *
     * @param other Vector to compare against.  Must have the same [dimensions].
     * @throws IllegalArgumentException when dimensions differ.
     */
    fun cosineSimilarity(other: EmbeddingVector): Float {
        require(dimensions == other.dimensions) {
            "Dimension mismatch: $dimensions vs ${other.dimensions}."
        }
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in values.indices) {
            dot += values[i] * other.values[i]
            normA += values[i] * values[i]
            normB += other.values[i] * other.values[i]
        }
        val denom = Math.sqrt((normA * normB).toDouble()).toFloat()
        return if (denom == 0f) 0f else dot / denom
    }
}

// ── Error type ────────────────────────────────────────────────────────────────

/**
 * Thrown by [EmbeddingProvider] on unrecoverable model or API failure.
 *
 * @param message   Human-readable description.  Must not contain API keys.
 * @param modelName The model that raised the error.
 * @param retryable True when a retry may succeed (transient error).
 * @param cause     Underlying throwable, if any.
 */
class EmbeddingException(
    message: String,
    val modelName: String = "",
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

// ── Interface ─────────────────────────────────────────────────────────────────

/**
 * Domain port for converting text to dense float embedding vectors.
 *
 * Implementations live in `:data`:
 * - `CloudEmbeddingProviderAdapter` — calls the backend embedding endpoint.
 * - `OnDeviceEmbeddingProviderAdapter` — delegates to
 *   [com.aiassistant.core.common.OnDeviceEmbeddingModel] (MiniLM).
 *
 * Neither implementation is visible at this domain layer.
 *
 * ## Thread safety
 * Implementations must be safe to call concurrently from multiple coroutines.
 * On-device implementations must serialise access if the underlying model
 * is not thread-safe.
 *
 * ## Usage
 * ```kotlin
 * val queryEmbedding = embeddingProvider.embed(userQuery)
 * val results = vectorStore.search(userId, queryEmbedding, topK = 5)
 * ```
 */
interface EmbeddingProvider {

    /**
     * The name of the underlying embedding model.
     * E.g. `"all-MiniLM-L6-v2"`, `"text-embedding-3-small"`.
     */
    val modelName: String

    /**
     * Dimensionality of vectors produced by this provider.
     * All vectors from a given provider instance have the same dimension.
     */
    val embeddingDimension: Int

    /**
     * `true` when this provider is ready to accept embedding requests.
     * For on-device providers this reflects model initialisation status;
     * for cloud providers this reflects network availability.
     */
    val isReady: Boolean

    /**
     * Embed a single [text] string and return its [EmbeddingVector].
     *
     * @param text Input text.  Must not be blank.
     * @return [EmbeddingVector] for [text].
     * @throws EmbeddingException On model or API failure.
     * @throws IllegalArgumentException When [text] is blank.
     */
    suspend fun embed(text: String): EmbeddingVector

    /**
     * Embed a batch of [texts] in a single call.
     *
     * Implementations may call [embed] sequentially when batching is not
     * supported by the underlying model.  Callers should always prefer this
     * method for bulk operations to allow implementations to optimise.
     *
     * @param texts Non-empty list of strings to embed.
     * @return List of [EmbeddingVector] in the same order as [texts].
     * @throws EmbeddingException On model or API failure.
     * @throws IllegalArgumentException When [texts] is empty.
     */
    suspend fun embedBatch(texts: List<String>): List<EmbeddingVector>
}
