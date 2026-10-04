/*
 * Unit tests for IngestDocumentUseCase.
 *
 * Uses a fake DocumentLoader — no production credentials, no network.
 * All paths of IngestResult are covered.
 */
package com.aiassistant.domain.usecase.document

import com.aiassistant.domain.agent.DocumentLoadException
import com.aiassistant.domain.agent.DocumentLoader
import com.aiassistant.domain.agent.LoadedDocument
import com.aiassistant.domain.agent.UnsupportedDocumentFormatException
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

// ── Fake DocumentLoader implementations ──────────────────────────────────────

private val SUPPORTED_MIMES = setOf("text/plain", "text/markdown")
private val SUPPORTED_EXTS  = setOf(".txt", ".md")

/** Loader that successfully returns a fixed document for supported formats. */
private class FakeDocumentLoader(
    override val maxFileSizeBytes: Long = 10L * 1024 * 1024,
) : DocumentLoader {

    override fun supports(mimeType: String, filename: String): Boolean {
        val norm = mimeType.substringBefore(";").trim().lowercase()
        val ext  = ".${filename.substringAfterLast('.', "").lowercase()}"
        return norm in SUPPORTED_MIMES || ext in SUPPORTED_EXTS
    }

    override suspend fun load(
        fileBytes: ByteArray,
        filename: String,
        documentId: String,
        mimeType: String,
    ): LoadedDocument {
        if (!supports(mimeType, filename)) throw UnsupportedDocumentFormatException(filename, mimeType)
        return LoadedDocument(
            documentId = documentId,
            text = fileBytes.decodeToString(),
            pageCount = 1,
            mimeType = mimeType.ifBlank { "text/plain" },
            filename = filename,
            extractionMetadata = mapOf("checksum" to "abc123"),
        )
    }
}

/** Loader that always throws DocumentLoadException on load(). */
private class BrokenDocumentLoader : DocumentLoader {
    override val maxFileSizeBytes: Long = 10L * 1024 * 1024
    override fun supports(mimeType: String, filename: String) = true
    override suspend fun load(
        fileBytes: ByteArray, filename: String, documentId: String, mimeType: String,
    ): LoadedDocument = throw DocumentLoadException("parse", filename, "Corrupt file content")
}

/** Loader that always throws an unexpected RuntimeException. */
private class CrashingDocumentLoader : DocumentLoader {
    override val maxFileSizeBytes: Long = 10L * 1024 * 1024
    override fun supports(mimeType: String, filename: String) = true
    override suspend fun load(
        fileBytes: ByteArray, filename: String, documentId: String, mimeType: String,
    ): LoadedDocument = throw RuntimeException("Unexpected crash")
}

// ── IngestDocumentUseCaseTest ─────────────────────────────────────────────────

class IngestDocumentUseCaseTest {

    private fun useCase(loader: DocumentLoader = FakeDocumentLoader()) =
        IngestDocumentUseCase(loader)

    // ── IngestResult.Success ──────────────────────────────────────────────────

    @Test fun `returns Success for valid TXT file`() = runTest {
        val result = useCase().execute(
            fileBytes = "Hello world".toByteArray(),
            filename  = "notes.txt",
            documentId = "doc-1",
            mimeType  = "text/plain",
        )
        result.shouldBeInstanceOf<IngestResult.Success>()
        (result as IngestResult.Success).document.documentId shouldBe "doc-1"
    }

    @Test fun `returns Success for Markdown file`() = runTest {
        val result = useCase().execute(
            fileBytes = "# Title".toByteArray(),
            filename  = "readme.md",
            documentId = "doc-md",
        )
        result.shouldBeInstanceOf<IngestResult.Success>()
    }

    @Test fun `Success document text contains file content`() = runTest {
        val result = useCase().execute(
            fileBytes = "important content".toByteArray(),
            filename  = "f.txt",
            documentId = "doc-txt",
        )
        (result as IngestResult.Success).document.text shouldBe "important content"
    }

    @Test fun `Success document carries documentId`() = runTest {
        val result = useCase().execute("x".toByteArray(), "f.txt", "stable-id")
        (result as IngestResult.Success).document.documentId shouldBe "stable-id"
    }

    // ── IngestResult.UnsupportedFormat ────────────────────────────────────────

    @Test fun `returns UnsupportedFormat when loader does not support format`() = runTest {
        val result = useCase().execute(
            fileBytes = "data".toByteArray(),
            filename  = "sheet.xlsx",
            documentId = "doc-xlsx",
            mimeType  = "application/vnd.ms-excel",
        )
        result.shouldBeInstanceOf<IngestResult.UnsupportedFormat>()
    }

    @Test fun `UnsupportedFormat carries filename`() = runTest {
        val result = useCase().execute("data".toByteArray(), "bad.xlsx", "d",
            "application/vnd.ms-excel")
        (result as IngestResult.UnsupportedFormat).filename shouldBe "bad.xlsx"
    }

    @Test fun `UnsupportedFormat carries mimeType`() = runTest {
        val result = useCase().execute("data".toByteArray(), "bad.xlsx", "d",
            "application/vnd.ms-excel")
        (result as IngestResult.UnsupportedFormat).mimeType shouldBe "application/vnd.ms-excel"
    }

    @Test fun `returns UnsupportedFormat when UnsupportedDocumentFormatException thrown from loader`() = runTest {
        val result = useCase(FakeDocumentLoader()).execute(
            "x".toByteArray(), "doc.pdf", "d", "application/pdf",
        )
        result.shouldBeInstanceOf<IngestResult.UnsupportedFormat>()
    }

    // ── IngestResult.FileTooLarge ─────────────────────────────────────────────

    @Test fun `returns FileTooLarge when bytes exceed loader max`() = runTest {
        val tinyLoader = FakeDocumentLoader(maxFileSizeBytes = 3L)
        val result = useCase(tinyLoader).execute("hello".toByteArray(), "f.txt", "doc-big")
        result.shouldBeInstanceOf<IngestResult.FileTooLarge>()
    }

    @Test fun `FileTooLarge carries filename`() = runTest {
        val result = useCase(FakeDocumentLoader(maxFileSizeBytes = 2L))
            .execute("hello".toByteArray(), "big.txt", "d")
        (result as IngestResult.FileTooLarge).filename shouldBe "big.txt"
    }

    @Test fun `FileTooLarge sizeBytes equals actual file size`() = runTest {
        val bytes = "hello".toByteArray()
        val result = useCase(FakeDocumentLoader(maxFileSizeBytes = 2L))
            .execute(bytes, "f.txt", "d")
        (result as IngestResult.FileTooLarge).sizeBytes shouldBe bytes.size.toLong()
    }

    @Test fun `FileTooLarge maxBytes equals loader maxFileSizeBytes`() = runTest {
        val result = useCase(FakeDocumentLoader(maxFileSizeBytes = 2L))
            .execute("hello".toByteArray(), "f.txt", "d")
        (result as IngestResult.FileTooLarge).maxBytes shouldBe 2L
    }

    // ── IngestResult.ExtractionFailed ─────────────────────────────────────────

    @Test fun `returns ExtractionFailed when loader throws DocumentLoadException`() = runTest {
        val result = useCase(BrokenDocumentLoader()).execute("x".toByteArray(), "f.txt", "d")
        result.shouldBeInstanceOf<IngestResult.ExtractionFailed>()
    }

    @Test fun `ExtractionFailed carries stage from DocumentLoadException`() = runTest {
        val result = useCase(BrokenDocumentLoader()).execute("x".toByteArray(), "f.txt", "d")
        (result as IngestResult.ExtractionFailed).stage shouldBe "parse"
    }

    @Test fun `ExtractionFailed carries detail from DocumentLoadException`() = runTest {
        val result = useCase(BrokenDocumentLoader()).execute("x".toByteArray(), "f.txt", "d")
        (result as IngestResult.ExtractionFailed).detail shouldBe "Corrupt file content"
    }

    @Test fun `returns ExtractionFailed for empty bytes`() = runTest {
        val result = useCase().execute(ByteArray(0), "f.txt", "doc-empty")
        result.shouldBeInstanceOf<IngestResult.ExtractionFailed>()
        (result as IngestResult.ExtractionFailed).stage shouldBe "validation"
    }

    @Test fun `returns ExtractionFailed when IllegalArgumentException thrown`() = runTest {
        // Loader that accepts all formats but throws IllegalArgumentException on load
        val argErrorLoader = object : DocumentLoader {
            override val maxFileSizeBytes: Long = 10L * 1024 * 1024
            override fun supports(mimeType: String, filename: String) = true
            override suspend fun load(
                fileBytes: ByteArray, filename: String, documentId: String, mimeType: String,
            ): LoadedDocument = throw IllegalArgumentException("bad arg")
        }
        val result = useCase(argErrorLoader).execute("x".toByteArray(), "f.txt", "d")
        result.shouldBeInstanceOf<IngestResult.ExtractionFailed>()
    }

    // ── IngestResult.UnexpectedError ──────────────────────────────────────────

    @Test fun `returns UnexpectedError when loader throws RuntimeException`() = runTest {
        val result = useCase(CrashingDocumentLoader()).execute("x".toByteArray(), "f.txt", "d")
        result.shouldBeInstanceOf<IngestResult.UnexpectedError>()
    }

    @Test fun `UnexpectedError message is non-blank`() = runTest {
        val result = useCase(CrashingDocumentLoader()).execute("x".toByteArray(), "f.txt", "d")
        (result as IngestResult.UnexpectedError).message.isNotBlank() shouldBe true
    }

    // ── Never throws ─────────────────────────────────────────────────────────

    @Test fun `execute never throws regardless of input`() = runTest {
        val inputs = listOf(
            Triple(ByteArray(0), "empty.txt", ""),
            Triple("valid".toByteArray(), "f.xlsx", "application/vnd.ms-excel"),
            Triple("x".toByteArray(), "f.txt", "text/plain"),
        )
        inputs.forEach { (bytes, name, mime) ->
            val result = runCatching {
                useCase().execute(bytes, name, "d", mime)
            }
            result.isSuccess shouldBe true
        }
    }

    // ── mimeType optional ─────────────────────────────────────────────────────

    @Test fun `execute works when mimeType is blank and extension is txt`() = runTest {
        val result = useCase().execute("Hello".toByteArray(), "notes.txt", "doc-ext")
        result.shouldBeInstanceOf<IngestResult.Success>()
    }
}
