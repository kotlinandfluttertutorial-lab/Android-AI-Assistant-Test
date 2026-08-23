/**
 * DocumentRepositoryImplTest.kt â€” data module
 *
 * Purpose: Unit tests for [DocumentRepositoryImpl], focusing on:
 *   - syncStatus state transitions: PENDING â†’ PROCESSING â†’ READY / FAILED
 *   - delete clearing both local Room cache and remote entries
 *   - offline guard on upload/getIngestionStatus operations
 *   - Room-first emission from [getDocuments]
 *
 * Architecture: data module â€” unit tests (pure JVM, no Android framework).
 *               The [Context] dependency of [DocumentRepositoryImpl] is handled by
 *               a relaxed MockK mock; we never call [readBytesFromUri] directly in
 *               these tests.
 *
 * Test toolchain:
 * - Kotest DescribeSpec  â€” test structure and assertions
 * - MockK                â€” mocking DocumentDao, DocumentRemoteDataSource,
 *                          ConnectivityObserver, SecureStorage, Context
 * - kotlinx.coroutines.test â€” runTest + UnconfinedTestDispatcher
 * - Turbine               â€” Flow collection assertions
 *
 * Requirements covered: 4.1 (syncStatus transitions), 4.10 (delete clears local + remote),
 *                       21.1 (unit test coverage)
 */
package com.aiassistant.data.repository

import android.content.ContentResolver
import android.content.Context
import app.cash.turbine.test
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.database.dao.DocumentDao
import com.aiassistant.core.database.entity.DocumentEntity
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.core.security.SecureStorage
import com.aiassistant.data.remote.document.DocumentDto
import com.aiassistant.data.remote.document.DocumentRemoteDataSource
import com.aiassistant.data.remote.document.DocumentUploadResponseDto
import com.aiassistant.data.remote.document.JobStatusDto
import com.aiassistant.domain.model.IngestionStatus
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

// â”€â”€â”€ Test doubles â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

// â”€â”€â”€ Fixtures â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

private fun fakeDocumentEntity(
    id: String = "doc-1",
    userId: String = "user-1",
    ingestionStatus: String = "pending",
    jobId: String? = "job-1"
) = DocumentEntity(
    id = id,
    userId = userId,
    fileName = "test.pdf",
    mimeType = "application/pdf",
    sizeBytes = 1024L,
    ingestionStatus = ingestionStatus,
    jobId = jobId,
    pageCount = null,
    createdAt = 1_000_000L
)

private fun fakeJobStatusDto(jobId: String = "job-1", documentId: String = "doc-1", status: String = "processing") =
    JobStatusDto(
        jobId = jobId,
        documentId = documentId,
        status = status
    )

private fun fakeDocumentUploadResponseDto(
    documentId: String = "doc-1",
    jobId: String = "job-1",
    status: String = "pending"
) = DocumentUploadResponseDto(
    documentId = documentId,
    jobId = jobId,
    status = status
)

private fun fakeDocumentDto(id: String = "doc-1") = DocumentDto(
    id = id,
    fileName = "test.pdf",
    mimeType = "application/pdf",
    sizeBytes = 1024L,
    ingestionStatus = "ready",
    pageCount = null,
    createdAt = "2026-07-30T04:11:29Z"
)

// â”€â”€â”€ Spec â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

class DocumentRepositoryImplTest :
    DescribeSpec({

        // â”€â”€ Shared mocks â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        val documentDao: DocumentDao = mockk(relaxed = true)
        val remoteSource: DocumentRemoteDataSource = mockk()
        val connectivityObserver: ConnectivityObserver = mockk()
        val secureStorage: SecureStorage = mockk()
        val context: Context = mockk(relaxed = true)
        val dispatchers = TestDispatcherProvider()

        lateinit var repository: DocumentRepositoryImpl

        beforeEach {
            clearAllMocks()
            every { secureStorage.getJwt() } returns "header.payload.userId"
            every { connectivityObserver.isConnectedFlow } returns flowOf(true)
            every { connectivityObserver.isConnected() } returns true
            coEvery { remoteSource.getDocuments() } returns ApiResult.Success(emptyList())
            repository = DocumentRepositoryImpl(
                documentDao = documentDao,
                remoteSource = remoteSource,
                connectivityObserver = connectivityObserver,
                secureStorage = secureStorage,
                dispatchers = dispatchers,
                context = context
            )
        }

        afterEach {
            repository.cancelSync()
            unmockkAll()
        }

        // ─── getDocuments() ─── Room emission ────────────────────────────────────
        describe("getDocuments()") {
            it("emits ApiResult.Success containing documents from Room immediately and triggers sync") {
                runTest {
                    val entities = listOf(
                        fakeDocumentEntity(id = "doc-1", ingestionStatus = "pending")
                    )
                    val remoteDtos = listOf(fakeDocumentDto(id = "doc-1"))

                    every { documentDao.getDocumentsByUser(any()) } returns flowOf(entities)
                    coEvery { remoteSource.getDocuments() } returns ApiResult.Success(remoteDtos)
                    every { connectivityObserver.isConnected() } returns true

                    repository.getDocuments().test {
                        val result = awaitItem()
                        result.shouldBeInstanceOf<ApiResult.Success<*>>()
                        // We can't easily wait for the background sync launch in GlobalScope/repositoryScope
                        // without making it injectable, but we can verify it was eventually called if we wait.
                        awaitComplete()
                    }

                    // Verify sync was attempted
                    coVerify(atLeast = 1) { remoteSource.getDocuments() }
                }
            }

            it("emits empty list when Room has no documents") {
                runTest {
                    every { documentDao.getDocumentsByUser(any()) } returns flowOf(emptyList())

                    repository.getDocuments().test {
                        val result = awaitItem()
                        result.shouldBeInstanceOf<ApiResult.Success<*>>()
                        (result as ApiResult.Success).data shouldBe emptyList()
                        awaitComplete()
                    }
                }
            }
        }

        // â”€â”€â”€ getIngestionStatus() â€” syncStatus transition verification â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        describe("getIngestionStatus() â€” syncStatus transitions") {

            it("PENDING â†’ PROCESSING: polls remote, updates Room to processing, returns PROCESSING") {
                runTest {
                    val processingEntity = fakeDocumentEntity(ingestionStatus = "pending", jobId = "job-1")
                    every { documentDao.getDocumentById("doc-1") } returns flowOf(processingEntity)
                    coEvery { remoteSource.getJobStatus("job-1") } returns
                        ApiResult.Success(fakeJobStatusDto(status = "processing"))
                    every { connectivityObserver.isConnected() } returns true

                    val result = repository.getIngestionStatus("doc-1")

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data shouldBe IngestionStatus.PROCESSING
                    // Room should have been updated with the new status
                    coVerify(atLeast = 1) {
                        documentDao.updateDocument(match { it.ingestionStatus == "processing" })
                    }
                }
            }

            it("PENDING â†’ READY: polls remote, updates Room to ready, returns READY") {
                runTest {
                    val entity = fakeDocumentEntity(ingestionStatus = "pending", jobId = "job-1")
                    every { documentDao.getDocumentById("doc-1") } returns flowOf(entity)
                    coEvery { remoteSource.getJobStatus("job-1") } returns
                        ApiResult.Success(fakeJobStatusDto(status = "ready"))
                    every { connectivityObserver.isConnected() } returns true

                    val result = repository.getIngestionStatus("doc-1")

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data shouldBe IngestionStatus.READY
                    coVerify(atLeast = 1) {
                        documentDao.updateDocument(match { it.ingestionStatus == "ready" })
                    }
                }
            }

            it("PENDING â†’ FAILED: polls remote, updates Room to failed, returns FAILED") {
                runTest {
                    val entity = fakeDocumentEntity(ingestionStatus = "pending", jobId = "job-1")
                    every { documentDao.getDocumentById("doc-1") } returns flowOf(entity)
                    coEvery { remoteSource.getJobStatus("job-1") } returns
                        ApiResult.Success(fakeJobStatusDto(status = "failed"))
                    every { connectivityObserver.isConnected() } returns true

                    val result = repository.getIngestionStatus("doc-1")

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data shouldBe IngestionStatus.FAILED
                    coVerify(atLeast = 1) {
                        documentDao.updateDocument(match { it.ingestionStatus == "failed" })
                    }
                }
            }

            it("PROCESSING â†’ READY: updates Room to ready when job reports ready") {
                runTest {
                    val entity = fakeDocumentEntity(ingestionStatus = "processing", jobId = "job-1")
                    every { documentDao.getDocumentById("doc-1") } returns flowOf(entity)
                    coEvery { remoteSource.getJobStatus("job-1") } returns
                        ApiResult.Success(fakeJobStatusDto(status = "ready"))
                    every { connectivityObserver.isConnected() } returns true

                    val result = repository.getIngestionStatus("doc-1")

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data shouldBe IngestionStatus.READY
                    coVerify(atLeast = 1) {
                        documentDao.updateDocument(match { it.ingestionStatus == "ready" })
                    }
                }
            }

            it("PROCESSING â†’ FAILED: updates Room to failed when job reports failed") {
                runTest {
                    val entity = fakeDocumentEntity(ingestionStatus = "processing", jobId = "job-1")
                    every { documentDao.getDocumentById("doc-1") } returns flowOf(entity)
                    coEvery { remoteSource.getJobStatus("job-1") } returns
                        ApiResult.Success(fakeJobStatusDto(status = "failed"))
                    every { connectivityObserver.isConnected() } returns true

                    val result = repository.getIngestionStatus("doc-1")

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data shouldBe IngestionStatus.FAILED
                    coVerify(atLeast = 1) {
                        documentDao.updateDocument(match { it.ingestionStatus == "failed" })
                    }
                }
            }

            it("returns ApiResult.NetworkUnavailable without calling remote when offline") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    val result = repository.getIngestionStatus("doc-1")

                    result shouldBe ApiResult.NetworkUnavailable
                    coVerify(exactly = 0) { remoteSource.getJobStatus(any()) }
                }
            }

            it("propagates ApiResult.Error when remote job polling fails with server error") {
                runTest {
                    val entity = fakeDocumentEntity(ingestionStatus = "pending", jobId = "job-1")
                    every { documentDao.getDocumentById("doc-1") } returns flowOf(entity)
                    coEvery { remoteSource.getJobStatus("job-1") } returns
                        ApiResult.Error(DomainError.ServerError("Server error", 500))
                    every { connectivityObserver.isConnected() } returns true

                    val result = repository.getIngestionStatus("doc-1")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ServerError>()
                }
            }
        }

        // â”€â”€â”€ deleteDocument() â€” clears local + remote â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        describe("deleteDocument()") {

            describe("online â€” both local and remote are cleared") {
                it("deletes from Room DAO immediately") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { remoteSource.deleteDocument(any()) } returns ApiResult.Success(Unit)

                        repository.deleteDocument("doc-1")

                        coVerify(exactly = 1) { documentDao.deleteDocument("doc-1") }
                    }
                }

                it("calls remote deleteDocument when connected") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { remoteSource.deleteDocument("doc-1") } returns ApiResult.Success(Unit)

                        repository.deleteDocument("doc-1")

                        coVerify(exactly = 1) { remoteSource.deleteDocument("doc-1") }
                    }
                }

                it("returns ApiResult.Success(Unit) when both local and remote succeed") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { remoteSource.deleteDocument("doc-1") } returns ApiResult.Success(Unit)

                        val result = repository.deleteDocument("doc-1")

                        result shouldBe ApiResult.Success(Unit)
                    }
                }

                it("returns ApiResult.Success(Unit) even when remote delete fails (best-effort)") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { remoteSource.deleteDocument("doc-1") } returns
                            ApiResult.Error(DomainError.ServerError("Server error", 500))

                        val result = repository.deleteDocument("doc-1")

                        // Local delete always wins; remote failure is swallowed
                        result shouldBe ApiResult.Success(Unit)
                        coVerify(exactly = 1) { documentDao.deleteDocument("doc-1") }
                    }
                }
            }

            describe("offline â€” only local cache is cleared") {
                it("deletes from Room DAO even when offline") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        repository.deleteDocument("doc-1")

                        coVerify(exactly = 1) { documentDao.deleteDocument("doc-1") }
                    }
                }

                it("does NOT call remote when offline") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        repository.deleteDocument("doc-1")

                        coVerify(exactly = 0) { remoteSource.deleteDocument(any()) }
                    }
                }

                it("returns ApiResult.Success(Unit) offline after local delete") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        val result = repository.deleteDocument("doc-1")

                        result shouldBe ApiResult.Success(Unit)
                    }
                }
            }
        }

        // ─── uploadDocument() ─── connectivity guard ──────────────────────────────
        describe("uploadDocument()") {
            it("returns Success and inserts into Room on successful upload") {
                runTest {
                    val uploadResponse = fakeDocumentUploadResponseDto(documentId = "new-doc", jobId = "new-job")
                    coEvery { remoteSource.uploadDocument(any()) } returns ApiResult.Success(uploadResponse)
                    every { connectivityObserver.isConnected() } returns true
                    // Return a real InputStream with minimal bytes so readBytesFromUri() terminates
                    // immediately. A relaxed-mock InputStream.read() returns 0 forever → OOM hang.
                    val fakeInputStream = "pdf-content".toByteArray().inputStream()
                    every { context.contentResolver } returns mockk {
                        every { openInputStream(any()) } returns fakeInputStream
                    }

                    val result = repository.uploadDocument("content://test/doc.pdf", "doc.pdf", "application/pdf")

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    coVerify { documentDao.insertDocument(match { it.id == "new-doc" && it.jobId == "new-job" }) }
                }
            }

            it("returns ApiResult.NetworkUnavailable without attempting upload when offline") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    val result = repository.uploadDocument(
                        fileUri = "content://test/doc.pdf",
                        fileName = "doc.pdf",
                        mimeType = "application/pdf"
                    )

                    result shouldBe ApiResult.NetworkUnavailable
                    coVerify(exactly = 0) { remoteSource.uploadDocument(any()) }
                }
            }
        }
    })
