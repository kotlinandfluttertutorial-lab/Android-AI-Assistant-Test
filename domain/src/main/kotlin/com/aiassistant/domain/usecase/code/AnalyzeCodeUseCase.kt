/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AnalyzeCodeUseCase.kt
 * Purpose    : Encapsulates the 'AnalyzeCode' business operation
 *
 * Architecture Layer : Domain
 * Pattern Used       : Clean Architecture Use Case
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
 * Module     : domain
 * File       : AnalyzeCodeUseCase.kt
 * Purpose    : Encapsulates the 'AnalyzeCode' business operation
 *
 * Architecture Layer : Domain
 * Pattern Used       : Clean Architecture Use Case
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
 * AnalyzeCodeUseCase.kt
 *
 * Purpose: Submits a code analysis request to the AI Orchestrator via CodeRepository
 *          and returns the structured result for display in the CodeAnalysis screen.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), CodeRepository, CodeAnalysisRequest, CodeAnalysisResult
 *
 * Requirements: 12.1, 12.2, 12.3, 12.4, 12.6
 */
package com.aiassistant.domain.usecase.code

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.CodeAnalysisRequest
import com.aiassistant.domain.model.CodeAnalysisResult
import com.aiassistant.domain.repository.CodeRepository
import javax.inject.Inject

/**
 * Use case for submitting code to the AI Orchestrator for analysis.
 *
 * Supports three analysis actions:
 * - **Explain** (Req 12.2): Returns a Markdown-formatted explanation covering what the
 *   code does, why it is written that way, and suggested improvements.
 * - **Fix Bug** (Req 12.3): Returns corrected code with inline comments describing each
 *   change made.
 * - **Generate Tests** (Req 12.4): Returns a complete test suite in the same language
 *   following the Arrange-Act-Assert (AAA) pattern.
 *
 * The result includes a `languageId` field from the AI response that is used to select
 * the correct syntax highlighting renderer (Requirement 12.6).
 *
 * @param codeRepository Repository providing the AI code analysis operation.
 */
class AnalyzeCodeUseCase @Inject constructor(private val codeRepository: CodeRepository) {

    /**
     * Submits [request] to the AI Orchestrator and returns the analysis result.
     *
     * @param request The code analysis request containing the code snippet, language,
     *                and the desired analysis action.
     * @return [ApiResult.Success] with [CodeAnalysisResult] on success, containing the
     *         AI-generated content and `languageId` for syntax highlighting.
     */
    suspend operator fun invoke(request: CodeAnalysisRequest): ApiResult<CodeAnalysisResult> =
        codeRepository.analyzeCode(request)
}
