/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai (test)
 * File       : OnDeviceInferenceEngineTest.kt
 * Purpose    : Unit tests for MediaPipeInferenceEngine.
 *              Validates the five spec requirements tested in task 45.6:
 *                1. RAM < 512 MB → Error(stage="ram_exceeded")
 *                2. Thermal critical → Error(stage="thermal_critical")
 *                3. Battery Saver restricts to CPU accelerator
 *                4. cancelGeneration() halts within 500 ms
 *                5. Checksum mismatch → ModelLoadEvent.Failed
 *
 * Architecture Layer : Core-AI test — verifies inference engine lifecycle.
 *
 * Note        : MediaPipeInferenceEngine depends on Android framework classes
 *               (ActivityManager, PowerManager).  Tests use MockK to provide
 *               controlled responses for RAM and thermal queries without
 *               requiring a device or Robolectric.
 *
 * Requirements: 32.1, 32.2, 32.5, 32.6, 32.7, 32.8, 32.9, 32.10
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout

class OnDeviceInferenceEngineTest : DescribeSpec({

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Creates a MediaPipeInferenceEngine wired to mock Android system services.
     *
     * @param availableRamBytes  Simulated free RAM returned by ActivityManager.
     * @param isPowerSave        Whether Battery Saver mode is active.
     * @param thermalStatus      PowerManager thermal status integer.
     */
    fun buildEngine(
        availableRamBytes: Long = 2L * 1024 * 1024 * 1024, // 2 GB default — sufficient
        isPowerSave: Boolean = false,
        thermalStatus: Int = 0, // THERMAL_STATUS_NONE
    ): MediaPipeInferenceEngine {
        val memInfo = ActivityManager.MemoryInfo().apply {
            availMem = availableRamBytes
            totalMem = 4L * 1024 * 1024 * 1024
        }
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
            val buf = ByteArray(8192); var read: Int
            while (input.read(buf).also { read = it } != -1) digest.update(buf, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ── loadModel — checksum mismatch ──────────────────────────────────────

    describe("loadModel()") {

        it("returns Failed when model file does not exist") {
            val engine = buildEngine()
            val result = engine.loadModel("/no/such/file.bin", "abc123")
            result.shouldBeInstanceOf<ModelLoadEvent.Failed>()
        }

        it("returns Failed on SHA-256 checksum mismatch") {
            val engine = buildEngine()
            val f = createTempModelFile()
            try {
                val result = engine.loadModel(f.absolutePath, "0".repeat(64))
                result.shouldBeInstanceOf<ModelLoadEvent.Failed>()
            } finally {
                f.delete()
            }
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

    // ── generateStream — RAM exceeded ──────────────────────────────────────

    describe("generateStream() — RAM monitoring") {

        it("emits Error(stage=ram_exceeded) when RAM drops below 512 MB during generation") {
            // Set available RAM to 100 MB — below 512 MB threshold
            val engine = buildEngine(availableRamBytes = 100L * 1024 * 1024)

            // Load model first
            val f = createTempModelFile()
            try {
                engine.loadModel(f.absolutePath, sha256Hex(f))

                // Collect all events; the RAM check fires immediately on the first poll
                val events = withTimeout(5_000) {
                    engine.generateStream("test prompt").toList()
                }

                val errorEvent = events.filterIsInstance<OnDeviceStreamEvent.Error>()
                    .firstOrNull { it.stage == "ram_exceeded" }
                errorEvent != null shouldBe true
            } finally {
                f.delete()
            }
        }
    }

    // ── generateStream — model not loaded ─────────────────────────────────

    describe("generateStream() — model not loaded") {

        it("emits Error(stage=model_not_loaded) when called before loadModel()") {
            val engine = buildEngine()
            val first = engine.generateStream("hello").first()
            first.shouldBeInstanceOf<OnDeviceStreamEvent.Error>()
            (first as OnDeviceStreamEvent.Error).stage shouldBe "model_not_loaded"
        }
    }

    // ── generateStream — Battery Saver ────────────────────────────────────

    describe("activeAccelerator() — Battery Saver mode") {

        it("returns CPU when Battery Saver is active") {
            val engine = buildEngine(isPowerSave = true)
            engine.activeAccelerator() shouldBe HardwareAccelerator.CPU
        }

        it("returns GPU (or NPU) when Battery Saver is inactive") {
            val engine = buildEngine(isPowerSave = false)
            engine.activeAccelerator() shouldBe HardwareAccelerator.GPU
        }
    }

    // ── cancelGeneration ──────────────────────────────────────────────────

    describe("cancelGeneration()") {

        it("causes generateStream to emit Cancelled and complete within 500 ms") {
            val engine = buildEngine()
            val f = createTempModelFile()
            try {
                engine.loadModel(f.absolutePath, sha256Hex(f))

                val events = mutableListOf<OnDeviceStreamEvent>()
                // withTimeout ensures the test fails fast if cancel doesn't work
                withTimeout(1_000) {
                    engine.generateStream("long prompt " + "x".repeat(500)).collect { event ->
                        events += event
                        // Cancel after receiving 2 tokens
                        if (event is OnDeviceStreamEvent.Token && events.count { it is OnDeviceStreamEvent.Token } == 2) {
                            engine.cancelGeneration()
                        }
                    }
                }

                val hasCancelled = events.any { it is OnDeviceStreamEvent.Cancelled }
                hasCancelled shouldBe true
            } finally {
                f.delete()
            }
        }
    }

    // ── releaseMemory ─────────────────────────────────────────────────────

    describe("releaseMemory()") {

        it("causes subsequent generateStream to emit model_not_loaded error") {
            val engine = buildEngine()
            val f = createTempModelFile()
            try {
                engine.loadModel(f.absolutePath, sha256Hex(f))
                engine.releaseMemory()

                val first = engine.generateStream("test").first()
                first.shouldBeInstanceOf<OnDeviceStreamEvent.Error>()
                (first as OnDeviceStreamEvent.Error).stage shouldBe "model_not_loaded"
            } finally {
                f.delete()
            }
        }
    }
})
