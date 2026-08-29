/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai (test)
 * File       : OnDeviceInferenceEngineTest.kt
 * Purpose    : Unit tests for MediaPipeInferenceEngine implementation.
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import com.aiassistant.core.common.HardwareAccelerator
import com.aiassistant.core.common.ModelLoadEvent
import com.aiassistant.core.common.OnDeviceStreamEvent
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout

class OnDeviceInferenceEngineTest : DescribeSpec(
    {

    fun buildEngine(
        availableRamBytes: Long = 2L * 1024 * 1024 * 1024,
        isPowerSave: Boolean = false,
        thermalStatus: Int = 0
    ): MediaPipeInferenceEngine {
        val activityManager = mockk<ActivityManager> {
            every { getMemoryInfo(any()) } answers {
                val info = firstArg<ActivityManager.MemoryInfo>()
                info.availMem = availableRamBytes
                info.totalMem = 4L * 1024 * 1024 * 1024
            }
        }
        val powerManager = mockk<PowerManager> {
            every { isPowerSaveMode } returns isPowerSave
            every { currentThermalStatus } returns thermalStatus
        }
        val context = mockk<Context> {
            every { getSystemService(Context.ACTIVITY_SERVICE) } returns activityManager
            every { getSystemService(Context.POWER_SERVICE) } returns powerManager
        }
        return MediaPipeInferenceEngine(context)
    }

    fun createTempModelFile(): java.io.File {
        val f = java.io.File.createTempFile("engine_test_model", ".bin")
        f.writeText("dummy weights content")
        return f
    }

    fun sha256Hex(file: java.io.File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var read: Int
            while (input.read(buf).also { read = it } != -1) digest.update(buf, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    describe("loadModel()") {
        it("returns Failed when model file does not exist") {
            val engine = buildEngine()
            val result = engine.loadModel("/no/such/file.bin", "abc123")
            result.shouldBeInstanceOf<ModelLoadEvent.Failed>()
        }
        it("returns Ready when checksum matches") {
            val engine = buildEngine()
            val f = createTempModelFile()
            try {
                val result = engine.loadModel(f.absolutePath, sha256Hex(f))
                result shouldBe ModelLoadEvent.Ready
            } finally {
                f.delete()
            }
        }
    }

    describe("generateStream() — RAM monitoring") {
        it("emits Error(stage=ram_exceeded) when RAM drops below 512 MB") {
            val engine = buildEngine(availableRamBytes = 100L * 1024 * 1024)
            val f = createTempModelFile()
            try {
                engine.loadModel(f.absolutePath, sha256Hex(f))
                val events = withTimeout(5_000) {
                    engine.generateStream("test prompt").toList()
                }
                val errorEvent = events.filterIsInstance<OnDeviceStreamEvent.Error>()
                    .firstOrNull { it.stage == "ram_exceeded" }
                (errorEvent != null) shouldBe true
            } finally {
                f.delete()
            }
        }
    }

    describe("activeAccelerator()") {
        it("returns CPU when Battery Saver is active") {
            val engine = buildEngine(isPowerSave = true)
            engine.activeAccelerator() shouldBe HardwareAccelerator.CPU
        }
    }

    describe("cancelGeneration()") {
        it("causes generateStream to emit Cancelled") {
            val engine = buildEngine()
            val f = createTempModelFile()
            try {
                engine.loadModel(f.absolutePath, sha256Hex(f))
                val events = mutableListOf<OnDeviceStreamEvent>()
                withTimeout(1_000) {
                    engine.generateStream("long prompt").collect { event ->
                        events += event
                        if (event is OnDeviceStreamEvent.Token) {
                            engine.cancelGeneration()
                        }
                    }
                }
                events.any { it is OnDeviceStreamEvent.Cancelled } shouldBe true
            } finally {
                f.delete()
            }
        }
    }

    describe("releaseMemory()") {
        it("causes subsequent generateStream to emit model_not_loaded error") {
            val engine = buildEngine()
            val f = createTempModelFile()
            try {
                engine.loadModel(f.absolutePath, sha256Hex(f))
                engine.releaseMemory()
                val first = engine.generateStream("test").first()
                first.shouldBeInstanceOf<OnDeviceStreamEvent.Error>()
                val error = first as OnDeviceStreamEvent.Error
                error.stage shouldBe "model_not_loaded"
            } finally {
                f.delete()
            }
        }
    }
})
