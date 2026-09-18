/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : ContrastRatioNewTokensTest.kt
 * Purpose    : WCAG AA contrast ratio tests for new AI-mode color tokens
 *              added in Phase 2.1 (Phase 14.10 / 12.7).
 *
 *              All foreground/background pairs must meet:
 *              - 4.5:1 for normal text (< 18 sp or < 14 sp bold)
 *              - 3.0:1 for large text  (>= 18 sp or >= 14 sp bold)
 *
 * Architecture : Unit test — no Android instrumentation required.
 * ============================================================
 */
package com.aiassistant.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastRatioNewTokensTest {

    // WCAG contrast ratio formula
    private fun contrastRatio(foreground: Color, background: Color): Double {
        val l1 = maxOf(foreground.luminance(), background.luminance())
        val l2 = minOf(foreground.luminance(), background.luminance())
        return (l1 + 0.05) / (l2 + 0.05)
    }

    private fun assertNormalTextContrast(fg: Color, bg: Color, label: String) {
        val ratio = contrastRatio(fg, bg)
        assertTrue(
            "WCAG AA normal-text contrast (4.5:1) failed for $label. Actual: ${"%.2f".format(ratio)}:1",
            ratio >= 4.5
        )
    }

    private fun assertLargeTextContrast(fg: Color, bg: Color, label: String) {
        val ratio = contrastRatio(fg, bg)
        assertTrue(
            "WCAG AA large-text contrast (3.0:1) failed for $label. Actual: ${"%.2f".format(ratio)}:1",
            ratio >= 3.0
        )
    }

    // ── Gemma mode tokens ────────────────────────────────────────────────────

    @Test
    fun gemmaOnContainer_light_meetsNormalContrast() {
        // onContainerLight on containerLight
        assertNormalTextContrast(
            fg = AppColors.gemmaOnContainerLight,
            bg = AppColors.gemmaContainerLight,
            label = "Gemma onContainer/Container (light)"
        )
    }

    @Test
    fun gemmaOnContainer_dark_meetsNormalContrast() {
        assertNormalTextContrast(
            fg = AppColors.gemmaOnContainerDark,
            bg = AppColors.gemmaContainerDark,
            label = "Gemma onContainer/Container (dark)"
        )
    }

    // ── Cloud mode tokens ────────────────────────────────────────────────────

    @Test
    fun cloudOnContainer_light_meetsNormalContrast() {
        assertNormalTextContrast(
            fg = AppColors.cloudOnContainerLight,
            bg = AppColors.cloudContainerLight,
            label = "Cloud onContainer/Container (light)"
        )
    }

    @Test
    fun cloudOnContainer_dark_meetsNormalContrast() {
        assertNormalTextContrast(
            fg = AppColors.cloudOnContainerDark,
            bg = AppColors.cloudContainerDark,
            label = "Cloud onContainer/Container (dark)"
        )
    }

    // ── RAG mode tokens ──────────────────────────────────────────────────────

    @Test
    fun ragOnContainer_light_meetsNormalContrast() {
        assertNormalTextContrast(
            fg = AppColors.ragOnContainerLight,
            bg = AppColors.ragContainerLight,
            label = "RAG onContainer/Container (light)"
        )
    }

    @Test
    fun ragOnContainer_dark_meetsNormalContrast() {
        assertNormalTextContrast(
            fg = AppColors.ragOnContainerDark,
            bg = AppColors.ragContainerDark,
            label = "RAG onContainer/Container (dark)"
        )
    }

    // ── Code block tokens ────────────────────────────────────────────────────

    @Test
    fun codeBlock_onSurface_meetsNormalContrast() {
        assertNormalTextContrast(
            fg = AppColors.codeBlockOnSurface,
            bg = AppColors.codeBlockBackground,
            label = "CodeBlock onSurface/Background"
        )
    }

    @Test
    fun codeBlock_label_meetsLargeTextContrast() {
        assertLargeTextContrast(
            fg = AppColors.codeBlockLineNumber,
            bg = AppColors.codeBlockBackground,
            label = "CodeBlock lineNumber/Background"
        )
    }
}
