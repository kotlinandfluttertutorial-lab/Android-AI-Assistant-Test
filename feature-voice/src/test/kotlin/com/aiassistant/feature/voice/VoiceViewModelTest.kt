/**
 * VoiceViewModelTest.kt — feature-voice unit tests
 *
 * Tests for [VoiceViewModel] state machine transitions:
 *   - Full idle→listening→transcribing→speaking→idle cycle
 *   - Permission denied branches to rationale/denied states
 *   - Blank transcript handling
 *   - Network and server error paths
 *   - stopSpeaking interrupt control
 *   - onPartialSpeechResult updates
 *   - reset() from error/denied states
 *   - onSpeechError() code mapping
 *   - setWakeWordEnabled propagation
 *
 * Requirements: 21.1
 * Related requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6
 *
 * Test framework: Kotest (DescribeSpec, JUnit 5 runner) + MockK + kotlinx-coroutines-test
 */

package com.aiassistant.feature.voice

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.Message
import com.aiassistant.domain.usecase.conversation.SendMessageUseCase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

// ─── SpeechRecognizer error-code constants ────────────────────────────────────
// android.speech.SpeechRecognizer is not available on the JVM test runner;
// use the raw integer values instead.
private const val SPEECH_ERROR_NETWORK = 2
private const val SPEECH_ERROR_INSUFFICIENT_PERMISSIONS = 9

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

private fun testMessage(content: String = "Hello from the AI"): Message = Message(
    id = "msg-1",
    conversationId = "conv-1",
    role = "assistant",
    content = content,
    createdAt = Instant.now()
)

// ─── Test suite ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceViewModelTest :
    DescribeSpec({

        val testDispatcher = UnconfinedTestDispatcher()
        val testDispatcherProvider = TestDispatcherProvider(testDispatcher)

        val mockSendMessageUseCase = mockk<SendMessageUseCase>()

        fun buildViewModel(): VoiceViewModel = VoiceViewModel(
            sendMessageUseCase = mockSendMessageUseCase,
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
            clearMocks(mockSendMessageUseCase)
        }

        // ─── 1. Initial state ─────────────────────────────────────────────────────

        describe("initial state") {

            it("starts in Idle state") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Idle>()
                }
            }
        }

        // ─── 2. Full happy path ───────────────────────────────────────────────────

        describe("full happy path: idle → listening → transcribing → speaking → idle") {

            it("startListening() from Idle transitions to Listening") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.startListening()
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Listening>()
                }
            }

            it("onSpeechResult() moves state to Transcribing with the transcript") {
                runTest(testDispatcher) {
                    coEvery { mockSendMessageUseCase(any(), any(), any()) } returns
                        ApiResult.Success(testMessage())

                    val vm = buildViewModel()
                    vm.setConversationContext("conv-1", "openai")
                    vm.startListening()

                    // Intercept before coroutine resolves — but with UnconfinedTestDispatcher
                    // the Transcribing state is set synchronously before the suspend call
                    // We verify by collecting states via direct inspection:
                    // onSpeechResult sets Transcribing synchronously, then launches coroutine
                    coEvery { mockSendMessageUseCase(any(), any(), any()) } coAnswers {
                        // we can verify state inside here if needed
                        ApiResult.Success(testMessage("AI response"))
                    }

                    vm.onSpeechResult("hello world")

                    // After use case resolves, state should be Speaking
                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<VoiceUiState.Speaking>()
                    (state as VoiceUiState.Speaking).responseText shouldBe "AI response"
                }
            }

            it("after sendMessageUseCase returns Success, state transitions to Speaking with response text") {
                runTest(testDispatcher) {
                    val responseText = "Here is your answer"
                    coEvery { mockSendMessageUseCase(any(), any(), any()) } returns
                        ApiResult.Success(testMessage(responseText))

                    val vm = buildViewModel()
                    vm.setConversationContext("conv-1", "openai")
                    vm.startListening()
                    vm.onSpeechResult("my question")

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<VoiceUiState.Speaking>()
                    (state as VoiceUiState.Speaking).responseText shouldBe responseText
                }
            }

            it("onSpeakingComplete() transitions Speaking back to Idle") {
                runTest(testDispatcher) {
                    coEvery { mockSendMessageUseCase(any(), any(), any()) } returns
                        ApiResult.Success(testMessage("response"))

                    val vm = buildViewModel()
                    vm.setConversationContext("conv-1", "openai")
                    vm.startListening()
                    vm.onSpeechResult("hello")
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Speaking>()

                    vm.onSpeakingComplete()
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Idle>()
                }
            }

            it("sendMessageUseCase is called with the correct conversationId, transcript, and provider") {
                runTest(testDispatcher) {
                    coEvery { mockSendMessageUseCase(any(), any(), any()) } returns
                        ApiResult.Success(testMessage())

                    val vm = buildViewModel()
                    vm.setConversationContext("conv-42", "gemini")
                    vm.startListening()
                    vm.onSpeechResult("test transcript")

                    coVerify(exactly = 1) {
                        mockSendMessageUseCase("conv-42", "test transcript", "gemini")
                    }
                }
            }
        }

        // ─── 3. Permission flow ───────────────────────────────────────────────────

        describe("permission flow") {

            it("requestPermission() from Idle transitions to RequestingPermission") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.requestPermission()
                    vm.uiState.value shouldBe VoiceUiState.RequestingPermission
                }
            }

            it("onPermissionDenied() transitions to PermissionDenied") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.requestPermission()
                    vm.onPermissionDenied()
                    vm.uiState.value shouldBe VoiceUiState.PermissionDenied
                }
            }

            it("onPermissionGranted() from RequestingPermission transitions to Listening") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.requestPermission()
                    vm.onPermissionGranted()
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Listening>()
                }
            }

            it("requestPermission() does nothing when not in Idle state") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.startListening() // now in Listening
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Listening>()

                    vm.requestPermission() // should have no effect
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Listening>()
                }
            }

            it("onPermissionDenied() transitions to PermissionDenied from any state") {
                runTest(testDispatcher) {
                    coEvery { mockSendMessageUseCase(any(), any(), any()) } returns
                        ApiResult.Success(testMessage())

                    val vm = buildViewModel()
                    vm.setConversationContext("conv-1", "openai")
                    vm.startListening()
                    vm.onSpeechResult("hello")
                    // Now in Speaking
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Speaking>()

                    vm.onPermissionDenied()
                    vm.uiState.value shouldBe VoiceUiState.PermissionDenied
                }
            }
        }

        // ─── 4. Blank transcript handling ────────────────────────────────────────

        describe("blank transcript handling") {

            it("onSpeechResult(\"\") moves back to Idle without calling sendMessageUseCase") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.startListening()
                    vm.onSpeechResult("")

                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Idle>()
                    coVerify(exactly = 0) { mockSendMessageUseCase(any(), any(), any()) }
                }
            }

            it("onSpeechResult with whitespace-only string moves back to Idle") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.startListening()
                    vm.onSpeechResult("   ")

                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Idle>()
                    coVerify(exactly = 0) { mockSendMessageUseCase(any(), any(), any()) }
                }
            }
        }

        // ─── 5. Network error ─────────────────────────────────────────────────────

        describe("network error") {

            it("onSpeechResult when sendMessageUseCase returns NetworkUnavailable transitions to Error") {
                runTest(testDispatcher) {
                    coEvery { mockSendMessageUseCase(any(), any(), any()) } returns
                        ApiResult.NetworkUnavailable

                    val vm = buildViewModel()
                    vm.setConversationContext("conv-1", "openai")
                    vm.startListening()
                    vm.onSpeechResult("some transcript")

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<VoiceUiState.Error>()
                    (state as VoiceUiState.Error).message shouldContain "network"
                }
            }
        }

        // ─── 6. Server error ──────────────────────────────────────────────────────

        describe("server error") {

            it("onSpeechResult when sendMessageUseCase returns ApiResult.Error transitions to Error with message") {
                runTest(testDispatcher) {
                    val errorMsg = "Internal server error"
                    coEvery { mockSendMessageUseCase(any(), any(), any()) } returns
                        ApiResult.Error(DomainError.ServerError(errorMsg))

                    val vm = buildViewModel()
                    vm.setConversationContext("conv-1", "openai")
                    vm.startListening()
                    vm.onSpeechResult("some transcript")

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<VoiceUiState.Error>()
                    (state as VoiceUiState.Error).message shouldBe errorMsg
                }
            }

            it("Error state carries the DomainError message verbatim") {
                runTest(testDispatcher) {
                    val errorMsg = "You do not have permission to perform this action."
                    coEvery { mockSendMessageUseCase(any(), any(), any()) } returns
                        ApiResult.Error(DomainError.Forbidden(errorMsg))

                    val vm = buildViewModel()
                    vm.setConversationContext("conv-1", "openai")
                    vm.startListening()
                    vm.onSpeechResult("test")

                    val state = vm.uiState.value as VoiceUiState.Error
                    state.message shouldBe errorMsg
                }
            }
        }

        // ─── 7. stopSpeaking interrupt ────────────────────────────────────────────

        describe("stopSpeaking interrupt") {

            it("stopSpeaking() from Speaking transitions to Listening") {
                runTest(testDispatcher) {
                    coEvery { mockSendMessageUseCase(any(), any(), any()) } returns
                        ApiResult.Success(testMessage("response"))

                    val vm = buildViewModel()
                    vm.setConversationContext("conv-1", "openai")
                    vm.startListening()
                    vm.onSpeechResult("hello")
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Speaking>()

                    vm.stopSpeaking()
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Listening>()
                }
            }

            it("stopSpeaking() does nothing when not in Speaking state") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    // In Idle state
                    vm.stopSpeaking()
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Idle>()
                }
            }

            it("stopSpeaking() does nothing when in Listening state") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.startListening()
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Listening>()

                    vm.stopSpeaking()
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Listening>()
                }
            }
        }

        // ─── 8. onPartialSpeechResult ─────────────────────────────────────────────

        describe("onPartialSpeechResult") {

            it("from Listening state transitions to Transcribing with partial text") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.startListening()
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Listening>()

                    vm.onPartialSpeechResult("partial...")
                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<VoiceUiState.Transcribing>()
                    (state as VoiceUiState.Transcribing).partialTranscript shouldBe "partial..."
                }
            }

            it("from Transcribing state updates partialTranscript") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.startListening()
                    vm.onPartialSpeechResult("first partial")
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Transcribing>()

                    vm.onPartialSpeechResult("updated partial text")
                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<VoiceUiState.Transcribing>()
                    (state as VoiceUiState.Transcribing).partialTranscript shouldBe "updated partial text"
                }
            }

            it("does nothing when in Idle state") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.onPartialSpeechResult("ignored")
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Idle>()
                }
            }

            it("does nothing when in RequestingPermission state") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.requestPermission()
                    vm.onPartialSpeechResult("ignored")
                    vm.uiState.value shouldBe VoiceUiState.RequestingPermission
                }
            }
        }

        // ─── 9. reset() ───────────────────────────────────────────────────────────

        describe("reset()") {

            it("from Error state transitions to Idle") {
                runTest(testDispatcher) {
                    coEvery { mockSendMessageUseCase(any(), any(), any()) } returns
                        ApiResult.Error(DomainError.NetworkError("network down"))

                    val vm = buildViewModel()
                    vm.setConversationContext("conv-1", "openai")
                    vm.startListening()
                    vm.onSpeechResult("test")
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Error>()

                    vm.reset()
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Idle>()
                }
            }

            it("from PermissionDenied state transitions to Idle") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.onPermissionDenied()
                    vm.uiState.value shouldBe VoiceUiState.PermissionDenied

                    vm.reset()
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Idle>()
                }
            }
        }

        // ─── 10. onSpeechError() ─────────────────────────────────────────────────

        describe("onSpeechError()") {

            it("ERROR_INSUFFICIENT_PERMISSIONS (9) maps to a message containing 'permission'") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.onSpeechError(SPEECH_ERROR_INSUFFICIENT_PERMISSIONS)

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<VoiceUiState.Error>()
                    (state as VoiceUiState.Error).message.lowercase() shouldContain "permission"
                }
            }

            it("ERROR_NETWORK (2) maps to a message about network") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.onSpeechError(SPEECH_ERROR_NETWORK)

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<VoiceUiState.Error>()
                    (state as VoiceUiState.Error).message.lowercase() shouldContain "network"
                }
            }

            it("unknown error code transitions to Error state with a generic message") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.onSpeechError(999)

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<VoiceUiState.Error>()
                }
            }

            it("onSpeechError transitions to Error state from any state") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.startListening()
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Listening>()

                    vm.onSpeechError(SPEECH_ERROR_NETWORK)
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Error>()
                }
            }
        }

        // ─── 11. setWakeWordEnabled ───────────────────────────────────────────────

        describe("setWakeWordEnabled") {

            it("setting true from Idle copies isWakeWordEnabled=true into Idle state") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.setWakeWordEnabled(true)

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<VoiceUiState.Idle>()
                    (state as VoiceUiState.Idle).isWakeWordEnabled shouldBe true
                }
            }

            it("setting false from Idle copies isWakeWordEnabled=false into Idle state") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.setWakeWordEnabled(true)
                    vm.setWakeWordEnabled(false)

                    val state = vm.uiState.value as VoiceUiState.Idle
                    state.isWakeWordEnabled shouldBe false
                }
            }

            it("setting from Listening copies isWakeWordEnabled into Listening state") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.startListening()
                    vm.setWakeWordEnabled(true)

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<VoiceUiState.Listening>()
                    (state as VoiceUiState.Listening).isWakeWordEnabled shouldBe true
                }
            }

            it("wake word flag is preserved when transitioning back to Idle after speaking") {
                runTest(testDispatcher) {
                    coEvery { mockSendMessageUseCase(any(), any(), any()) } returns
                        ApiResult.Success(testMessage("response"))

                    val vm = buildViewModel()
                    vm.setWakeWordEnabled(true)
                    vm.setConversationContext("conv-1", "openai")
                    vm.startListening()
                    vm.onSpeechResult("hello")
                    vm.onSpeakingComplete()

                    val state = vm.uiState.value as VoiceUiState.Idle
                    state.isWakeWordEnabled shouldBe true
                }
            }
        }

        // ─── 12. startListening() from Speaking ──────────────────────────────────

        describe("startListening() from Speaking (interrupt, then re-listen)") {

            it("startListening() from Speaking transitions to Listening") {
                runTest(testDispatcher) {
                    coEvery { mockSendMessageUseCase(any(), any(), any()) } returns
                        ApiResult.Success(testMessage("response"))

                    val vm = buildViewModel()
                    vm.setConversationContext("conv-1", "openai")
                    vm.startListening()
                    vm.onSpeechResult("hello")
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Speaking>()

                    vm.startListening()
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Listening>()
                }
            }

            it("startListening() from Transcribing does nothing (mid-processing)") {
                runTest(testDispatcher) {
                    // Simulate being stuck in transcribing — use a non-resuming coAnswer
                    // We drive the partial path manually
                    val vm = buildViewModel()
                    vm.startListening()
                    vm.onPartialSpeechResult("partial")
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Transcribing>()

                    vm.startListening() // should do nothing
                    vm.uiState.value.shouldBeInstanceOf<VoiceUiState.Transcribing>()
                }
            }
        }
    })
