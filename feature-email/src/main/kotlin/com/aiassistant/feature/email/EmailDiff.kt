/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-email
 * File       : EmailDiff.kt
 * Purpose    : EmailDiff — feature-email module component
 *
 * Architecture Layer : Feature (feature-email)
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

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-email
 * File       : EmailDiff.kt
 * Purpose    : EmailDiff — feature-email module component
 *
 * Architecture Layer : Feature (feature-email)
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
 * EmailDiff.kt
 *
 * Purpose: Word-level diff computation for the inline grammar correction view.
 * Architecture: feature-email â€” pure Kotlin utility, no Android dependencies.
 * Requirements: 14.5
 */
package com.aiassistant.feature.email

/**
 * Represents the type of change for a word-level diff span.
 *
 * - [UNCHANGED]: word present in both original and corrected text.
 * - [ADDED]:     word only in corrected text (new word or replacement).
 * - [REMOVED]:   word only in original text (deleted or replaced).
 */
enum class DiffType { UNCHANGED, ADDED, REMOVED }

/**
 * A single span in the word-level diff output.
 *
 * @param text The word or token for this span.
 * @param type The change classification ([DiffType]).
 */
data class DiffSpan(val text: String, val type: DiffType)

/**
 * Computes a word-level diff between [original] and [corrected] texts.
 *
 * Uses LCS (Longest Common Subsequence) at the word level. Returns a list of
 * [DiffSpan] where:
 * - [DiffType.UNCHANGED]: word present in both
 * - [DiffType.REMOVED]: word only in original
 * - [DiffType.ADDED]: word only in corrected
 *
 * Empty or blank inputs produce an empty list.
 *
 * @param original  The original draft email text.
 * @param corrected The grammar-corrected email text.
 * @return Ordered list of [DiffSpan] representing the diff.
 */
fun computeWordDiff(original: String, corrected: String): List<DiffSpan> {
    val origWords = original.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val corrWords = corrected.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    if (origWords.isEmpty() && corrWords.isEmpty()) return emptyList()

    val m = origWords.size
    val n = corrWords.size

    // Build LCS table
    val dp = Array(m + 1) { IntArray(n + 1) }
    for (i in 1..m) {
        for (j in 1..n) {
            dp[i][j] = if (origWords[i - 1] == corrWords[j - 1]) {
                dp[i - 1][j - 1] + 1
            } else {
                maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
    }

    // Backtrack to build diff spans
    val spans = mutableListOf<DiffSpan>()
    var i = m
    var j = n
    while (i > 0 || j > 0) {
        when {
            i > 0 && j > 0 && origWords[i - 1] == corrWords[j - 1] -> {
                spans.add(DiffSpan(origWords[i - 1], DiffType.UNCHANGED))
                i--
                j--
            }
            j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j]) -> {
                spans.add(DiffSpan(corrWords[j - 1], DiffType.ADDED))
                j--
            }
            else -> {
                spans.add(DiffSpan(origWords[i - 1], DiffType.REMOVED))
                i--
            }
        }
    }
    return spans.reversed()
}
