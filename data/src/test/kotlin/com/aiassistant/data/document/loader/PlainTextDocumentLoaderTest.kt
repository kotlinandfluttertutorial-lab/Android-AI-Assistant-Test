/*
 * Unit tests for PlainTextDocumentLoader.
 *
 * No production credentials, no Android framework, no network.
 * All tests run on the JVM with kotlinx-coroutines-test.
 */
package com.aiassistant.data.document.loader

import com.aiassistant.domain.agent.DocumentLoadException
import com.aiassistant.domain.agent.UnsupportedDocumentFormatException
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PlainTextDocumentLoaderTest {

    private fun loader(maxBytes: Long = 10L * 1024 * 1024) = PlainTextDocumentLoader(maxBytes)

    // ── supports() ───────────────────────────────────────────────────────────

    @Test fun `supports returns true for text-plain mime`() {
        loader().supports("text/plain", "f.bin").shouldBeTrue()
    }

    @Test fun `supports returns true for text-markdown mime`() {
        loader().supports("text/markdown", "f.bin").shouldBeTrue()
    }

    @Test fun `supports returns true for txt extension when mime blank`() {
        loader().supports("", "notes.txt").shouldBeTrue()
    }

    @Test fun `supports returns true for md extension when mime blank`() {
        loader().supports("", "readme.md").shouldBeTrue()
    }

    @Test fun `supports returns true for uppercase TXT extension`() {
        loader().supports("", "FILE.TXT").shouldBeTrue()
    }

    @Test fun `supports returns true for uppercase MD extension`() {
        loader().supports("", "DOC.MD").shouldBeTrue()
    }

    @Test fun `supports returns false for pdf`() {
        loader().supports("application/pdf", "doc.pdf").shouldBeFalse()
    }

    @Test fun `supports returns false for docx`() {
        loader().supports(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "doc.docx",
        ).shouldBeFalse()
    }

    @Test fun `supports strips mime charset parameter`() {
        loader().supports("text/plain; charset=utf-8", "f.bin").shouldBeTrue()
    }

    @Test fun `maxFileSizeBytes is propagated`() {
        loader(maxBytes = 42L).maxFileSizeBytes shouldBe 42L
    }

    // ── load() — validation ───────────────────────────────────────────────────

    @Test fun `load throws IllegalArgumentException for empty bytes`() = runTest {
        assertThrows<IllegalArgumentException> {
            loader().load(ByteArray(0), "f.txt", "doc-1")
        }
    }

    @Test fun `load throws IllegalArgumentException when file exceeds size limit`() = runTest {
        val tinyLoader = loader(maxBytes = 3L)
        assertThrows<IllegalArgumentException> {
            tinyLoader.load("hello".toByteArray(), "f.txt", "doc-2")
        }
    }

    @Test fun `load throws UnsupportedDocumentFormatException for xlsx`() = runTest {
        assertThrows<UnsupportedDocumentFormatException> {
            loader().load("data".toByteArray(), "f.xlsx", "doc-3", "application/vnd.ms-excel")
        }
    }

    @Test fun `load throws UnsupportedDocumentFormatException for pdf`() = runTest {
        assertThrows<UnsupportedDocumentFormatException> {
            loader().load("%PDF-1.4".toByteArray(), "report.pdf", "doc-4", "application/pdf")
        }
    }

    // ── load() — happy path ───────────────────────────────────────────────────

    @Test fun `load returns LoadedDocument with correct documentId`() = runTest {
        val doc = loader().load("Hello".toByteArray(), "f.txt", "my-id")
        doc.documentId shouldBe "my-id"
    }

    @Test fun `load returns LoadedDocument with filename`() = runTest {
        val doc = loader().load("Hello".toByteArray(), "notes.txt", "doc-5")
        doc.filename shouldBe "notes.txt"
    }

    @Test fun `load page count is always 1`() = runTest {
        val doc = loader().load("Hello world".toByteArray(), "f.txt", "doc-6")
        doc.pageCount shouldBe 1
    }

    @Test fun `load sets mimeType from explicit argument`() = runTest {
        val doc = loader().load("text".toByteArray(), "f.txt", "doc-7", "text/plain")
        doc.mimeType shouldBe "text/plain"
    }

    @Test fun `load infers text-markdown mimeType from md extension`() = runTest {
        val doc = loader().load("# Title".toByteArray(), "readme.md", "doc-8")
        doc.mimeType shouldContain "markdown"
    }

    @Test fun `load infers text-plain mimeType from txt extension`() = runTest {
        val doc = loader().load("text".toByteArray(), "notes.txt", "doc-9")
        doc.mimeType shouldContain "plain"
    }

    @Test fun `load text equals decoded content`() = runTest {
        val doc = loader().load("Hello world".toByteArray(Charsets.UTF_8), "f.txt", "doc-10")
        doc.text shouldContain "Hello world"
    }

    @Test fun `load isEmpty is false for non-blank content`() = runTest {
        val doc = loader().load("Hello".toByteArray(), "f.txt", "doc-11")
        doc.isEmpty.shouldBeFalse()
    }

    // ── load() — checksum ─────────────────────────────────────────────────────

    @Test fun `load metadata contains checksum`() = runTest {
        val doc = loader().load("content".toByteArray(), "f.txt", "doc-cs")
        (doc.extractionMetadata["checksum"] != null).shouldBeTrue()
    }

    @Test fun `load checksum matches sha256 of raw bytes`() = runTest {
        val raw = "checksum test".toByteArray(Charsets.UTF_8)
        val doc = loader().load(raw, "f.txt", "doc-cs2")
        val expected = sha256Hex(raw)
        doc.extractionMetadata["checksum"] shouldBe expected
    }

    @Test fun `same content produces same checksum`() = runTest {
        val raw = "deterministic".toByteArray()
        val doc1 = loader().load(raw, "a.txt", "d1")
        val doc2 = loader().load(raw, "b.txt", "d2")
        doc1.extractionMetadata["checksum"] shouldBe doc2.extractionMetadata["checksum"]
    }

    @Test fun `different content produces different checksum`() = runTest {
        val doc1 = loader().load("aaa".toByteArray(), "f.txt", "d1")
        val doc2 = loader().load("bbb".toByteArray(), "f.txt", "d2")
        doc1.extractionMetadata["checksum"] shouldNotBe doc2.extractionMetadata["checksum"]
    }

    // ── load() — encoding ─────────────────────────────────────────────────────

    @Test fun `load decodes UTF-8 correctly`() = runTest {
        val text = "Héllo wörld"
        val doc = loader().load(text.toByteArray(Charsets.UTF_8), "f.txt", "doc-utf8")
        doc.text shouldContain "Héllo"
    }

    @Test fun `load decodes UTF-8-BOM correctly`() = runTest {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val raw = bom + "BOM text".toByteArray(Charsets.UTF_8)
        val doc = loader().load(raw, "f.txt", "doc-bom")
        doc.text shouldContain "BOM text"
    }

    @Test fun `load decodes Latin-1 as fallback`() = runTest {
        // 0xE9 = 'é' in Latin-1 but not valid standalone UTF-8
        val latin1Bytes = byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte()) // "café"
        val doc = loader().load(latin1Bytes, "f.txt", "doc-latin1")
        doc.text shouldContain "caf"
    }

    @Test fun `load encoding recorded in metadata`() = runTest {
        val doc = loader().load("hello".toByteArray(Charsets.UTF_8), "f.txt", "doc-enc")
        (doc.extractionMetadata["encoding"] != null).shouldBeTrue()
    }

    @Test fun `load line count recorded in metadata`() = runTest {
        val text = "line1\nline2\nline3\n"
        val doc = loader().load(text.toByteArray(), "f.txt", "doc-lines")
        val lineCount = doc.extractionMetadata["lineCount"]?.toIntOrNull() ?: 0
        (lineCount >= 3).shouldBeTrue()
    }

    // ── cleanText() ───────────────────────────────────────────────────────────

    @Test fun `cleanText removes CRLF line endings`() {
        val result = loader().cleanText("line1\r\nline2")
        result shouldNotContain "\r"
    }

    @Test fun `cleanText removes lone CR`() {
        val result = loader().cleanText("line1\rline2")
        result shouldNotContain "\r"
    }

    @Test fun `cleanText collapses three or more blank lines to two`() {
        val result = loader().cleanText("a\n\n\n\n\nb")
        // After collapsing: at most one blank line (two consecutive newlines) between paragraphs
        result shouldNotContain "\n\n\n"
    }

    @Test fun `cleanText trims trailing spaces per line`() {
        val result = loader().cleanText("hello   \nworld   ")
        result.lines().forEach { line ->
            line.endsWith(" ").shouldBeFalse()
        }
    }

    @Test fun `cleanText strips overall leading and trailing whitespace`() {
        val result = loader().cleanText("  \n  hello  \n  ")
        result.first().isWhitespace().shouldBeFalse()
        result.last().isWhitespace().shouldBeFalse()
    }

    @Test fun `cleanText returns empty string unchanged`() {
        loader().cleanText("") shouldBe ""
    }

    @Test fun `cleanText preserves meaningful content`() {
        val text = "# Title\n\nParagraph one.\n\nParagraph two."
        val result = loader().cleanText(text)
        result shouldContain "Title"
        result shouldContain "Paragraph one"
        result shouldContain "Paragraph two"
    }

    // ── UnsupportedDocumentFormatException properties ─────────────────────────

    @Test fun `UnsupportedDocumentFormatException exposes mimeType`() = runTest {
        val ex = runCatching {
            loader().load("x".toByteArray(), "f.xlsx", "doc-err", "application/vnd.ms-excel")
        }.exceptionOrNull()
        (ex is UnsupportedDocumentFormatException).shouldBeTrue()
        (ex as UnsupportedDocumentFormatException).mimeType shouldBe "application/vnd.ms-excel"
    }

    @Test fun `UnsupportedDocumentFormatException exposes filename`() = runTest {
        val ex = runCatching {
            loader().load("x".toByteArray(), "bad.xlsx", "doc-err2", "")
        }.exceptionOrNull()
        (ex is UnsupportedDocumentFormatException).shouldBeTrue()
        (ex as UnsupportedDocumentFormatException).filename shouldBe "bad.xlsx"
    }
}
