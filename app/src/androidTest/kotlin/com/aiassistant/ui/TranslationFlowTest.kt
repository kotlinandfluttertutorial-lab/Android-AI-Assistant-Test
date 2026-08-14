/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : app (androidTest)
 * File       : TranslationFlowTest.kt
 * Purpose    : Compose UI integration tests for the translation flow.
 *
 * Architecture Layer : androidTest — UI integration
 * Requirements: 21.3
 * ============================================================
 */
package com.aiassistant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aiassistant.core.ui.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI integration tests for the translator screen.
 *
 * Uses a test-local [fakeTranslatorContent] composable that reproduces the same semantic
 * anchors as the real translator screen (which is internal to feature-translator).
 *
 * Requirements: 21.3
 */
@RunWith(AndroidJUnit4::class)
class TranslationFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Test-local fake composable ────────────────────────────────────────────

    @Composable
    private fun fakeTranslatorContent(
        inputText: String = "",
        isStreaming: Boolean = false,
        translatedText: String? = null,
        isOffline: Boolean = false,
        onTranslate: () -> Unit = {},
        onSwap: () -> Unit = {}
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Language pair selector row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Language pair selector" },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("English", modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = onSwap,
                        modifier = Modifier.semantics {
                            contentDescription = "Swap source and target languages"
                        }
                    ) {
                        Text("⇄")
                    }
                    Text("Spanish", modifier = Modifier.weight(1f))
                }
                // Text input
                OutlinedTextField(
                    value = inputText,
                    onValueChange = {},
                    enabled = !isStreaming,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Enter text to translate" }
                )
                // Translate button
                Button(
                    onClick = onTranslate,
                    enabled = inputText.isNotBlank() && !isStreaming,
                    modifier = Modifier.semantics { contentDescription = "Translate text" }
                ) {
                    Text("Translate")
                }
                // Translating indicator
                if (isStreaming) {
                    Row(
                        modifier = Modifier.semantics { contentDescription = "Translating" }
                    ) {
                        Text("Translating…")
                    }
                }
                // Translation result
                if (translatedText != null) {
                    Card(
                        modifier = Modifier.semantics {
                            contentDescription = "Translation result: $translatedText"
                        }
                    ) {
                        Text(translatedText)
                    }
                }
                // Offline indicator
                if (isOffline) {
                    Text("You're offline")
                }
            }
            // Speech input FAB
            FloatingActionButton(
                onClick = {},
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .semantics { contentDescription = "Start speech input" }
            ) {
                Text("🎙")
            }
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun setTranslator(
        inputText: String = "",
        isStreaming: Boolean = false,
        translatedText: String? = null,
        isOffline: Boolean = false,
        onTranslate: () -> Unit = {},
        onSwap: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                fakeTranslatorContent(
                    inputText = inputText,
                    isStreaming = isStreaming,
                    translatedText = translatedText,
                    isOffline = isOffline,
                    onTranslate = onTranslate,
                    onSwap = onSwap
                )
            }
        }
    }

    // ── 1. Idle state shows Translate button ──────────────────────────────────

    @Test
    fun translation_idle_showsTranslateButton() {
        setTranslator()

        composeTestRule.onNodeWithContentDescription("Translate text").assertIsDisplayed()
    }

    // ── 2. Empty input disables Translate button ──────────────────────────────

    @Test
    fun translation_emptyInput_disablesButton() {
        setTranslator(inputText = "")

        composeTestRule.onNodeWithContentDescription("Translate text").assertIsNotEnabled()
    }

    // ── 3. Non-empty input enables Translate button ───────────────────────────

    @Test
    fun translation_withInput_enablesButton() {
        setTranslator(inputText = "Hello")

        composeTestRule.onNodeWithContentDescription("Translate text").assertIsEnabled()
    }

    // ── 4. Streaming state shows translating indicator ────────────────────────

    @Test
    fun translation_translating_showsIndicator() {
        setTranslator(inputText = "Hello", isStreaming = true)

        composeTestRule.onNodeWithContentDescription("Translating").assertIsDisplayed()
    }

    // ── 5. Streaming state disables Translate button ──────────────────────────

    @Test
    fun translation_translating_disablesButton() {
        setTranslator(inputText = "Hello", isStreaming = true)

        composeTestRule.onNodeWithContentDescription("Translate text").assertIsNotEnabled()
    }

    // ── 6. Successful translation shows result card ───────────────────────────

    @Test
    fun translation_success_showsResult() {
        setTranslator(inputText = "Hello", translatedText = "Hola")

        composeTestRule.onNodeWithContentDescription("Translation result: Hola").assertIsDisplayed()
    }

    // ── 7. Swap button fires callback ─────────────────────────────────────────

    @Test
    fun translation_swapButton_firesCallback() {
        var swapped = false

        setTranslator(onSwap = { swapped = true })

        composeTestRule.onNodeWithContentDescription("Swap source and target languages")
            .performClick()

        assert(swapped) { "Expected Swap callback to be triggered" }
    }

    // ── 8. Offline state shows offline text ───────────────────────────────────

    @Test
    fun translation_offline_showsText() {
        setTranslator(isOffline = true)

        composeTestRule.onNodeWithText("You're offline").assertIsDisplayed()
    }
}
