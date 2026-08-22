/**
 * MeetingViewModelTest.kt — feature-meeting unit tests
 *
 * Tests for [MeetingViewModel] state machine transitions:
 *   - Full Idle → Recording → Processing → Complete cycle
 *   - Permission denied branches to PermissionDenied state
 *   - Summary contains extracted action items in Complete state
 *   - Network and server error paths
 *   - Guard conditions (no-ops when called in wrong state)
 *   - parseActionItems helper
 *   - reset() from error/terminal states
 *
 * Requirements: 21.1
 * Related requirements: 19.1, 5.6
 *
 * Test framework: Kotest (DescribeSpec, JUnit 5 runner) + MockK + kotlinx-coroutines-test
 */

package com.aiassistant.feature.meeting

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.usecase.meeting.GetMeetingSummaryUseCase
import com.aiassistant.domain.usecase.meeting.StartMeetingRecordingUseCase
import com.aiassistant.domain.usecase.meeting.StopMeetingRecordingUseCase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

// ─── Constants ────────────────────────────────────────────────────────────────

private const val TEST_USER_ID = "user-123"
private const val TEST_SESSION_ID = "session-abc-456"

// ─── Test suite ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class MeetingViewModelTest :
    DescribeSpec({

        val testDispatcher = UnconfinedTestDispatcher()
        val testDispatcherProvider = TestDispatcherProvider(testDispatcher)

        val mockStartRecordingUseCase = mockk<StartMeetingRecordingUseCase>()
        val mockStopRecordingUseCase = mockk<StopMeetingRecordingUseCase>()
        val mockGetSummaryUseCase = mockk<GetMeetingSummaryUseCase>()

        fun buildViewModel(): MeetingViewModel = MeetingViewModel(
            startMeetingRecordingUseCase = mockStartRecordingUseCase,
            stopMeetingRecordingUseCase = mockStopRecordingUseCase,
            getMeetingSummaryUseCase = mockGetSummaryUseCase,
            dispatchers = testDispatcherProvider
        )

        beforeSpec {
            Dispatchers.setMain(testDispatcher)
        }

        afterSpec {
            Dispatchers.resetMain()
            unmockkAll()
        }

        beforeEach {
            clearMocks(mockStartRecordingUseCase, mockStopRecordingUseCase, mockGetSummaryUseCase)
        }

        // ─── 1. Initial state ──────────────────────────────────────────────────────

        describe("initial state") {

            it("starts in Idle state") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.uiState.value.shouldBeInstanceOf<MeetingUiState.Idle>()
                }
            }
        }

        // ─── 2. Full happy path: Idle → Recording → Processing → Complete ──────────

        describe("full state machine cycle: Idle → Recording → Processing → Complete") {

            it("startRecording() from Idle transitions to Recording with sessionId and durationSeconds=0") {
                runTest(testDispatcher) {
                    coEvery { mockStartRecordingUseCase(TEST_USER_ID) } returns
                        ApiResult.Success(TEST_SESSION_ID)

                    val vm = buildViewModel()
                    vm.startRecording(TEST_USER_ID)

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<MeetingUiState.Recording>()
                    (state as MeetingUiState.Recording).sessionId shouldBe TEST_SESSION_ID
                    state.durationSeconds shouldBe 0
                }
            }

            it("stopRecording() from Recording transitions to Processing") {
                runTest(testDispatcher) {
                    coEvery { mockStartRecordingUseCase(TEST_USER_ID) } returns
                        ApiResult.Success(TEST_SESSION_ID)
                    coEvery { mockStopRecordingUseCase(TEST_SESSION_ID, any()) } returns
                        ApiResult.Success(Unit)

                    val vm = buildViewModel()
                    vm.startRecording(TEST_USER_ID)
                    vm.uiState.value.shouldBeInstanceOf<MeetingUiState.Recording>()

                    vm.stopRecording("")

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<MeetingUiState.Processing>()
                    (state as MeetingUiState.Processing).sessionId shouldBe TEST_SESSION_ID
                }
            }

            it("fetchSummary() from Processing transitions to Complete with summary text") {
                runTest(testDispatcher) {
                    val summaryText = "Meeting summary content."
                    coEvery { mockStartRecordingUseCase(TEST_USER_ID) } returns
                        ApiResult.Success(TEST_SESSION_ID)
                    coEvery { mockStopRecordingUseCase(TEST_SESSION_ID, any()) } returns
                        ApiResult.Success(Unit)
                    coEvery { mockGetSummaryUseCase(TEST_SESSION_ID) } returns
                        ApiResult.Success(summaryText)

                    val vm = buildViewModel()
                    vm.startRecording(TEST_USER_ID)
                    vm.stopRecording("")
                    vm.fetchSummary()

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<MeetingUiState.Complete>()
                    (state as MeetingUiState.Complete).sessionId shouldBe TEST_SESSION_ID
                    state.summary shouldBe summaryText
                }
            }

            it("complete cycle reaches Complete state from Idle in sequence") {
                runTest(testDispatcher) {
                    val summaryText = "Summary of the all-hands meeting."
                    coEvery { mockStartRecordingUseCase(TEST_USER_ID) } returns
                        ApiResult.Success(TEST_SESSION_ID)
                    coEvery { mockStopRecordingUseCase(TEST_SESSION_ID, any()) } returns
                        ApiResult.Success(Unit)
                    coEvery { mockGetSummaryUseCase(TEST_SESSION_ID) } returns
                        ApiResult.Success(summaryText)

                    val vm = buildViewModel()

                    // 1. Idle
                    vm.uiState.value.shouldBeInstanceOf<MeetingUiState.Idle>()

                    // 2. Recording
                    vm.startRecording(TEST_USER_ID)
                    vm.uiState.value.shouldBeInstanceOf<MeetingUiState.Recording>()

                    // 3. Processing
                    vm.stopRecording("")
                    vm.uiState.value.shouldBeInstanceOf<MeetingUiState.Processing>()

                    // 4. Complete
                    vm.fetchSummary()
                    vm.uiState.value.shouldBeInstanceOf<MeetingUiState.Complete>()
                }
            }

            it("startMeetingRecordingUseCase is called with the correct userId") {
                runTest(testDispatcher) {
                    coEvery { mockStartRecordingUseCase(any()) } returns
                        ApiResult.Success(TEST_SESSION_ID)

                    val vm = buildViewModel()
                    vm.startRecording(TEST_USER_ID)

                    coVerify(exactly = 1) { mockStartRecordingUseCase(TEST_USER_ID) }
                }
            }

            it("stopMeetingRecordingUseCase is called with the sessionId from Recording state") {
                runTest(testDispatcher) {
                    coEvery { mockStartRecordingUseCase(TEST_USER_ID) } returns
                        ApiResult.Success(TEST_SESSION_ID)
                    coEvery { mockStopRecordingUseCase(any(), any()) } returns
                        ApiResult.Success(Unit)

                    val vm = buildViewModel()
                    vm.startRecording(TEST_USER_ID)
                    vm.stopRecording("")

                    coVerify(exactly = 1) { mockStopRecordingUseCase(TEST_SESSION_ID, any()) }
                }
            }

            it("getMeetingSummaryUseCase is called with the sessionId from Processing state") {
                runTest(testDispatcher) {
                    coEvery { mockStartRecordingUseCase(TEST_USER_ID) } returns
                        ApiResult.Success(TEST_SESSION_ID)
                    coEvery { mockStopRecordingUseCase(TEST_SESSION_ID, any()) } returns
                        ApiResult.Success(Unit)
                    coEvery { mockGetSummaryUseCase(any()) } returns
                        ApiResult.Success("summary")

                    val vm = buildViewModel()
                    vm.startRecording(TEST_USER_ID)
                    vm.stopRecording("")
                    vm.fetchSummary()

                    coVerify(exactly = 1) { mockGetSummaryUseCase(TEST_SESSION_ID) }
                }
            }
        }

        // ─── 3. Permission denied branches to PermissionDenied state ──────────────

        describe("permission flow — microphone permission denied branches to rationale state") {

            it("requestPermission() from Idle transitions to RequestingPermission") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.requestPermission()
                    vm.uiState.value shouldBe MeetingUiState.RequestingPermission
                }
            }

            it("onPermissionDenied() transitions to PermissionDenied from any state") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.onPermissionDenied()
                    vm.uiState.value shouldBe MeetingUiState.PermissionDenied
                }
            }

            it("onPermissionDenied() after requestPermission() transitions to PermissionDenied") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.requestPermission()
                    vm.uiState.value shouldBe MeetingUiState.RequestingPermission

                    vm.onPermissionDenied()
                    vm.uiState.value shouldBe MeetingUiState.PermissionDenied
                }
            }

            it("onPermissionGranted() from RequestingPermission transitions back to Idle (ready to record)") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.requestPermission()
                    vm.uiState.value shouldBe MeetingUiState.RequestingPermission

                    vm.onPermissionGranted()
                    vm.uiState.value.shouldBeInstanceOf<MeetingUiState.Idle>()
                }
            }

            it("requestPermission() does nothing when not in Idle state") {
                runTest(testDispatcher) {
                    coEvery { mockStartRecordingUseCase(TEST_USER_ID) } returns
                        ApiResult.Success(TEST_SESSION_ID)

                    val vm = buildViewModel()
                    vm.startRecording(TEST_USER_ID)
                    vm.uiState.value.shouldBeInstanceOf<MeetingUiState.Recording>()

                    vm.requestPermission() // should be a no-op in Recording state
                    vm.uiState.value.shouldBeInstanceOf<MeetingUiState.Recording>()
                }
            }

            it("onPermissionDenied() transitions to PermissionDenied from Recording state") {
                runTest(testDispatcher) {
                    coEvery { mockStartRecordingUseCase(TEST_USER_ID) } returns
                        ApiResult.Success(TEST_SESSION_ID)

                    val vm = buildViewModel()
                    vm.startRecording(TEST_USER_ID)
                    vm.uiState.value.shouldBeInstanceOf<MeetingUiState.Recording>()

                    vm.onPermissionDenied()
                    vm.uiState.value shouldBe MeetingUiState.PermissionDenied
                }
            }

            it("PermissionDenied is a terminal state reachable regardless of prior state") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()

                    // From Idle directly
                    vm.onPermissionDenied()
                    vm.uiState.value shouldBe MeetingUiState.PermissionDenied
                }
            }
        }

        // ─── 4. Summary contains extracted action items ────────────────────────────

        describe("Complete state contains extracted action items from summary text") {

            it("action items are parsed and included in Complete state when summary has action item lines") {
                runTest(testDispatcher) {
                    val summaryWithActionItems = """
                    This was a productive meeting.

                    - [Alice]: Review the design document by Friday
                    - [Bob]: Schedule a follow-up call with the client
                    - [Carol]: Update the project timeline in Jira
                    """.trimIndent()

                    coEvery { mockStartRecordingUseCase(TEST_USER_ID) } returns
                        ApiResult.Success(TEST_SESSION_ID)
                    coEvery { mockStopRecordingUseCase(TEST_SESSION_ID, any()) } returns
                        ApiResult.Success(Unit)
                    coEvery { mockGetSummaryUseCase(TEST_SESSION_ID) } returns
                        ApiResult.Success(summaryWithActionItems)

                    val vm = buildViewModel()
                    vm.startRecording(TEST_USER_ID)
                    vm.stopRecording("")
                    vm.fetchSummary()

                    val state = vm.uiState.value as MeetingUiState.Complete
                    state.actionItems shouldHaveSize 3
                    state.actionItems.shouldContainExactly(
                        ActionItem(assignee = "Alice", description = "Review the design document by Friday"),
                        ActionItem(assignee = "Bob", description = "Schedule a follow-up call with the client"),
                        ActionItem(assignee = "Carol", description = "Update the project timeline in Jira")
                    )
                }
            }

            it("Complete state has empty action items list when summary has no action item lines") {
                runTest(testDispatcher) {
                    val summaryNoActionItems = "Good meeting. No specific action items identified."

                    coEvery { mockStartRecordingUseCase(TEST_USER_ID) } returns
                        ApiResult.Success(TEST_SESSION_ID)
                    coEvery { mockStopRecordingUseCase(TEST_SESSION_ID, any()) } returns
                        ApiResult.Success(Unit)
                    coEvery { mockGetSummaryUseCase(TEST_SESSION_ID) } returns
                        ApiResult.Success(summaryNoActionItems)

                    val vm = buildViewModel()
                    vm.startRecording(TEST_USER_ID)
                    vm.stopRecording("")
                    vm.fetchSummary()

                    val state = vm.uiState.value as MeetingUiState.Complete
                    state.actionItems shouldHaveSize 0
                }
            }

            it("action items preserve assignee and description exactly") {
                runTest(testDispatcher) {
                    val summaryText = "- [John Smith]: Prepare the Q4 budget report"

                    coEvery { mockStartRecordingUseCase(TEST_USER_ID) } returns
                        ApiResult.Success(TEST_SESSION_ID)
                    coEvery { mockStopRecordingUseCase(TEST_SESSION_ID, any()) } returns
                        ApiResult.Success(Unit)
                    coEvery { mockGetSummaryUseCase(TEST_SESSION_ID) } returns
                        ApiResult.Success(summaryText)

                    val vm = buildViewModel()
                    vm.startRecording(TEST_USER_ID)
                    vm.stopRecording("")
                    vm.fetchSummary()

                    val state = vm.uiState.value as MeetingUiState.Complete
                    state.actionItems shouldHaveSize 1
                    state.actionItems[0].assignee shouldBe "John Smith"
                    state.actionItems[0].description shouldBe "Prepare the Q4 budget report"
                }
            }
        }

        // ─── 5. parseActionItems helper ───────────────────────────────────────────

        describe("parseActionItems helper") {

            it("parses single action item correctly") {
                val vm = buildViewModel()
                val items = vm.parseActionItems("- [Alice]: Do something important")
                items shouldHaveSize 1
                items[0] shouldBe ActionItem(assignee = "Alice", description = "Do something important")
            }

            it("parses multiple action items from multi-line text") {
                val vm = buildViewModel()
                val text = """
                Some preamble text.
                - [Alice]: First task
                - [Bob]: Second task
                More content.
                """.trimIndent()
                val items = vm.parseActionItems(text)
                items shouldHaveSize 2
                items[0] shouldBe ActionItem(assignee = "Alice", description = "First task")
                items[1] shouldBe ActionItem(assignee = "Bob", description = "Second task")
            }

            it("returns empty list when no action item lines are present") {
                val vm = buildViewModel()
                val items = vm.parseActionItems("No action items here.")
                items shouldHaveSize 0
            }

            it("does not match lines without the [Assignee]: pattern") {
                val vm = buildViewModel()
                val text = "- Alice: some task\n- Do something without assignee"
                val items = vm.parseActionItems(text)
                items shouldHaveSize 0
            }

            it("handles assignee names with spaces") {
                val vm = buildViewModel()
                val items = vm.parseActionItems("- [Jane Doe]: Write the report")
                items shouldHaveSize 1
                items[0].assignee shouldBe "Jane Doe"
            }
        }

        // ─── 6. Duration update ───────────────────────────────────────────────────

        describe("updateRecordingDuration") {

            it("updates durationSeconds while in Recording state") {
                runTest(testDispatcher) {
                    coEvery { mockStartRecordingUseCase(TEST_USER_ID) } returns
                        ApiResult.Success(TEST_SESSION_ID)

                    val vm = buildViewModel()
                    vm.startRecording(TEST_USER_ID)
                    vm.uiState.value.shouldBeInstanceOf<MeetingUiState.Recording>()

                    vm.updateRecordingDuration(42)

                    val state = vm.uiState.value as MeetingUiState.Recording
                    state.durationSeconds shouldBe 42
                }
            }

            it("does nothing when not in Recording state") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    // In Idle — should have no effect
                    vm.updateRecordingDuration(10)
                    vm.uiState.value.shouldBeInstanceOf<MeetingUiState.Idle>()
                }
            }
        }

        // ─── 7. Guard conditions (no-ops in wrong state) ──────────────────────────

        describe("guard conditions prevent invalid transitions") {

            it("stopRecording() does nothing when in Idle state") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.stopRecording("") // should be no-op
                    vm.uiState.value.shouldBeInstanceOf<MeetingUiState.Idle>()
                    coVerify(exactly = 0) { mockStopRecordingUseCase(any(), any()) }
                }
            }

            it("stopRecording() does nothing when in Processing state") {
                runTest(testDispatcher) {
                    coEvery { mockStartRecordingUseCase(TEST_USER_ID) } returns
                        ApiResult.Success(TEST_SESSION_ID)
                    coEvery { mockStopRecordingUseCase(TEST_SESSION_ID, any()) } returns
                        ApiResult.Success(Unit)

                    val vm = buildViewModel()
                    vm.startRecording(TEST_USER_ID)
                    vm.stopRecording("")
                    vm.uiState.value.shouldBeInstanceOf<MeetingUiState.Processing>()

                    // Calling stopRecording again in Processing state should be no-op
                    vm.stopRecording("")
                    vm.uiState.value.shouldBeInstanceOf<MeetingUiState.Processing>()
                    coVerify(exactly = 1) { mockStopRecordingUseCase(any(), any()) }
                }
            }

            it("fetchSummary() does nothing when in Idle state") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.fetchSummary() // should be no-op
                    vm.uiState.value.shouldBeInstanceOf<MeetingUiState.Idle>()
                    coVerify(exactly = 0) { mockGetSummaryUseCase(any()) }
                }
            }

            it("fetchSummary() does nothing when in Recording state") {
                runTest(testDispatcher) {
                    coEvery { mockStartRecordingUseCase(TEST_USER_ID) } returns
                        ApiResult.Success(TEST_SESSION_ID)

                    val vm = buildViewModel()
                    vm.startRecording(TEST_USER_ID)
                    vm.uiState.value.shouldBeInstanceOf<MeetingUiState.Recording>()

                    vm.fetchSummary() // should be no-op
                    vm.uiState.value.shouldBeInstanceOf<MeetingUiState.Recording>()
                    coVerify(exactly = 0) { mockGetSummaryUseCase(any()) }
                }
            }
        }

        // ─── 8. Error paths ───────────────────────────────────────────────────────

        describe("error paths") {

            it("startRecording() transitions to Error when use case returns ApiResult.Error") {
                runTest(testDispatcher) {
                    val errorMsg = "Failed to start recording session"
                    coEvery { mockStartRecordingUseCase(TEST_USER_ID) } returns
                        ApiResult.Error(DomainError.ServerError(errorMsg))

                    val vm = buildViewModel()
                    vm.startRecording(TEST_USER_ID)

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<MeetingUiState.Error>()
                    (state as MeetingUiState.Error).message shouldBe errorMsg
                }
            }

            it("startRecording() transitions to Error on NetworkUnavailable") {
                runTest(testDispatcher) {
                    coEvery { mockStartRecordingUseCase(TEST_USER_ID) } returns
                        ApiResult.NetworkUnavailable

                    val vm = buildViewModel()
                    vm.startRecording(TEST_USER_ID)

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<MeetingUiState.Error>()
                    (state as MeetingUiState.Error).message shouldContain "network"
                }
            }

            it("stopRecording() transitions to Error when use case returns ApiResult.Error") {
                runTest(testDispatcher) {
                    val errorMsg = "Failed to stop recording"
                    coEvery { mockStartRecordingUseCase(TEST_USER_ID) } returns
                        ApiResult.Success(TEST_SESSION_ID)
                    coEvery { mockStopRecordingUseCase(TEST_SESSION_ID, any()) } returns
                        ApiResult.Error(DomainError.NetworkError(errorMsg))

                    val vm = buildViewModel()
                    vm.startRecording(TEST_USER_ID)
                    vm.stopRecording("")

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<MeetingUiState.Error>()
                    (state as MeetingUiState.Error).message shouldBe errorMsg
                }
            }

            it("stopRecording() transitions to Error on NetworkUnavailable") {
                runTest(testDispatcher) {
                    coEvery { mockStartRecordingUseCase(TEST_USER_ID) } returns
                        ApiResult.Success(TEST_SESSION_ID)
                    coEvery { mockStopRecordingUseCase(TEST_SESSION_ID, any()) } returns
                        ApiResult.NetworkUnavailable

                    val vm = buildViewModel()
                    vm.startRecording(TEST_USER_ID)
                    vm.stopRecording("")

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<MeetingUiState.Error>()
                    (state as MeetingUiState.Error).message shouldContain "network"
                }
            }

            it("fetchSummary() transitions to Error when use case returns ApiResult.Error") {
                runTest(testDispatcher) {
                    val errorMsg = "Transcription failed on the server"
                    coEvery { mockStartRecordingUseCase(TEST_USER_ID) } returns
                        ApiResult.Success(TEST_SESSION_ID)
                    coEvery { mockStopRecordingUseCase(TEST_SESSION_ID, any()) } returns
                        ApiResult.Success(Unit)
                    coEvery { mockGetSummaryUseCase(TEST_SESSION_ID) } returns
                        ApiResult.Error(DomainError.ServerError(errorMsg))

                    val vm = buildViewModel()
                    vm.startRecording(TEST_USER_ID)
                    vm.stopRecording("")
                    vm.fetchSummary()

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<MeetingUiState.Error>()
                    (state as MeetingUiState.Error).message shouldBe errorMsg
                }
            }

            it("fetchSummary() transitions to Error on NetworkUnavailable") {
                runTest(testDispatcher) {
                    coEvery { mockStartRecordingUseCase(TEST_USER_ID) } returns
                        ApiResult.Success(TEST_SESSION_ID)
                    coEvery { mockStopRecordingUseCase(TEST_SESSION_ID, any()) } returns
                        ApiResult.Success(Unit)
                    coEvery { mockGetSummaryUseCase(TEST_SESSION_ID) } returns
                        ApiResult.NetworkUnavailable

                    val vm = buildViewModel()
                    vm.startRecording(TEST_USER_ID)
                    vm.stopRecording("")
                    vm.fetchSummary()

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<MeetingUiState.Error>()
                    (state as MeetingUiState.Error).message shouldContain "network"
                }
            }
        }

        // ─── 9. reset() ───────────────────────────────────────────────────────────

        describe("reset()") {

            it("from Error state transitions back to Idle") {
                runTest(testDispatcher) {
                    coEvery { mockStartRecordingUseCase(TEST_USER_ID) } returns
                        ApiResult.Error(DomainError.ServerError("server error"))

                    val vm = buildViewModel()
                    vm.startRecording(TEST_USER_ID)
                    vm.uiState.value.shouldBeInstanceOf<MeetingUiState.Error>()

                    vm.reset()
                    vm.uiState.value.shouldBeInstanceOf<MeetingUiState.Idle>()
                }
            }

            it("from PermissionDenied state transitions back to Idle") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.onPermissionDenied()
                    vm.uiState.value shouldBe MeetingUiState.PermissionDenied

                    vm.reset()
                    vm.uiState.value.shouldBeInstanceOf<MeetingUiState.Idle>()
                }
            }

            it("from Complete state transitions back to Idle") {
                runTest(testDispatcher) {
                    coEvery { mockStartRecordingUseCase(TEST_USER_ID) } returns
                        ApiResult.Success(TEST_SESSION_ID)
                    coEvery { mockStopRecordingUseCase(TEST_SESSION_ID, any()) } returns
                        ApiResult.Success(Unit)
                    coEvery { mockGetSummaryUseCase(TEST_SESSION_ID) } returns
                        ApiResult.Success("summary text")

                    val vm = buildViewModel()
                    vm.startRecording(TEST_USER_ID)
                    vm.stopRecording("")
                    vm.fetchSummary()
                    vm.uiState.value.shouldBeInstanceOf<MeetingUiState.Complete>()

                    vm.reset()
                    vm.uiState.value.shouldBeInstanceOf<MeetingUiState.Idle>()
                }
            }
        }
    })
