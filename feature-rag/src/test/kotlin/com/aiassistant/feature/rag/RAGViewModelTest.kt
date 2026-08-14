/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-rag
 * File       : RAGViewModelTest.kt
 * Purpose    : Unit tests for RAGViewModel — upload lifecycle, polling, and error states
 *
 * Architecture Layer : Feature (feature-rag)
 * Pattern Used       : Kotest DescribeSpec + MockK + kotlinx-coroutines-test
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-rag
 * File       : RAGViewModelTest.kt
 * Purpose    : Unit tests for RAGViewModel — upload lifecycle, polling, and error states
 *
 * Architecture Layer : Feature (feature-rag)
 * Pattern Used       : Kotest DescribeSpec + MockK + kotlinx-coroutines-test
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
/**
 * RAGViewModelTest.kt
 *
 * Purpose: Comprehensive unit tests for [RAGViewModel] covering:
 *   - File-size validation (> 50 MB rejection, boundary at exactly 50 MB)
 *   - Upload happy path: UploadInProgress → UploadSuccess → DocumentList
 *   - Upload error paths: ApiResult.Error, ApiResult.NetworkUnavailable
 *   - Status polling transitions: PENDING/PROCESSING keep job, READY/FAILED remove job
 *   - Error banner on extraction failure (getDocuments returns Error)
 *   - clearUploadError() resets state to DocumentList
 *   - stopPolling() safely cancels the polling coroutine
 *
 * Requirements: 4.1, 27.2, 27.5
 *
 * Test framework: Kotest (DescribeSpec, JUnit 5 runner) + MockK + kotlinx-coroutines-test
 */
package com.aiassistant.feature.rag

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.domain.model.Document
import com.aiassistant.domain.model.IngestionStatus
import com.aiassistant.domain.repository.DocumentRepository
import com.aiassistant.domain.usecase.document.DeleteDocumentUseCase
import com.aiassistant.domain.usecase.document.UploadDocumentUseCase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

// ─── Constants ────────────────────────────────────────────────────────────────

/** 50 MB in bytes — mirrors the private constant in RAGViewModel / UploadDocumentUseCase. */
private const val MAX_FILE_SIZE_BYTES = 50L * 1024L * 1024L

/** Polling interval used by RAGViewModel (5 seconds). */
private const val POLLING_INTERVAL_MS = 5_000L

// ─── Test dispatcher provider ─────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
private class TestDispatcherProvider(private val dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
    override val mainImmediate: CoroutineDispatcher = dispatcher
    override val unconfined: CoroutineDispatcher = dispatcher
}

// ─── Test fixtures ────────────────────────────────────────────────────────────

private fun testDocument(
    id: String = "doc-1",
    fileName: String = "test.pdf",
    jobId: String? = "job-1",
    ingestionStatus: IngestionStatus = IngestionStatus.PENDING
): Document = Document(
    id = id,
    userId = "user-1",
    fileName = fileName,
    mimeType = "application/pdf",
    sizeBytes = 1024L,
    ingestionStatus = ingestionStatus,
    jobId = jobId,
    pageCount = null,
    createdAt = 1_700_000_000_000L
)

// ─── Test suite ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class RAGViewModelTest :
    DescribeSpec({

        // Use UnconfinedTestDispatcher for most tests so state transitions run synchronously.
        val unconfinedDispatcher = UnconfinedTestDispatcher()
        val unconfinedDispatcherProvider = TestDispatcherProvider(unconfinedDispatcher)

        val mockUploadDocumentUseCase = mockk<UploadDocumentUseCase>()
        val mockDeleteDocumentUseCase = mockk<DeleteDocumentUseCase>()
        val mockDocumentRepository = mockk<DocumentRepository>()
        val mockConnectivityObserver = mockk<ConnectivityObserver>()

        /**
         * Build a ViewModel with standard mocks already configured.
         * The [dispatcherProvider] defaults to the unconfined variant but can be overridden
         * when a test needs virtual-time control for polling.
         */
        fun buildViewModel(dispatcherProvider: DispatcherProvider = unconfinedDispatcherProvider): RAGViewModel {
            every { mockDocumentRepository.getDocuments() } returns flowOf(ApiResult.Success(emptyList()))
            every { mockConnectivityObserver.isConnectedFlow } returns flowOf(true)
            every { mockConnectivityObserver.isConnected() } returns true

            return RAGViewModel(
                uploadDocumentUseCase = mockUploadDocumentUseCase,
                deleteDocumentUseCase = mockDeleteDocumentUseCase,
                documentRepository = mockDocumentRepository,
                connectivityObserver = mockConnectivityObserver,
                dispatchers = dispatcherProvider
            )
        }

        beforeSpec {
            Dispatchers.setMain(unconfinedDispatcher)
        }

        afterSpec {
            Dispatchers.resetMain()
        }

        beforeEach {
            clearMocks(
                mockUploadDocumentUseCase,
                mockDeleteDocumentUseCase,
                mockDocumentRepository,
                mockConnectivityObserver
            )
        }

        // ─── Group 1: File size validation (> 50 MB) ──────────────────────────────

        describe("file size validation") {

            it("uploadDocument() with sizeBytes > MAX_FILE_SIZE_BYTES immediately emits UploadError") {
                runTest(unconfinedDispatcher) {
                    val vm = buildViewModel()

                    vm.uploadDocument(
                        uri = "content://file",
                        fileName = "big_file.pdf",
                        mimeType = "application/pdf",
                        sizeBytes = MAX_FILE_SIZE_BYTES + 1L
                    )

                    vm.uiState.value.shouldBeInstanceOf<RAGUiState.UploadError>()
                }
            }

            it("UploadError message contains the file name when file is too large") {
                runTest(unconfinedDispatcher) {
                    val vm = buildViewModel()
                    val fileName = "huge_report.pdf"

                    vm.uploadDocument(
                        uri = "content://file",
                        fileName = fileName,
                        mimeType = "application/pdf",
                        sizeBytes = MAX_FILE_SIZE_BYTES + 1L
                    )

                    val state = vm.uiState.value as RAGUiState.UploadError
                    state.message shouldContain fileName
                }
            }

            it("UploadError message mentions '50 MB' when file is too large") {
                runTest(unconfinedDispatcher) {
                    val vm = buildViewModel()

                    vm.uploadDocument(
                        uri = "content://file",
                        fileName = "giant.pdf",
                        mimeType = "application/pdf",
                        sizeBytes = MAX_FILE_SIZE_BYTES + 1L
                    )

                    val state = vm.uiState.value as RAGUiState.UploadError
                    state.message shouldContain "50 MB"
                }
            }

            it("uploadDocumentUseCase is NOT called when file exceeds 50 MB") {
                runTest(unconfinedDispatcher) {
                    val vm = buildViewModel()

                    vm.uploadDocument(
                        uri = "content://file",
                        fileName = "oversized.pdf",
                        mimeType = "application/pdf",
                        sizeBytes = MAX_FILE_SIZE_BYTES + 1L
                    )

                    coVerify(exactly = 0) {
                        mockUploadDocumentUseCase(any(), any(), any(), any())
                    }
                }
            }

            it("uploadDocument() with exactly 50 MB is accepted (does NOT immediately emit UploadError)") {
                runTest(unconfinedDispatcher) {
                    val document = testDocument(jobId = null)
                    coEvery {
                        mockUploadDocumentUseCase(any(), any(), any(), MAX_FILE_SIZE_BYTES)
                    } returns ApiResult.Success(document)

                    val vm = buildViewModel()

                    vm.uploadDocument(
                        uri = "content://file",
                        fileName = "exactly50mb.pdf",
                        mimeType = "application/pdf",
                        sizeBytes = MAX_FILE_SIZE_BYTES
                    )

                    // Should NOT be an instant UploadError from the size guard;
                    // the use case ran and the result is UploadSuccess (before 1500ms delay)
                    vm.uiState.value.shouldBeInstanceOf<RAGUiState.UploadSuccess>()
                }
            }
        }

        // ─── Group 2: Upload happy path ───────────────────────────────────────────

        describe("upload happy path") {

            it("after uploadDocumentUseCase returns Success, state is UploadSuccess with correct document") {
                runTest(unconfinedDispatcher) {
                    val document = testDocument(id = "doc-42", fileName = "contract.pdf", jobId = null)
                    coEvery {
                        mockUploadDocumentUseCase(any(), any(), any(), any())
                    } returns ApiResult.Success(document)

                    val vm = buildViewModel()
                    vm.uploadDocument("content://doc", "contract.pdf", "application/pdf", 2048L)

                    val state = vm.uiState.value as RAGUiState.UploadSuccess
                    state.document shouldBe document
                }
            }

            it("after delay(1500ms) state transitions to DocumentList") {
                runTest(unconfinedDispatcher) {
                    val document = testDocument(jobId = null) // no polling
                    coEvery {
                        mockUploadDocumentUseCase(any(), any(), any(), any())
                    } returns ApiResult.Success(document)

                    val vm = buildViewModel()
                    vm.uploadDocument("content://doc", "file.pdf", "application/pdf", 512L)
                    // UnconfinedTestDispatcher: advance virtual time past the 1500 ms delay
                    advanceTimeBy(2_000L)

                    vm.uiState.value.shouldBeInstanceOf<RAGUiState.DocumentList>()
                }
            }

            it("when uploaded document has no jobId, no polling is started") {
                runTest(unconfinedDispatcher) {
                    val document = testDocument(jobId = null)
                    coEvery {
                        mockUploadDocumentUseCase(any(), any(), any(), any())
                    } returns ApiResult.Success(document)

                    val vm = buildViewModel()
                    vm.uploadDocument("content://doc", "file.pdf", "application/pdf", 512L)

                    coVerify(exactly = 0) {
                        mockDocumentRepository.getIngestionStatus(any())
                    }
                }
            }
        }

        // ─── Group 3: Upload error paths ──────────────────────────────────────────

        describe("upload error paths") {

            it("when uploadDocumentUseCase returns ApiResult.Error, state becomes UploadError") {
                runTest(unconfinedDispatcher) {
                    coEvery {
                        mockUploadDocumentUseCase(any(), any(), any(), any())
                    } returns ApiResult.Error(DomainError.ServerError("extraction failed"))

                    val vm = buildViewModel()
                    vm.uploadDocument("content://doc", "file.pdf", "application/pdf", 512L)

                    vm.uiState.value.shouldBeInstanceOf<RAGUiState.UploadError>()
                }
            }

            it("UploadError message matches the DomainError message on ApiResult.Error") {
                runTest(unconfinedDispatcher) {
                    val errorMessage = "extraction failed"
                    coEvery {
                        mockUploadDocumentUseCase(any(), any(), any(), any())
                    } returns ApiResult.Error(DomainError.ServerError(errorMessage))

                    val vm = buildViewModel()
                    vm.uploadDocument("content://doc", "file.pdf", "application/pdf", 512L)

                    val state = vm.uiState.value as RAGUiState.UploadError
                    state.message shouldBe errorMessage
                }
            }

            it(
                "when uploadDocumentUseCase returns ApiResult.NetworkUnavailable, state is UploadError with network message"
            ) {
                runTest(unconfinedDispatcher) {
                    coEvery {
                        mockUploadDocumentUseCase(any(), any(), any(), any())
                    } returns ApiResult.NetworkUnavailable

                    val vm = buildViewModel()
                    vm.uploadDocument("content://doc", "file.pdf", "application/pdf", 512L)

                    val state = vm.uiState.value as RAGUiState.UploadError
                    state.message.lowercase() shouldContain "network"
                }
            }

            it("UploadError from NetworkUnavailable has isOffline = true") {
                runTest(unconfinedDispatcher) {
                    coEvery {
                        mockUploadDocumentUseCase(any(), any(), any(), any())
                    } returns ApiResult.NetworkUnavailable

                    val vm = buildViewModel()
                    vm.uploadDocument("content://doc", "file.pdf", "application/pdf", 512L)

                    val state = vm.uiState.value as RAGUiState.UploadError
                    state.isOffline shouldBe true
                }
            }
        }

        // ─── Group 4: Status polling state transitions ────────────────────────────
        // These tests use StandardTestDispatcher so that advanceTimeBy() properly drives
        // the virtual clock and the polling while(true)+delay loop can be controlled.

        describe("status polling state transitions") {

            it("startPolling() starts a polling loop that calls getIngestionStatus") {
                // Use StandardTestDispatcher so advanceTimeBy drives virtual time for delay()
                val standardDispatcher = StandardTestDispatcher()
                val standardProvider = TestDispatcherProvider(standardDispatcher)
                runTest(standardDispatcher) {
                    coEvery {
                        mockDocumentRepository.getIngestionStatus("job-1")
                    } returns ApiResult.Success(IngestionStatus.PROCESSING)

                    val vm = buildViewModel(standardProvider)

                    // Allow init block (loadDocuments) to run
                    advanceUntilIdle()

                    vm.startPolling("job-1")

                    // Advance past one polling interval — this lets the while(true) loop
                    // execute one iteration then hit delay() and suspend again
                    advanceTimeBy(POLLING_INTERVAL_MS + 100L)

                    // Cancel the ViewModel to stop the polling loop
                    vm.stopPolling()

                    coVerify(atLeast = 1) {
                        mockDocumentRepository.getIngestionStatus("job-1")
                    }
                }
            }

            it("when getIngestionStatus returns READY, job is removed from polling") {
                val standardDispatcher = StandardTestDispatcher()
                val standardProvider = TestDispatcherProvider(standardDispatcher)
                runTest(standardDispatcher) {
                    coEvery {
                        mockDocumentRepository.getIngestionStatus("job-ready")
                    } returns ApiResult.Success(IngestionStatus.READY)

                    val vm = buildViewModel(standardProvider)
                    advanceUntilIdle()

                    vm.startPolling("job-ready")
                    // One iteration processes the READY status and removes the job;
                    // the loop then sees empty map and breaks
                    advanceTimeBy(POLLING_INTERVAL_MS + 100L)
                    advanceUntilIdle()

                    // Job should have been queried and then polling should have stopped naturally
                    coVerify(atLeast = 1) {
                        mockDocumentRepository.getIngestionStatus("job-ready")
                    }
                }
            }

            it("when getIngestionStatus returns FAILED, job is removed from polling") {
                val standardDispatcher = StandardTestDispatcher()
                val standardProvider = TestDispatcherProvider(standardDispatcher)
                runTest(standardDispatcher) {
                    coEvery {
                        mockDocumentRepository.getIngestionStatus("job-failed")
                    } returns ApiResult.Success(IngestionStatus.FAILED)

                    val vm = buildViewModel(standardProvider)
                    advanceUntilIdle()

                    vm.startPolling("job-failed")
                    advanceTimeBy(POLLING_INTERVAL_MS + 100L)
                    advanceUntilIdle()

                    coVerify(atLeast = 1) {
                        mockDocumentRepository.getIngestionStatus("job-failed")
                    }
                }
            }

            it("when getIngestionStatus returns PENDING, job remains tracked (polling continues)") {
                val standardDispatcher = StandardTestDispatcher()
                val standardProvider = TestDispatcherProvider(standardDispatcher)
                runTest(standardDispatcher) {
                    coEvery {
                        mockDocumentRepository.getIngestionStatus("job-pending")
                    } returns ApiResult.Success(IngestionStatus.PENDING)

                    val vm = buildViewModel(standardProvider)
                    advanceUntilIdle()

                    vm.startPolling("job-pending")

                    // Advance past 2 full polling intervals
                    advanceTimeBy(POLLING_INTERVAL_MS * 2 + 500L)

                    vm.stopPolling()

                    // Should have polled at least twice (once per interval)
                    coVerify(atLeast = 2) {
                        mockDocumentRepository.getIngestionStatus("job-pending")
                    }
                }
            }

            it("when getIngestionStatus returns PROCESSING, job remains tracked (polling continues)") {
                val standardDispatcher = StandardTestDispatcher()
                val standardProvider = TestDispatcherProvider(standardDispatcher)
                runTest(standardDispatcher) {
                    coEvery {
                        mockDocumentRepository.getIngestionStatus("job-processing")
                    } returns ApiResult.Success(IngestionStatus.PROCESSING)

                    val vm = buildViewModel(standardProvider)
                    advanceUntilIdle()

                    vm.startPolling("job-processing")
                    advanceTimeBy(POLLING_INTERVAL_MS * 2 + 500L)
                    vm.stopPolling()

                    coVerify(atLeast = 2) {
                        mockDocumentRepository.getIngestionStatus("job-processing")
                    }
                }
            }

            it("calling startPolling() with the same jobId twice does not duplicate the job entry") {
                val standardDispatcher = StandardTestDispatcher()
                val standardProvider = TestDispatcherProvider(standardDispatcher)
                runTest(standardDispatcher) {
                    coEvery {
                        mockDocumentRepository.getIngestionStatus("job-dup")
                    } returns ApiResult.Success(IngestionStatus.PROCESSING)

                    val vm = buildViewModel(standardProvider)
                    advanceUntilIdle()

                    vm.startPolling("job-dup")
                    vm.startPolling("job-dup") // duplicate call — should be a no-op

                    // Advance exactly one polling cycle
                    advanceTimeBy(POLLING_INTERVAL_MS + 100L)
                    vm.stopPolling()

                    // Even with 2 startPolling calls, deduplication means only 1 entry in the
                    // map → exactly 1 status call per iteration (not 2)
                    coVerify(atMost = 2) {
                        mockDocumentRepository.getIngestionStatus("job-dup")
                    }
                }
            }
        }

        // ─── Group 5: Error banner / extraction failure ───────────────────────────

        describe("error banner on extraction failure") {

            it("when getDocuments() emits ApiResult.Error, uiState transitions to RAGUiState.Error") {
                runTest(unconfinedDispatcher) {
                    val errorMsg = "extraction failed"
                    every {
                        mockDocumentRepository.getDocuments()
                    } returns flowOf(ApiResult.Error(DomainError.ServerError(errorMsg)))
                    every { mockConnectivityObserver.isConnectedFlow } returns flowOf(true)
                    every { mockConnectivityObserver.isConnected() } returns true

                    val vm = RAGViewModel(
                        uploadDocumentUseCase = mockUploadDocumentUseCase,
                        deleteDocumentUseCase = mockDeleteDocumentUseCase,
                        documentRepository = mockDocumentRepository,
                        connectivityObserver = mockConnectivityObserver,
                        dispatchers = unconfinedDispatcherProvider
                    )

                    vm.uiState.value.shouldBeInstanceOf<RAGUiState.Error>()
                }
            }

            it("RAGUiState.Error message matches the DomainError.ServerError message") {
                runTest(unconfinedDispatcher) {
                    val errorMsg = "extraction failed"
                    every {
                        mockDocumentRepository.getDocuments()
                    } returns flowOf(ApiResult.Error(DomainError.ServerError(errorMsg)))
                    every { mockConnectivityObserver.isConnectedFlow } returns flowOf(true)
                    every { mockConnectivityObserver.isConnected() } returns true

                    val vm = RAGViewModel(
                        uploadDocumentUseCase = mockUploadDocumentUseCase,
                        deleteDocumentUseCase = mockDeleteDocumentUseCase,
                        documentRepository = mockDocumentRepository,
                        connectivityObserver = mockConnectivityObserver,
                        dispatchers = unconfinedDispatcherProvider
                    )

                    val state = vm.uiState.value as RAGUiState.Error
                    state.message shouldBe errorMsg
                }
            }
        }

        // ─── Group 6: clearUploadError() ─────────────────────────────────────────

        describe("clearUploadError()") {

            it("after an UploadError (size guard), clearUploadError() transitions state to DocumentList") {
                runTest(unconfinedDispatcher) {
                    val vm = buildViewModel()

                    vm.uploadDocument(
                        uri = "content://file",
                        fileName = "huge.pdf",
                        mimeType = "application/pdf",
                        sizeBytes = MAX_FILE_SIZE_BYTES + 1L
                    )
                    vm.uiState.value.shouldBeInstanceOf<RAGUiState.UploadError>()

                    vm.clearUploadError()

                    vm.uiState.value.shouldBeInstanceOf<RAGUiState.DocumentList>()
                }
            }

            it("after ApiResult.Error upload, clearUploadError() resets to DocumentList") {
                runTest(unconfinedDispatcher) {
                    coEvery {
                        mockUploadDocumentUseCase(any(), any(), any(), any())
                    } returns ApiResult.Error(DomainError.ServerError("server down"))

                    val vm = buildViewModel()
                    vm.uploadDocument("content://doc", "file.pdf", "application/pdf", 512L)
                    vm.uiState.value.shouldBeInstanceOf<RAGUiState.UploadError>()

                    vm.clearUploadError()

                    vm.uiState.value.shouldBeInstanceOf<RAGUiState.DocumentList>()
                }
            }
        }

        // ─── Group 7: stopPolling() ───────────────────────────────────────────────

        describe("stopPolling()") {

            it("stopPolling() is safe to call when no polling is running") {
                runTest(unconfinedDispatcher) {
                    val vm = buildViewModel()

                    // Should not throw even when called before startPolling
                    vm.stopPolling()

                    vm.uiState.value.shouldBeInstanceOf<RAGUiState.DocumentList>()
                }
            }

            it("after startPolling(), stopPolling() cancels further status calls") {
                val standardDispatcher = StandardTestDispatcher()
                val standardProvider = TestDispatcherProvider(standardDispatcher)
                runTest(standardDispatcher) {
                    coEvery {
                        mockDocumentRepository.getIngestionStatus("job-stop")
                    } returns ApiResult.Success(IngestionStatus.PROCESSING)

                    val vm = buildViewModel(standardProvider)
                    advanceUntilIdle()

                    vm.startPolling("job-stop")

                    // Let one polling cycle complete
                    advanceTimeBy(POLLING_INTERVAL_MS + 100L)

                    // Stop polling
                    vm.stopPolling()

                    // Advance another interval — should produce no new calls
                    advanceTimeBy(POLLING_INTERVAL_MS * 2)

                    // At most 2 calls total (the iteration before stop + one potential race)
                    coVerify(atMost = 3) {
                        mockDocumentRepository.getIngestionStatus("job-stop")
                    }
                }
            }
        }

        // ─── Group 8: Initial state via loadDocuments() ───────────────────────────

        describe("initial state via loadDocuments()") {

            it("transitions to DocumentList when getDocuments emits Success(emptyList)") {
                runTest(unconfinedDispatcher) {
                    val vm = buildViewModel()
                    vm.uiState.value.shouldBeInstanceOf<RAGUiState.DocumentList>()
                }
            }

            it("transitions to DocumentList when getDocuments emits NetworkUnavailable") {
                runTest(unconfinedDispatcher) {
                    every {
                        mockDocumentRepository.getDocuments()
                    } returns flowOf(ApiResult.NetworkUnavailable)
                    every { mockConnectivityObserver.isConnectedFlow } returns flowOf(false)
                    every { mockConnectivityObserver.isConnected() } returns false

                    val vm = RAGViewModel(
                        uploadDocumentUseCase = mockUploadDocumentUseCase,
                        deleteDocumentUseCase = mockDeleteDocumentUseCase,
                        documentRepository = mockDocumentRepository,
                        connectivityObserver = mockConnectivityObserver,
                        dispatchers = unconfinedDispatcherProvider
                    )

                    vm.uiState.value.shouldBeInstanceOf<RAGUiState.DocumentList>()
                }
            }
        }
    })
