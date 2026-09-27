/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : CodeAgentAction.kt
 * Purpose    : Extended set of code actions supported by CodeAgent.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Enum value type
 *
 * Design Decision:
 *   The existing CodeAction enum (EXPLAIN, FIX_BUG, GENERATE_TESTS) maps
 *   directly to the three backend actions supported by /code/analyze.
 *   Phase 4 adds GENERATE, REFACTOR, and REVIEW.  These three new actions
 *   are handled by the backend's LLM call via action-specific prompts;
 *   the backend /code/analyze endpoint already accepts any Literal string
 *   so extending the action set is backward-compatible.
 *
 *   We introduce CodeAgentAction as a superset enum rather than changing
 *   the existing CodeAction enum to avoid breaking the CodeViewModel state
 *   machine and its existing test coverage.
 *
 * Dependencies: kotlinx.serialization
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.serialization.Serializable

/**
 * Full set of code operations CodeAgent can perform.
 *
 * The first three values map 1:1 to the existing [com.aiassistant.domain.model.CodeAction]
 * enum (EXPLAIN, FIX_BUG, GENERATE_TESTS) and use the same backend action string.
 * The last three are Phase 4 additions.
 *
 * @param apiValue  The lowercase string value sent to `POST /code/analyze` as `action`.
 */
@Serializable
enum class CodeAgentAction(val apiValue: String) {

    // ── Existing actions (backed by CodeAction + /code/analyze) ─────────────

    /** Produce a Markdown explanation with "What it does / How it works / Improvements" sections. */
    EXPLAIN("explain"),

    /** Find and fix bugs; annotate changed lines with `# FIX:` / `// FIX:` comments. */
    FIX_BUG("fix_bug"),

    /** Generate a complete runnable test file for the submitted code. */
    GENERATE_TESTS("generate_tests"),

    // ── Phase 4 additions ────────────────────────────────────────────────────

    /** Generate new code from a natural-language description (prompt in code field). */
    GENERATE("generate"),

    /** Improve code quality, readability, and structure without changing behaviour. */
    REFACTOR("refactor"),

    /** Perform a code review: identify issues, suggest improvements, rate quality. */
    REVIEW("review");

    companion object {
        /** Convert a raw string to a [CodeAgentAction]; defaults to [EXPLAIN] if unknown. */
        fun fromApiValue(value: String): CodeAgentAction =
            entries.firstOrNull { it.apiValue == value.lowercase() } ?: EXPLAIN
    }
}
