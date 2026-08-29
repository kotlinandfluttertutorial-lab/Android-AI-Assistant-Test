/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai (test)
 * File       : OnDeviceEmbeddingModelTest.kt
 * Purpose    : Unit tests for MiniLmEmbeddingModel implementation.
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag

import com.aiassistant.core.common.ModelLoadEvent
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

class OnDeviceEmbeddingModelTest : DescribeSpec(
    {

    describe("MiniLmEmbeddingModel — initialize()") {
        it("returns Failed when model file does not exist") {
            val model = MiniLmEmbeddingModel()
            val result = model.initialize("/nonexistent/path/model.bin", "abc123")
            result.shouldBeInstanceOf<ModelLoadEvent.Failed>()
        }
        it("returns Ready on success") {
            val model = MiniLmEmbeddingModel()
            val f = File.createTempFile("embedding_test", ".bin")
            try {
                val result = model.initialize(f.absolutePath, "dummy")
                result shouldBe ModelLoadEvent.Ready
            } finally {
                f.delete()
            }
        }
    }

    describe("MiniLmEmbeddingModel — generateEmbedding()") {
        it("produces embedding of correct dimension") {
            val model = MiniLmEmbeddingModel()
            model.generateEmbedding("Hello world").size shouldBe model.embeddingDimension
        }
        it("is deterministic") {
            val model = MiniLmEmbeddingModel()
            val text = "The quick brown fox"
            val result1 = model.generateEmbedding(text)
            val result2 = model.generateEmbedding(text)
            result1.contentEquals(result2) shouldBe true
        }
    }
})
