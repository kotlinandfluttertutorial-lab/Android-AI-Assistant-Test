/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai
 * File       : OnDeviceEmbeddingModel.kt
 * Purpose    : Contract and sealed event types for the on-device text embedding
 *              model used in the RAG pipeline.  The default binding is
 *              MiniLmEmbeddingModel; test code can inject a deterministic stub.
 *
 * Architecture Layer : Core-AI — on-device RAG pipeline (ingestion stage 3 of 4
 *                      and query stage 1 of 3).
 *                      Used by LocalVectorIndex (addChunk + search) and
 *                      OnDeviceIngestDocumentUseCase / OnDeviceQueryUseCase.
 *
 * Dependencies       : Pure Kotlin interface — zero Android framework imports.
 *                      MiniLmEmbeddingModel uses java.security.MessageDigest for
 *                      checksum verification.
 *
 * Design Decision    : SHA-256 checksum verification is mandatory at initialize()
 *                      rather than deferred to first use.  A corrupted model that
 *                      passes silently would produce garbage embeddings that are
 *                      mathematically valid but semantically meaningless — very
 *                      hard to diagnose.  Failing fast with ModelLoadEvent.Failed
 *                      triggers the re-download prompt immediately.
 *
 *                      embeddingDimension is exposed as a property (not hardcoded)
 *                      so LocalVectorIndex and totalEmbeddingBytes() calculations
 *                      are correct when the underlying model changes (e.g. switching
 *                      from MiniLM-L6-v2 384-dim to a 768-dim variant).
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag

/** Events produced by [OnDeviceEmbeddingModel.initialize]. */
sealed class ModelLoadEvent {
    /** Model loaded and checksum verified successfully. */
    data object Ready : ModelLoadEvent()

    /**
     * Model failed to load.
     *
     * @param reason Human-readable failure description shown in ManageModelsScreen.
     * @param cause  Underlying exception, if any.
     */
    data class Failed(val reason: String, val cause: Throwable? = null) : ModelLoadEvent()
}

/**
 * Contract for the on-device text embedding model.
 *
 * Implementations must satisfy:
 * - **Determinism**: identical [text] on the same device/model weights → identical [FloatArray].
 * - **Dimension**: [embeddingDimension] ≥ 384.
 * - **Truncation**: inputs longer than 512 tokens are silently truncated before embedding.
 * - **Checksum**: [initialize] must reject a model file whose SHA-256 hash ≠ [expectedChecksum].
 */
interface OnDeviceEmbeddingModel {

    /** Number of float32 values in every embedding vector produced by this model. */
    val embeddingDimension: Int

    /** True after a successful [initialize] call; false otherwise. */
    val isReady: Boolean

    /**
     * Loads the model from [modelPath] and verifies its SHA-256 checksum.
     *
     * Must be called before [generateEmbedding].
     *
     * @param modelPath          Absolute path to the `.bin` / `.tflite` model file.
     * @param expectedChecksum   Lowercase hex SHA-256 string from the model manifest.
     * @return [ModelLoadEvent.Ready] on success; [ModelLoadEvent.Failed] on any error
     *         including checksum mismatch, missing file, or I/O failure.
     */
    suspend fun initialize(modelPath: String, expectedChecksum: String): ModelLoadEvent

    /**
     * Converts [text] to a float32 embedding vector of length [embeddingDimension].
     *
     * Precondition: [isReady] must be true; throws [IllegalStateException] otherwise.
     *
     * Text longer than 512 tokens (~2 048 chars) is truncated.  The truncation is
     * silent and deterministic — the same long input always produces the same embedding.
     *
     * @param text Input text to embed.
     * @return L2-normalised float32 embedding vector.
     */
    fun generateEmbedding(text: String): FloatArray
}

// ─── MiniLM-L6-v2 implementation ─────────────────────────────────────────────

/**
 * Maximum number of characters accepted before truncation.
 * Derived from: 512 tokens × 4 chars/token (Chunker.CHARS_PER_TOKEN).
 */
private const val MAX_INPUT_CHARS = 512 * Chunker.CHARS_PER_TOKEN

/** Minimum embedding dimension guaranteed by the spec (MiniLM-L6-v2 = 384). */
const val MINI_LM_EMBEDDING_DIM = 384

/**
 * [OnDeviceEmbeddingModel] backed by MiniLM-L6-v2 (384-dim, all-MiniLM-L6-v2).
 *
 * ### Production wiring
 * Replace the body of [runInference] with a call to the TensorFlow Lite interpreter
 * or MediaPipe Tasks TextEmbedder when the `.tflite` model file is bundled:
 * ```kotlin
 * val interpreter = Interpreter(FileUtil.loadMappedFile(context, "minilm_l6_v2.tflite"))
 * interpreter.run(inputBuffer, outputBuffer)
 * ```
 *
 * ### Current implementation
 * Uses a deterministic hash-based stub so the rest of the RAG pipeline (chunking,
 * vector index, routing) can be built, tested, and demonstrated without bundling
 * the actual 90 MB model file.  The stub satisfies the determinism invariant tested
 * by Property 38.
 */
class MiniLmEmbeddingModel : OnDeviceEmbeddingModel {

    override val embeddingDimension: Int = MINI_LM_EMBEDDING_DIM

    private var _isReady = false
    override val isReady: Boolean get() = _isReady

    /**
     * Verifies the SHA-256 checksum of the file at [modelPath] and marks the model ready.
     *
     * In production this would also deserialize the TFLite flatbuffer and warm up
     * the interpreter.  Here we only perform the checksum check so tests can inject
     * a real (or dummy) file path and exercise the failure branch.
     */
    override suspend fun initialize(modelPath: String, expectedChecksum: String): ModelLoadEvent {
        return try {
            val file = java.io.File(modelPath)
            if (!file.exists()) {
                return ModelLoadEvent.Failed("Model file not found at: $modelPath")
            }

            val actualChecksum = computeSha256(file)
            if (!actualChecksum.equals(expectedChecksum, ignoreCase = true)) {
                return ModelLoadEvent.Failed(
                    "Checksum mismatch for $modelPath. " +
                        "Expected: $expectedChecksum  Got: $actualChecksum"
                )
            }

            // TODO: Load TFLite interpreter here when model file is bundled.
            // val interpreter = Interpreter(FileUtil.loadMappedFile(context, modelPath))

            _isReady = true
            ModelLoadEvent.Ready
        } catch (e: Exception) {
            ModelLoadEvent.Failed("Failed to initialize embedding model: ${e.message}", e)
        }
    }

    /**
     * Generates a deterministic 384-dim embedding for [text].
     *
     * **Stub behaviour:** Produces a pseudo-random but deterministic FloatArray derived
     * from the text's hash code.  Every call with the same [text] on the same JVM
     * returns an identical array (Property 38 determinism invariant).
     *
     * Replace with real TFLite inference when the model file is available.
     */
    override fun generateEmbedding(text: String): FloatArray {
        check(_isReady) {
            "OnDeviceEmbeddingModel.generateEmbedding() called before initialize(). " +
                "Call initialize() and wait for ModelLoadEvent.Ready first."
        }

        // Truncate to 512-token limit (4 chars/token approximation)
        val truncated = if (text.length > MAX_INPUT_CHARS) text.substring(0, MAX_INPUT_CHARS) else text

        // TODO: Replace with actual TFLite inference:
        // val inputBuffer = tokenize(truncated)
        // val outputBuffer = Array(1) { FloatArray(MINI_LM_EMBEDDING_DIM) }
        // interpreter.run(inputBuffer, outputBuffer)
        // return l2Normalize(outputBuffer[0])

        // Deterministic stub: seed a pseudo-random generator from the text hash so
        // identical inputs always produce identical outputs (Property 38).
        val seed = truncated.fold(0L) { acc, c -> acc * 31 + c.code }
        val rng = java.util.Random(seed)
        val raw = FloatArray(MINI_LM_EMBEDDING_DIM) { rng.nextFloat() * 2f - 1f }
        return l2Normalize(raw)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** L2-normalises a vector in-place and returns it. */
    private fun l2Normalize(vector: FloatArray): FloatArray {
        val norm = Math.sqrt(vector.fold(0.0) { acc, v -> acc + v * v }).toFloat()
        if (norm < 1e-9f) return vector   // zero vector — return as-is to avoid NaN
        for (i in vector.indices) vector[i] /= norm
        return vector
    }

    /** Computes the lowercase hex SHA-256 hash of [file]. */
    private fun computeSha256(file: java.io.File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
