/**
 * CameraViewModelTest.kt — feature-camera module
 *
 * Purpose: Unit tests for [CameraViewModel] covering the four core scenarios:
 *   1. Resolution rejection — images exceeding 4096×4096 pixels produce an Error state.
 *   2. Progress indicator trigger — submitting an image transitions to Analyzing before result.
 *   3. Vision-incapable provider error — non-vision provider produces VisionUnsupported state.
 *   4. QR / barcode decode result — decoded payload is posted as a Message in the Conversation.
 *
 * Architecture: feature-camera — test layer (pure JVM, no Android framework required).
 * Dependencies: Kotest (DescribeSpec, JUnit 5 runner), MockK, kotlinx-coroutines-test
 *
 * Requirements: 21.1
 * Related requirements: 6.1, 6.2, 6.4, 6.5, 6.6
 *
 * Test framework: Kotest (DescribeSpec, JUnit 5 runner) + MockK
 */
package com.aiassistant.feature.camera

import android.net.Uri
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.Message
import com.aiassistant.domain.usecase.conversation.SendMessageUseCase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

// ─── Test helpers ─────────────────────────────────────────────────────────────

/** Returns a fake [Uri] whose [toString] is the given [value]. */
private fun fakeUri(value: String = "content://media/image/1"): Uri = mockk<Uri>().also {
    io.mockk.every { it.toString() } returns value
}

private fun testMessage(
    id: String = "msg-1",
    conversationId: String = "conv-1",
    content: String = "test content"
): Message = Message(
    id = id,
    conversationId = conversationId,
    role = "user",
    content = content,
    inputTokens = 0,
    outputTokens = 0,
    provider = "gpt-4o",
    syncStatus = "pending",
    createdAt = Instant.now()
)

// ─── Test dispatcher provider ─────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
private class TestDispatcherProvider(private val dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
    override val mainImmediate: CoroutineDispatcher = dispatcher
    override val unconfined: CoroutineDispatcher = dispatcher
}

// ─── CameraViewModelTest ──────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest :
    DescribeSpec({

        val testDispatcher = UnconfinedTestDispatcher()
        val testDispatcherProvider = TestDispatcherProvider(testDispatcher)

        val mockSendMessageUseCase = mockk<SendMessageUseCase>()

        fun buildViewModel(): CameraViewModel = CameraViewModel(
            sendMessageUseCase = mockSendMessageUseCase,
            dispatchers = testDispatcherProvider
        )

        beforeSpec {
            Dispatchers.setMain(testDispatcher)
        }

        afterSpec {
            Dispatchers.resetMain()
        }

        beforeEach {
            clearMocks(mockSendMessageUseCase)
        }

        // ─── 1. Initial state ─────────────────────────────────────────────────────

        describe("initial state") {

            it("starts in Idle state") {
                val vm = buildViewModel()
                vm.uiState.value.shouldBeInstanceOf<CameraUiState.Idle>()
            }
        }

        // ─── 2. Resolution rejection (Requirement 6.1 / 6.2) ─────────────────────
        //
        // THE AI_Assistant SHALL accept images with a maximum resolution of 4096×4096.
        // Images that exceed this limit must be rejected with an Error state.

        describe("onImageCaptured — resolution rejection") {

            it("transitions to ImageSelected when width and height are within 4096×4096") {
                val vm = buildViewModel()
                val uri = fakeUri()

                vm.onImageCaptured(uri, width = 1920, height = 1080)

                vm.uiState.value.shouldBeInstanceOf<CameraUiState.ImageSelected>()
            }

            it("accepts images at the exact 4096×4096 boundary") {
                val vm = buildViewModel()
                val uri = fakeUri()

                vm.onImageCaptured(uri, width = 4096, height = 4096)

                vm.uiState.value.shouldBeInstanceOf<CameraUiState.ImageSelected>()
            }

            it("accepts images where only one dimension equals 4096") {
                val vm = buildViewModel()

                vm.onImageCaptured(fakeUri(), width = 4096, height = 2000)
                vm.uiState.value.shouldBeInstanceOf<CameraUiState.ImageSelected>()

                val vm2 = buildViewModel()
                vm2.onImageCaptured(fakeUri(), width = 2000, height = 4096)
                vm2.uiState.value.shouldBeInstanceOf<CameraUiState.ImageSelected>()
            }

            it("transitions to Error when width exceeds 4096") {
                val vm = buildViewModel()

                vm.onImageCaptured(fakeUri(), width = 4097, height = 1080)

                val state = vm.uiState.value
                state.shouldBeInstanceOf<CameraUiState.Error>()
            }

            it("transitions to Error when height exceeds 4096") {
                val vm = buildViewModel()

                vm.onImageCaptured(fakeUri(), width = 1080, height = 4097)

                val state = vm.uiState.value
                state.shouldBeInstanceOf<CameraUiState.Error>()
            }

            it("transitions to Error when both dimensions exceed 4096") {
                val vm = buildViewModel()

                vm.onImageCaptured(fakeUri(), width = 8192, height = 8192)

                val state = vm.uiState.value
                state.shouldBeInstanceOf<CameraUiState.Error>()
            }

            it("error message mentions the maximum resolution constraint") {
                val vm = buildViewModel()

                vm.onImageCaptured(fakeUri(), width = 5000, height = 3000)

                val error = vm.uiState.value as CameraUiState.Error
                error.message.shouldContainIgnoringCase("4096")
            }

            it("stores the uri, width, and height in ImageSelected on success") {
                val vm = buildViewModel()
                val uri = fakeUri("content://media/external/image/42")

                vm.onImageCaptured(uri, width = 800, height = 600)

                val state = vm.uiState.value as CameraUiState.ImageSelected
                state.uri shouldBe uri
                state.width shouldBe 800
                state.height shouldBe 600
            }

            it("does NOT call SendMessageUseCase when resolution is rejected") {
                val vm = buildViewModel()

                vm.onImageCaptured(fakeUri(), width = 9999, height = 9999)

                coVerify(exactly = 0) { mockSendMessageUseCase(any(), any(), any()) }
            }
        }

        // ─── 3. Progress indicator trigger (Requirement 6.2) ─────────────────────
        //
        // WHEN an image is submitted, THE AI_Assistant SHALL display an analysis progress
        // indicator (i.e. enter the Analyzing state) immediately on submission.

        describe("submitForAnalysis — progress indicator trigger") {

            it("transitions to Analyzing immediately when a vision-capable provider is active") {
                runTest(testDispatcher) {
                    // Use a StandardTestDispatcher so we can capture the in-progress state
                    val scheduler = TestCoroutineScheduler()
                    val pausingDispatcher = StandardTestDispatcher(scheduler)
                    val pausingProvider = TestDispatcherProvider(pausingDispatcher)
                    Dispatchers.setMain(pausingDispatcher)

                    val vm = CameraViewModel(
                        sendMessageUseCase = mockSendMessageUseCase,
                        dispatchers = pausingProvider
                    )
                    vm.setConversationContext("conv-1", "gpt-4o")

                    // Suspend mid-coroutine so the use case never returns
                    coEvery { mockSendMessageUseCase(any(), any(), any()) } coAnswers {
                        kotlinx.coroutines.delay(Long.MAX_VALUE)
                        ApiResult.Success(testMessage())
                    }

                    vm.submitForAnalysis(fakeUri(), prompt = "Describe this image", provider = "gpt-4o")

                    // Before the coroutine's IO work resumes, state should be Analyzing
                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<CameraUiState.Analyzing>()

                    Dispatchers.setMain(testDispatcher)
                }
            }

            it("Analyzing state carries the submitted imageUri and prompt") {
                runTest(testDispatcher) {
                    val scheduler = TestCoroutineScheduler()
                    val pausingDispatcher = StandardTestDispatcher(scheduler)
                    Dispatchers.setMain(pausingDispatcher)

                    val vm = CameraViewModel(
                        sendMessageUseCase = mockSendMessageUseCase,
                        dispatchers = TestDispatcherProvider(pausingDispatcher)
                    )
                    vm.setConversationContext("conv-1", "gpt-4o")

                    val imageUri = fakeUri("content://media/image/99")
                    val userPrompt = "What is in this picture?"

                    coEvery { mockSendMessageUseCase(any(), any(), any()) } coAnswers {
                        kotlinx.coroutines.delay(Long.MAX_VALUE)
                        ApiResult.Success(testMessage())
                    }

                    vm.submitForAnalysis(imageUri, prompt = userPrompt, provider = "gpt-4o")

                    val state = vm.uiState.value as CameraUiState.Analyzing
                    state.imageUri shouldBe imageUri
                    state.prompt shouldBe userPrompt

                    Dispatchers.setMain(testDispatcher)
                }
            }

            it("transitions to VisionResult after SendMessageUseCase returns Success") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    val uri = fakeUri()
                    coEvery {
                        mockSendMessageUseCase(any(), any(), any())
                    } returns ApiResult.Success(testMessage(content = "A scenic mountain view."))

                    vm.setConversationContext("conv-1", "gpt-4o")
                    vm.submitForAnalysis(uri, prompt = "Describe", provider = "gpt-4o")

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<CameraUiState.VisionResult>()
                    (state as CameraUiState.VisionResult).aiResponse shouldBe "A scenic mountain view."
                }
            }

            it("transitions to Error when SendMessageUseCase returns ApiResult.Error") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    coEvery {
                        mockSendMessageUseCase(any(), any(), any())
                    } returns ApiResult.Error(DomainError.ServerError("Server failure"))

                    vm.setConversationContext("conv-1", "gpt-4o")
                    vm.submitForAnalysis(fakeUri(), prompt = "Test", provider = "gpt-4o")

                    vm.uiState.value.shouldBeInstanceOf<CameraUiState.Error>()
                }
            }

            it("transitions to Error with offline message when NetworkUnavailable") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    coEvery {
                        mockSendMessageUseCase(any(), any(), any())
                    } returns ApiResult.NetworkUnavailable

                    vm.setConversationContext("conv-1", "gpt-4o")
                    vm.submitForAnalysis(fakeUri(), prompt = "Test", provider = "gpt-4o")

                    val state = vm.uiState.value as CameraUiState.Error
                    state.message.lowercase().shouldContainIgnoringCase("network")
                }
            }
        }

        // ─── 4. Vision-incapable provider error (Requirement 6.6) ─────────────────
        //
        // IF the selected LLM_Provider does not support vision input, THEN THE AI_Orchestrator
        // SHALL return an error identifying the capability gap and suggesting a compatible provider.

        describe("submitForAnalysis — vision-incapable provider") {

            it("transitions to VisionUnsupported immediately for 'ollama' (non-vision provider)") {
                val vm = buildViewModel()

                vm.submitForAnalysis(fakeUri(), prompt = "Describe", provider = "ollama")

                vm.uiState.value.shouldBeInstanceOf<CameraUiState.VisionUnsupported>()
            }

            it("transitions to VisionUnsupported immediately for 'llama' (non-vision provider)") {
                val vm = buildViewModel()

                vm.submitForAnalysis(fakeUri(), prompt = "Describe", provider = "llama")

                vm.uiState.value.shouldBeInstanceOf<CameraUiState.VisionUnsupported>()
            }

            it("transitions to VisionUnsupported immediately for 'mistral' (non-vision provider)") {
                val vm = buildViewModel()

                vm.submitForAnalysis(fakeUri(), prompt = "Describe", provider = "mistral")

                vm.uiState.value.shouldBeInstanceOf<CameraUiState.VisionUnsupported>()
            }

            it("VisionUnsupported state carries the active provider name") {
                val vm = buildViewModel()

                vm.submitForAnalysis(fakeUri(), prompt = "Test", provider = "ollama")

                val state = vm.uiState.value as CameraUiState.VisionUnsupported
                state.activeProvider shouldBe "ollama"
            }

            it("VisionUnsupported state includes at least one suggested compatible provider") {
                val vm = buildViewModel()

                vm.submitForAnalysis(fakeUri(), prompt = "Test", provider = "llama")

                val state = vm.uiState.value as CameraUiState.VisionUnsupported
                state.suggestedProviders.isNotEmpty() shouldBe true
            }

            it("VisionUnsupported suggested providers contain known vision-capable names") {
                val vm = buildViewModel()

                vm.submitForAnalysis(fakeUri(), prompt = "Test", provider = "mistral")

                val state = vm.uiState.value as CameraUiState.VisionUnsupported
                // At least one suggestion must be from the known vision-capable set
                val hasKnownSuggestion = state.suggestedProviders.any { suggested ->
                    suggested in CameraViewModel.VISION_CAPABLE_PROVIDERS
                }
                hasKnownSuggestion shouldBe true
            }

            it("does NOT call SendMessageUseCase when provider lacks vision support") {
                val vm = buildViewModel()

                vm.submitForAnalysis(fakeUri(), prompt = "Test", provider = "ollama")

                coVerify(exactly = 0) { mockSendMessageUseCase(any(), any(), any()) }
            }

            it("does NOT transition to VisionUnsupported for 'gpt-4o' (vision-capable)") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    coEvery {
                        mockSendMessageUseCase(any(), any(), any())
                    } returns ApiResult.Success(testMessage())

                    vm.setConversationContext("conv-1", "gpt-4o")
                    vm.submitForAnalysis(fakeUri(), prompt = "Describe", provider = "gpt-4o")

                    vm.uiState.value.shouldBeInstanceOf<CameraUiState.VisionResult>()
                }
            }

            it("does NOT transition to VisionUnsupported for 'gemini-1.5-pro' (vision-capable)") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    coEvery {
                        mockSendMessageUseCase(any(), any(), any())
                    } returns ApiResult.Success(testMessage())

                    vm.setConversationContext("conv-1", "gemini-1.5-pro")
                    vm.submitForAnalysis(fakeUri(), prompt = "Describe", provider = "gemini-1.5-pro")

                    vm.uiState.value.shouldBeInstanceOf<CameraUiState.VisionResult>()
                }
            }

            it("does NOT transition to VisionUnsupported for 'claude-3-5-sonnet' (vision-capable)") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    coEvery {
                        mockSendMessageUseCase(any(), any(), any())
                    } returns ApiResult.Success(testMessage())

                    vm.setConversationContext("conv-1", "claude-3-5-sonnet")
                    vm.submitForAnalysis(fakeUri(), prompt = "Describe", provider = "claude-3-5-sonnet")

                    vm.uiState.value.shouldBeInstanceOf<CameraUiState.VisionResult>()
                }
            }
        }

        // ─── 5. onProviderVisionUnsupported — direct API ──────────────────────────

        describe("onProviderVisionUnsupported") {

            it("transitions to VisionUnsupported with the given provider and suggestions") {
                val vm = buildViewModel()
                val suggestions = listOf("gpt-4o", "gemini-1.5-pro")

                vm.onProviderVisionUnsupported(activeProvider = "ollama", suggestedProviders = suggestions)

                val state = vm.uiState.value as CameraUiState.VisionUnsupported
                state.activeProvider shouldBe "ollama"
                state.suggestedProviders shouldBe suggestions
            }

            it("can be called from any state") {
                val vm = buildViewModel()
                vm.startCapture()
                vm.uiState.value.shouldBeInstanceOf<CameraUiState.Capturing>()

                vm.onProviderVisionUnsupported("mistral", listOf("gpt-4o"))

                vm.uiState.value.shouldBeInstanceOf<CameraUiState.VisionUnsupported>()
            }
        }

        // ─── 6. QR / barcode decode result (Requirement 6.4 / 6.5) ──────────────
        //
        // THE AI_Assistant SHALL support barcode and QR code scanning and return the decoded
        // payload as a Message in the active Conversation (Requirement 6.5).

        describe("onBarcodeDetected — QR / barcode decode result") {

            it("transitions to BarcodeResult immediately with the decoded payload") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    coEvery {
                        mockSendMessageUseCase(any(), any(), any())
                    } returns ApiResult.Success(testMessage())

                    vm.onBarcodeDetected(
                        payload = "https://example.com",
                        format = "QR_CODE",
                        conversationId = "conv-1",
                        provider = "gpt-4o"
                    )

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<CameraUiState.BarcodeResult>()
                    (state as CameraUiState.BarcodeResult).payload shouldBe "https://example.com"
                }
            }

            it("BarcodeResult state carries the barcode format") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    coEvery {
                        mockSendMessageUseCase(any(), any(), any())
                    } returns ApiResult.Success(testMessage())

                    vm.onBarcodeDetected(
                        payload = "1234567890128",
                        format = "EAN_13",
                        conversationId = "conv-1",
                        provider = "gpt-4o"
                    )

                    val state = vm.uiState.value as CameraUiState.BarcodeResult
                    state.format shouldBe "EAN_13"
                }
            }

            it("calls SendMessageUseCase with the decoded payload posted to the conversation") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    coEvery {
                        mockSendMessageUseCase(any(), any(), any())
                    } returns ApiResult.Success(testMessage())

                    vm.onBarcodeDetected(
                        payload = "https://qr-code.example.com/data",
                        format = "QR_CODE",
                        conversationId = "conv-42",
                        provider = "gpt-4o"
                    )

                    // The use case must be invoked exactly once with the correct conversationId
                    coVerify(exactly = 1) {
                        mockSendMessageUseCase(
                            conversationId = "conv-42",
                            content = any(),
                            provider = "gpt-4o"
                        )
                    }
                }
            }

            it("message content includes the decoded payload text") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    var capturedContent = ""
                    coEvery {
                        mockSendMessageUseCase(
                            any(),
                            capture(
                                io.mockk.CapturingSlot<String>().also {
                                    coEvery { mockSendMessageUseCase(any(), any(), any()) } coAnswers {
                                        capturedContent = secondArg()
                                        ApiResult.Success(testMessage(content = secondArg()))
                                    }
                                }
                            ),
                            any()
                        )
                    } returns ApiResult.Success(testMessage())

                    vm.onBarcodeDetected(
                        payload = "SCAN_PAYLOAD_DATA",
                        format = "QR_CODE",
                        conversationId = "conv-1",
                        provider = "gpt-4o"
                    )

                    capturedContent.shouldContainIgnoringCase("SCAN_PAYLOAD_DATA")
                }
            }

            it("message content includes the barcode format label") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    var capturedContent = ""
                    coEvery { mockSendMessageUseCase(any(), any(), any()) } coAnswers {
                        capturedContent = secondArg()
                        ApiResult.Success(testMessage(content = secondArg()))
                    }

                    vm.onBarcodeDetected(
                        payload = "12345",
                        format = "QR_CODE",
                        conversationId = "conv-1",
                        provider = "gpt-4o"
                    )

                    capturedContent.shouldContainIgnoringCase("QR_CODE")
                }
            }

            it("works with DATA_MATRIX format") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    coEvery {
                        mockSendMessageUseCase(any(), any(), any())
                    } returns ApiResult.Success(testMessage())

                    vm.onBarcodeDetected(
                        payload = "DATA_MATRIX_CONTENT",
                        format = "DATA_MATRIX",
                        conversationId = "conv-1",
                        provider = "gpt-4o"
                    )

                    val state = vm.uiState.value as CameraUiState.BarcodeResult
                    state.payload shouldBe "DATA_MATRIX_CONTENT"
                    state.format shouldBe "DATA_MATRIX"
                }
            }
        }

        // ─── 7. State machine helpers ─────────────────────────────────────────────

        describe("requestPermission") {

            it("transitions from Idle to RequestingPermission") {
                val vm = buildViewModel()
                vm.requestPermission()
                vm.uiState.value.shouldBeInstanceOf<CameraUiState.RequestingPermission>()
            }

            it("does nothing when already past Idle state") {
                val vm = buildViewModel()
                vm.startCapture()
                vm.uiState.value.shouldBeInstanceOf<CameraUiState.Capturing>()

                vm.requestPermission() // should have no effect
                vm.uiState.value.shouldBeInstanceOf<CameraUiState.Capturing>()
            }
        }

        describe("onPermissionGranted") {

            it("transitions from RequestingPermission to Capturing") {
                val vm = buildViewModel()
                vm.requestPermission()
                vm.onPermissionGranted()
                vm.uiState.value.shouldBeInstanceOf<CameraUiState.Capturing>()
            }
        }

        describe("onPermissionDenied") {

            it("transitions to PermissionDenied from any state") {
                val vm = buildViewModel()
                vm.startCapture()
                vm.onPermissionDenied()
                vm.uiState.value.shouldBeInstanceOf<CameraUiState.PermissionDenied>()
            }
        }

        describe("startCapture") {

            it("transitions from Idle to Capturing") {
                val vm = buildViewModel()
                vm.startCapture()
                vm.uiState.value.shouldBeInstanceOf<CameraUiState.Capturing>()
            }
        }

        describe("reset") {

            it("transitions back to Idle from Error state") {
                val vm = buildViewModel()
                vm.onImageCaptured(fakeUri(), width = 9999, height = 9999) // triggers Error
                vm.uiState.value.shouldBeInstanceOf<CameraUiState.Error>()

                vm.reset()
                vm.uiState.value.shouldBeInstanceOf<CameraUiState.Idle>()
            }

            it("transitions back to Idle from VisionUnsupported state") {
                val vm = buildViewModel()
                vm.onProviderVisionUnsupported("ollama", listOf("gpt-4o"))
                vm.uiState.value.shouldBeInstanceOf<CameraUiState.VisionUnsupported>()

                vm.reset()
                vm.uiState.value.shouldBeInstanceOf<CameraUiState.Idle>()
            }

            it("transitions back to Idle from BarcodeResult state") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    coEvery { mockSendMessageUseCase(any(), any(), any()) } returns
                        ApiResult.Success(testMessage())

                    vm.onBarcodeDetected("data", "QR_CODE", "conv-1", "gpt-4o")
                    vm.uiState.value.shouldBeInstanceOf<CameraUiState.BarcodeResult>()

                    vm.reset()
                    vm.uiState.value.shouldBeInstanceOf<CameraUiState.Idle>()
                }
            }
        }

        // ─── 8. VISION_CAPABLE_PROVIDERS constant ────────────────────────────────

        describe("VISION_CAPABLE_PROVIDERS constant") {

            it("contains gpt-4o") {
                CameraViewModel.VISION_CAPABLE_PROVIDERS shouldContain "gpt-4o"
            }

            it("contains gemini-1.5-pro") {
                CameraViewModel.VISION_CAPABLE_PROVIDERS shouldContain "gemini-1.5-pro"
            }

            it("contains claude-3-5-sonnet") {
                CameraViewModel.VISION_CAPABLE_PROVIDERS shouldContain "claude-3-5-sonnet"
            }

            it("does NOT contain ollama") {
                (CameraViewModel.VISION_CAPABLE_PROVIDERS.contains("ollama")) shouldBe false
            }

            it("does NOT contain llama") {
                (CameraViewModel.VISION_CAPABLE_PROVIDERS.contains("llama")) shouldBe false
            }

            it("does NOT contain mistral") {
                (CameraViewModel.VISION_CAPABLE_PROVIDERS.contains("mistral")) shouldBe false
            }
        }

        // ─── 9. MAX_IMAGE_DIMENSION constant ─────────────────────────────────────

        describe("MAX_IMAGE_DIMENSION constant") {

            it("equals 4096") {
                CameraViewModel.MAX_IMAGE_DIMENSION shouldBe 4096
            }
        }
    })
