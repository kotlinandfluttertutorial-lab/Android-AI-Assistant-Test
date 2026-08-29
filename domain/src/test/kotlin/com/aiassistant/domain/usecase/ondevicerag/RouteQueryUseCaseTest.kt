/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain (test)
 * File       : RouteQueryUseCaseTest.kt
 * Purpose    : Unit tests for RouteQueryUseCase.
 *              Validates:
 *                1. Log entry created for every invocation.
 *                2. ON_DEVICE path returned when bitmask == 0b1111.
 *                3. CLOUD fallback recorded when any signal is unset.
 *
 * Requirements: 21.1, 31.2, 36.1, 36.2
 * ============================================================
 */
package com.aiassistant.domain.usecase.ondevicerag

import com.aiassistant.core.ai.ondevicerag.CapabilityBit
import com.aiassistant.core.ai.ondevicerag.QueryRouter
import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.OnDeviceInferencePath
import com.aiassistant.domain.repository.QueryRoutingLogRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

class RouteQueryUseCaseTest : DescribeSpec({

    val router = QueryRouter()   // real implementation — pure function, no mocks needed

    describe("RouteQueryUseCase — routing decisions") {

        it("returns ON_DEVICE when bitmask == 15 and no preference") {
            val logRepo = mockk<QueryRoutingLogRepository>(relaxed = true)
            val useCase = RouteQueryUseCase(router, logRepo)

            val result = useCase("user1", CapabilityBit.FULLY_CAPABLE, null)

            result.shouldBeInstanceOf<ApiResult.Success<*>>()
            (result as ApiResult.Success).data.path shouldBe OnDeviceInferencePath.ON_DEVICE
        }

        it("returns CLOUD when bitmask is 0 (no signals present)") {
            val logRepo = mockk<QueryRoutingLogRepository>(relaxed = true)
            val useCase = RouteQueryUseCase(router, logRepo)

            val result = useCase("user1", 0, null)

            (result as ApiResult.Success).data.path shouldBe OnDeviceInferencePath.CLOUD
        }

        it("creates a log entry for every invocation — even on CLOUD path") {
            val logRepo = mockk<QueryRoutingLogRepository>(relaxed = true)
            coEvery { logRepo.logDecision(any(), any()) } returns ApiResult.Success(Unit)
            val useCase = RouteQueryUseCase(router, logRepo)

            useCase("user1", 0, null)
            useCase("user1", 15, null)
            useCase("user1", 7, null)

            coVerify(exactly = 3) { logRepo.logDecision(eq("user1"), any()) }
        }

        it("returns ON_DEVICE for offline on-device-capable bitmask (7) regardless of PREFER_CLOUD") {
            val logRepo = mockk<QueryRoutingLogRepository>(relaxed = true)
            val useCase = RouteQueryUseCase(router, logRepo)

            // bitmask 7 = bits 0-2 set, bit 3 unset → offline
            val result = useCase("user1", 7, com.aiassistant.domain.model.OnDevicePathPreference.PREFER_CLOUD)

            (result as ApiResult.Success).data.path shouldBe OnDeviceInferencePath.ON_DEVICE
        }

        it("log failure does not propagate as an error — result is still Success") {
            val logRepo = mockk<QueryRoutingLogRepository>()
            coEvery { logRepo.logDecision(any(), any()) } throws RuntimeException("DB error")
            val useCase = RouteQueryUseCase(router, logRepo)

            // Should not throw — log failures are swallowed
            val result = useCase("user1", 15, null)
            result.shouldBeInstanceOf<ApiResult.Success<*>>()
        }
    }

    describe("RouteQueryUseCase — fallbackOccurred field") {

        it("defaults to false on initial evaluate() call") {
            val logRepo = mockk<QueryRoutingLogRepository>(relaxed = true)
            val useCase = RouteQueryUseCase(router, logRepo)

            val result = useCase("user1", 15, null)
            (result as ApiResult.Success).data.fallbackOccurred shouldBe false
        }
    }
})
