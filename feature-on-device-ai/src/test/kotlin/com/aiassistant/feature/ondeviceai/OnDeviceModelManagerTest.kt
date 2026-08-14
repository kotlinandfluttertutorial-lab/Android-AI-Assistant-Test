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
 * Pattern Used       : JUnit 4 with MockK
 *
 * Requirements: 31.2, 31.6, 31.7
 * ============================================================
 */

package com.aiassistant.feature.ondeviceai

import android.content.Context
import android.content.res.AssetManager
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OnDeviceModelManagerTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var assetManager: AssetManager
    private lateinit var manager: OnDeviceModelManager

    // A small dummy model entry for tests
    private val testEntry = ModelEntry(
        id = "test-model",
        displayName = "Test Model",
        fileName = "test-model.gguf",
        downloadUrl = "https://example.com/test-model.gguf",
        sha256 = "placeholder", // overridden per test
        sizeBytes = 1024,
        quantization = "INT4"
    )

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        assetManager = mockk(relaxed = true)
        every { context.assets } returns assetManager
        every { context.filesDir } returns tmpFolder.root

        // Provide a real models sub-dir so File resolution works
        File(tmpFolder.root, "models").mkdirs()

        manager = OnDeviceModelManager(context)
    }

    // ─── checkModelState ─────────────────────────────────────────────────────

    @Test
    fun `checkModelState returns Absent when file does not exist`() = runTest {
        val state = manager.checkModelState(testEntry)
        assertTrue(
            "Expected ModelFileState.Absent but got $state",
            state is ModelFileState.Absent
        )
    }

    @Test
    fun `checkModelState returns Ready when file exists and checksum matches`() = runTest {
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

    @Test
    fun `checkModelState returns Corrupt when file exists but checksum mismatches`() = runTest {
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

    // ─── verifyChecksum ──────────────────────────────────────────────────────

    @Test
    fun `verifyChecksum returns true for matching checksum`() {
        val content = "model bytes".toByteArray()
        val hash = sha256Hex(content)
        val file = tmpFolder.newFile("model.bin")
        file.writeBytes(content)

        assertTrue(manager.verifyChecksum(file, hash))
    }

    @Test
    fun `verifyChecksum returns false for mismatched checksum`() {
        val file = tmpFolder.newFile("model.bin")
        file.writeText("some content")

        assertFalse(manager.verifyChecksum(file, "0".repeat(64)))
    }

    @Test
    fun `verifyChecksum returns false when file does not exist`() {
        val nonExistent = File(tmpFolder.root, "does_not_exist.bin")
        assertFalse(manager.verifyChecksum(nonExistent, "0".repeat(64)))
    }

    @Test
    fun `verifyChecksum is case-insensitive for hex string`() {
        val content = "hello world".toByteArray()
        val hashUpper = sha256Hex(content).uppercase()
        val file = tmpFolder.newFile("model2.bin")
        file.writeBytes(content)

        assertTrue(manager.verifyChecksum(file, hashUpper))
    }

    // ─── checksumOfStream ────────────────────────────────────────────────────

    @Test
    fun `checksumOfStream returns correct SHA-256 hex`() {
        val content = "stream content".toByteArray()
        val expected = sha256Hex(content)
        val stream = ByteArrayInputStream(content)

        assertEquals(expected, manager.checksumOfStream(stream))
    }

    // ─── loadManifest ────────────────────────────────────────────────────────

    @Test
    fun `loadManifest parses valid JSON`() = runTest {
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

    // ─── modelFile resolution ────────────────────────────────────────────────

    @Test
    fun `modelFile returns path inside filesDir models directory`() {
        val file = manager.modelFile(testEntry)
        assertTrue(
            "Model file should be under filesDir/models",
            file.absolutePath.contains("models")
        )
        assertEquals("test-model.gguf", file.name)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
