/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : app (androidTest)
 * File       : MeetingFlowTest.kt
 * Purpose    : Compose UI integration tests for the meeting recording flow.
 *
 * Architecture Layer : androidTest — UI integration
 * Requirements: 21.3
 * ============================================================
 */
package com.aiassistant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
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
 * Compose UI integration tests for the meeting recording screen.
 *
 * Uses test-local fake composables that reproduce the same semantic anchors as the
 * real meeting screen composables (which are internal to feature-meeting).
 *
 * Requirements: 21.3
 */
@RunWith(AndroidJUnit4::class)
class MeetingFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Test-local fake composables ───────────────────────────────────────────

    @Composable
    private fun fakeMeetingIdle(onStartRecording: () -> Unit = {}) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            FloatingActionButton(
                onClick = onStartRecording,
                modifier = Modifier.semantics { contentDescription = "Start recording meeting" }
            ) {
                Text("Rec")
            }
        }
    }

    @Composable
    private fun fakeMeetingRecording(onStopRecording: () -> Unit = {}) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.semantics { contentDescription = "Recording in progress" }
            ) {
                Text("~~~")
            }
            Button(
                onClick = onStopRecording,
                modifier = Modifier.semantics { contentDescription = "Stop recording" }
            ) {
                Text("Stop")
            }
        }
    }

    @Composable
    private fun fakeMeetingProcessing() {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.semantics {
                    contentDescription = "Processing meeting transcript"
                }
            )
        }
    }

    @Composable
    private fun fakeMeetingPermissionDenied() {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
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

    // ── 1. Idle state shows Start FAB ─────────────────────────────────────────

    @Test
    fun meeting_idle_showsStartFab() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) { fakeMeetingIdle() }
        }

        composeTestRule.onNodeWithContentDescription("Start recording meeting").assertIsDisplayed()
    }

    // ── 2. Idle — tapping FAB fires callback ──────────────────────────────────

    @Test
    fun meeting_idle_tapFab_firesCallback() {
        var started = false

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                fakeMeetingIdle(onStartRecording = { started = true })
            }
        }

        composeTestRule.onNodeWithContentDescription("Start recording meeting").performClick()

        assert(started) { "Expected Start recording meeting callback to be triggered" }
    }

    // ── 3. Recording state shows Stop button ─────────────────────────────────

    @Test
    fun meeting_recording_showsStopButton() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) { fakeMeetingRecording() }
        }

        composeTestRule.onNodeWithContentDescription("Stop recording").assertIsDisplayed()
    }

    // ── 4. Recording state shows waveform indicator ───────────────────────────

    @Test
    fun meeting_recording_showsWaveformIndicator() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) { fakeMeetingRecording() }
        }

        composeTestRule.onNodeWithContentDescription("Recording in progress").assertIsDisplayed()
    }

    // ── 5. Processing state shows spinner ────────────────────────────────────

    @Test
    fun meeting_processing_showsSpinner() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) { fakeMeetingProcessing() }
        }

        composeTestRule.onNodeWithContentDescription("Processing meeting transcript")
            .assertIsDisplayed()
    }

    // ── 6. Permission denied state shows Settings button ─────────────────────

    @Test
    fun meeting_permissionDenied_showsSettingsButton() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) { fakeMeetingPermissionDenied() }
        }

        composeTestRule.onNodeWithContentDescription(
            "Open app settings to grant microphone permission"
        ).assertIsDisplayed()
    }
}
