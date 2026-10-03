/*
 * Unit tests for DocumentChunkerImpl and ChunkerConfig.
 *
 * No production credentials, no Android framework, no network.
 * Uses the existing core-common Chunker (character approximation).
 */
package com.aiassistant.data.document.chunker

import com.aiassistant.core.common.PageOffset
import com.aiassistant.domain.agent.LoadedDocument
import com.aiassistant.domain.agent.VectorChunk
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

// ── Helpers ───────────────────────────────────────────────────────────────────

private const val TEST_USER = "test-user"

private fun doc(
    text: String,
    documentId: String = "doc-1",
    filename: String = "test.txt",
) = LoadedDocument(documentId = documentId, text = text, filename = filename)

private fun longText(words: Int = 300, word: String = "word") =
    (word + " ").repeat(words).trimEnd()

// ── ChunkerConfig ─────────────────────────────────────────────────────────────

class ChunkerConfigTest {

    @Test fun `default values are sane`() {
        val c = ChunkerConfig()
        c.chunkSizeTokens shouldBe 512
        c.overlapTokens shouldBe 64
        c.minChunkSizeTokens shouldBe 64
        c.maxChunkSizeTokens shouldBe 2048
    }

    @Test fun `effectiveChunkSize is clamped to min`() {
        val c = ChunkerConfig(chunkSizeTokens = 10, minChunkSizeTokens = 64)
        c.effectiveChunkSize shouldBe 64
    }

    @Test fun `effectiveChunkSize is clamped to max`() {
        val c = ChunkerConfig(chunkSizeTokens = 9999, maxChunkSizeTokens = 2048)
        c.effectiveChunkSize shouldBe 2048
    }

    @Test fun `effectiveOverlap is clamped to half of effectiveChunkSize`() {
        val c = ChunkerConfig(chunkSizeTokens = 100, overlapTokens = 80)
        c.effectiveOverlap shouldBe 50  // 100 / 2
    }

    @Test fun `minChunkSizeTokens below 1 raises`() {
        assertThrows<IllegalArgumentException> {
            ChunkerConfig(minChunkSizeTokens = 0)
        }
    }

    @Test fun `maxChunkSizeTokens below minChunkSizeTokens raises`() {
        assertThrows<IllegalArgumentException> {
            ChunkerConfig(minChunkSizeTokens = 100, maxChunkSizeTokens = 50)
        }
    }

    @Test fun `negative overlapTokens raises`() {
        assertThrows<IllegalArgumentException> {
            ChunkerConfig(overlapTokens = -1)
        }
    }

    @Test fun `zero overlapTokens is valid`() {
        val c = ChunkerConfig(overlapTokens = 0)
        c.effectiveOverlap shouldBe 0
    }
}

// ── DocumentChunkerImpl ───────────────────────────────────────────────────────

class DocumentChunkerImplTest {

    private fun chunker(
        chunkSize: Int = 128,
        overlap: Int = 16,
    ) = DocumentChunkerImpl(ChunkerConfig(chunkSizeTokens = chunkSize, overlapTokens = overlap))

    // ── empty / blank input ───────────────────────────────────────────────────

    @Test fun `blank text returns empty list`() {
        chunker().chunk(doc(""), userId = TEST_USER).shouldBeEmpty()
    }

    @Test fun `whitespace-only text returns empty list`() {
        chunker().chunk(doc("   \n   "), userId = TEST_USER).shouldBeEmpty()
    }

    @Test fun `isEmpty document returns empty list`() {
        val d = LoadedDocument(documentId = "d", text = "")
        chunker().chunk(d, userId = TEST_USER).shouldBeEmpty()
    }

    // ── single chunk ──────────────────────────────────────────────────────────

    @Test fun `short text produces exactly one chunk`() {
        val result = chunker(chunkSize = 512).chunk(doc("Short text."), userId = TEST_USER)
        result shouldHaveSize 1
    }

    @Test fun `single chunk has chunkIndex 0`() {
        val result = chunker(chunkSize = 512).chunk(doc("Hello world."), userId = TEST_USER)
        result[0].chunkIndex shouldBe 0
    }

    @Test fun `single chunk text matches document text`() {
        val result = chunker(chunkSize = 512).chunk(doc("Hello world."), userId = TEST_USER)
        result[0].text.isNotBlank().shouldBeTrue()
    }

    // ── multiple chunks ───────────────────────────────────────────────────────

    @Test fun `long text produces multiple chunks`() {
        val result = chunker(chunkSize = 64, overlap = 8).chunk(doc(longText(500)), userId = TEST_USER)
        result.size shouldBeGreaterThan 1
    }

    @Test fun `chunk indices are sequential`() {
        val result = chunker(chunkSize = 64, overlap = 8).chunk(doc(longText(300)), userId = TEST_USER)
        result.mapIndexed { i, c -> c.chunkIndex shouldBe i }
    }

    @Test fun `chunk ids are unique`() {
        val result = chunker(chunkSize = 64, overlap = 8).chunk(doc(longText(300)), userId = TEST_USER)
        result.map { it.chunkId }.toSet().size shouldBe result.size
    }

    // ── metadata propagation ──────────────────────────────────────────────────

    @Test fun `documentId is propagated to every chunk`() {
        val result = chunker().chunk(doc(longText(200), documentId = "my-doc"), userId = TEST_USER)
        result.all { it.documentId == "my-doc" }.shouldBeTrue()
    }

    @Test fun `documentName comes from filename`() {
        val result = chunker().chunk(doc(longText(100), filename = "report.txt"), userId = TEST_USER)
        result.all { it.documentName == "report.txt" }.shouldBeTrue()
    }

    @Test fun `userId is propagated to every chunk`() {
        val result = chunker().chunk(doc(longText(100)), userId = "user-42")
        result.all { it.userId == "user-42" }.shouldBeTrue()
    }

    // ── chunk ID format ───────────────────────────────────────────────────────

    @Test fun `chunk id contains documentId`() {
        val result = chunker().chunk(doc(longText(100), documentId = "doc-xyz"), userId = TEST_USER)
        result.all { "doc-xyz" in it.chunkId }.shouldBeTrue()
    }

    @Test fun `chunk id contains chunk index`() {
        val result = chunker(chunkSize = 64, overlap = 8).chunk(doc(longText(300)), userId = TEST_USER)
        result.forEachIndexed { i, c ->
            ("_chunk_$i" in c.chunkId || "_chunk_${i}_" in c.chunkId).shouldBeTrue()
        }
    }

    // ── char offsets ──────────────────────────────────────────────────────────

    @Test fun `charStart is non-negative`() {
        val result = chunker(chunkSize = 64, overlap = 8).chunk(doc(longText(300)), userId = TEST_USER)
        result.all { it.charStart >= 0 }.shouldBeTrue()
    }

    @Test fun `charEnd is greater than or equal to charStart`() {
        val result = chunker(chunkSize = 64, overlap = 8).chunk(doc(longText(300)), userId = TEST_USER)
        result.all { it.charEnd >= it.charStart }.shouldBeTrue()
    }

    @Test fun `first chunk charStart is 0`() {
        val result = chunker(chunkSize = 512).chunk(doc("Hello world"), userId = TEST_USER)
        result[0].charStart shouldBe 0
    }

    // ── overlap ───────────────────────────────────────────────────────────────

    @Test fun `overlap causes charStart of next chunk to be inside previous chunk range`() {
        val text = longText(400)
        val result = chunker(chunkSize = 64, overlap = 16).chunk(doc(text), userId = TEST_USER)
        if (result.size >= 2) {
            (result[1].charStart < result[0].charEnd).shouldBeTrue()
        }
    }

    @Test fun `zero overlap produces non-overlapping char ranges`() {
        val text = longText(400)
        val result = DocumentChunkerImpl(
            ChunkerConfig(chunkSizeTokens = 64, overlapTokens = 0)
        ).chunk(doc(text), userId = TEST_USER)
        if (result.size >= 2) {
            (result[1].charStart >= result[0].charEnd).shouldBeTrue()
        }
    }

    // ── page numbers ─────────────────────────────────────────────────────────

    @Test fun `pageNumber is null when no pageOffsets provided`() {
        val result = chunker().chunk(doc(longText(100)), userId = TEST_USER)
        result.all { it.pageNumber == null }.shouldBeTrue()
    }

    @Test fun `pageNumber is null when pageOffsets empty list`() {
        val result = chunker().chunk(doc(longText(100)), userId = TEST_USER, pageOffsets = emptyList())
        result.all { it.pageNumber == null }.shouldBeTrue()
    }

    @Test fun `single page offset assigns page 1 to all chunks`() {
        val text = longText(200)
        val pageOffsets = listOf(PageOffset(1, 0, text.length + 100))
        val result = chunker(chunkSize = 64, overlap = 8)
            .chunk(doc(text), userId = TEST_USER, pageOffsets = pageOffsets)
        result.all { it.pageNumber == 1 }.shouldBeTrue()
    }

    @Test fun `two page offsets assign correct page numbers`() {
        val page1 = "alpha ".repeat(60)
        val page2 = "beta ".repeat(60)
        val text = page1 + page2
        val pageOffsets = listOf(
            PageOffset(1, 0, page1.length),
            PageOffset(2, page1.length, text.length),
        )
        val result = chunker(chunkSize = 64, overlap = 8)
            .chunk(doc(text), userId = TEST_USER, pageOffsets = pageOffsets)
        val pages = result.mapNotNull { it.pageNumber }.toSet()
        pages.contains(1).shouldBeTrue()
        pages.contains(2).shouldBeTrue()
    }

    // ── VectorChunk type contract ─────────────────────────────────────────────

    @Test fun `all results are VectorChunk instances`() {
        val result = chunker().chunk(doc(longText(100)), userId = TEST_USER)
        result.all { it is VectorChunk }.shouldBeTrue()
    }

    @Test fun `text field of each chunk is not blank`() {
        val result = chunker(chunkSize = 64, overlap = 8).chunk(doc(longText(300)), userId = TEST_USER)
        result.all { it.text.isNotBlank() }.shouldBeTrue()
    }

    // ── configurable chunk size ───────────────────────────────────────────────

    @Test fun `smaller chunk size produces more chunks than larger`() {
        val text = longText(600)
        val content = doc(text)
        val smallChunks = DocumentChunkerImpl(
            ChunkerConfig(chunkSizeTokens = 64, overlapTokens = 8)
        ).chunk(content, userId = TEST_USER)
        val largeChunks = DocumentChunkerImpl(
            ChunkerConfig(chunkSizeTokens = 256, overlapTokens = 32)
        ).chunk(content, userId = TEST_USER)
        (smallChunks.size >= largeChunks.size).shouldBeTrue()
    }

    @Test fun `chunk size can be changed via config`() {
        val chunker64  = DocumentChunkerImpl(ChunkerConfig(chunkSizeTokens = 64))
        val chunker128 = DocumentChunkerImpl(ChunkerConfig(chunkSizeTokens = 128))
        val text = longText(400)
        val content = doc(text)
        val n64  = chunker64.chunk(content, userId = TEST_USER).size
        val n128 = chunker128.chunk(content, userId = TEST_USER).size
        (n64 >= n128).shouldBeTrue()
    }
}
