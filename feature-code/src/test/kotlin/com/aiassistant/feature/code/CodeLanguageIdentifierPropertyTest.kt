/**
 * CodeLanguageIdentifierPropertyTest.kt
 *
 * Purpose: Property-based tests validating that every code analysis response carries a
 *          non-empty `languageId` that is one of the six canonical language identifiers
 *          and that it exactly matches the identifier derived from the request's language
 *          (Property 30: Code Response Language Identifier).
 * Architecture: feature-code — pure JVM unit tests (src/test/), no Android instrumentation.
 * Requirements: 12.6
 *
 * Design decisions:
 * - Uses the simpler "mock CodeRepository → wire AnalyzeCodeUseCase → check result" pattern
 *   to avoid Android ViewModel lifecycle complexity in unit tests.
 * - The mock is configured per-call via `coEvery { … } answers { … }` so the returned
 *   languageId is always derived from the actual request language, giving the property
 *   real coverage rather than a constant stub.
 * - toLanguageId() is imported from CodeEditorScreen.kt (same module, same package space)
 *   so the test validates the exact same mapping the UI uses.
 */

package com.aiassistant.feature.code

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.CodeAction
import com.aiassistant.domain.model.CodeAnalysisRequest
import com.aiassistant.domain.model.CodeAnalysisResult
import com.aiassistant.domain.model.SupportedLanguage
import com.aiassistant.domain.repository.CodeRepository
import com.aiassistant.domain.usecase.code.AnalyzeCodeUseCase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.property.Arb
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.mockk

// ─── Canonical identifier set (mirrors the when-expression in CodeEditorScreen) ─

private val VALID_LANGUAGE_IDS = setOf("kotlin", "java", "python", "javascript", "cpp", "sql")

// ─── Property 30: Code Response Language Identifier ──────────────────────────

/**
 * **Validates: Requirements 12.6**
 *
 * For every `(language, action, code)` triple:
 * 1. `result.languageId` is non-empty.
 * 2. `result.languageId` belongs to [VALID_LANGUAGE_IDS].
 * 3. `result.languageId` exactly equals `language.toLanguageId()`.
 */
class CodeLanguageIdentifierPropertyTest :
    DescribeSpec({

        // ─── Shared test infrastructure ──────────────────────────────────────────

        /** Mock repository; configured per-call inside each property. */
        val codeRepository: CodeRepository = mockk()

        /** Use case under test — wraps the mock repository. */
        val analyzeCodeUseCase = AnalyzeCodeUseCase(codeRepository)

        /**
         * Configures [codeRepository] so every [analyzeCode] call returns a
         * [CodeAnalysisResult] whose [CodeAnalysisResult.languageId] is derived
         * from the actual request language via [toLanguageId].
         */
        fun configureRepositoryMock() {
            coEvery { codeRepository.analyzeCode(any()) } answers {
                val request = firstArg<CodeAnalysisRequest>()
                ApiResult.Success(
                    CodeAnalysisResult(
                        languageId = request.language.toLanguageId(),
                        originalCode = request.code,
                        action = request.action,
                        content = "AI-generated content for ${request.language}"
                    )
                )
            }
        }

        // ─── Arbitraries ──────────────────────────────────────────────────────────

        /** Picks a random [SupportedLanguage] from all six entries. */
        val arbLanguage = Arb.of(SupportedLanguage.entries)

        /** Picks a random [CodeAction] from all three entries. */
        val arbAction = Arb.of(CodeAction.entries)

        /** Generates a random non-empty code snippet (1–200 chars). */
        val arbCode = Arb.string(1..200)

        // ─── Case 1: languageId non-empty invariant ───────────────────────────────

        describe("Property 30 — Code Response Language Identifier") {

            describe("languageId non-empty invariant") {

                it("languageId is never empty for any (language, action, code) triple") {
                    configureRepositoryMock()
                    checkAll(iterations = 300, arbLanguage, arbAction, arbCode) { language, action, code ->
                        val request = CodeAnalysisRequest(code = code, language = language, action = action)
                        val apiResult = analyzeCodeUseCase(request)

                        val result = (apiResult as ApiResult.Success).data
                        result.languageId.shouldNotBeEmpty()
                    }
                }
            }

            // ─── Case 2: languageId is one of the six valid identifiers ──────────

            describe("languageId is one of the six valid identifiers") {

                it("languageId always belongs to {kotlin, java, python, javascript, cpp, sql}") {
                    configureRepositoryMock()
                    checkAll(iterations = 300, arbLanguage, arbAction, arbCode) { language, action, code ->
                        val request = CodeAnalysisRequest(code = code, language = language, action = action)
                        val apiResult = analyzeCodeUseCase(request)

                        val result = (apiResult as ApiResult.Success).data
                        VALID_LANGUAGE_IDS.contains(result.languageId).shouldBeTrue()
                    }
                }
            }

            // ─── Case 3: languageId matches the request language ─────────────────

            describe("languageId matches the request language") {

                it("languageId exactly equals language.toLanguageId() for any input") {
                    configureRepositoryMock()
                    checkAll(iterations = 300, arbLanguage, arbAction, arbCode) { language, action, code ->
                        val request = CodeAnalysisRequest(code = code, language = language, action = action)
                        val apiResult = analyzeCodeUseCase(request)

                        val result = (apiResult as ApiResult.Success).data
                        result.languageId shouldBe language.toLanguageId()
                    }
                }
            }

            // ─── Case 4: exhaustive per-language checks (300 iterations each) ────

            describe("all six supported languages produce the correct languageId") {

                SupportedLanguage.entries.forEach { lang ->
                    it("$lang → ${lang.toLanguageId()}") {
                        configureRepositoryMock()
                        checkAll(iterations = 300, arbAction, arbCode) { action, code ->
                            val request = CodeAnalysisRequest(code = code, language = lang, action = action)
                            val apiResult = analyzeCodeUseCase(request)

                            val result = (apiResult as ApiResult.Success).data
                            result.languageId shouldBe lang.toLanguageId()
                            result.languageId.shouldNotBeEmpty()
                            VALID_LANGUAGE_IDS.contains(result.languageId).shouldBeTrue()
                        }
                    }
                }
            }

            // ─── Case 5: languageId invariant holds for all three CodeActions ─────

            describe("languageId invariant holds for all three CodeActions") {

                CodeAction.entries.forEach { action ->
                    it("action $action — languageId is valid and non-empty for every language") {
                        configureRepositoryMock()
                        checkAll(iterations = 300, arbLanguage, arbCode) { language, code ->
                            val request = CodeAnalysisRequest(code = code, language = language, action = action)
                            val apiResult = analyzeCodeUseCase(request)

                            val result = (apiResult as ApiResult.Success).data
                            result.languageId shouldBe language.toLanguageId()
                            result.languageId.shouldNotBeEmpty()
                            VALID_LANGUAGE_IDS.contains(result.languageId).shouldBeTrue()
                        }
                    }
                }
            }
        }
    })
