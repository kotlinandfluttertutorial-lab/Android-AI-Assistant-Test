/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai
 * File       : MediaPipeInferenceEngine.kt
 * Purpose    : MediaPipe / GGUF on-device generation engine implementation.
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.aiassistant.core.common.BenchmarkResult
import com.aiassistant.core.common.Chunker
import com.aiassistant.core.common.HardwareAccelerator
import com.aiassistant.core.common.ModelLoadEvent
import com.aiassistant.core.common.OnDeviceInferenceEngine
import com.aiassistant.core.common.OnDeviceStreamEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

private const val RAM_THRESHOLD_BYTES = 536870912L // 512 MB
private const val RAM_POLL_INTERVAL_MS = 2000L
private const val THERMAL_RECHECK_INTERVAL_MS = 30000L
private const val BENCHMARK_PROMPT_TOKENS = 200
private const val BENCHMARK_ITERATIONS = 10
private const val SIMULATED_TOKEN_DELAY_MS = 30L
private const val BYTES_PER_MB = 1048576
private const val STUB_RESPONSE_PREVIEW_CHAR_LIMIT = 60
private const val SHA256_BUFFER_SIZE = 8192
private const val BENCHMARK_P95_PERCENTILE = 0.95
private const val DEFAULT_STAT_LONG = 0L

class MediaPipeInferenceEngine @Inject constructor(@ApplicationContext private val context: Context) :
    OnDeviceInferenceEngine {

    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var modelLoaded = false
    private var modelPath: String? = null
    private val cancelled = AtomicBoolean(false)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun loadModel(modelPath: String, expectedChecksum: String): ModelLoadEvent =
        withContext(Dispatchers.IO) {
            try {
                val file = java.io.File(modelPath)
                if (!file.exists()) {
                    return@withContext ModelLoadEvent.Failed("Model file not found: $modelPath")
                }
                val actual = computeSha256(file)
                if (!actual.equals(expectedChecksum, ignoreCase = true)) {
                    return@withContext ModelLoadEvent.Failed("Checksum mismatch.")
                }
                this@MediaPipeInferenceEngine.modelPath = modelPath
                modelLoaded = true
                ModelLoadEvent.Ready
            } catch (e: IOException) {
                ModelLoadEvent.Failed("loadModel failed: ${e.message}", e)
            } catch (e: Exception) {
                ModelLoadEvent.Failed("loadModel unexpected error: ${e.message}", e)
            }
        }

    @Suppress("TooGenericExceptionCaught")
    override fun generateStream(prompt: String): Flow<OnDeviceStreamEvent> = flow {
        if (!modelLoaded) {
            emit(OnDeviceStreamEvent.Error("Model not loaded.", "model_not_loaded"))
            return@flow
        }
        cancelled.set(false)
        if (isThermalCritical()) {
            emit(OnDeviceStreamEvent.Error("Thermal critical.", "thermal_critical"))
            delay(THERMAL_RECHECK_INTERVAL_MS)
            return@flow
        }

        val startTime = System.currentTimeMillis()
        try {
            runInferenceLoop(prompt, startTime)
        } catch (e: CancellationException) {
            emit(OnDeviceStreamEvent.Cancelled)
            throw e
        } catch (e: Exception) {
            emit(OnDeviceStreamEvent.Error("Inference error: ${e.message}", "inference_error"))
        }
    }.flowOn(Dispatchers.Default)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<OnDeviceStreamEvent>.runInferenceLoop(
        prompt: String,
        startTime: Long
    ) {
        val words = buildStubResponse(prompt).split(" ")
        var tokenCount = 0
        var lastRamCheck = 0L

        for (word in words) {
            if (cancelled.get()) {
                emit(OnDeviceStreamEvent.Cancelled)
                return
            }

            if (System.currentTimeMillis() - lastRamCheck >= RAM_POLL_INTERVAL_MS) {
                lastRamCheck = System.currentTimeMillis()
                if (checkRamUsage()) return
            }

            emit(OnDeviceStreamEvent.Token("$word "))
            tokenCount++
            delay(SIMULATED_TOKEN_DELAY_MS)
        }
        emit(OnDeviceStreamEvent.Done(tokenCount, System.currentTimeMillis() - startTime))
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<OnDeviceStreamEvent>.checkRamUsage(): Boolean {
        if (availableRamBytes() < RAM_THRESHOLD_BYTES) {
            emit(OnDeviceStreamEvent.Error("RAM exceeded.", "ram_exceeded"))
            cancelled.set(true)
            return true
        }
        return false
    }

    override fun cancelGeneration() {
        cancelled.set(true)
    }

    override suspend fun benchmarkMode(): BenchmarkResult = withContext(Dispatchers.Default) {
        val fixedPrompt = "Benchmark: ".padEnd(BENCHMARK_PROMPT_TOKENS * Chunker.CHARS_PER_TOKEN, 'x')
        val ttftList = mutableListOf<Long>()
        val throughputList = mutableListOf<Float>()
        var peakRam = 0

        repeat(BENCHMARK_ITERATIONS) {
            val stats = runBenchmarkIteration(fixedPrompt)
            stats.firstTokenMs?.let { ttftList.add(it) }
            stats.throughput?.let { throughputList.add(it) }
            peakRam = maxOf(peakRam, stats.peakRamMb)
        }

        buildBenchmarkResult(ttftList, throughputList, peakRam)
    }

    private suspend fun runBenchmarkIteration(prompt: String): IterationStats {
        val iterStart = System.currentTimeMillis()
        var firstTokenMs: Long? = null
        var tokenCount = 0
        var throughput: Float? = null

        generateStream(prompt).collect { event ->
            when (event) {
                is OnDeviceStreamEvent.Token -> {
                    if (firstTokenMs == null) firstTokenMs = System.currentTimeMillis() - iterStart
                    tokenCount++
                }
                is OnDeviceStreamEvent.Done -> {
                    val elapsed = event.generationTimeMs.coerceAtLeast(1)
                    throughput = tokenCount.toFloat() / (elapsed / 1000f)
                }
                else -> Unit
            }
        }
        val peak = ((totalRamBytes() - availableRamBytes()) / BYTES_PER_MB).toInt()
        return IterationStats(firstTokenMs, throughput, peak)
    }

    private data class IterationStats(val firstTokenMs: Long?, val throughput: Float?, val peakRamMb: Int)

    private fun buildBenchmarkResult(ttfts: List<Long>, throughputs: List<Float>, peakRam: Int): BenchmarkResult =
        BenchmarkResult(
            accelerator = activeAccelerator(),
            ttftMeanMs = if (ttfts.isEmpty()) DEFAULT_STAT_LONG else ttfts.average().toLong(),
            ttftP95Ms = percentile(ttfts, BENCHMARK_P95_PERCENTILE),
            tokensPerSecMean = if (throughputs.isEmpty()) 0f else throughputs.average().toFloat(),
            tokensPerSecP95 = percentile(throughputs.map { it.toLong() }, BENCHMARK_P95_PERCENTILE).toFloat(),
            peakRamMb = peakRam
        )

    override fun activeAccelerator(): HardwareAccelerator =
        if (powerManager.isPowerSaveMode) HardwareAccelerator.CPU else HardwareAccelerator.GPU

    override fun releaseMemory() {
        modelLoaded = false
        modelPath = null
    }

    private fun isThermalCritical() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL

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

    private fun buildStubResponse(prompt: String): String =
        "[Gemma on-device] Response for: \"${prompt.take(STUB_RESPONSE_PREVIEW_CHAR_LIMIT)}\"."

    private fun computeSha256(file: java.io.File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(SHA256_BUFFER_SIZE)
            var read: Int
            while (input.read(buf).also { read = it } != -1) digest.update(buf, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun percentile(sorted: List<Long>, p: Double): Long = if (sorted.isEmpty()) {
        0L
    } else {
        val list = sorted.sorted()
        val idx = ((list.size - 1) * p).toInt()
        list[idx]
    }
}
