/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai (test)
 * File       : ChunkerTest.kt
 * Purpose    : Unit tests for the Chunker class.
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag

import com.aiassistant.core.common.Chunker
import com.aiassistant.core.common.PageOffset
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
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
            Chunker(chunkSizeTokens = 100, overlapTokens = 50)
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
            val text = "A".repeat(200)
            val chunks = chunker.chunk(text, "doc1", "test.txt")

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
            chunks.forEachIndexed { idx, c -> c.chunkIndex shouldBe idx }
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

        it("respects maxChunkSizeTokens") {
            val chunker2 = Chunker(chunkSizeTokens = 20, overlapTokens = 4, maxChunkSizeTokens = 20)
            val text = "Z".repeat(500)
            val maxChars = 20 * Chunker.CHARS_PER_TOKEN
            chunker2.chunk(text, "d", "f").forEach { chunk ->
                chunk.content.length shouldBeLessThanOrEqual maxChars
            }
        }

        it("assigns pageNumber from pageOffsets") {
            val text = "Page one text. Page two text."
            val pageOffsets = listOf(
                PageOffset(pageNumber = 1, startCharOffset = 0, endCharOffset = 15),
                PageOffset(pageNumber = 2, startCharOffset = 15, endCharOffset = text.length),
            )
            val chunker2 = Chunker(chunkSizeTokens = 5, overlapTokens = 1)
            val chunks = chunker2.chunk(text, "doc", "doc.pdf", pageOffsets)
            chunks.first().pageNumber shouldBe 1
        }

        it("pageNumber is null when no pageOffsets provided") {
            val chunks = chunker.chunk("Some text.", "doc", "doc.txt")
            chunks.forEach { it.pageNumber shouldBe null }
        }

        it("produces deterministic chunk IDs") {
            val text = "C".repeat(200)
            val chunks1 = chunker.chunk(text, "docX", "f.txt")
            val chunks2 = chunker.chunk(text, "docX", "f.txt")
            chunks1.map { it.id } shouldBe chunks2.map { it.id }
        }
    }
})
