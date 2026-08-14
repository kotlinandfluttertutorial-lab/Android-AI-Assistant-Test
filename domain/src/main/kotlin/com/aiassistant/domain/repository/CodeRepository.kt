/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : CodeRepository.kt
 * Purpose    : Domain contract defining data access operations for Code entities
 *
 * Architecture Layer : Domain
 * Pattern Used       : Repository Interface
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
 * File       : CodeRepository.kt
 * Purpose    : Domain contract defining data access operations for Code entities
 *
 * Architecture Layer : Domain
 * Pattern Used       : Repository Interface
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
 * CodeRepository.kt
 *
 * Purpose: Domain-layer repository interface for code analysis operations.
 *          Implemented in the data module; injected into use cases at runtime.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), domain model (CodeAnalysisRequest, CodeAnalysisResult)
 *
 * Requirements: 12.1, 12.2, 12.3, 12.4, 12.6
 */
package com.aiassistant.domain.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.CodeAnalysisRequest
import com.aiassistant.domain.model.CodeAnalysisResult

/**
 * Contract for code analysis operations between the domain and data layers.
 *
 * The data module provides a concrete implementation backed by the AI Orchestrator
 * backend. The AI analyses submitted code and returns a structured [CodeAnalysisResult]
 * containing the `languageId` field used for syntax highlighting (Requirement 12.6).
 */
interface CodeRepository {

    /**
     * Submits code for AI analysis and returns the structured result.
     *
     * The AI Orchestrator performs the requested [CodeAnalysisRequest.action]:
     * - [com.aiassistant.domain.model.CodeAction.EXPLAIN] â€” structured explanation with
     *   what/why/improvements sections (Requirement 12.2).
     * - [com.aiassistant.domain.model.CodeAction.FIX_BUG] â€” corrected code with inline
     *   change comments (Requirement 12.3).
     * - [com.aiassistant.domain.model.CodeAction.GENERATE_TESTS] â€” complete test suite
     *   in the same language following the AAA pattern (Requirement 12.4).
     *
     * @param request The code analysis request containing code, language, and action.
     * @return [ApiResult.Success] with [CodeAnalysisResult] on success, or an error variant.
     */
    suspend fun analyzeCode(request: CodeAnalysisRequest): ApiResult<CodeAnalysisResult>
}
