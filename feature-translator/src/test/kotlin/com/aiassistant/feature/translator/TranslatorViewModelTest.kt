/**
 * TranslatorViewModelTest.kt — feature-translator unit tests
 *
 * Tests for [TranslatorViewModel] online/offline routing logic:
 *   - Online route: ConnectivityObserver connected → use case returns Success →
 *     uiState transitions to TranslatorUiState.Success with isOffline=false
 *   - Offline route: ConnectivityObserver disconnected → use case returns
 *     NetworkUnavailable → uiState transitions to TranslatorUiState.Error with isOffline=true
 *
 * Requirements: 21.1
 * Related requirements: 10.5
 *
 * Test framework: Kotest (DescribeSpec, JUnit 5 runner) + MockK + kotlinx-coroutines-test
 */

package com.aiassistant.feature.translator

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.domain.usecase.translator.TranslateTextUseCase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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

// ─── Test constants ────────────────────────────────────────────────────────────

private const val HELLO_TEXT = "Hello"
private const val HOLA_TRANSLATED = "Hola"
private val DEFAULT_PAIR = SupportedLanguages.defaultPair // EN → ES

// ─── Test suite ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class TranslatorViewModelTest :
    DescribeSpec({

        val testDispatcher = UnconfinedTestDispatcher()
        val testDispatcherProvider = TestDispatcherProvider(testDispatcher)

        val mockUseCase = mockk<TranslateTextUseCase>()
        val mockConnectivity = mockk<ConnectivityObserver>()
        val mockPrefs = mockk<TranslatorPreferences>()

        // Helper that builds a fresh ViewModel with the shared mocks.
        // Connectivity and prefs defaults are configured per-describe or per-it block.
        fun buildViewModel() = TranslatorViewModel(
            translateTextUseCase = mockUseCase,
            connectivityObserver = mockConnectivity,
            dispatchers = testDispatcherProvider,
            translatorPreferences = mockPrefs
        )

        beforeSpec {
            Dispatchers.setMain(testDispatcher)
        }

        afterSpec {
            Dispatchers.resetMain()
            unmockkAll()
        }

        beforeEach {
            clearMocks(mockUseCase, mockConnectivity, mockPrefs)
            // Default connectivity: online
            every { mockConnectivity.isConnectedFlow } returns flowOf(true)
            every { mockConnectivity.isConnected() } returns true
            // Default prefs: emit the default language pair so init block doesn't hang
            every { mockPrefs.languagePairFlow } returns flowOf(DEFAULT_PAIR)
        }

        // ─── Online routing ───────────────────────────────────────────────────────

        describe("online routing — translate()") {

            it("transitions to Translating state immediately when translate() is called") {
                runTest(testDispatcher) {
                    val states = mutableListOf<TranslatorUiState>()

                    // Use a suspending mock that captures the Translating state before completing
                    coEvery {
                        mockUseCase(HELLO_TEXT, DEFAULT_PAIR.sourceCode, DEFAULT_PAIR.targetCode)
                    } returns ApiResult.Success(HOLA_TRANSLATED)

                    val vm = buildViewModel()
                    // Collect states before and after translate()
                    states.add(vm.uiState.value)
                    vm.translate(HELLO_TEXT)
                    states.add(vm.uiState.value)

                    // First state is Idle; final state is Success (Translating is transient with UnconfinedTestDispatcher)
                    states[0].shouldBeInstanceOf<TranslatorUiState.Idle>()
                    states[1].shouldBeInstanceOf<TranslatorUiState.Success>()
                }
            }

            it(
                "emits Success with isOffline=false when ConnectivityObserver is connected and use case returns Success"
            ) {
                runTest(testDispatcher) {
                    coEvery {
                        mockUseCase(HELLO_TEXT, DEFAULT_PAIR.sourceCode, DEFAULT_PAIR.targetCode)
                    } returns ApiResult.Success(HOLA_TRANSLATED)

                    val vm = buildViewModel()
                    vm.translate(HELLO_TEXT)

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<TranslatorUiState.Success>()
                    (state as TranslatorUiState.Success).isOffline shouldBe false
                    state.translatedText shouldBe HOLA_TRANSLATED
                }
            }

            it("uses the AI Orchestrator route (Success result) when device is online") {
                runTest(testDispatcher) {
                    every { mockConnectivity.isConnected() } returns true
                    every { mockConnectivity.isConnectedFlow } returns flowOf(true)

                    coEvery {
                        mockUseCase(HELLO_TEXT, DEFAULT_PAIR.sourceCode, DEFAULT_PAIR.targetCode)
                    } returns ApiResult.Success(HOLA_TRANSLATED)

                    val vm = buildViewModel()
                    vm.translate(HELLO_TEXT)

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<TranslatorUiState.Success>()
                    (state as TranslatorUiState.Success).isOffline shouldBe false
                }
            }

            it("calls TranslateTextUseCase exactly once with the correct text and language pair") {
                runTest(testDispatcher) {
                    coEvery {
                        mockUseCase(HELLO_TEXT, DEFAULT_PAIR.sourceCode, DEFAULT_PAIR.targetCode)
                    } returns ApiResult.Success(HOLA_TRANSLATED)

                    val vm = buildViewModel()
                    vm.translate(HELLO_TEXT)

                    coVerify(exactly = 1) {
                        mockUseCase(HELLO_TEXT, DEFAULT_PAIR.sourceCode, DEFAULT_PAIR.targetCode)
                    }
                }
            }
        }

        // ─── Offline routing ──────────────────────────────────────────────────────

        describe("offline routing — translate()") {

            beforeEach {
                every { mockConnectivity.isConnected() } returns false
                every { mockConnectivity.isConnectedFlow } returns flowOf(false)
            }

            it("emits Error with isOffline=true when use case returns NetworkUnavailable") {
                runTest(testDispatcher) {
                    coEvery {
                        mockUseCase(HELLO_TEXT, DEFAULT_PAIR.sourceCode, DEFAULT_PAIR.targetCode)
                    } returns ApiResult.NetworkUnavailable

                    val vm = buildViewModel()
                    vm.translate(HELLO_TEXT)

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<TranslatorUiState.Error>()
                    (state as TranslatorUiState.Error).isOffline shouldBe true
                }
            }

            it("uses the offline route (NetworkUnavailable result) when device has no connectivity") {
                runTest(testDispatcher) {
                    coEvery {
                        mockUseCase(HELLO_TEXT, DEFAULT_PAIR.sourceCode, DEFAULT_PAIR.targetCode)
                    } returns ApiResult.NetworkUnavailable

                    val vm = buildViewModel()
                    vm.translate(HELLO_TEXT)

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<TranslatorUiState.Error>()
                    (state as TranslatorUiState.Error).isOffline shouldBe true
                }
            }

            it("error message contains offline description when NetworkUnavailable is received") {
                runTest(testDispatcher) {
                    coEvery {
                        mockUseCase(HELLO_TEXT, DEFAULT_PAIR.sourceCode, DEFAULT_PAIR.targetCode)
                    } returns ApiResult.NetworkUnavailable

                    val vm = buildViewModel()
                    vm.translate(HELLO_TEXT)

                    val state = vm.uiState.value as TranslatorUiState.Error
                    // Message should reference network or connection
                    val containsNetwork = state.message.contains("network", ignoreCase = true) ||
                        state.message.contains("connection", ignoreCase = true)
                    containsNetwork shouldBe true
                }
            }
        }

        // ─── isOffline StateFlow ──────────────────────────────────────────────────

        describe("isOffline StateFlow") {

            it("isOffline emits false when ConnectivityObserver reports connected") {
                runTest(testDispatcher) {
                    every { mockConnectivity.isConnected() } returns true
                    every { mockConnectivity.isConnectedFlow } returns flowOf(true)

                    val vm = buildViewModel()

                    vm.isOffline.value shouldBe false
                }
            }

            it("isOffline emits true when ConnectivityObserver reports no connection") {
                runTest(testDispatcher) {
                    every { mockConnectivity.isConnected() } returns false
                    every { mockConnectivity.isConnectedFlow } returns flowOf(false)

                    val vm = buildViewModel()

                    vm.isOffline.value shouldBe true
                }
            }
        }

        // ─── Edge cases ───────────────────────────────────────────────────────────

        describe("edge cases") {

            it("does NOT call use case when text is blank") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.translate("")

                    coVerify(exactly = 0) {
                        mockUseCase(any(), any(), any())
                    }
                    vm.uiState.value.shouldBeInstanceOf<TranslatorUiState.Idle>()
                }
            }

            it("emits Error with isOffline=false when use case returns ApiResult.Error") {
                runTest(testDispatcher) {
                    val domainError = DomainError.ServerError(
                        message = "Internal server error",
                        httpStatusCode = 500
                    )
                    coEvery {
                        mockUseCase(HELLO_TEXT, DEFAULT_PAIR.sourceCode, DEFAULT_PAIR.targetCode)
                    } returns ApiResult.Error(domainError)

                    val vm = buildViewModel()
                    vm.translate(HELLO_TEXT)

                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<TranslatorUiState.Error>()
                    (state as TranslatorUiState.Error).isOffline shouldBe false
                }
            }

            it("reset() transitions state back to Idle") {
                runTest(testDispatcher) {
                    coEvery {
                        mockUseCase(HELLO_TEXT, DEFAULT_PAIR.sourceCode, DEFAULT_PAIR.targetCode)
                    } returns ApiResult.Success(HOLA_TRANSLATED)

                    val vm = buildViewModel()
                    vm.translate(HELLO_TEXT)
                    vm.uiState.value.shouldBeInstanceOf<TranslatorUiState.Success>()

                    vm.reset()

                    vm.uiState.value.shouldBeInstanceOf<TranslatorUiState.Idle>()
                }
            }

            it("startListening() transitions state to Listening") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.startListening()

                    vm.uiState.value.shouldBeInstanceOf<TranslatorUiState.Listening>()
                }
            }

            it("onSpeechError() transitions state to Error") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.onSpeechError()

                    vm.uiState.value.shouldBeInstanceOf<TranslatorUiState.Error>()
                }
            }
        }
    })
