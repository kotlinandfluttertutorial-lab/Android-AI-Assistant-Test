/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : BenchmarkOnDeviceUseCase.kt
 * Purpose    : Delegates to OnDeviceInferenceEngine.benchmarkMode() and maps
 *              the result to the domain's OnDeviceBenchmarkResult so
 *              BenchmarkScreen never imports core-ai types.
 *
 * Architecture Layer : Domain — pure Kotlin use case.
 *
 * Requirements: 32.3, 32.4, 32.5
 * ============================================================
 */
package com.aiassistant.domain.usecase.ondevicerag

import com.aiassistant.core.ai.ondevicerag.HardwareAccelerator
import com.aiassistant.core.ai.ondevicerag.OnDeviceInferenceEngine
import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.OnDeviceAccelerator
import com.aiassistant.domain.model.OnDeviceBenchmarkResult
import javax.inject.Inject

/**
 * Runs the inference benchmark suite and returns structured results.
 *
 * Delegates to [OnDeviceInferenceEngine.benchmarkMode] which executes a
 * 200-token fixed prompt 10 times and captures TTFT and throughput stats.
 *
 * @param inferenceEngine The on-device Gemma / GGUF inference engine.
 */
class BenchmarkOnDeviceUseCase @Inject constructor(
    private val inferenceEngine: OnDeviceInferenceEngine,
) {

    /**
     * Runs the benchmark and returns an [OnDeviceBenchmarkResult].
     *
     * This is a long-running suspend call (10 × inference iterations).
     * Callers should launch it in a dedicated [CoroutineScope] and show
     * a progress indicator for the duration.
     */
    suspend operator fun invoke(): ApiResult<OnDeviceBenchmarkResult> = try {
        val coreResult = inferenceEngine.benchmarkMode()
        val domainResult = OnDeviceBenchmarkResult(
            accelerator = coreResult.accelerator.toDomain(),
            ttftMeanMs = coreResult.ttftMeanMs,
            ttftP95Ms = coreResult.ttftP95Ms,
            tokensPerSecMean = coreResult.tokensPerSecMean,
            tokensPerSecP95 = coreResult.tokensPerSecP95,
            peakRamMb = coreResult.peakRamMb,
        )
        ApiResult.Success(domainResult)
    } catch (e: Exception) {
        ApiResult.Error(
            com.aiassistant.core.common.DomainError.ServerError(
                message = "Benchmark failed: ${e.message}",
                code = 500,
            )
        )
    }

    private fun HardwareAccelerator.toDomain(): OnDeviceAccelerator = when (this) {
        HardwareAccelerator.CPU -> OnDeviceAccelerator.CPU
        HardwareAccelerator.GPU -> OnDeviceAccelerator.GPU
        HardwareAccelerator.NPU -> OnDeviceAccelerator.NPU
    }
}
