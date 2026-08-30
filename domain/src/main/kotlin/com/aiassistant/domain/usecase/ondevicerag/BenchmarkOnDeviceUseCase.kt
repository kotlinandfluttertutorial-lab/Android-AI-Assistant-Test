/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : BenchmarkOnDeviceUseCase.kt
 * Purpose    : Delegates to OnDeviceInferenceEngine.benchmarkMode() and maps
 *              the result to the domain's OnDeviceBenchmarkResult.
 * ============================================================
 */
package com.aiassistant.domain.usecase.ondevicerag

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.common.HardwareAccelerator
import com.aiassistant.core.common.OnDeviceInferenceEngine
import com.aiassistant.domain.model.OnDeviceAccelerator
import com.aiassistant.domain.model.OnDeviceBenchmarkResult
import javax.inject.Inject

class BenchmarkOnDeviceUseCase @Inject constructor(private val inferenceEngine: OnDeviceInferenceEngine) {

    @Suppress("TooGenericExceptionCaught")
    suspend operator fun invoke(): ApiResult<OnDeviceBenchmarkResult> = try {
        val coreResult = inferenceEngine.benchmarkMode()
        val domainResult = OnDeviceBenchmarkResult(
            accelerator = coreResult.accelerator.toDomain(),
            ttftMeanMs = coreResult.ttftMeanMs,
            ttftP95Ms = coreResult.ttftP95Ms,
            tokensPerSecMean = coreResult.tokensPerSecMean,
            tokensPerSecP95 = coreResult.tokensPerSecP95,
            peakRamMb = coreResult.peakRamMb
        )
        ApiResult.Success(domainResult)
    } catch (e: Exception) {
        ApiResult.Error(
            DomainError.ServerError(
                message = "Benchmark failed: ${e.message}",
                httpStatusCode = 500
            )
        )
    }

    private fun HardwareAccelerator.toDomain(): OnDeviceAccelerator = when (this) {
        HardwareAccelerator.CPU -> OnDeviceAccelerator.CPU
        HardwareAccelerator.GPU -> OnDeviceAccelerator.GPU
        HardwareAccelerator.NPU -> OnDeviceAccelerator.NPU
    }
}
