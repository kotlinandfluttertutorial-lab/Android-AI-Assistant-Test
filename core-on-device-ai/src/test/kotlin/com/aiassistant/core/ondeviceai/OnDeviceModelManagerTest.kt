/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-ai
 * File       : OnDeviceModelManagerTest.kt
 * Purpose    : Unit tests for OnDeviceModelManager — covers checksum verification,
 *              absent / corrupt / ready states, and manifest loading.
 *
 * Architecture Layer : Feature (feature-on-device-ai) — tests
 * Pattern Used       : Kotest DescribeSpec + JUnit 5 runner + MockK
 *
 * Requirements: 31.2, 31.6, 31.7
 * ============================================================
 */

package com.aiassistant.feature.ondeviceai

import android.content.Context
import android.content.res.AssetManager
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Unit tests for [OnDeviceModelManager].
 *
 * Tests cover:
 * - [ModelFileState.Absent] when the model file does not exist.
 * - [ModelFileState.Ready] when the file exists and the SHA-256 matches.
 * - [ModelFileState.Corrupt] when the file exists but the hash mismatches (file deleted).
 * - SHA-256 computation helpers: [OnDeviceModelManager.verifyChecksum] and
 *   [OnDeviceModelManager.checksumOfStream].
 * - Manifest JSON parsing via [OnDeviceModelManager.loadManifest].
 * - [OnDeviceModelManager.modelFile] path resolution.
 */
class OnDeviceModelManagerTest : DescribeSpec({

    // ─── Setup ────────────────────────────────────────────────────────────────

    val tmpDir = Files.createTempDirectory("ondevice-test").toFile()

    afterSpec {
        tmpDir.deleteRecursively()
    }

    val context = mockk<Context>(relaxed = true)
    val assetManager = mockk<AssetManager>(relaxed = true)

    every { context.assets } returns assetManager
    every { context.filesDir } returns tmpDir

    // Provide a real models sub-dir so File resolution works
    File(tmpDir, "models").mkdirs()

    val manager = OnDeviceModelManager(context)

    // A small dummy model entry for tests
    val testEntry = ModelEntry(
        id = "test-model",
        displayName = "Test Model",
        fileName = "test-model.gguf",
        downloadUrl = "https://example.com/test-model.gguf",
        sha256 = "placeholder", // overridden per test
        sizeBytes = 1024,
        quantization = "INT4",
    )

    // ─── checkModelState ─────────────────────────────────────────────────────

    describe("checkModelState") {

        it("returns Absent when file does not exist") {
            runTest {
                val state = manager.checkModelState(testEntry)
                state.shouldBeInstanceOf<ModelFileState.Absent>()
            }
        }

        it("returns Ready when file exists and checksum matches") {
            val content = "fake gguf content".toByteArray()
            val correctHash = sha256Hex(content)
            val entry = testEntry.copy(sha256 = correctHash)

            val file = manager.modelFile(entry)
            file.writeBytes(content)

            runTest {
                val state = manager.checkModelState(entry)
                state.shouldBeInstanceOf<ModelFileState.Ready>()
                (state as ModelFileState.Ready).file.absolutePath shouldBe file.absolutePath
            }

            // Cleanup for subsequent tests
            file.delete()
        }

        it("returns Corrupt when file exists but checksum mismatches and deletes the file") {
            val content = "fake gguf content".toByteArray()
            val entry = testEntry.copy(sha256 = "0".repeat(64)) // wrong hash

            val file = manager.modelFile(entry)
            file.writeBytes(content)

            runTest {
                val state = manager.checkModelState(entry)
                state.shouldBeInstanceOf<ModelFileState.Corrupt>()
                // Corrupt file must be deleted by the manager
                file.exists().shouldBeFalse()
            }
        }
    }

    // ─── verifyChecksum ──────────────────────────────────────────────────────

    describe("verifyChecksum") {

        it("returns true for matching checksum") {
            val content = "model bytes".toByteArray()
            val hash = sha256Hex(content)
            val file = File(tmpDir, "verify-test.bin")
            file.writeBytes(content)

            manager.verifyChecksum(file, hash).shouldBeTrue()
            file.delete()
        }

        it("returns false for mismatched checksum") {
            val file = File(tmpDir, "mismatch.bin")
            file.writeText("some content")

            manager.verifyChecksum(file, "0".repeat(64)).shouldBeFalse()
            file.delete()
        }

        it("returns false when file does not exist") {
            val nonExistent = File(tmpDir, "does_not_exist.bin")
            manager.verifyChecksum(nonExistent, "0".repeat(64)).shouldBeFalse()
        }

        it("is case-insensitive for the expected hex string") {
            val content = "hello world".toByteArray()
            val hashUpper = sha256Hex(content).uppercase()
            val file = File(tmpDir, "case-insensitive.bin")
            file.writeBytes(content)

            manager.verifyChecksum(file, hashUpper).shouldBeTrue()
            file.delete()
        }
    }

    // ─── checksumOfStream ────────────────────────────────────────────────────

    describe("checksumOfStream") {

        it("returns correct SHA-256 hex for a given stream") {
            val content = "stream content".toByteArray()
            val expected = sha256Hex(content)
            val stream = ByteArrayInputStream(content)

            manager.checksumOfStream(stream) shouldBe expected
        }

        it("handles empty stream returning known SHA-256 of empty bytes") {
            val expected = sha256Hex(ByteArray(0))
            val stream = ByteArrayInputStream(ByteArray(0))
            manager.checksumOfStream(stream) shouldBe expected
        }
    }

    // ─── loadManifest ────────────────────────────────────────────────────────

    describe("loadManifest") {

        it("parses valid JSON manifest with one model entry") {
            val manifestJson = """
                {
                  "models": [
                    {
                      "id": "llama3-8b-int4",
                      "displayName": "Llama 3 8B INT4",
                      "fileName": "llama3.gguf",
                      "downloadUrl": "https://example.com/llama3.gguf",
                      "sha256": "abc123",
                      "sizeBytes": 4919998976,
                      "quantization": "INT4"
                    }
                  ]
                }
            """.trimIndent()

            every { assetManager.open("model_manifest.json") } returns
                ByteArrayInputStream(manifestJson.toByteArray())

            runTest {
                val manifest = manager.loadManifest()
                manifest.models.size shouldBe 1
                val first = manifest.models.first()
                first.id shouldBe "llama3-8b-int4"
                first.quantization shouldBe "INT4"
                first.sizeBytes shouldBe 4919998976L
                first.downloadUrl shouldContain "llama3.gguf"
            }
        }

        it("parses manifest with multiple model entries") {
            val manifestJson = """
                {
                  "models": [
                    {
                      "id": "model-a",
                      "displayName": "Model A",
                      "fileName": "model-a.gguf",
                      "downloadUrl": "https://example.com/model-a.gguf",
                      "sha256": "aaa",
                      "sizeBytes": 1000,
                      "quantization": "INT4"
                    },
                    {
                      "id": "model-b",
                      "displayName": "Model B",
                      "fileName": "model-b.gguf",
                      "downloadUrl": "https://example.com/model-b.gguf",
                      "sha256": "bbb",
                      "sizeBytes": 2000,
                      "quantization": "INT8"
                    }
                  ]
                }
            """.trimIndent()

            every { assetManager.open("model_manifest.json") } returns
                ByteArrayInputStream(manifestJson.toByteArray())

            runTest {
                val manifest = manager.loadManifest()
                manifest.models.size shouldBe 2
                manifest.models[0].id shouldBe "model-a"
                manifest.models[1].id shouldBe "model-b"
                manifest.models[1].quantization shouldBe "INT8"
            }
        }
    }

    // ─── modelFile resolution ────────────────────────────────────────────────

    describe("modelFile") {

        it("returns a path inside the models subdirectory of filesDir") {
            val file = manager.modelFile(testEntry)
            file.absolutePath shouldContain "models"
            file.name shouldBe "test-model.gguf"
        }

        it("creates the models directory when it does not exist") {
            val cleanDir = Files.createTempDirectory("ondevice-clean").toFile()
            val cleanContext = mockk<Context>(relaxed = true)
            every { cleanContext.assets } returns assetManager
            every { cleanContext.filesDir } returns cleanDir

            val cleanManager = OnDeviceModelManager(cleanContext)
            val file = cleanManager.modelFile(testEntry)

            file.parentFile?.exists()?.shouldBeTrue()
            cleanDir.deleteRecursively()
        }
    }
})

// ─── Test helpers ─────────────────────────────────────────────────────────────

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(bytes).joinToString("") { "%02x".format(it) }
}
