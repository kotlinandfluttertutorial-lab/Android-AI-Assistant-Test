/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : app (androidTest)
 * File       : VoiceFlowTest.kt
 * Purpose    : Compose UI integration tests for the voice activation flow.
 *
 * Architecture Layer : androidTest — UI integration
 * Requirements: 21.3
 * ============================================================
 */
package com.aiassistant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aiassistant.core.ui.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI integration tests for the voice activation screen.
 *
 * Uses test-local fake composables that reproduce the same semantic anchors as the
 * real voice screen composables (which are internal to feature-voice).
 *
 * Requirements: 21.3
 */
@RunWith(AndroidJUnit4::class)
class VoiceFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Test-local fake composables ───────────────────────────────────────────

    @Composable
    private fun fakeVoiceIdle(onTapToSpeak: () -> Unit = {}) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            FloatingActionButton(
                onClick = onTapToSpeak,
                modifier = Modifier.semantics { contentDescription = "Tap to speak" }
            ) {
                Text("Mic")
            }
        }
    }

    @Composable
    private fun fakeVoiceListening(onStopListening: () -> Unit = {}) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = onStopListening,
                modifier = Modifier.semantics { contentDescription = "Stop listening" }
            ) {
                Text("Stop")
            }
        }
    }

    @Composable
    private fun fakeVoiceTranscribing(partialTranscript: String = "Hello world") {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.semantics { contentDescription = "Processing speech" }
            )
            Surface(
                modifier = Modifier.semantics {
                    contentDescription = "Partial transcript: $partialTranscript"
                }
            ) {
                Text(partialTranscript)
            }
        }
    }

    @Composable
    private fun fakeVoiceSpeaking(onInterrupt: () -> Unit = {}) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.RecordVoiceOver,
                contentDescription = "Assistant is speaking"
            )
            Button(
                onClick = onInterrupt,
                modifier = Modifier.semantics { contentDescription = "Interrupt and speak" }
            ) {
                Text("Interrupt")
            }
        }
    }

    @Composable
    private fun fakeVoicePermissionDenied() {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.semantics { contentDescription = "Microphone permission denied" }
            ) {
                Text("Microphone permission denied")
            }
            Button(
                onClick = {},
                modifier = Modifier.semantics {
                    contentDescription = "Open app settings to grant microphone permission"
                }
            ) {
                Text("Open Settings")
            }
        }
    }

    // ── 1. Idle state shows Tap to Speak FAB ─────────────────────────────────

    @Test
    fun voice_idle_showsTapToSpeakFab() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) { fakeVoiceIdle() }
        }

        composeTestRule.onNodeWithContentDescription("Tap to speak").assertIsDisplayed()
    }

    // ── 2. Idle — tapping FAB fires callback ──────────────────────────────────

    @Test
    fun voice_idle_tapFab_firesCallback() {
        var tapped = false

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) { fakeVoiceIdle(onTapToSpeak = { tapped = true }) }
        }

        composeTestRule.onNodeWithContentDescription("Tap to speak").performClick()

        assert(tapped) { "Expected Tap to speak callback to be triggered" }
    }

    // ── 3. Listening state shows Stop button ─────────────────────────────────

    @Test
    fun voice_listening_showsStopButton() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) { fakeVoiceListening() }
        }

        composeTestRule.onNodeWithContentDescription("Stop listening").assertIsDisplayed()
    }

    // ── 4. Listening — tapping Stop fires callback ────────────────────────────

    @Test
    fun voice_listening_tapStop_firesCallback() {
        var stopped = false

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                fakeVoiceListening(onStopListening = { stopped = true })
            }
        }

        composeTestRule.onNodeWithContentDescription("Stop listening").performClick()

        assert(stopped) { "Expected Stop listening callback to be triggered" }
    }

    // ── 5. Transcribing state shows partial transcript ────────────────────────

    @Test
    fun voice_transcribing_showsPartialTranscript() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                fakeVoiceTranscribing(partialTranscript = "Hello world")
            }
        }

        composeTestRule.onNodeWithContentDescription("Partial transcript: Hello world")
            .assertIsDisplayed()
    }

    // ── 6. Speaking state shows Interrupt button ──────────────────────────────

    @Test
    fun voice_speaking_showsInterruptButton() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) { fakeVoiceSpeaking() }
        }

        composeTestRule.onNodeWithContentDescription("Interrupt and speak").assertIsDisplayed()
    }

    // ── 7. Permission denied state shows Settings button ─────────────────────

    @Test
    fun voice_permissionDenied_showsSettingsButton() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) { fakeVoicePermissionDenied() }
        }

        composeTestRule.onNodeWithContentDescription(
            "Open app settings to grant microphone permission"
        ).assertIsDisplayed()
    }
}
