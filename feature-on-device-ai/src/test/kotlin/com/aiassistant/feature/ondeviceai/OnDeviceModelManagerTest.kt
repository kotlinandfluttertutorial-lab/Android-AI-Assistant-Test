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
 * Pattern Used       : Kotest DescribeSpec + MockK
 *
 * Requirements: 31.2, 31.6, 31.7
 * ============================================================
 */

package com.aiassistant.feature.ondeviceai

import android.content.Context
import android.content.res.AssetManager
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class OnDeviceModelManagerTest :
    DescribeSpec({

        val tmpFolder = Files.createTempDirectory("model-manager-test").toFile()
        val context = mockk<Context>(relaxed = true)
        val assetManager = mockk<AssetManager>(relaxed = true)
        lateinit var manager: OnDeviceModelManager

        // A small dummy model entry for tests
        val testEntry = ModelEntry(
            id = "test-model",
            displayName = "Test Model",
            fileName = "test-model.gguf",
            downloadUrl = "https://example.com/test-model.gguf",
            sha256 = "placeholder", // overridden per test
            sizeBytes = 1024,
            quantization = "INT4"
        )

        beforeSpec {
            every { context.assets } returns assetManager
            every { context.filesDir } returns tmpFolder

            // Provide a real models sub-dir so File resolution works
            File(tmpFolder, "models").mkdirs()

            manager = OnDeviceModelManager(context)
        }

        afterSpec {
            unmockkAll()
            tmpFolder.deleteRecursively()
        }

        describe("checkModelState") {
            it("returns Absent when file does not exist") {
                runTest {
                    val state = manager.checkModelState(testEntry)
                    assertTrue(
                        "Expected ModelFileState.Absent but got $state",
                        state is ModelFileState.Absent
                    )
                }
            }

            it("returns Ready when file exists and checksum matches") {
                runTest {
                    val content = "fake gguf content".toByteArray()
                    val correctHash = sha256Hex(content)
                    val entry = testEntry.copy(sha256 = correctHash)

                    val file = manager.modelFile(entry)
                    file.writeBytes(content)

                    val state = manager.checkModelState(entry)
                    assertTrue(
                        "Expected ModelFileState.Ready but got $state",
                        state is ModelFileState.Ready
                    )
                    assertEquals(file.absolutePath, (state as ModelFileState.Ready).file.absolutePath)
                }
            }

            it("returns Corrupt when file exists but checksum mismatches") {
                runTest {
                    val content = "fake gguf content".toByteArray()
                    val entry = testEntry.copy(sha256 = "0".repeat(64)) // wrong hash

                    val file = manager.modelFile(entry)
                    file.writeBytes(content)

                    val state = manager.checkModelState(entry)
                    assertTrue(
                        "Expected ModelFileState.Corrupt but got $state",
                        state is ModelFileState.Corrupt
                    )
                    // Corrupt file must be deleted by the manager
                    assertFalse("Corrupt file should have been deleted", file.exists())
                }
            }
        }

        describe("verifyChecksum") {
            it("returns true for matching checksum") {
                val content = "model bytes".toByteArray()
                val hash = sha256Hex(content)
                val file = File(tmpFolder, "model.bin")
                file.writeBytes(content)

                assertTrue(manager.verifyChecksum(file, hash))
            }

            it("returns false for mismatched checksum") {
                val file = File(tmpFolder, "model_mismatch.bin")
                file.writeText("some content")

                assertFalse(manager.verifyChecksum(file, "0".repeat(64)))
            }

            it("returns false when file does not exist") {
                val nonExistent = File(tmpFolder, "does_not_exist.bin")
                assertFalse(manager.verifyChecksum(nonExistent, "0".repeat(64)))
            }

            it("is case-insensitive for hex string") {
                val content = "hello world".toByteArray()
                val hashUpper = sha256Hex(content).uppercase()
                val file = File(tmpFolder, "model2.bin")
                file.writeBytes(content)

                assertTrue(manager.verifyChecksum(file, hashUpper))
            }
        }

        describe("checksumOfStream") {
            it("returns correct SHA-256 hex") {
                val content = "stream content".toByteArray()
                val expected = sha256Hex(content)
                val stream = ByteArrayInputStream(content)

                assertEquals(expected, manager.checksumOfStream(stream))
            }
        }

        describe("loadManifest") {
            it("parses valid JSON") {
                runTest {
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

                    val manifest = manager.loadManifest()

                    assertEquals(1, manifest.models.size)
                    with(manifest.models.first()) {
                        assertEquals("llama3-8b-int4", id)
                        assertEquals("INT4", quantization)
                        assertEquals(4919998976L, sizeBytes)
                    }
                }
            }
        }

        describe("modelFile resolution") {
            it("returns path inside filesDir models directory") {
                val file = manager.modelFile(testEntry)
                assertTrue(
                    "Model file should be under filesDir/models",
                    file.absolutePath.contains("models")
                )
                assertEquals("test-model.gguf", file.name)
            }
        }
    })

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(bytes).joinToString("") { "%02x".format(it) }
}
