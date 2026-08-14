/**
 * ProfileViewModelTest.kt — feature-profile unit tests
 *
 * Tests for [ProfileViewModel] state logic:
 *   - Initial state loading combining user + memory flows
 *   - deleteMemory triggers embedding removal (deletingMemoryIds lifecycle)
 *   - startEditMemory / updateEditContent / cancelEditMemory dialog state transitions
 *   - saveMemoryEdit commits edit to MemoryRepository
 *   - dismissError clears transient error message
 *   - retry() resets and re-observes
 *
 * Requirements: 21.1
 * Related requirements: 7.3, 7.4
 *
 * Test framework: Kotest (DescribeSpec, JUnit 5 runner) + MockK + kotlinx-coroutines-test
 */

package com.aiassistant.feature.profile

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.Memory
import com.aiassistant.domain.model.MemoryType
import com.aiassistant.domain.model.User
import com.aiassistant.domain.model.UserRole
import com.aiassistant.domain.repository.MemoryRepository
import com.aiassistant.domain.repository.UserRepository
import com.aiassistant.domain.usecase.memory.DeleteMemoryUseCase
import com.aiassistant.domain.usecase.memory.GetMemoriesUseCase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

// ─── Test dispatcher provider ──────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
private class TestDispatcherProvider(private val dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
    override val mainImmediate: CoroutineDispatcher = dispatcher
    override val unconfined: CoroutineDispatcher = dispatcher
}

// ─── Test fixtures ─────────────────────────────────────────────────────────────

private val testUser = User(
    id = "u1",
    email = "test@example.com",
    displayName = "Test User",
    avatarUrl = null,
    role = UserRole.USER,
    activeProvider = "openai_gpt4o",
    createdAt = 0L,
    updatedAt = 0L
)

private val testMemory1 = Memory(
    id = "m1",
    userId = "u1",
    content = "Prefers bullet points",
    memoryType = MemoryType.PREFERENCE,
    createdAt = 0L
)

private val testMemory2 = Memory(
    id = "m2",
    userId = "u1",
    content = "Works at ACME",
    memoryType = MemoryType.FACT,
    createdAt = 0L
)

private val testMemories = listOf(testMemory1, testMemory2)

// ─── Test suite ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest :
    DescribeSpec({

        val testDispatcher = UnconfinedTestDispatcher()
        val testDispatcherProvider = TestDispatcherProvider(testDispatcher)

        val mockUserRepository = mockk<UserRepository>()
        val mockMemoryRepository = mockk<MemoryRepository>()
        val mockGetMemoriesUseCase = mockk<GetMemoriesUseCase>()
        val mockDeleteMemoryUseCase = mockk<DeleteMemoryUseCase>()

        fun buildViewModel() = ProfileViewModel(
            userRepository = mockUserRepository,
            memoryRepository = mockMemoryRepository,
            getMemoriesUseCase = mockGetMemoriesUseCase,
            deleteMemoryUseCase = mockDeleteMemoryUseCase,
            dispatchers = testDispatcherProvider
        )

        beforeSpec {
            Dispatchers.setMain(testDispatcher)
        }

        afterSpec {
            Dispatchers.resetMain()
        }

        beforeEach {
            clearMocks(mockUserRepository, mockMemoryRepository, mockGetMemoriesUseCase, mockDeleteMemoryUseCase)

            // Default stubs — happy path
            every { mockUserRepository.getCurrentUser() } returns flowOf(ApiResult.Success(testUser))
            every { mockGetMemoriesUseCase() } returns flowOf(ApiResult.Success(testMemories))
        }

        // ─── Initial state loading ────────────────────────────────────────────────

        describe("initial state loading") {

            it("emits Content with user and memories when both flows succeed") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    val state = vm.uiState.value as ProfileUiState.Content
                    state.user shouldBe testUser
                    state.memories shouldBe testMemories
                }
            }

            it("emits Error when UserRepository returns ApiResult.Error") {
                runTest(testDispatcher) {
                    val error = DomainError.ServerError("User load failed", 500)
                    every { mockUserRepository.getCurrentUser() } returns flowOf(ApiResult.Error(error))

                    val vm = buildViewModel()
                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<ProfileUiState.Error>()
                    (state as ProfileUiState.Error).message shouldBe "User load failed"
                }
            }

            it("emits Error when GetMemoriesUseCase returns ApiResult.Error") {
                runTest(testDispatcher) {
                    val error = DomainError.ServerError("Memories load failed", 500)
                    every { mockGetMemoriesUseCase() } returns flowOf(ApiResult.Error(error))

                    val vm = buildViewModel()
                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<ProfileUiState.Error>()
                    (state as ProfileUiState.Error).message shouldBe "Memories load failed"
                }
            }

            it("emits Error with network message when user flow returns NetworkUnavailable") {
                runTest(testDispatcher) {
                    every { mockUserRepository.getCurrentUser() } returns flowOf(ApiResult.NetworkUnavailable)

                    val vm = buildViewModel()
                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<ProfileUiState.Error>()
                    (state as ProfileUiState.Error).message shouldBe "No network connection. Please try again."
                }
            }

            it("emits Error with network message when memories flow returns NetworkUnavailable") {
                runTest(testDispatcher) {
                    every { mockGetMemoriesUseCase() } returns flowOf(ApiResult.NetworkUnavailable)

                    val vm = buildViewModel()
                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<ProfileUiState.Error>()
                    (state as ProfileUiState.Error).message shouldBe "No network connection. Please try again."
                }
            }

            it("populates Content.memories with the list from GetMemoriesUseCase") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    val state = vm.uiState.value as ProfileUiState.Content
                    state.memories.size shouldBe 2
                    state.memories shouldContain testMemory1
                    state.memories shouldContain testMemory2
                }
            }
        }

        // ─── deleteMemory ─────────────────────────────────────────────────────────

        describe("deleteMemory") {

            it("adds the memoryId to deletingMemoryIds immediately (before use case completes)") {
                runTest(testDispatcher) {
                    val deferred = CompletableDeferred<ApiResult<Unit>>()
                    coEvery { mockDeleteMemoryUseCase("m1") } coAnswers { deferred.await() }

                    val vm = buildViewModel()
                    launch { vm.deleteMemory("m1") }

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.deletingMemoryIds shouldContain "m1"

                    deferred.complete(ApiResult.Success(Unit))
                }
            }

            it("calls deleteMemoryUseCase with the correct memoryId") {
                runTest(testDispatcher) {
                    coEvery { mockDeleteMemoryUseCase("m1") } returns ApiResult.Success(Unit)

                    val vm = buildViewModel()
                    vm.deleteMemory("m1")

                    coVerify(exactly = 1) { mockDeleteMemoryUseCase("m1") }
                }
            }

            it("removes the memoryId from deletingMemoryIds on success") {
                runTest(testDispatcher) {
                    coEvery { mockDeleteMemoryUseCase("m1") } returns ApiResult.Success(Unit)

                    val vm = buildViewModel()
                    vm.deleteMemory("m1")

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.deletingMemoryIds.contains("m1") shouldBe false
                }
            }

            it("removes the memoryId from deletingMemoryIds on ApiResult.Error (with error message)") {
                runTest(testDispatcher) {
                    val error = DomainError.ServerError("Delete failed", 500)
                    coEvery { mockDeleteMemoryUseCase("m1") } returns ApiResult.Error(error)

                    val vm = buildViewModel()
                    vm.deleteMemory("m1")

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.deletingMemoryIds.contains("m1") shouldBe false
                }
            }

            it("sets errorMessage when deleteMemoryUseCase returns ApiResult.Error") {
                runTest(testDispatcher) {
                    val error = DomainError.ServerError("Delete failed", 500)
                    coEvery { mockDeleteMemoryUseCase("m1") } returns ApiResult.Error(error)

                    val vm = buildViewModel()
                    vm.deleteMemory("m1")

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.errorMessage shouldBe "Delete failed"
                }
            }

            it("can delete multiple memories concurrently — both ids appear in deletingMemoryIds") {
                runTest(testDispatcher) {
                    val deferred1 = CompletableDeferred<ApiResult<Unit>>()
                    val deferred2 = CompletableDeferred<ApiResult<Unit>>()
                    coEvery { mockDeleteMemoryUseCase("m1") } coAnswers { deferred1.await() }
                    coEvery { mockDeleteMemoryUseCase("m2") } coAnswers { deferred2.await() }

                    val vm = buildViewModel()
                    launch { vm.deleteMemory("m1") }
                    launch { vm.deleteMemory("m2") }

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.deletingMemoryIds shouldContain "m1"
                    state.deletingMemoryIds shouldContain "m2"

                    deferred1.complete(ApiResult.Success(Unit))
                    deferred2.complete(ApiResult.Success(Unit))
                }
            }

            it("does nothing when state is not Content (e.g., Loading)") {
                runTest(testDispatcher) {
                    // Return non-emitting flows to stay in Loading state
                    every { mockUserRepository.getCurrentUser() } returns flow { }
                    every { mockGetMemoriesUseCase() } returns flow { }

                    val vm = buildViewModel()
                    vm.uiState.value.shouldBeInstanceOf<ProfileUiState.Loading>()

                    vm.deleteMemory("m1")

                    coVerify(exactly = 0) { mockDeleteMemoryUseCase(any()) }
                }
            }
        }

        // ─── startEditMemory ──────────────────────────────────────────────────────

        describe("startEditMemory") {

            it("sets editingMemory and editContent to the memory's content") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.startEditMemory(testMemory1)

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.editingMemory shouldBe testMemory1
                    state.editContent shouldBe testMemory1.content
                }
            }

            it("does nothing when state is not Content") {
                runTest(testDispatcher) {
                    every { mockUserRepository.getCurrentUser() } returns flow { }
                    every { mockGetMemoriesUseCase() } returns flow { }

                    val vm = buildViewModel()
                    vm.uiState.value.shouldBeInstanceOf<ProfileUiState.Loading>()

                    vm.startEditMemory(testMemory1)

                    // State should remain Loading — no change
                    vm.uiState.value.shouldBeInstanceOf<ProfileUiState.Loading>()
                }
            }
        }

        // ─── updateEditContent ────────────────────────────────────────────────────

        describe("updateEditContent") {

            it("updates editContent in Content state") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.startEditMemory(testMemory1)
                    vm.updateEditContent("Updated content")

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.editContent shouldBe "Updated content"
                }
            }

            it("does nothing when state is not Content") {
                runTest(testDispatcher) {
                    every { mockUserRepository.getCurrentUser() } returns flow { }
                    every { mockGetMemoriesUseCase() } returns flow { }

                    val vm = buildViewModel()
                    vm.uiState.value.shouldBeInstanceOf<ProfileUiState.Loading>()

                    vm.updateEditContent("New content")

                    vm.uiState.value.shouldBeInstanceOf<ProfileUiState.Loading>()
                }
            }
        }

        // ─── cancelEditMemory ─────────────────────────────────────────────────────

        describe("cancelEditMemory") {

            it("clears editingMemory and editContent") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.startEditMemory(testMemory1)
                    vm.cancelEditMemory()

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.editingMemory.shouldBeNull()
                    state.editContent shouldBe ""
                }
            }
        }

        // ─── saveMemoryEdit ───────────────────────────────────────────────────────

        describe("saveMemoryEdit") {

            it("calls memoryRepository.updateMemory with correct id and new content") {
                runTest(testDispatcher) {
                    coEvery { mockMemoryRepository.updateMemory("m1", "Revised content") } returns
                        ApiResult.Success(testMemory1.copy(content = "Revised content"))

                    val vm = buildViewModel()
                    vm.startEditMemory(testMemory1)
                    vm.updateEditContent("Revised content")
                    vm.saveMemoryEdit()

                    coVerify(exactly = 1) { mockMemoryRepository.updateMemory("m1", "Revised content") }
                }
            }

            it("sets isSavingEdit=true while the update is in flight") {
                runTest(testDispatcher) {
                    val deferred = CompletableDeferred<ApiResult<Memory>>()
                    coEvery { mockMemoryRepository.updateMemory(any(), any()) } coAnswers { deferred.await() }

                    val vm = buildViewModel()
                    vm.startEditMemory(testMemory1)

                    launch { vm.saveMemoryEdit() }

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.isSavingEdit shouldBe true

                    deferred.complete(ApiResult.Success(testMemory1))
                }
            }

            it("clears editingMemory and isSavingEdit on success") {
                runTest(testDispatcher) {
                    coEvery { mockMemoryRepository.updateMemory("m1", any()) } returns
                        ApiResult.Success(testMemory1.copy(content = "New content"))

                    val vm = buildViewModel()
                    vm.startEditMemory(testMemory1)
                    vm.updateEditContent("New content")
                    vm.saveMemoryEdit()

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.editingMemory.shouldBeNull()
                    state.isSavingEdit shouldBe false
                }
            }

            it("sets errorMessage and clears isSavingEdit on ApiResult.Error") {
                runTest(testDispatcher) {
                    val error = DomainError.ServerError("Update failed", 500)
                    coEvery { mockMemoryRepository.updateMemory(any(), any()) } returns ApiResult.Error(error)

                    val vm = buildViewModel()
                    vm.startEditMemory(testMemory1)
                    vm.saveMemoryEdit()

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.isSavingEdit shouldBe false
                    state.errorMessage shouldBe "Update failed"
                }
            }

            it("sets errorMessage on unexpected result (NetworkUnavailable)") {
                runTest(testDispatcher) {
                    coEvery { mockMemoryRepository.updateMemory(any(), any()) } returns ApiResult.NetworkUnavailable

                    val vm = buildViewModel()
                    vm.startEditMemory(testMemory1)
                    vm.saveMemoryEdit()

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.isSavingEdit shouldBe false
                    state.errorMessage shouldBe "Failed to save changes. Please try again."
                }
            }

            it("does nothing when editingMemory is null") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    // Do NOT call startEditMemory — editingMemory stays null
                    vm.saveMemoryEdit()

                    coVerify(exactly = 0) { mockMemoryRepository.updateMemory(any(), any()) }
                }
            }
        }

        // ─── dismissError ─────────────────────────────────────────────────────────

        describe("dismissError") {

            it("clears errorMessage in Content state") {
                runTest(testDispatcher) {
                    val error = DomainError.ServerError("Delete failed", 500)
                    coEvery { mockDeleteMemoryUseCase("m1") } returns ApiResult.Error(error)

                    val vm = buildViewModel()
                    vm.deleteMemory("m1")

                    // Verify error is set
                    (vm.uiState.value as ProfileUiState.Content).errorMessage shouldBe "Delete failed"

                    vm.dismissError()

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.errorMessage.shouldBeNull()
                }
            }

            it("does nothing when state is not Content") {
                runTest(testDispatcher) {
                    every { mockUserRepository.getCurrentUser() } returns flow { }
                    every { mockGetMemoriesUseCase() } returns flow { }

                    val vm = buildViewModel()
                    vm.uiState.value.shouldBeInstanceOf<ProfileUiState.Loading>()

                    vm.dismissError()

                    vm.uiState.value.shouldBeInstanceOf<ProfileUiState.Loading>()
                }
            }
        }

        // ─── retry ────────────────────────────────────────────────────────────────

        describe("retry") {

            it("resets uiState to Loading and re-observes profile data") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.uiState.value.shouldBeInstanceOf<ProfileUiState.Content>()

                    // Re-stub to emit the same data after retry
                    every { mockUserRepository.getCurrentUser() } returns flowOf(ApiResult.Success(testUser))
                    every { mockGetMemoriesUseCase() } returns flowOf(ApiResult.Success(testMemories))

                    vm.retry()

                    // After retry the combine fires again and produces Content
                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<ProfileUiState.Content>()
                    (state as ProfileUiState.Content).user shouldBe testUser
                }
            }
        }

        // ─── name editing ─────────────────────────────────────────────────────────

        describe("startEditName / updateEditingName / cancelEditName") {

            it("startEditName sets isEditingName=true and pre-populates editingName") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.startEditName()

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.isEditingName shouldBe true
                    state.editingName shouldBe testUser.displayName
                }
            }

            it("updateEditingName updates the draft name") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.startEditName()
                    vm.updateEditingName("New Name")

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.editingName shouldBe "New Name"
                }
            }

            it("cancelEditName closes the dialog and clears editingName") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.startEditName()
                    vm.updateEditingName("Edited Name")
                    vm.cancelEditName()

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.isEditingName shouldBe false
                    state.editingName shouldBe ""
                }
            }

            it("does nothing when state is not Content") {
                runTest(testDispatcher) {
                    every { mockUserRepository.getCurrentUser() } returns flow { }
                    every { mockGetMemoriesUseCase() } returns flow { }

                    val vm = buildViewModel()
                    vm.uiState.value.shouldBeInstanceOf<ProfileUiState.Loading>()

                    vm.startEditName()

                    vm.uiState.value.shouldBeInstanceOf<ProfileUiState.Loading>()
                }
            }
        }

        describe("saveDisplayName") {

            it("calls userRepository.updateDisplayName with trimmed name") {
                runTest(testDispatcher) {
                    coEvery { mockUserRepository.updateDisplayName("New Name") } returns
                        ApiResult.Success(testUser.copy(displayName = "New Name"))

                    val vm = buildViewModel()
                    vm.startEditName()
                    vm.updateEditingName("  New Name  ")
                    vm.saveDisplayName()

                    coVerify(exactly = 1) { mockUserRepository.updateDisplayName("New Name") }
                }
            }

            it("sets isSavingName=true while request is in-flight") {
                runTest(testDispatcher) {
                    val deferred = CompletableDeferred<ApiResult<com.aiassistant.domain.model.User>>()
                    coEvery { mockUserRepository.updateDisplayName(any()) } coAnswers { deferred.await() }

                    val vm = buildViewModel()
                    vm.startEditName()
                    vm.updateEditingName("New Name")

                    launch { vm.saveDisplayName() }

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.isSavingName shouldBe true

                    deferred.complete(ApiResult.Success(testUser))
                }
            }

            it("closes dialog and clears isSavingName on success") {
                runTest(testDispatcher) {
                    coEvery { mockUserRepository.updateDisplayName("New Name") } returns
                        ApiResult.Success(testUser.copy(displayName = "New Name"))

                    val vm = buildViewModel()
                    vm.startEditName()
                    vm.updateEditingName("New Name")
                    vm.saveDisplayName()

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.isEditingName shouldBe false
                    state.isSavingName shouldBe false
                }
            }

            it("sets errorMessage and clears isSavingName on ApiResult.Error") {
                runTest(testDispatcher) {
                    val error = DomainError.ServerError("Name update failed", 500)
                    coEvery { mockUserRepository.updateDisplayName(any()) } returns ApiResult.Error(error)

                    val vm = buildViewModel()
                    vm.startEditName()
                    vm.updateEditingName("New Name")
                    vm.saveDisplayName()

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.isSavingName shouldBe false
                    state.errorMessage shouldBe "Name update failed"
                }
            }

            it("does nothing when editingName is blank") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.startEditName()
                    vm.updateEditingName("   ")
                    vm.saveDisplayName()

                    coVerify(exactly = 0) { mockUserRepository.updateDisplayName(any()) }
                }
            }
        }

        // ─── requestDataExport (Requirement 28.1) ────────────────────────────────

        describe("requestDataExport") {

            it("transitions dataExportStatus Idle → Requesting → Requested on success") {
                runTest(testDispatcher) {
                    coEvery { mockUserRepository.requestDataExport() } returns ApiResult.Success(Unit)

                    val vm = buildViewModel()
                    (vm.uiState.value as ProfileUiState.Content).dataExportStatus shouldBe DataExportStatus.Idle

                    vm.requestDataExport()

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.dataExportStatus shouldBe DataExportStatus.Requested
                }
            }

            it("sets Requesting while request is in-flight") {
                runTest(testDispatcher) {
                    val deferred = CompletableDeferred<ApiResult<Unit>>()
                    coEvery { mockUserRepository.requestDataExport() } coAnswers { deferred.await() }

                    val vm = buildViewModel()
                    launch { vm.requestDataExport() }

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.dataExportStatus shouldBe DataExportStatus.Requesting

                    deferred.complete(ApiResult.Success(Unit))
                }
            }

            it("sets Failed with message on ApiResult.Error") {
                runTest(testDispatcher) {
                    val error = DomainError.ServerError("Export unavailable", 503)
                    coEvery { mockUserRepository.requestDataExport() } returns ApiResult.Error(error)

                    val vm = buildViewModel()
                    vm.requestDataExport()

                    val state = vm.uiState.value as ProfileUiState.Content
                    val status = state.dataExportStatus
                    status.shouldBeInstanceOf<DataExportStatus.Failed>()
                    (status as DataExportStatus.Failed).message shouldBe "Export unavailable"
                }
            }

            it("prevents double-submission when already Requesting") {
                runTest(testDispatcher) {
                    val deferred = CompletableDeferred<ApiResult<Unit>>()
                    coEvery { mockUserRepository.requestDataExport() } coAnswers { deferred.await() }

                    val vm = buildViewModel()
                    launch { vm.requestDataExport() }
                    // Second call should be ignored
                    vm.requestDataExport()

                    coVerify(exactly = 1) { mockUserRepository.requestDataExport() }

                    deferred.complete(ApiResult.Success(Unit))
                }
            }

            it("dismissExportStatus resets status to Idle") {
                runTest(testDispatcher) {
                    coEvery { mockUserRepository.requestDataExport() } returns ApiResult.Success(Unit)

                    val vm = buildViewModel()
                    vm.requestDataExport()
                    (vm.uiState.value as ProfileUiState.Content).dataExportStatus shouldBe DataExportStatus.Requested

                    vm.dismissExportStatus()

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.dataExportStatus shouldBe DataExportStatus.Idle
                }
            }
        }

        // ─── account deletion (Requirement 28.2) ─────────────────────────────────

        describe("initiateAccountDeletion / cancelAccountDeletion") {

            it("initiateAccountDeletion transitions to Confirming state") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.initiateAccountDeletion()

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.accountDeletionState.shouldBeInstanceOf<AccountDeletionState.Confirming>()
                }
            }

            it("cancelAccountDeletion resets to Idle") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.initiateAccountDeletion()
                    vm.cancelAccountDeletion()

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.accountDeletionState shouldBe AccountDeletionState.Idle
                }
            }

            it("updateDeletionConfirmationInput updates the confirmationInput field") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.initiateAccountDeletion()
                    vm.updateDeletionConfirmationInput("DEL")

                    val state = vm.uiState.value as ProfileUiState.Content
                    val confirming = state.accountDeletionState as AccountDeletionState.Confirming
                    confirming.confirmationInput shouldBe "DEL"
                }
            }
        }

        describe("confirmAccountDeletion") {

            it("does nothing when confirmationInput is not exactly 'DELETE'") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.initiateAccountDeletion()
                    vm.updateDeletionConfirmationInput("delete") // wrong case
                    vm.confirmAccountDeletion()

                    coVerify(exactly = 0) { mockUserRepository.deleteAccount() }
                    vm.uiState.value.shouldBeInstanceOf<ProfileUiState.Content>()
                }
            }

            it("transitions to Deleting state when confirmation is correct and then calls deleteAccount") {
                runTest(testDispatcher) {
                    coEvery { mockUserRepository.deleteAccount() } returns ApiResult.Success(Unit)

                    val vm = buildViewModel()
                    vm.initiateAccountDeletion()
                    vm.updateDeletionConfirmationInput(ProfileViewModel.DELETION_CONFIRMATION_PHRASE)
                    vm.confirmAccountDeletion()

                    coVerify(exactly = 1) { mockUserRepository.deleteAccount() }
                }
            }

            it("emits AccountDeleted event on success") {
                runTest(testDispatcher) {
                    coEvery { mockUserRepository.deleteAccount() } returns ApiResult.Success(Unit)

                    val vm = buildViewModel()
                    val events = mutableListOf<ProfileEvent>()
                    val job = launch { vm.profileEvents.collect { events.add(it) } }

                    vm.initiateAccountDeletion()
                    vm.updateDeletionConfirmationInput(ProfileViewModel.DELETION_CONFIRMATION_PHRASE)
                    vm.confirmAccountDeletion()

                    events shouldContain ProfileEvent.AccountDeleted

                    job.cancel()
                }
            }

            it("transitions to Deleted state on success") {
                runTest(testDispatcher) {
                    coEvery { mockUserRepository.deleteAccount() } returns ApiResult.Success(Unit)

                    val vm = buildViewModel()
                    vm.initiateAccountDeletion()
                    vm.updateDeletionConfirmationInput(ProfileViewModel.DELETION_CONFIRMATION_PHRASE)
                    vm.confirmAccountDeletion()

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.accountDeletionState shouldBe AccountDeletionState.Deleted
                }
            }

            it("transitions to Failed state on ApiResult.Error") {
                runTest(testDispatcher) {
                    val error = DomainError.ServerError("Deletion service unavailable", 503)
                    coEvery { mockUserRepository.deleteAccount() } returns ApiResult.Error(error)

                    val vm = buildViewModel()
                    vm.initiateAccountDeletion()
                    vm.updateDeletionConfirmationInput(ProfileViewModel.DELETION_CONFIRMATION_PHRASE)
                    vm.confirmAccountDeletion()

                    val state = vm.uiState.value as ProfileUiState.Content
                    val deletionState = state.accountDeletionState
                    deletionState.shouldBeInstanceOf<AccountDeletionState.Failed>()
                    (deletionState as AccountDeletionState.Failed).message shouldBe "Deletion service unavailable"
                }
            }

            it("dismissDeletionError resets to Idle from Failed state") {
                runTest(testDispatcher) {
                    val error = DomainError.ServerError("Deletion failed", 500)
                    coEvery { mockUserRepository.deleteAccount() } returns ApiResult.Error(error)

                    val vm = buildViewModel()
                    vm.initiateAccountDeletion()
                    vm.updateDeletionConfirmationInput(ProfileViewModel.DELETION_CONFIRMATION_PHRASE)
                    vm.confirmAccountDeletion()

                    vm.uiState.value.shouldBeInstanceOf<ProfileUiState.Content>()
                    (vm.uiState.value as ProfileUiState.Content).accountDeletionState
                        .shouldBeInstanceOf<AccountDeletionState.Failed>()

                    vm.dismissDeletionError()

                    val state = vm.uiState.value as ProfileUiState.Content
                    state.accountDeletionState shouldBe AccountDeletionState.Idle
                }
            }
        }
    })
