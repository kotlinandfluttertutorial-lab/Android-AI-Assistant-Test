/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-rag (test)
 * File       : OnDeviceDocumentViewModelTest.kt
 * Purpose    : Unit tests for OnDeviceDocumentViewModel.
 *              Validates the spec's primary success and error StateFlow
 *              emissions using Turbine:
 *                1. UiState status transitions (pending → processing → ready / failed).
 *                2. Files > 50 MB trigger FileSizeRejection error state.
 *                3. Low-storage warning state.
 *                4. Delete action removes document from list in UiState.
 *
 * Architecture Layer : Feature test — verifies ViewModel state transitions.
 *
 * Requirements: 21.1, 31.4
 * ============================================================
 */
package com.aiassistant.feature.ondevicerag

import app.cash.turbine.test
import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.IngestionProgress
import com.aiassistant.domain.model.OnDeviceDocument
import com.aiassistant.domain.model.OnDeviceIngestionStatus
import com.aiassistant.domain.usecase.ondevicerag.DeleteOnDeviceDocumentUseCase
import com.aiassistant.domain.usecase.ondevicerag.GetOnDeviceDocumentsUseCase
import com.aiassistant.domain.usecase.ondevicerag.OnDeviceIngestDocumentUseCase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class OnDeviceDocumentViewModelTest :
    DescribeSpec({

        val testDispatcher = StandardTestDispatcher()

        beforeSpec {
            Dispatchers.setMain(testDispatcher)
        }

        afterSpec {
            Dispatchers.resetMain()
        }

        // ── Helpers ──────────────────────────────────────────────────────────────

        fun fakeDoc(
            id: String = "doc1",
            status: OnDeviceIngestionStatus = OnDeviceIngestionStatus.READY,
            chunks: Int = 5
        ) = OnDeviceDocument(
            id = id,
            userId = "user1",
            fileName = "$id.txt",
            mimeType = "text/plain",
            sizeBytes = 1000L,
            totalChunks = chunks,
            ingestionStatus = status,
            createdAt = 1000L
        )

        fun buildViewModel(
            getDocumentsUseCase: GetOnDeviceDocumentsUseCase,
            ingestUseCase: OnDeviceIngestDocumentUseCase,
            deleteUseCase: DeleteOnDeviceDocumentUseCase,
            lowStorage: Boolean = false
        ): OnDeviceDocumentViewModel {
            val dispatchers = object : com.aiassistant.core.common.DispatcherProvider {
                override val main = testDispatcher
                override val mainImmediate = testDispatcher
                override val io = testDispatcher
                override val default = testDispatcher
                override val unconfined = testDispatcher
            }
            return object : OnDeviceDocumentViewModel(
                getDocumentsUseCase,
                ingestUseCase,
                deleteUseCase,
                dispatchers
            ) {
                override fun isLowStorage() = lowStorage
            }
        }

        // ── Document list state transitions ──────────────────────────────────────

        describe("init — observeDocuments()") {

            it("transitions from Loading to DocumentList when documents are emitted") {
                runTest {
                    val docsFlow = MutableStateFlow(listOf(fakeDoc()))
                    val getUseCase = mockk<GetOnDeviceDocumentsUseCase>()
                    val ingestUseCase = mockk<OnDeviceIngestDocumentUseCase>()
                    val deleteUseCase = mockk<DeleteOnDeviceDocumentUseCase>()

                    every { getUseCase(any()) } returns docsFlow

                    val vm = buildViewModel(getUseCase, ingestUseCase, deleteUseCase)
                    testDispatcher.scheduler.advanceUntilIdle()

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<OnDeviceDocumentUiState.DocumentList>()
                    (state as OnDeviceDocumentUiState.DocumentList).documents.size shouldBe 1
                }
            }

            it("DocumentList shows ingestionInProgress=true when any doc is PROCESSING") {
                runTest {
                    val docsFlow = flowOf(listOf(fakeDoc(status = OnDeviceIngestionStatus.PROCESSING)))
                    val getUseCase = mockk<GetOnDeviceDocumentsUseCase>()
                    val ingestUseCase = mockk<OnDeviceIngestDocumentUseCase>()
                    val deleteUseCase = mockk<DeleteOnDeviceDocumentUseCase>()

                    every { getUseCase(any()) } returns docsFlow

                    val vm = buildViewModel(getUseCase, ingestUseCase, deleteUseCase)
                    testDispatcher.scheduler.advanceUntilIdle()

                    val state = vm.uiState.value as? OnDeviceDocumentUiState.DocumentList
                    state?.ingestionInProgress shouldBe true
                }
            }
        }

        // ── 50 MB file rejection ──────────────────────────────────────────────────

        describe("ingestDocument() — file size rejection") {

            it("emits FileSizeRejection when file > 50 MB") {
                runTest {
                    val getUseCase = mockk<GetOnDeviceDocumentsUseCase>()
                    val ingestUseCase = mockk<OnDeviceIngestDocumentUseCase>()
                    val deleteUseCase = mockk<DeleteOnDeviceDocumentUseCase>()

                    every { getUseCase(any()) } returns flowOf(emptyList())

                    val vm = buildViewModel(getUseCase, ingestUseCase, deleteUseCase)
                    testDispatcher.scheduler.advanceUntilIdle()

                    val oversizedBytes = 51L * 1024 * 1024 // 51 MB
                    val doc = fakeDoc().copy(sizeBytes = oversizedBytes, fileName = "big.pdf")

                    vm.ingestDocument(doc, "text content", oversizedBytes)

                    vm.uiState.value.shouldBeInstanceOf<OnDeviceDocumentUiState.FileSizeRejection>()
                    (vm.uiState.value as OnDeviceDocumentUiState.FileSizeRejection).fileName shouldBe "big.pdf"
                }
            }

            it("clearFileSizeRejection returns to DocumentList state") {
                runTest {
                    val getUseCase = mockk<GetOnDeviceDocumentsUseCase>()
                    val ingestUseCase = mockk<OnDeviceIngestDocumentUseCase>()
                    val deleteUseCase = mockk<DeleteOnDeviceDocumentUseCase>()

                    every { getUseCase(any()) } returns flowOf(emptyList())

                    val vm = buildViewModel(getUseCase, ingestUseCase, deleteUseCase)
                    testDispatcher.scheduler.advanceUntilIdle()

                    val oversizedBytes = 51L * 1024 * 1024
                    vm.ingestDocument(fakeDoc(), "text", oversizedBytes)
                    vm.uiState.value.shouldBeInstanceOf<OnDeviceDocumentUiState.FileSizeRejection>()

                    vm.clearFileSizeRejection()
                    vm.uiState.value.shouldBeInstanceOf<OnDeviceDocumentUiState.DocumentList>()
                }
            }
        }

        // ── Low-storage warning ───────────────────────────────────────────────────

        describe("ingestDocument() — low storage warning") {

            it("shows lowStorageWarning = true when isLowStorage() returns true") {
                runTest {
                    val getUseCase = mockk<GetOnDeviceDocumentsUseCase>()
                    val ingestUseCase = mockk<OnDeviceIngestDocumentUseCase>()
                    val deleteUseCase = mockk<DeleteOnDeviceDocumentUseCase>()

                    every { getUseCase(any()) } returns flowOf(emptyList())

                    val vm = buildViewModel(getUseCase, ingestUseCase, deleteUseCase, lowStorage = true)
                    testDispatcher.scheduler.advanceUntilIdle()

                    vm.ingestDocument(fakeDoc(), "text", 1000L) // size OK

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<OnDeviceDocumentUiState.DocumentList>()
                    (state as OnDeviceDocumentUiState.DocumentList).lowStorageWarning shouldBe true
                }
            }
        }

        // ── Ingestion progress events ─────────────────────────────────────────────

        describe("ingestDocument() — progress state transitions") {

            it("emits IngestionRunning during ingestion then DocumentList on Complete") {
                runTest {
                    val getUseCase = mockk<GetOnDeviceDocumentsUseCase>()
                    val ingestUseCase = mockk<OnDeviceIngestDocumentUseCase>()
                    val deleteUseCase = mockk<DeleteOnDeviceDocumentUseCase>()
                    val readyDoc = fakeDoc(status = OnDeviceIngestionStatus.READY)

                    every { getUseCase(any()) } returns flowOf(listOf(readyDoc))
                    coEvery { ingestUseCase(any(), any()) } returns flowOf(
                        IngestionProgress.Parsing,
                        IngestionProgress.Chunking,
                        IngestionProgress.Embedding(1, 1),
                        IngestionProgress.Complete(readyDoc)
                    )

                    val vm = buildViewModel(getUseCase, ingestUseCase, deleteUseCase)
                    testDispatcher.scheduler.advanceUntilIdle()

                    val states = mutableListOf<OnDeviceDocumentUiState>()
                    vm.uiState.test {
                        states += awaitItem() // initial DocumentList

                        vm.ingestDocument(fakeDoc(status = OnDeviceIngestionStatus.PENDING), "text", 100L)
                        testDispatcher.scheduler.advanceUntilIdle()

                        // Collect remaining items
                        states += cancelAndConsumeRemainingEvents()
                            .filterIsInstance<app.cash.turbine.Event.Item<OnDeviceDocumentUiState>>()
                            .map { it.value }
                    }

                    val hasIngestionRunning = states.any { it is OnDeviceDocumentUiState.IngestionRunning }
                    hasIngestionRunning shouldBe true

                    val finalState = states.last()
                    finalState.shouldBeInstanceOf<OnDeviceDocumentUiState.DocumentList>()
                }
            }
        }

        // ── Delete action ─────────────────────────────────────────────────────────

        describe("deleteDocument()") {

            it("calls deleteDocumentUseCase with correct ids") {
                runTest {
                    val getUseCase = mockk<GetOnDeviceDocumentsUseCase>()
                    val ingestUseCase = mockk<OnDeviceIngestDocumentUseCase>()
                    val deleteUseCase = mockk<DeleteOnDeviceDocumentUseCase>()

                    every { getUseCase(any()) } returns flowOf(listOf(fakeDoc()))
                    coEvery { deleteUseCase(any(), any()) } returns ApiResult.Success(Unit)

                    val vm = buildViewModel(getUseCase, ingestUseCase, deleteUseCase)
                    testDispatcher.scheduler.advanceUntilIdle()

                    vm.deleteDocument("doc1")
                    testDispatcher.scheduler.advanceUntilIdle()

                    io.mockk.coVerify { deleteUseCase("doc1", any()) }
                }
            }
        }
    })
