/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : CodeAnalysis.kt
 * Purpose    : CodeAnalysis — domain module component
 *
 * Architecture Layer : Domain
 * Pattern Used       : Kotlin Class
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
 * CodeAnalysis.kt
 *
 * Purpose: Domain entities for the code analysis feature, including supported languages,
 *          available AI actions, and request/result data models.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: None (pure Kotlin data classes and enums)
 *
 * Requirements: 12.1, 12.2, 12.3, 12.4, 12.6
 */

package com.aiassistant.domain.model

/**
 * Programming languages supported by the Code Assistant for syntax highlighting
 * and AI analysis (Requirement 12.1).
 */
enum class SupportedLanguage {
    /** Kotlin â€” primary Android development language. */
    KOTLIN,

    /** Java â€” classic Android and JVM development language. */
    JAVA,

    /** Python â€” general-purpose scripting and data science language. */
    PYTHON,

    /** JavaScript â€” web and Node.js scripting language. */
    JAVASCRIPT,

    /** C++ â€” systems and performance-critical programming language. */
    CPP,

    /** SQL â€” structured query language for relational databases. */
    SQL
}

/**
 * AI analysis actions available in the Code Assistant.
 */
enum class CodeAction {
    /**
     * Explain the submitted code: what it does, why it works that way,
     * and suggested improvements (Requirement 12.2).
     */
    EXPLAIN,

    /**
     * Fix bugs in the submitted code: return corrected code with inline
     * comments describing each change (Requirement 12.3).
     */
    FIX_BUG,

    /**
     * Generate a complete unit test suite for the submitted code in the
     * same language, following the Arrange-Act-Assert pattern (Requirement 12.4).
     */
    GENERATE_TESTS
}

/**
 * Request payload sent to the AI for code analysis.
 *
 * @param code     The source code submitted by the user.
 * @param language The [SupportedLanguage] of the submitted code.
 * @param action   The [CodeAction] the AI should perform on the code.
 */
data class CodeAnalysisRequest(val code: String, val language: SupportedLanguage, val action: CodeAction)

/**
 * Result returned by the AI after analysing the submitted code.
 *
 * @param languageId   The language identifier string from the AI response (e.g., "kotlin",
 *                     "python"). Used to select the correct syntax highlighting renderer
 *                     (Requirement 12.6).
 * @param originalCode The original code submitted by the user, preserved for reference.
 * @param action       The [CodeAction] that was performed.
 * @param content      The AI-generated result:
 *                     - For [CodeAction.EXPLAIN]: Markdown-formatted explanation.
 *                     - For [CodeAction.FIX_BUG]: Corrected code with inline comments.
 *                     - For [CodeAction.GENERATE_TESTS]: Complete test suite source code.
 */
data class CodeAnalysisResult(
    val languageId: String,
    val originalCode: String,
    val action: CodeAction,
    val content: String
)
