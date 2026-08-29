/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai (test)
 * File       : OnDeviceEmbeddingModelTest.kt
 * Purpose    : Unit tests for MiniLmEmbeddingModel.
 *              Validates the three spec invariants:
 *                1. Checksum mismatch → ModelLoadEvent.Failed.
 *                2. Identical input → identical FloatArray output (determinism).
 *                3. Input > 512 tokens is truncated (same result as truncated input).
 *
 * Architecture Layer : Core-AI test — verifies embedding model lifecycle.
 *
 * Requirements: 34.5, 34.6, 34.7
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class OnDeviceEmbeddingModelTest : DescribeSpec({

    describe("MiniLmEmbeddingModel — initialize()") {

        it("returns Failed when model file does not exist") {
            val model = MiniLmEmbeddingModel()
            val result = model.initialize("/nonexistent/path/model.bin", "abc123")
            result.shouldBeInstanceOf<ModelLoadEvent.Failed>()
            model.isReady shouldBe false
        }

        it("returns Failed on SHA-256 checksum mismatch") {
            // Create a real temp file with known content
            val tmpFile = createTempFile()
            tmpFile.writeText("dummy model content")
            try {
                val model = MiniLmEmbeddingModel()
                val result = model.initialize(tmpFile.absolutePath, "0000000000000000000000000000000000000000000000000000000000000000")
                result.shouldBeInstanceOf<ModelLoadEvent.Failed>()
                model.isReady shouldBe false
            } finally {
                tmpFile.delete()
            }
        }

        it("returns Ready when checksum matches") {
            val tmpFile = createTempFile()
            tmpFile.writeText("valid model content")
            try {
                val expectedChecksum = computeSha256Hex(tmpFile)
                val model = MiniLmEmbeddingModel()
                val result = model.initialize(tmpFile.absolutePath, expectedChecksum)
                result shouldBe ModelLoadEvent.Ready
                model.isReady shouldBe true
            } finally {
                tmpFile.delete()
            }
        }
    }

    describe("MiniLmEmbeddingModel — generateEmbedding()") {

        it("throws IllegalStateException when called before initialize()") {
            val model = MiniLmEmbeddingModel()
            shouldThrow<IllegalStateException> {
                model.generateEmbedding("test")
            }
        }

        it("produces embedding of dimension MINI_LM_EMBEDDING_DIM (384)") {
            val model = readyModel()
            model.generateEmbedding("Hello world").size shouldBe MINI_LM_EMBEDDING_DIM
        }

        it("is deterministic — same input produces identical FloatArray twice") {
            val model = readyModel()
            val text = "The quick brown fox jumps over the lazy dog"
            val result1 = model.generateEmbedding(text)
            val result2 = model.generateEmbedding(text)
            result1.contentEquals(result2) shouldBe true
        }

        it("different inputs produce different embeddings") {
            val model = readyModel()
            val e1 = model.generateEmbedding("Apple")
            val e2 = model.generateEmbedding("Banana")
            e1.contentEquals(e2) shouldBe false
        }

        it("truncates input exceeding 512 tokens and produces same result as pre-truncated text") {
            val model = readyModel()
            val maxChars = 512 * Chunker.CHARS_PER_TOKEN
            val longText = "X".repeat(maxChars + 100)
            val truncatedText = longText.substring(0, maxChars)

            val embLong = model.generateEmbedding(longText)
            val embTrunc = model.generateEmbedding(truncatedText)
            embLong.contentEquals(embTrunc) shouldBe true
        }

        it("output is L2-normalised (magnitude ≈ 1.0)") {
            val model = readyModel()
            val emb = model.generateEmbedding("normalisation test")
            val magnitude = Math.sqrt(emb.fold(0.0) { acc, v -> acc + v * v })
            // Allow small floating point error
            (magnitude > 0.999 && magnitude < 1.001) shouldBe true
        }
    }
})

// ── Test helpers ──────────────────────────────────────────────────────────────

private fun createTempFile(): File = File.createTempFile("model_test", ".bin")

private suspend fun readyModel(): MiniLmEmbeddingModel {
    val tmpFile = createTempFile()
    tmpFile.writeText("stub model content for testing")
    val checksum = computeSha256Hex(tmpFile)
    val model = MiniLmEmbeddingModel()
    model.initialize(tmpFile.absolutePath, checksum)
    // Note: temp file intentionally NOT deleted — model may be accessed after this returns.
    // In a real test suite use a @TempDir; here we accept the small leak for simplicity.
    return model
}

private fun computeSha256Hex(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(8192)
        var read: Int
        while (input.read(buf).also { read = it } != -1) digest.update(buf, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
