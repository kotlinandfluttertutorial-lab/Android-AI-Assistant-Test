/**
 * TranslateTextUseCaseTest.kt — domain module unit tests
 *
 * Tests for translation use cases:
 *   - [TranslateTextUseCase] — validates text, sourceLanguage, targetLanguage not blank and
 *                              source != target; trims all inputs before delegating
 *
 * Requirements: 21.1
 * Related requirements: 10.5
 *
 * Test framework: Kotest (DescribeSpec, JUnit 5 runner) + MockK for TranslationRepository mocking
 */

package com.aiassistant.domain.usecase.translator

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.repository.TranslationRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

// ─── Shared test fixtures ──────────────────────────────────────────────────────

private const val VALID_TEXT = "Hello, world!"
private const val VALID_SOURCE = "en"
private const val VALID_TARGET = "fr"
private const val TRANSLATED_TEXT = "Bonjour, monde!"

// ─── TranslateTextUseCase ─────────────────────────────────────────────────────

class TranslateTextUseCaseTest :
    DescribeSpec({

        val translationRepository = mockk<TranslationRepository>()
        val translateTextUseCase = TranslateTextUseCase(translationRepository)

        beforeEach {
            clearMocks(translationRepository)
        }

        describe("TranslateTextUseCase") {

            describe("successful translation") {

                it("returns Success with translated text when all inputs are valid") {
                    coEvery {
                        translationRepository.translateText(VALID_TEXT, VALID_SOURCE, VALID_TARGET)
                    } returns ApiResult.Success(TRANSLATED_TEXT)

                    val result = translateTextUseCase(VALID_TEXT, VALID_SOURCE, VALID_TARGET)

                    result.shouldBeInstanceOf<ApiResult.Success<String>>()
                    (result as ApiResult.Success<String>).data shouldBe TRANSLATED_TEXT
                }

                it("delegates to repository exactly once with correct arguments") {
                    coEvery {
                        translationRepository.translateText(VALID_TEXT, VALID_SOURCE, VALID_TARGET)
                    } returns ApiResult.Success(TRANSLATED_TEXT)

                    translateTextUseCase(VALID_TEXT, VALID_SOURCE, VALID_TARGET)

                    coVerify(exactly = 1) {
                        translationRepository.translateText(VALID_TEXT, VALID_SOURCE, VALID_TARGET)
                    }
                }
            }

            describe("input trimming") {

                it("trims whitespace from all inputs before passing to repository") {
                    coEvery {
                        translationRepository.translateText(VALID_TEXT, VALID_SOURCE, VALID_TARGET)
                    } returns ApiResult.Success(TRANSLATED_TEXT)

                    translateTextUseCase("  $VALID_TEXT  ", "  $VALID_SOURCE  ", "  $VALID_TARGET  ")

                    coVerify(exactly = 1) {
                        translationRepository.translateText(VALID_TEXT, VALID_SOURCE, VALID_TARGET)
                    }
                }
            }

            describe("text validation") {

                it("returns ValidationError when text is blank") {
                    val result = translateTextUseCase("", VALID_SOURCE, VALID_TARGET)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error
                    error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("returns ValidationError when text is only whitespace") {
                    val result = translateTextUseCase("   ", VALID_SOURCE, VALID_TARGET)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("text ValidationError contains 'text' in fields map") {
                    val result = translateTextUseCase("", VALID_SOURCE, VALID_TARGET)

                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey TranslateTextUseCase.FIELD_TEXT
                }

                it("does NOT call repository when text is blank") {
                    translateTextUseCase("", VALID_SOURCE, VALID_TARGET)

                    coVerify(exactly = 0) { translationRepository.translateText(any(), any(), any()) }
                }
            }

            describe("sourceLanguage validation") {

                it("returns ValidationError when sourceLanguage is blank") {
                    val result = translateTextUseCase(VALID_TEXT, "", VALID_TARGET)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error
                    error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("sourceLanguage ValidationError contains 'sourceLanguage' in fields map") {
                    val result = translateTextUseCase(VALID_TEXT, "", VALID_TARGET)

                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey TranslateTextUseCase.FIELD_SOURCE
                }

                it("does NOT call repository when sourceLanguage is blank") {
                    translateTextUseCase(VALID_TEXT, "", VALID_TARGET)

                    coVerify(exactly = 0) { translationRepository.translateText(any(), any(), any()) }
                }
            }

            describe("targetLanguage validation") {

                it("returns ValidationError when targetLanguage is blank") {
                    val result = translateTextUseCase(VALID_TEXT, VALID_SOURCE, "")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error
                    error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("targetLanguage ValidationError contains 'targetLanguage' in fields map") {
                    val result = translateTextUseCase(VALID_TEXT, VALID_SOURCE, "")

                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey TranslateTextUseCase.FIELD_TARGET
                }

                it("does NOT call repository when targetLanguage is blank") {
                    translateTextUseCase(VALID_TEXT, VALID_SOURCE, "")

                    coVerify(exactly = 0) { translationRepository.translateText(any(), any(), any()) }
                }
            }

            describe("same-language validation") {

                it("returns ValidationError when source and target language are identical") {
                    val result = translateTextUseCase(VALID_TEXT, "en", "en")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error
                    error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("same-language ValidationError contains 'targetLanguage' in fields map") {
                    val result = translateTextUseCase(VALID_TEXT, "en", "en")

                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey TranslateTextUseCase.FIELD_TARGET
                }

                it("returns ValidationError when source and target differ only by case") {
                    val result = translateTextUseCase(VALID_TEXT, "EN", "en")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("does NOT call repository when source equals target language") {
                    translateTextUseCase(VALID_TEXT, "fr", "fr")

                    coVerify(exactly = 0) { translationRepository.translateText(any(), any(), any()) }
                }
            }

            describe("validation order") {

                it("text error wins over sourceLanguage error when both are blank") {
                    val result = translateTextUseCase("", "", VALID_TARGET)

                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey TranslateTextUseCase.FIELD_TEXT
                }

                it("sourceLanguage error wins over targetLanguage error when both are blank") {
                    val result = translateTextUseCase(VALID_TEXT, "", "")

                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey TranslateTextUseCase.FIELD_SOURCE
                }

                it("targetLanguage blank error wins over same-language error") {
                    val result = translateTextUseCase(VALID_TEXT, "en", "")

                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey TranslateTextUseCase.FIELD_TARGET
                }
            }

            describe("error propagation") {

                it("propagates NetworkUnavailable from repository") {
                    coEvery {
                        translationRepository.translateText(any(), any(), any())
                    } returns ApiResult.NetworkUnavailable

                    val result = translateTextUseCase(VALID_TEXT, VALID_SOURCE, VALID_TARGET)

                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }

                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery {
                        translationRepository.translateText(any(), any(), any())
                    } returns ApiResult.Error(error)

                    val result = translateTextUseCase(VALID_TEXT, VALID_SOURCE, VALID_TARGET)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })
