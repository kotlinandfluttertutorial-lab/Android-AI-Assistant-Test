/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai
 * File       : OnDeviceInferenceEngine.kt
 * Purpose    : Contract and sealed event types for the Gemma / GGUF on-device
 *              generation engine used exclusively as the generation component of
 *              the on-device RAG pipeline.  Embedding and retrieval are handled
 *              by OnDeviceEmbeddingModel and LocalVectorIndex respectively —
 *              this engine NEVER calls generateEmbedding or search.
 *
 * Architecture Layer : Core-AI — on-device RAG pipeline (query stage 3 of 3:
 *                      generation).
 *                      Called only by OnDeviceQueryUseCase after context is
 *                      assembled from the vector index.  Feature modules never
 *                      invoke this directly.
 *
 * Dependencies       : android.app.ActivityManager (RAM monitoring),
 *                      android.os.PowerManager (thermal + battery saver),
 *                      kotlinx.coroutines.flow (Flow<OnDeviceStreamEvent>).
 *
 * Design Decision    : Gemma is generation-only (Property 41 / Req 35.7).  The
 *                      interface exposes only generateStream, cancelGeneration,
 *                      benchmarkMode, and releaseMemory.  No embed / search
 *                      methods exist on this type.
 *
 *                      RAM is polled every 2 seconds during generation via
 *                      ActivityManager.MemoryInfo.  Dropping below 512 MB emits
 *                      OnDeviceStreamEvent.Error(stage="ram_exceeded") and
 *                      cancels generation so the caller can fall back to cloud.
 *
 *                      Thermal status is sampled once at generation start via
 *                      PowerManager.thermalStatus.  THERMAL_STATUS_CRITICAL
 *                      defers generation and emits Error(stage="thermal_critical");
 *                      re-checked every 30 seconds.
 *
 *                      Battery Saver mode restricts the accelerator to CPU-only
 *                      by passing a flag to the MediaPipe / JNI bridge.
 *
 *                      releaseMemory() is called by a lifecycle observer within
 *                      5 seconds of the app going to background.
 *
 *                      BenchmarkResult exposes p50/p95 TTFT, tokens/sec, and
 *                      peak RAM so ManageModelsScreen can display it without
 *                      coupling to the engine implementation.
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

// ── Accelerator enum ─────────────────────────────────────────────────────────

/** Hardware accelerator used by the inference engine. */
enum class HardwareAccelerator { CPU, GPU, NPU }

// ── Sealed event types ───────────────────────────────────────────────────────

/** Events emitted by [OnDeviceInferenceEngine.generateStream]. */
sealed class OnDeviceStreamEvent {

    /** One generated text token. */
    data class Token(val text: String) : OnDeviceStreamEvent()

    /**
     * Generation completed successfully.
     *
     * @param tokensGenerated   Number of tokens produced.
     * @param generationTimeMs  Wall-clock time from first token to Done.
     */
    data class Done(
        val tokensGenerated: Int,
        val generationTimeMs: Long,
    ) : OnDeviceStreamEvent()

    /**
     * Generation failed or was interrupted.
     *
     * @param message Human-readable description.
     * @param stage   Which lifecycle stage failed:
     *                "ram_exceeded" | "thermal_critical" | "checksum_mismatch" |
     *                "model_not_loaded" | "cancelled" | "inference_error"
     */
    data class Error(
        val message: String,
        val stage: String,
    ) : OnDeviceStreamEvent()

    /** Generation was cancelled via [OnDeviceInferenceEngine.cancelGeneration]. */
    data object Cancelled : OnDeviceStreamEvent()
}

// ── BenchmarkResult ──────────────────────────────────────────────────────────

/**
 * Result of [OnDeviceInferenceEngine.benchmarkMode].
 *
 * @param accelerator       Accelerator used during the run.
 * @param ttftMeanMs        Mean time-to-first-token in milliseconds.
 * @param ttftP95Ms         95th percentile TTFT in milliseconds.
 * @param tokensPerSecMean  Mean generation throughput (tokens/second).
 * @param tokensPerSecP95   95th percentile throughput.
 * @param peakRamMb         Peak RAM consumed during the benchmark run.
 */
data class BenchmarkResult(
    val accelerator: HardwareAccelerator,
    val ttftMeanMs: Long,
    val ttftP95Ms: Long,
    val tokensPerSecMean: Float,
    val tokensPerSecP95: Float,
    val peakRamMb: Int,
)

// ── Interface ────────────────────────────────────────────────────────────────

/**
 * Contract for the on-device Gemma / GGUF text generation engine.
 *
 * **Generation only** — this interface intentionally exposes no embedding or
 * vector-search methods (Property 41).
 */
interface OnDeviceInferenceEngine {

    /**
     * Loads the model from [modelPath] and verifies its SHA-256 checksum.
     *
     * @return [ModelLoadEvent.Ready] on success; [ModelLoadEvent.Failed] on
     *         checksum mismatch, missing file, or I/O failure.
     */
    suspend fun loadModel(modelPath: String, expectedChecksum: String): ModelLoadEvent

    /**
     * Streams generated tokens for [prompt] as a cold [Flow].
     *
     * The flow emits:
     * - [OnDeviceStreamEvent.Token] for each generated token.
     * - [OnDeviceStreamEvent.Done] when generation completes.
     * - [OnDeviceStreamEvent.Error] on RAM/thermal/model errors.
     * - [OnDeviceStreamEvent.Cancelled] when [cancelGeneration] is called.
     *
     * Precondition: [loadModel] must have returned [ModelLoadEvent.Ready].
     */
    fun generateStream(prompt: String): Flow<OnDeviceStreamEvent>

    /**
     * Cancels any in-progress generation.  Causes the active [generateStream]
     * Flow to emit [OnDeviceStreamEvent.Cancelled] and complete within 500 ms.
     *
     * Safe to call from any thread.  No-op if no generation is running.
     */
    fun cancelGeneration()

    /**
     * Runs the benchmark suite: 200-token fixed prompt × 10 iterations.
     *
     * @return [BenchmarkResult] with mean/p95 TTFT, throughput, and peak RAM.
     */
    suspend fun benchmarkMode(): BenchmarkResult

    /** Returns the accelerator currently active (CPU / GPU / NPU). */
    fun activeAccelerator(): HardwareAccelerator

    /**
     * Releases model weights from memory.
     *
     * Called by a lifecycle observer within 5 seconds of the app going to
     * background.  The model is reloaded on the next [generateStream] call.
     */
    fun releaseMemory()
}

// ── MediaPipe / stub implementation ──────────────────────────────────────────

/** RAM threshold below which generation is cancelled (512 MB). */
private const val RAM_THRESHOLD_BYTES = 512L * 1024L * 1024L

/** How often to poll RAM during generation (2 seconds per spec). */
private const val RAM_POLL_INTERVAL_MS = 2_000L

/** How long to wait before re-checking thermal status after a critical reading. */
private const val THERMAL_RECHECK_INTERVAL_MS = 30_000L

/** Number of tokens in the fixed benchmark prompt. */
private const val BENCHMARK_PROMPT_TOKENS = 200

/** Number of benchmark iterations. */
private const val BENCHMARK_ITERATIONS = 10

/**
 * [OnDeviceInferenceEngine] implementation targeting MediaPipe LLM Inference API
 * (and Gemma GGUF models as a fallback).
 *
 * ### Production wiring
 * Replace [runNativeInference] with a MediaPipe `LlmInference` session call:
 * ```kotlin
 * val options = LlmInference.LlmInferenceOptions.builder()
 *     .setModelPath(modelPath)
 *     .setMaxTokens(1024)
 *     .setResultListener { partialResult, done ->
 *         onToken(partialResult)
 *         if (done) onComplete()
 *     }.build()
 * val inference = LlmInference.createFromOptions(context, options)
 * inference.generateResponseAsync(prompt)
 * ```
 *
 * ### Current implementation
 * Uses a deterministic streaming stub so the full pipeline (routing, context
 * assembly, streaming UI) can be built and tested without the ~2 GB model file.
 */
@Singleton
class MediaPipeInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : OnDeviceInferenceEngine {

    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var modelLoaded = false
    private var modelPath: String? = null
    private val cancelled = AtomicBoolean(false)

    // ── loadModel ─────────────────────────────────────────────────────────────

    override suspend fun loadModel(modelPath: String, expectedChecksum: String): ModelLoadEvent =
        withContext(Dispatchers.IO) {
            try {
                val file = java.io.File(modelPath)
                if (!file.exists()) {
                    return@withContext ModelLoadEvent.Failed("Model file not found: $modelPath")
                }

                val actual = computeSha256(file)
                if (!actual.equals(expectedChecksum, ignoreCase = true)) {
                    return@withContext ModelLoadEvent.Failed(
                        "Checksum mismatch. Expected: $expectedChecksum  Got: $actual"
                    )
                }

                // TODO: Initialise MediaPipe LlmInference here.
                this@MediaPipeInferenceEngine.modelPath = modelPath
                modelLoaded = true
                ModelLoadEvent.Ready
            } catch (e: Exception) {
                ModelLoadEvent.Failed("loadModel failed: ${e.message}", e)
            }
        }

    // ── generateStream ────────────────────────────────────────────────────────

    override fun generateStream(prompt: String): Flow<OnDeviceStreamEvent> = flow {
        if (!modelLoaded) {
            emit(OnDeviceStreamEvent.Error("Model not loaded. Call loadModel() first.", "model_not_loaded"))
            return@flow
        }

        cancelled.set(false)

        // ── Thermal check at generation start ─────────────────────────────
        if (isThermalCritical()) {
            emit(OnDeviceStreamEvent.Error(
                "Device thermal status is critical. Generation deferred.",
                "thermal_critical"
            ))
            // Re-check every 30 s (caller decides whether to retry)
            delay(THERMAL_RECHECK_INTERVAL_MS)
            return@flow
        }

        // ── Battery saver: restrict to CPU ────────────────────────────────
        val forceCpu = powerManager.isPowerSaveMode

        val startTime = System.currentTimeMillis()
        var tokenCount = 0
        var ramExceeded = false

        try {
            // Stream tokens from native inference (stub for now)
            runNativeInference(
                prompt = prompt,
                forceCpu = forceCpu,
                onToken = { token ->
                    if (!cancelled.get()) {
                        // Emit token — checked inside coroutine context
                    }
                },
            ) { tokens ->
                // Completion callback — handled below
            }

            // ── Inline streaming with RAM monitoring ─────────────────────
            val words = buildStubResponse(prompt).split(" ")
            var lastRamCheck = System.currentTimeMillis()

            for (word in words) {
                if (cancelled.get()) {
                    emit(OnDeviceStreamEvent.Cancelled)
                    return@flow
                }

                // Poll RAM every RAM_POLL_INTERVAL_MS
                val now = System.currentTimeMillis()
                if (now - lastRamCheck >= RAM_POLL_INTERVAL_MS) {
                    lastRamCheck = now
                    val available = availableRamBytes()
                    if (available < RAM_THRESHOLD_BYTES) {
                        ramExceeded = true
                        emit(OnDeviceStreamEvent.Error(
                            "Available RAM dropped below 512 MB during generation. " +
                                "Cancelling — caller should retry via cloud.",
                            "ram_exceeded"
                        ))
                        cancelled.set(true)
                        return@flow
                    }
                }

                emit(OnDeviceStreamEvent.Token("$word "))
                tokenCount++
                delay(30L) // simulate ~30 ms/token throughput
            }

            if (!cancelled.get() && !ramExceeded) {
                emit(OnDeviceStreamEvent.Done(
                    tokensGenerated = tokenCount,
                    generationTimeMs = System.currentTimeMillis() - startTime,
                ))
            }

        } catch (e: CancellationException) {
            emit(OnDeviceStreamEvent.Cancelled)
            throw e
        } catch (e: Exception) {
            emit(OnDeviceStreamEvent.Error("Inference error: ${e.message}", "inference_error"))
        }
    }.flowOn(Dispatchers.Default)

    // ── cancelGeneration ──────────────────────────────────────────────────────

    override fun cancelGeneration() {
        cancelled.set(true)
        // TODO: call mediaPipeInference.cancel() here when wired up.
    }

    // ── benchmarkMode ─────────────────────────────────────────────────────────

    override suspend fun benchmarkMode(): BenchmarkResult = withContext(Dispatchers.Default) {
        val fixedPrompt = "Benchmark: ".padEnd(BENCHMARK_PROMPT_TOKENS * Chunker.CHARS_PER_TOKEN, 'x')
        val ttftList = mutableListOf<Long>()
        val throughputList = mutableListOf<Float>()
        var peakRam = 0

        repeat(BENCHMARK_ITERATIONS) {
            val iterStart = System.currentTimeMillis()
            var firstTokenMs = -1L
            var tokenCount = 0

            generateStream(fixedPrompt).collect { event ->
                when (event) {
                    is OnDeviceStreamEvent.Token -> {
                        if (firstTokenMs < 0) firstTokenMs = System.currentTimeMillis() - iterStart
                        tokenCount++
                    }
                    is OnDeviceStreamEvent.Done -> {
                        val elapsed = event.generationTimeMs.coerceAtLeast(1)
                        throughputList += tokenCount.toFloat() / (elapsed / 1000f)
                    }
                    else -> Unit
                }
            }

            if (firstTokenMs > 0) ttftList += firstTokenMs
            peakRam = maxOf(peakRam, ((totalRamBytes() - availableRamBytes()) / (1024 * 1024)).toInt())
        }

        val sortedTtft = ttftList.sorted()
        val sortedThroughput = throughputList.sorted()

        BenchmarkResult(
            accelerator = activeAccelerator(),
            ttftMeanMs = if (sortedTtft.isEmpty()) 0L else sortedTtft.average().toLong(),
            ttftP95Ms = percentile(sortedTtft, 0.95),
            tokensPerSecMean = if (sortedThroughput.isEmpty()) 0f else sortedThroughput.average().toFloat(),
            tokensPerSecP95 = percentile(sortedThroughput.map { it.toLong() }, 0.95).toFloat(),
            peakRamMb = peakRam,
        )
    }

    // ── activeAccelerator ─────────────────────────────────────────────────────

    override fun activeAccelerator(): HardwareAccelerator {
        return when {
            powerManager.isPowerSaveMode -> HardwareAccelerator.CPU
            // TODO: query MediaPipe for actual accelerator once wired up
            else -> HardwareAccelerator.GPU
        }
    }

    // ── releaseMemory ─────────────────────────────────────────────────────────

    override fun releaseMemory() {
        modelLoaded = false
        modelPath = null
        // TODO: mediaPipeInference.close() here to free native memory.
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun isThermalCritical(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL
    }

    private fun availableRamBytes(): Long {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.availMem
    }

    private fun totalRamBytes(): Long {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.totalMem
    }

    /**
     * Stub inference call — replace with MediaPipe LlmInference.generateResponseAsync().
     * [onToken] and [onComplete] callbacks match the MediaPipe result listener signature.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun runNativeInference(
        prompt: String,
        forceCpu: Boolean,
        onToken: (String) -> Unit,
        onComplete: (tokensGenerated: Int) -> Unit,
    ) {
        // TODO: wire up MediaPipe LlmInference here.
        // Currently a no-op; streaming is handled directly in generateStream().
    }

    private fun buildStubResponse(prompt: String): String =
        "[Gemma on-device] Context received. Generating answer for: \"${prompt.take(60)}\". " +
            "This response was generated entirely on-device. " +
            "No network calls were made to any external service."

    private fun computeSha256(file: java.io.File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var read: Int
            while (input.read(buf).also { read = it } != -1) digest.update(buf, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun percentile(sorted: List<Long>, p: Double): Long {
        if (sorted.isEmpty()) return 0L
        val idx = ((sorted.size - 1) * p).toInt()
        return sorted[idx]
    }
}
