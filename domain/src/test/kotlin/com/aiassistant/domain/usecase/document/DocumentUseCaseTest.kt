/**
 * DocumentUseCaseTest.kt — domain module unit tests
 *
 * Tests for document use cases:
 *   - [UploadDocumentUseCase] — validates file size ≤ 50 MB and allowed MIME types
 *   - [QueryDocumentUseCase]  — validates query not blank; trims before delegating
 *   - [DeleteDocumentUseCase] — pure delegation; no validation
 *
 * Requirements: 21.1
 * Related requirements: 4.1, 4.6, 4.10
 *
 * Test framework: Kotest (DescribeSpec, JUnit 5 runner) + MockK for DocumentRepository mocking
 */

package com.aiassistant.domain.usecase.document

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.Document
import com.aiassistant.domain.model.IngestionStatus
import com.aiassistant.domain.repository.DocumentRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

// ─── Shared test fixtures ──────────────────────────────────────────────────────

private val SAMPLE_DOCUMENT = Document(
    id = "doc-123",
    userId = "user-456",
    fileName = "test-file.pdf",
    mimeType = "application/pdf",
    sizeBytes = 1024L,
    ingestionStatus = IngestionStatus.PENDING,
    createdAt = 1_000_000L
)

private const val VALID_FILE_URI = "content://media/external/file/1"
private const val VALID_FILE_NAME = "test-file.pdf"
private const val VALID_MIME_TYPE = "application/pdf"
private const val VALID_SIZE_BYTES = 1L * 1024L * 1024L // 1 MB — well under 50 MB limit

// ─── UploadDocumentUseCase ─────────────────────────────────────────────────────

class UploadDocumentUseCaseTest :
    DescribeSpec({

        val documentRepository = mockk<DocumentRepository>()
        val uploadDocumentUseCase = UploadDocumentUseCase(documentRepository)

        beforeEach {
            clearMocks(documentRepository)
        }

        describe("UploadDocumentUseCase") {

            describe("successful upload") {

                it("returns Success with Document when all inputs are valid") {
                    coEvery {
                        documentRepository.uploadDocument(VALID_FILE_URI, VALID_FILE_NAME, VALID_MIME_TYPE)
                    } returns ApiResult.Success(SAMPLE_DOCUMENT)

                    val result =
                        uploadDocumentUseCase(VALID_FILE_URI, VALID_FILE_NAME, VALID_MIME_TYPE, VALID_SIZE_BYTES)

                    result.shouldBeInstanceOf<ApiResult.Success<Document>>()
                    (result as ApiResult.Success<Document>).data shouldBe SAMPLE_DOCUMENT
                }

                it("delegates to repository exactly once with correct arguments") {
                    coEvery {
                        documentRepository.uploadDocument(VALID_FILE_URI, VALID_FILE_NAME, VALID_MIME_TYPE)
                    } returns ApiResult.Success(SAMPLE_DOCUMENT)

                    uploadDocumentUseCase(VALID_FILE_URI, VALID_FILE_NAME, VALID_MIME_TYPE, VALID_SIZE_BYTES)

                    coVerify(exactly = 1) {
                        documentRepository.uploadDocument(VALID_FILE_URI, VALID_FILE_NAME, VALID_MIME_TYPE)
                    }
                }

                it("accepts file at exactly the 50 MB limit") {
                    val exactLimit = UploadDocumentUseCase.MAX_FILE_SIZE_BYTES
                    coEvery {
                        documentRepository.uploadDocument(any(), any(), any())
                    } returns ApiResult.Success(SAMPLE_DOCUMENT)

                    val result = uploadDocumentUseCase(VALID_FILE_URI, VALID_FILE_NAME, VALID_MIME_TYPE, exactLimit)

                    result.shouldBeInstanceOf<ApiResult.Success<Document>>()
                    coVerify(exactly = 1) { documentRepository.uploadDocument(any(), any(), any()) }
                }

                it("accepts all allowed MIME types") {
                    val allowedMimes = UploadDocumentUseCase.ALLOWED_MIME_TYPES
                    coEvery {
                        documentRepository.uploadDocument(any(), any(), any())
                    } returns ApiResult.Success(SAMPLE_DOCUMENT)

                    allowedMimes.forEach { mime ->
                        val result = uploadDocumentUseCase(VALID_FILE_URI, VALID_FILE_NAME, mime, VALID_SIZE_BYTES)
                        result.shouldBeInstanceOf<ApiResult.Success<Document>>()
                    }
                }
            }

            describe("file size validation") {

                it("returns ValidationError when file size exceeds 50 MB") {
                    val oversized = UploadDocumentUseCase.MAX_FILE_SIZE_BYTES + 1L

                    val result = uploadDocumentUseCase(VALID_FILE_URI, VALID_FILE_NAME, VALID_MIME_TYPE, oversized)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error
                    error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("file size ValidationError contains '${UploadDocumentUseCase.FIELD_FILE}' in fields map") {
                    val oversized = UploadDocumentUseCase.MAX_FILE_SIZE_BYTES + 1L

                    val result = uploadDocumentUseCase(VALID_FILE_URI, VALID_FILE_NAME, VALID_MIME_TYPE, oversized)

                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey UploadDocumentUseCase.FIELD_FILE
                }

                it("does NOT call repository when file is too large") {
                    val oversized = UploadDocumentUseCase.MAX_FILE_SIZE_BYTES + 1L

                    uploadDocumentUseCase(VALID_FILE_URI, VALID_FILE_NAME, VALID_MIME_TYPE, oversized)

                    coVerify(exactly = 0) { documentRepository.uploadDocument(any(), any(), any()) }
                }
            }

            describe("MIME type validation") {

                it("returns ValidationError when MIME type is not in allowed set") {
                    val result = uploadDocumentUseCase(VALID_FILE_URI, VALID_FILE_NAME, "image/jpeg", VALID_SIZE_BYTES)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error
                    error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("MIME type ValidationError contains '${UploadDocumentUseCase.FIELD_MIME_TYPE}' in fields map") {
                    val result = uploadDocumentUseCase(VALID_FILE_URI, VALID_FILE_NAME, "image/jpeg", VALID_SIZE_BYTES)

                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey UploadDocumentUseCase.FIELD_MIME_TYPE
                }

                it("does NOT call repository when MIME type is unsupported") {
                    uploadDocumentUseCase(VALID_FILE_URI, VALID_FILE_NAME, "application/zip", VALID_SIZE_BYTES)

                    coVerify(exactly = 0) { documentRepository.uploadDocument(any(), any(), any()) }
                }

                it("size is validated before MIME type — size error wins when both are invalid") {
                    val oversized = UploadDocumentUseCase.MAX_FILE_SIZE_BYTES + 1L

                    val result = uploadDocumentUseCase(VALID_FILE_URI, VALID_FILE_NAME, "image/jpeg", oversized)

                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey UploadDocumentUseCase.FIELD_FILE
                }
            }

            describe("error propagation") {

                it("propagates NetworkUnavailable from repository") {
                    coEvery {
                        documentRepository.uploadDocument(any(), any(), any())
                    } returns ApiResult.NetworkUnavailable

                    val result =
                        uploadDocumentUseCase(VALID_FILE_URI, VALID_FILE_NAME, VALID_MIME_TYPE, VALID_SIZE_BYTES)

                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }

                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery {
                        documentRepository.uploadDocument(any(), any(), any())
                    } returns ApiResult.Error(error)

                    val result =
                        uploadDocumentUseCase(VALID_FILE_URI, VALID_FILE_NAME, VALID_MIME_TYPE, VALID_SIZE_BYTES)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── QueryDocumentUseCase ─────────────────────────────────────────────────────

class QueryDocumentUseCaseTest :
    DescribeSpec({

        val documentRepository = mockk<DocumentRepository>()
        val queryDocumentUseCase = QueryDocumentUseCase(documentRepository)

        beforeEach {
            clearMocks(documentRepository)
        }

        describe("QueryDocumentUseCase") {

            describe("successful query") {

                it("returns Success with response text when query is valid") {
                    coEvery {
                        documentRepository.queryDocument("doc-123", "What is this document about?")
                    } returns ApiResult.Success("This document is about...")

                    val result = queryDocumentUseCase("doc-123", "What is this document about?")

                    result.shouldBeInstanceOf<ApiResult.Success<String>>()
                    (result as ApiResult.Success<String>).data shouldBe "This document is about..."
                }

                it("delegates to repository exactly once with correct arguments") {
                    coEvery {
                        documentRepository.queryDocument(any(), any())
                    } returns ApiResult.Success("Some answer")

                    queryDocumentUseCase("doc-123", "My query")

                    coVerify(exactly = 1) { documentRepository.queryDocument("doc-123", "My query") }
                }

                it("trims whitespace from query before passing to repository") {
                    coEvery {
                        documentRepository.queryDocument(any(), "trimmed query")
                    } returns ApiResult.Success("Answer")

                    queryDocumentUseCase("doc-123", "  trimmed query  ")

                    coVerify(exactly = 1) { documentRepository.queryDocument("doc-123", "trimmed query") }
                }
            }

            describe("query validation") {

                it("returns ValidationError when query is blank") {
                    val result = queryDocumentUseCase("doc-123", "")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error
                    error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("returns ValidationError when query is only whitespace") {
                    val result = queryDocumentUseCase("doc-123", "   ")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("ValidationError contains 'query' in fields map") {
                    val result = queryDocumentUseCase("doc-123", "")

                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey QueryDocumentUseCase.FIELD_QUERY
                }

                it("does NOT call repository when query is blank") {
                    queryDocumentUseCase("doc-123", "")

                    coVerify(exactly = 0) { documentRepository.queryDocument(any(), any()) }
                }
            }

            describe("error propagation") {

                it("propagates NetworkUnavailable from repository") {
                    coEvery {
                        documentRepository.queryDocument(any(), any())
                    } returns ApiResult.NetworkUnavailable

                    val result = queryDocumentUseCase("doc-123", "valid query")

                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }

                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery {
                        documentRepository.queryDocument(any(), any())
                    } returns ApiResult.Error(error)

                    val result = queryDocumentUseCase("doc-123", "valid query")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── DeleteDocumentUseCase ─────────────────────────────────────────────────────

class DeleteDocumentUseCaseTest :
    DescribeSpec({

        val documentRepository = mockk<DocumentRepository>()
        val deleteDocumentUseCase = DeleteDocumentUseCase(documentRepository)

        beforeEach {
            clearMocks(documentRepository)
        }

        describe("DeleteDocumentUseCase") {

            describe("successful deletion") {

                it("returns Success with Unit when repository succeeds") {
                    coEvery { documentRepository.deleteDocument("doc-123") } returns ApiResult.Success(Unit)

                    val result = deleteDocumentUseCase("doc-123")

                    result.shouldBeInstanceOf<ApiResult.Success<Unit>>()
                }

                it("delegates to repository exactly once with the given documentId") {
                    coEvery { documentRepository.deleteDocument("doc-123") } returns ApiResult.Success(Unit)

                    deleteDocumentUseCase("doc-123")

                    coVerify(exactly = 1) { documentRepository.deleteDocument("doc-123") }
                }
            }

            describe("error propagation") {

                it("propagates NetworkUnavailable from repository") {
                    coEvery { documentRepository.deleteDocument(any()) } returns ApiResult.NetworkUnavailable

                    val result = deleteDocumentUseCase("doc-123")

                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }

                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { documentRepository.deleteDocument(any()) } returns ApiResult.Error(error)

                    val result = deleteDocumentUseCase("doc-123")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })
