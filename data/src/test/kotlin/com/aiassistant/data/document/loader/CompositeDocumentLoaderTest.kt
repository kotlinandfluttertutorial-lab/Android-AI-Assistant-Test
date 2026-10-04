/*
 * Unit tests for CompositeDocumentLoader.
 *
 * No production credentials, no Android framework, no network.
 */
package com.aiassistant.data.document.loader

import com.aiassistant.domain.agent.UnsupportedDocumentFormatException
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CompositeDocumentLoaderTest {

    private fun composite(maxBytes: Long = 10L * 1024 * 1024): CompositeDocumentLoader =
        CompositeDocumentLoader(textLoader = PlainTextDocumentLoader(maxBytes))

    // ── supports() ───────────────────────────────────────────────────────────

    @Test fun `supports TXT by mime`() =
        composite().supports("text/plain", "f.bin").shouldBeTrue()

    @Test fun `supports MD by extension`() =
        composite().supports("", "readme.md").shouldBeTrue()

    @Test fun `does not support PDF`() =
        composite().supports("application/pdf", "doc.pdf").shouldBeFalse()

    @Test fun `does not support DOCX`() =
        composite().supports(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "doc.docx",
        ).shouldBeFalse()

    @Test fun `does not support XLSX`() =
        composite().supports("application/vnd.ms-excel", "data.xlsx").shouldBeFalse()

    // ── maxFileSizeBytes ──────────────────────────────────────────────────────

    @Test fun `maxFileSizeBytes equals text loader limit`() {
        composite(maxBytes = 5_000L).maxFileSizeBytes shouldBe 5_000L
    }

    // ── load() — delegates to PlainTextDocumentLoader ─────────────────────────

    @Test fun `load delegates TXT to PlainTextDocumentLoader`() = runTest {
        val doc = composite().load("Hello world".toByteArray(), "f.txt", "doc-c1")
        doc.text shouldContain "Hello world"
        doc.documentId shouldBe "doc-c1"
    }

    @Test fun `load delegates Markdown to PlainTextDocumentLoader`() = runTest {
        val doc = composite().load("# Heading\n\nParagraph".toByteArray(), "notes.md", "doc-c2")
        doc.text shouldContain "Heading"
    }

    @Test fun `load checksum is present`() = runTest {
        val raw = "checksum test".toByteArray()
        val doc = composite().load(raw, "f.txt", "doc-cs")
        doc.extractionMetadata["checksum"] shouldBe sha256Hex(raw)
    }

    // ── load() — validation errors propagated ────────────────────────────────

    @Test fun `load throws for empty bytes`() = runTest {
        assertThrows<IllegalArgumentException> {
            composite().load(ByteArray(0), "f.txt", "doc-c3")
        }
    }

    @Test fun `load throws for oversized file`() = runTest {
        val tiny = composite(maxBytes = 2L)
        assertThrows<IllegalArgumentException> {
            tiny.load("hello".toByteArray(), "f.txt", "doc-c4")
        }
    }

    @Test fun `load throws UnsupportedDocumentFormatException for PDF`() = runTest {
        assertThrows<UnsupportedDocumentFormatException> {
            composite().load("%PDF-1.4".toByteArray(), "report.pdf", "doc-c5", "application/pdf")
        }
    }

    @Test fun `load throws UnsupportedDocumentFormatException for DOCX`() = runTest {
        assertThrows<UnsupportedDocumentFormatException> {
            composite().load("PK\u0003\u0004".toByteArray(), "doc.docx", "doc-c6",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        }
    }

    @Test fun `load throws UnsupportedDocumentFormatException for unknown extension`() = runTest {
        assertThrows<UnsupportedDocumentFormatException> {
            composite().load("data".toByteArray(), "file.xyz", "doc-c7", "")
        }
    }

    // ── implements DocumentLoader interface ───────────────────────────────────

    @Test fun `CompositeDocumentLoader satisfies DocumentLoader interface`() {
        val loader: com.aiassistant.domain.agent.DocumentLoader = composite()
        loader shouldBe loader  // compile-time check
    }
}
