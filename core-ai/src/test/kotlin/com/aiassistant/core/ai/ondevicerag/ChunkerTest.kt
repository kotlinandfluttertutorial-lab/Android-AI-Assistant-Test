/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai (test)
 * File       : ChunkerTest.kt
 * Purpose    : Unit tests for the Chunker class.
 *              Validates the three spec invariants:
 *                1. Union of all chunk content == full source text (no gaps).
 *                2. overlapTokens > chunkSizeTokens/2 throws at construction.
 *                3. Min/max chunk size params are respected.
 *
 * Architecture Layer : Core-AI test — verifies the chunking stage of the
 *                      on-device RAG ingestion pipeline.
 *
 * Requirements: 33.4, 35.2
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

class ChunkerTest : DescribeSpec({

    describe("Chunker construction") {

        it("throws when overlapTokens > chunkSizeTokens / 2") {
            shouldThrow<IllegalArgumentException> {
                Chunker(chunkSizeTokens = 100, overlapTokens = 51)
            }
        }

        it("allows overlapTokens == chunkSizeTokens / 2") {
            // Should not throw
            Chunker(chunkSizeTokens = 100, overlapTokens = 50)
        }

        it("throws when minChunkSizeTokens is zero") {
            shouldThrow<IllegalArgumentException> {
                Chunker(minChunkSizeTokens = 0)
            }
        }
    }

    describe("Chunker.chunk()") {

        val chunker = Chunker(chunkSizeTokens = 10, overlapTokens = 2)

        it("returns empty list for blank text") {
            chunker.chunk("   ", "doc1", "test.txt").shouldBeEmpty()
        }

        it("returns single chunk when text fits within one chunk") {
            val text = "Hello world"
            val chunks = chunker.chunk(text, "doc1", "test.txt")
            chunks shouldHaveSize 1
            chunks[0].content shouldBe text
        }

        it("union of all chunk content covers full input text with no gaps") {
            // 200-char text — will produce multiple chunks with 10-token (40-char) windows
            val text = "A".repeat(200)
            val chunks = chunker.chunk(text, "doc1", "test.txt")

            // Reconstruct using offsets — every character must appear
            val covered = BooleanArray(text.length)
            for (chunk in chunks) {
                for (i in chunk.startCharOffset until chunk.endCharOffset) {
                    covered[i] = true
                }
            }
            covered.all { it } shouldBe true
        }

        it("chunk indices are sequential starting from 0") {
            val text = "B".repeat(300)
            val chunks = chunker.chunk(text, "doc1", "test.txt")
            chunks.mapIndexed { idx, c -> c.chunkIndex shouldBe idx }
        }

        it("each chunk has non-blank content") {
            val text = "Word ".repeat(50)
            val chunks = chunker.chunk(text.trim(), "doc1", "test.txt")
            chunks.forEach { it.content.shouldNotBeBlank() }
        }

        it("assigns documentId and documentName to every chunk") {
            val chunks = chunker.chunk("Some text here for testing.", "myDoc", "myFile.txt")
            chunks.forEach {
                it.documentId shouldBe "myDoc"
                it.documentName shouldBe "myFile.txt"
            }
        }

        it("respects maxChunkSizeTokens — no chunk exceeds limit") {
            val chunker2 = Chunker(chunkSizeTokens = 20, overlapTokens = 4, maxChunkSizeTokens = 20)
            val text = "Z".repeat(500)
            val maxChars = 20 * Chunker.CHARS_PER_TOKEN
            chunker2.chunk(text, "d", "f").forEach { chunk ->
                chunk.content.length shouldBeLessThanOrEqual maxChars
            }
        }

        it("assigns pageNumber from pageOffsets when provided") {
            val text = "Page one text. Page two text."
            val pageOffsets = listOf(
                PageOffset(pageNumber = 1, startCharOffset = 0, endCharOffset = 15),
                PageOffset(pageNumber = 2, startCharOffset = 15, endCharOffset = text.length),
            )
            val chunker2 = Chunker(chunkSizeTokens = 5, overlapTokens = 1)
            val chunks = chunker2.chunk(text, "doc", "doc.pdf", pageOffsets)
            // First chunk starts at 0 — must be page 1
            chunks.first().pageNumber shouldBe 1
        }

        it("pageNumber is null when no pageOffsets provided") {
            val chunks = chunker.chunk("Some text.", "doc", "doc.txt")
            chunks.forEach { it.pageNumber shouldBe null }
        }

        it("produces deterministic chunk IDs from documentId + index") {
            val text = "C".repeat(200)
            val chunks1 = chunker.chunk(text, "docX", "f.txt")
            val chunks2 = chunker.chunk(text, "docX", "f.txt")
            chunks1.map { it.id } shouldBe chunks2.map { it.id }
        }
    }
})
