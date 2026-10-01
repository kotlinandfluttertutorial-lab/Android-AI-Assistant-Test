/*
 * Domain unit tests for DocumentLoader interface and its value objects /
 * error types: LoadedDocument, DocumentLoadException, UnsupportedDocumentFormatException.
 */
package com.aiassistant.domain.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

// ── Minimal stub ──────────────────────────────────────────────────────────────

private class FakeDocumentLoader : DocumentLoader {
    override val maxFileSizeBytes: Long = 10L * 1024 * 1024 // 10 MB

    override fun supports(mimeType: String, filename: String): Boolean =
        mimeType == "text/plain" || filename.endsWith(".txt") || filename.endsWith(".md")

    override suspend fun load(
        fileBytes: ByteArray,
        filename: String,
        documentId: String,
        mimeType: String,
    ): LoadedDocument {
        if (!supports(mimeType, filename)) throw UnsupportedDocumentFormatException(filename, mimeType)
        if (fileBytes.isEmpty()) throw DocumentLoadException("read", filename, "empty file")
        return LoadedDocument(
            documentId = documentId,
            text = fileBytes.decodeToString(),
            pageCount = 1,
            mimeType = mimeType.ifBlank { "text/plain" },
            filename = filename,
        )
    }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

class DocumentLoaderTest {

    private fun loader() = FakeDocumentLoader()

    // ── LoadedDocument ────────────────────────────────────────────────────────

    @Test fun `LoadedDocument valid construction`() {
        val doc = LoadedDocument(documentId = "d1", text = "hello")
        doc.documentId shouldBe "d1"
        doc.text shouldBe "hello"
        doc.pageCount shouldBe 0
    }

    @Test fun `LoadedDocument blank document id raises`() {
        shouldThrow<IllegalArgumentException> {
            LoadedDocument(documentId = "  ", text = "text")
        }
    }

    @Test fun `LoadedDocument isEmpty when text is blank`() {
        LoadedDocument(documentId = "d1", text = "   ").isEmpty.shouldBeTrue()
        LoadedDocument(documentId = "d1", text = "content").isEmpty.shouldBeFalse()
    }

    @Test fun `LoadedDocument stores all fields`() {
        val doc = LoadedDocument(
            documentId = "d1",
            text = "body",
            pageCount = 5,
            mimeType = "application/pdf",
            filename = "report.pdf",
            extractionMetadata = mapOf("author" to "Alice"),
        )
        doc.pageCount shouldBe 5
        doc.filename shouldBe "report.pdf"
        doc.extractionMetadata["author"] shouldBe "Alice"
    }

    // ── DocumentLoadException ─────────────────────────────────────────────────

    @Test fun `DocumentLoadException message contains stage and filename`() {
        val e = DocumentLoadException("ocr", "scan.pdf", "low quality")
        e.message!! shouldContain "ocr"
        e.message!! shouldContain "scan.pdf"
        e.message!! shouldContain "low quality"
        e.stage shouldBe "ocr"
        e.filename shouldBe "scan.pdf"
    }

    @Test fun `DocumentLoadException without detail only shows stage and filename`() {
        val e = DocumentLoadException("read", "file.txt")
        e.message!! shouldContain "read"
        e.message!! shouldContain "file.txt"
    }

    @Test fun `DocumentLoadException preserves cause`() {
        val cause = RuntimeException("disk error")
        val e = DocumentLoadException("read", "f.txt", cause = cause)
        e.cause shouldBe cause
    }

    // ── UnsupportedDocumentFormatException ────────────────────────────────────

    @Test fun `UnsupportedDocumentFormatException message contains filename`() {
        val e = UnsupportedDocumentFormatException("data.xlsx", "application/vnd.ms-excel")
        e.message!! shouldContain "data.xlsx"
        e.message!! shouldContain "Unsupported format"
    }

    @Test fun `UnsupportedDocumentFormatException is a DocumentLoadException`() {
        val e = UnsupportedDocumentFormatException("bad.bin")
        (e is DocumentLoadException).shouldBeTrue()
    }

    // ── Interface contract ────────────────────────────────────────────────────

    @Test fun `supports returns true for txt file`() {
        loader().supports("text/plain", "notes.txt").shouldBeTrue()
    }

    @Test fun `supports returns true for md extension`() {
        loader().supports("", "readme.md").shouldBeTrue()
    }

    @Test fun `supports returns false for pdf`() {
        loader().supports("application/pdf", "report.pdf").shouldBeFalse()
    }

    @Test fun `maxFileSizeBytes is positive`() {
        (loader().maxFileSizeBytes > 0).shouldBeTrue()
    }

    @Test fun `load extracts text from bytes`() = runTest {
        val bytes = "Hello world".toByteArray()
        val doc = loader().load(bytes, "file.txt", "doc-1", "text/plain")
        doc.documentId shouldBe "doc-1"
        doc.text shouldBe "Hello world"
        doc.filename shouldBe "file.txt"
    }

    @Test fun `load throws UnsupportedDocumentFormatException for unsupported format`() = runTest {
        shouldThrow<UnsupportedDocumentFormatException> {
            loader().load("data".toByteArray(), "data.xlsx", "doc-2", "application/vnd.ms-excel")
        }
    }

    @Test fun `load throws DocumentLoadException for empty bytes`() = runTest {
        shouldThrow<DocumentLoadException> {
            loader().load(ByteArray(0), "empty.txt", "doc-3", "text/plain")
        }
    }

    @Test fun `stub satisfies interface at compile time`() {
        val l: DocumentLoader = FakeDocumentLoader()
        l.maxFileSizeBytes shouldBe 10L * 1024 * 1024
    }
}
