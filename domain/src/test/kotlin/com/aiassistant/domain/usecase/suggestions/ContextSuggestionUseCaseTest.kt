/**
 * ContextSuggestionUseCaseTest.kt — domain module unit tests
 *
 * Tests for context suggestion use cases:
 *   - GetContextSuggestionsUseCase: privacy mode, global toggle, rate-gate, result clamping,
 *     error propagation
 *   - DismissSuggestionUseCase: dismiss/query/clear lifecycle
 *
 * Requirements: 33.1, 33.4, 33.5, 33.7, 33.8
 *
 * Test framework: Kotest (DescribeSpec, JUnit 5 runner) + MockK for ContextSuggestionRepository
 */

package com.aiassistant.domain.usecase.suggestions

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DefaultDispatcherProvider
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.ContextSuggestion
import com.aiassistant.domain.model.ScreenContext
import com.aiassistant.domain.model.SuggestionType
import com.aiassistant.domain.model.TargetScreenType
import com.aiassistant.domain.repository.ContextSuggestionRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

// ─── Shared test fixtures ──────────────────────────────────────────────────────

private const val SCREEN_ID_A = "note-123"
private const val SCREEN_ID_B = "note-456"

private val NOTE_CONTEXT_A = ScreenContext.NoteContext(
    noteContent = "Meeting notes for Q2 planning session.",
    screenInstanceId = SCREEN_ID_A
)

private val NOTE_CONTEXT_B = ScreenContext.NoteContext(
    noteContent = "Personal journal entry.",
    screenInstanceId = SCREEN_ID_B
)

private fun makeSuggestion(id: String = "sug-1", type: SuggestionType = SuggestionType.SUMMARIZE): ContextSuggestion =
    ContextSuggestion(
        id = id,
        type = type,
        displayText = "Summarize this note",
        preFillText = "Please summarize the following note:",
        targetScreenType = TargetScreenType.NOTE
    )

private val SAMPLE_SUGGESTIONS = listOf(
    makeSuggestion("sug-1", SuggestionType.SUMMARIZE),
    makeSuggestion("sug-2", SuggestionType.EXPAND),
    makeSuggestion("sug-3", SuggestionType.ADD_ACTION_ITEMS)
)

private val FOUR_SUGGESTIONS = SAMPLE_SUGGESTIONS + makeSuggestion("sug-4", SuggestionType.DRAFT_AGENDA)

// ─── GetContextSuggestionsUseCase ──────────────────────────────────────────────

class GetContextSuggestionsUseCaseTest :
    DescribeSpec({
        val repository = mockk<ContextSuggestionRepository>()
        val dispatchers = DefaultDispatcherProvider()

        fun makeUseCase() = GetContextSuggestionsUseCase(repository, dispatchers)

        beforeEach { clearMocks(repository) }

        describe("GetContextSuggestionsUseCase") {

            describe("privacy mode guard") {
                it("returns empty list immediately when privacy mode is enabled") {
                    val useCase = makeUseCase()
                    val result = useCase(NOTE_CONTEXT_A, isPrivacyModeEnabled = true, isSuggestionsEnabled = true)
                    result.shouldBeInstanceOf<ApiResult.Success<List<ContextSuggestion>>>()
                    (result as ApiResult.Success).data shouldBe emptyList()
                }
                it("does NOT call repository when privacy mode is enabled") {
                    val useCase = makeUseCase()
                    useCase(NOTE_CONTEXT_A, isPrivacyModeEnabled = true, isSuggestionsEnabled = true)
                    coVerify(exactly = 0) { repository.getSuggestions(any()) }
                }
            }

            describe("global suggestions toggle guard") {
                it("returns empty list immediately when suggestions globally disabled") {
                    val useCase = makeUseCase()
                    val result = useCase(NOTE_CONTEXT_A, isPrivacyModeEnabled = false, isSuggestionsEnabled = false)
                    result.shouldBeInstanceOf<ApiResult.Success<List<ContextSuggestion>>>()
                    (result as ApiResult.Success).data shouldBe emptyList()
                }
                it("does NOT call repository when suggestions globally disabled") {
                    val useCase = makeUseCase()
                    useCase(NOTE_CONTEXT_A, isPrivacyModeEnabled = false, isSuggestionsEnabled = false)
                    coVerify(exactly = 0) { repository.getSuggestions(any()) }
                }
            }

            describe("rate gate") {
                it("allows first call and returns repository results") {
                    val useCase = makeUseCase()
                    coEvery { repository.getSuggestions(NOTE_CONTEXT_A) } returns ApiResult.Success(SAMPLE_SUGGESTIONS)
                    val result = useCase(NOTE_CONTEXT_A, isPrivacyModeEnabled = false, isSuggestionsEnabled = true)
                    result.shouldBeInstanceOf<ApiResult.Success<List<ContextSuggestion>>>()
                    (result as ApiResult.Success).data shouldBe SAMPLE_SUGGESTIONS
                }
                it("blocks second call within 5 seconds — returns empty list without repository call") {
                    val useCase = makeUseCase()
                    coEvery { repository.getSuggestions(NOTE_CONTEXT_A) } returns ApiResult.Success(SAMPLE_SUGGESTIONS)
                    // First call — allowed
                    useCase(NOTE_CONTEXT_A, isPrivacyModeEnabled = false, isSuggestionsEnabled = true)
                    // Second call immediately after — should be gated
                    val secondResult =
                        useCase(NOTE_CONTEXT_A, isPrivacyModeEnabled = false, isSuggestionsEnabled = true)
                    secondResult.shouldBeInstanceOf<ApiResult.Success<List<ContextSuggestion>>>()
                    (secondResult as ApiResult.Success).data shouldBe emptyList()
                    // Repository should have been called exactly once (for the first call only)
                    coVerify(exactly = 1) { repository.getSuggestions(NOTE_CONTEXT_A) }
                }
                it("allows call after 5+ seconds have passed") {
                    val useCase = makeUseCase()
                    coEvery { repository.getSuggestions(NOTE_CONTEXT_A) } returns ApiResult.Success(SAMPLE_SUGGESTIONS)
                    // Simulate first call by manually setting an old timestamp
                    useCase.resetRateGate()
                    // Populate rate-gate map with an old timestamp by using reflection on the companion constant
                    // Instead, we call once, then reset and call again to test the allow path.
                    useCase(NOTE_CONTEXT_A, isPrivacyModeEnabled = false, isSuggestionsEnabled = true)
                    // Reset gate to simulate time passage
                    useCase.resetRateGate()
                    val result = useCase(NOTE_CONTEXT_A, isPrivacyModeEnabled = false, isSuggestionsEnabled = true)
                    result.shouldBeInstanceOf<ApiResult.Success<List<ContextSuggestion>>>()
                    (result as ApiResult.Success).data shouldBe SAMPLE_SUGGESTIONS
                    coVerify(exactly = 2) { repository.getSuggestions(NOTE_CONTEXT_A) }
                }
                it("different screen instances have independent rate gates") {
                    val useCase = makeUseCase()
                    coEvery { repository.getSuggestions(NOTE_CONTEXT_A) } returns ApiResult.Success(SAMPLE_SUGGESTIONS)
                    coEvery { repository.getSuggestions(NOTE_CONTEXT_B) } returns ApiResult.Success(SAMPLE_SUGGESTIONS)
                    // Call screen A — allowed
                    useCase(NOTE_CONTEXT_A, isPrivacyModeEnabled = false, isSuggestionsEnabled = true)
                    // Call screen B immediately — should also be allowed (different screen instance)
                    val resultB = useCase(NOTE_CONTEXT_B, isPrivacyModeEnabled = false, isSuggestionsEnabled = true)
                    resultB.shouldBeInstanceOf<ApiResult.Success<List<ContextSuggestion>>>()
                    (resultB as ApiResult.Success).data shouldBe SAMPLE_SUGGESTIONS
                    coVerify(exactly = 1) { repository.getSuggestions(NOTE_CONTEXT_A) }
                    coVerify(exactly = 1) { repository.getSuggestions(NOTE_CONTEXT_B) }
                }
            }

            describe("result validation and clamping") {
                it("returns list of up to 3 suggestions from repository") {
                    val useCase = makeUseCase()
                    coEvery { repository.getSuggestions(NOTE_CONTEXT_A) } returns ApiResult.Success(SAMPLE_SUGGESTIONS)
                    val result = useCase(NOTE_CONTEXT_A, isPrivacyModeEnabled = false, isSuggestionsEnabled = true)
                    (result as ApiResult.Success).data.size shouldBe 3
                    result.data shouldBe SAMPLE_SUGGESTIONS
                }
                it("clamps list to 3 items when repository returns more than 3") {
                    val useCase = makeUseCase()
                    coEvery { repository.getSuggestions(NOTE_CONTEXT_A) } returns ApiResult.Success(FOUR_SUGGESTIONS)
                    val result = useCase(NOTE_CONTEXT_A, isPrivacyModeEnabled = false, isSuggestionsEnabled = true)
                    (result as ApiResult.Success).data.size shouldBe 3
                    result.data shouldBe FOUR_SUGGESTIONS.take(3)
                }
                it("returns empty Success when repository returns 0 items") {
                    val useCase = makeUseCase()
                    coEvery { repository.getSuggestions(NOTE_CONTEXT_A) } returns ApiResult.Success(emptyList())
                    val result = useCase(NOTE_CONTEXT_A, isPrivacyModeEnabled = false, isSuggestionsEnabled = true)
                    result.shouldBeInstanceOf<ApiResult.Success<List<ContextSuggestion>>>()
                    (result as ApiResult.Success).data shouldBe emptyList()
                }
            }

            describe("error propagation") {
                it("propagates NetworkUnavailable from repository") {
                    val useCase = makeUseCase()
                    coEvery { repository.getSuggestions(NOTE_CONTEXT_A) } returns ApiResult.NetworkUnavailable
                    val result = useCase(NOTE_CONTEXT_A, isPrivacyModeEnabled = false, isSuggestionsEnabled = true)
                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
                it("propagates ServerError from repository") {
                    val useCase = makeUseCase()
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { repository.getSuggestions(NOTE_CONTEXT_A) } returns ApiResult.Error(error)
                    val result = useCase(NOTE_CONTEXT_A, isPrivacyModeEnabled = false, isSuggestionsEnabled = true)
                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── DismissSuggestionUseCase ──────────────────────────────────────────────────

class DismissSuggestionUseCaseTest :
    DescribeSpec({
        fun makeUseCase() = DismissSuggestionUseCase()

        describe("DismissSuggestionUseCase") {

            describe("isDismissed before any dismiss") {
                it("returns false when no suggestion has been dismissed for a screen instance") {
                    val useCase = makeUseCase()
                    useCase.isDismissed(SCREEN_ID_A, SuggestionType.SUMMARIZE) shouldBe false
                }
                it("returns false for every type when screen has no dismissals") {
                    val useCase = makeUseCase()
                    SuggestionType.entries.forEach { type ->
                        useCase.isDismissed(SCREEN_ID_A, type) shouldBe false
                    }
                }
            }

            describe("isDismissed after invoke") {
                it("returns true after dismissing a suggestion type") {
                    val useCase = makeUseCase()
                    useCase.invoke(SCREEN_ID_A, SuggestionType.SUMMARIZE)
                    useCase.isDismissed(SCREEN_ID_A, SuggestionType.SUMMARIZE) shouldBe true
                }
                it("multiple dismissals on the same screen instance are tracked independently") {
                    val useCase = makeUseCase()
                    useCase.invoke(SCREEN_ID_A, SuggestionType.SUMMARIZE)
                    useCase.invoke(SCREEN_ID_A, SuggestionType.EXPAND)
                    useCase.isDismissed(SCREEN_ID_A, SuggestionType.SUMMARIZE) shouldBe true
                    useCase.isDismissed(SCREEN_ID_A, SuggestionType.EXPAND) shouldBe true
                }
            }

            describe("dismissing one type does not affect other types on the same screen instance") {
                it("non-dismissed type remains false after dismissing another type") {
                    val useCase = makeUseCase()
                    useCase.invoke(SCREEN_ID_A, SuggestionType.SUMMARIZE)
                    useCase.isDismissed(SCREEN_ID_A, SuggestionType.EXPAND) shouldBe false
                    useCase.isDismissed(SCREEN_ID_A, SuggestionType.ADD_ACTION_ITEMS) shouldBe false
                }
            }

            describe("screen instance isolation") {
                it("dismissing on screen A does not affect screen B") {
                    val useCase = makeUseCase()
                    useCase.invoke(SCREEN_ID_A, SuggestionType.SUMMARIZE)
                    useCase.isDismissed(SCREEN_ID_B, SuggestionType.SUMMARIZE) shouldBe false
                }
                it("screen A and screen B can each have their own independent dismissals") {
                    val useCase = makeUseCase()
                    useCase.invoke(SCREEN_ID_A, SuggestionType.SUMMARIZE)
                    useCase.invoke(SCREEN_ID_B, SuggestionType.EXPAND)
                    useCase.isDismissed(SCREEN_ID_A, SuggestionType.SUMMARIZE) shouldBe true
                    useCase.isDismissed(SCREEN_ID_A, SuggestionType.EXPAND) shouldBe false
                    useCase.isDismissed(SCREEN_ID_B, SuggestionType.SUMMARIZE) shouldBe false
                    useCase.isDismissed(SCREEN_ID_B, SuggestionType.EXPAND) shouldBe true
                }
            }

            describe("clearSession") {
                it("resets all dismissed state across all screen instances") {
                    val useCase = makeUseCase()
                    useCase.invoke(SCREEN_ID_A, SuggestionType.SUMMARIZE)
                    useCase.invoke(SCREEN_ID_B, SuggestionType.EXPAND)
                    useCase.clearSession()
                    useCase.isDismissed(SCREEN_ID_A, SuggestionType.SUMMARIZE) shouldBe false
                    useCase.isDismissed(SCREEN_ID_B, SuggestionType.EXPAND) shouldBe false
                }
                it("allows dismissals to be re-added after clearSession") {
                    val useCase = makeUseCase()
                    useCase.invoke(SCREEN_ID_A, SuggestionType.SUMMARIZE)
                    useCase.clearSession()
                    useCase.invoke(SCREEN_ID_A, SuggestionType.SUMMARIZE)
                    useCase.isDismissed(SCREEN_ID_A, SuggestionType.SUMMARIZE) shouldBe true
                }
            }
        }
    })
