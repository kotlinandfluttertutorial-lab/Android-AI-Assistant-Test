/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-meeting
 * File       : MeetingRecorderScreen.kt
 * Purpose    : Compose UI screen for the MeetingRecorder feature
 *
 * Architecture Layer : Feature (feature-meeting)
 * Pattern Used       : Jetpack Compose Screen
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
 * Module     : feature-meeting
 * File       : MeetingRecorderScreen.kt
 * Purpose    : Compose UI screen for the MeetingRecorder feature
 *
 * Architecture Layer : Feature (feature-meeting)
 * Pattern Used       : Jetpack Compose Screen
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
 * MeetingRecorderScreen.kt
 *
 * Purpose: Jetpack Compose screen for the Meeting Recorder feature. Renders UI based on
 *          MeetingUiState and coordinates with MeetingRecorderManager for MediaRecorder
 *          hardware.
 * Architecture: feature-meeting â€” UI layer; observes MeetingViewModel state and drives
 *               MeetingRecorderManager. Navigation to MeetingSummaryScreen is driven by
 *               LaunchedEffect when state transitions to Complete.
 * Dependencies: MeetingViewModel (Hilt), MeetingRecorderManager (remembered),
 *               core-ui (ErrorBanner, AppTheme, spacing tokens), Compose Material 3,
 *               android.Manifest.permission.RECORD_AUDIO
 *
 * Requirements: 19.1, 5.6
 *
 * Design decisions:
 * - MeetingRecorderManager is created via `remember { MeetingRecorderManager(context) }`
 *   and released in DisposableEffect â€” no context leaks into the ViewModel.
 * - RECORD_AUDIO permission is requested using ActivityResultContracts.RequestPermission.
 * - Live duration timer is implemented as a LaunchedEffect that increments every second
 *   while in Recording state, calling viewModel.updateRecordingDuration().
 * - Waveform animation uses pulsing concentric circles driven by InfiniteTransition.
 * - All interactive elements carry contentDescription for TalkBack (Requirement 23.1).
 */
package com.aiassistant.feature.meeting

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.spacing
import kotlinx.coroutines.delay

/**
 * Entry-point composable for the Meeting Recorder screen.
 *
 * @param onNavigateBack  Called when the user presses the back button.
 * @param onRecordingComplete Called when the state reaches Complete â€” navigate to summary.
 * @param viewModel       Hilt-injected [MeetingViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingRecorderScreen(
    onNavigateBack: () -> Unit = {},
    onRecordingComplete: () -> Unit = {},
    viewModel: MeetingViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // â”€â”€â”€ MeetingRecorderManager lifecycle â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    val manager = remember { MeetingRecorderManager(context) }

    DisposableEffect(Unit) {
        onDispose { manager.release() }
    }

    // â”€â”€â”€ Permission launcher â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                viewModel.onPermissionGranted()
            } else {
                viewModel.onPermissionDenied()
            }
        }
    )

    // â”€â”€â”€ State-driven side effects â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    // Navigate to summary when recording is complete.
    LaunchedEffect(uiState) {
        if (uiState is MeetingUiState.Complete) {
            onRecordingComplete()
        }
    }

    // Live timer while recording.
    LaunchedEffect(uiState) {
        if (uiState is MeetingUiState.Recording) {
            var seconds = (uiState as MeetingUiState.Recording).durationSeconds
            while (true) {
                delay(1_000L)
                seconds++
                viewModel.updateRecordingDuration(seconds)
            }
        }
    }

    // â”€â”€â”€ Scaffold â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Meeting Recorder") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.semantics {
                            contentDescription = "Navigate back"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val state = uiState) {
                is MeetingUiState.Idle -> IdleContent(
                    onStartRecording = {
                        viewModel.requestPermission()
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                )

                is MeetingUiState.RequestingPermission -> {
                    // Show rationale dialog, then launch the system permission dialog.
                    PermissionRationaleDialog(
                        onConfirm = {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onDismiss = {
                            viewModel.onPermissionDenied()
                        }
                    )
                }

                is MeetingUiState.PermissionDenied -> PermissionDeniedContent(
                    onOpenSettings = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                        ).apply {
                            data = android.net.Uri.fromParts(
                                "package",
                                context.packageName,
                                null
                            )
                        }
                        context.startActivity(intent)
                    }
                )

                is MeetingUiState.Recording -> RecordingContent(
                    durationSeconds = state.durationSeconds,
                    onStopRecording = {
                        val audioFilePath = manager.stopRecording()
                        viewModel.stopRecording(audioFilePath)
                        viewModel.fetchSummary()
                    }
                )

                is MeetingUiState.Processing -> ProcessingContent()

                is MeetingUiState.Complete -> {
                    // Navigation handled in LaunchedEffect above.
                    ProcessingContent()
                }

                is MeetingUiState.Error -> ErrorContent(
                    message = state.message,
                    onRetry = { viewModel.reset() }
                )
            }
        }
    }
}

// â”€â”€â”€ State-specific content composables â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun IdleContent(onStartRecording: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MaterialTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        FloatingActionButton(
            onClick = onStartRecording,
            modifier = Modifier
                .size(80.dp)
                .semantics { contentDescription = "Start recording meeting" },
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))

        Text(
            text = "Tap to start recording",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

        Text(
            text = "Microphone permission is required",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PermissionRationaleDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Microphone Permission Required") },
        text = {
            Text(
                "Meeting Recorder needs access to your microphone to capture audio. " +
                    "Please grant microphone permission to start recording."
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics {
                    contentDescription = "Grant microphone permission"
                }
            ) {
                Text("Grant Permission")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics {
                    contentDescription = "Deny microphone permission"
                }
            ) {
                Text("Not Now")
            }
        }
    )
}

@Composable
private fun PermissionDeniedContent(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MaterialTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        ErrorBanner(
            message = "Microphone permission is required for meeting recording. " +
                "Please grant the permission in app settings.",
            contentDescription = "Microphone permission denied"
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))

        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.semantics {
                contentDescription = "Open app settings to grant microphone permission"
            }
        ) {
            Text(text = "Open Settings")
        }
    }
}

@Composable
private fun RecordingContent(durationSeconds: Int, onStopRecording: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform_pulse")

    val pulse1 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_1"
    )
    val pulse2 by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, delayMillis = 300),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_2"
    )

    val primaryColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MaterialTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Waveform animation â€” pulsing concentric circles
        Box(
            modifier = Modifier
                .size(120.dp)
                .semantics { contentDescription = "Recording in progress" },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = this.center
                val maxRadius = size.minDimension / 2f

                drawCircle(
                    color = primaryColor.copy(alpha = pulse2 * 0.3f),
                    radius = maxRadius * pulse2,
                    center = center
                )
                drawCircle(
                    color = primaryColor.copy(alpha = pulse1 * 0.5f),
                    radius = maxRadius * pulse1 * 0.7f,
                    center = center,
                    style = Stroke(width = 4.dp.toPx())
                )
                drawCircle(
                    color = primaryColor,
                    radius = 24.dp.toPx(),
                    center = center
                )
            }
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))

        // Live duration timer
        Text(
            text = formatDuration(durationSeconds),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics {
                contentDescription = "Recording duration: ${formatDurationA11y(durationSeconds)}"
            }
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

        Text(
            text = "Recordingâ€¦",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.xl))

        OutlinedButton(
            onClick = onStopRecording,
            modifier = Modifier.semantics { contentDescription = "Stop recording" }
        ) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(MaterialTheme.spacing.xs))
            Text(text = "Stop Recording")
        }
    }
}

@Composable
private fun ProcessingContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MaterialTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(64.dp)
                .semantics { contentDescription = "Processing meeting transcript" }
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))

        Text(
            text = "Processing transcriptâ€¦",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

        Text(
            text = "This may take a moment",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MaterialTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        ErrorBanner(
            message = message,
            onRetry = onRetry
        )
    }
}

// â”€â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

private fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

private fun formatDurationA11y(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return buildString {
        if (h > 0) append("$h hours ")
        if (m > 0) append("$m minutes ")
        append("$s seconds")
    }.trim()
}

// â”€â”€â”€ Previews â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Preview(showBackground = true, name = "MeetingRecorder â€“ Idle")
@Composable
private fun MeetingRecorderIdlePreview() {
    AppTheme(dynamicColor = false) {
        IdleContent(onStartRecording = {})
    }
}

@Preview(showBackground = true, name = "MeetingRecorder â€“ Recording")
@Composable
private fun MeetingRecorderRecordingPreview() {
    AppTheme(dynamicColor = false) {
        RecordingContent(durationSeconds = 125, onStopRecording = {})
    }
}

@Preview(showBackground = true, name = "MeetingRecorder â€“ Processing")
@Composable
private fun MeetingRecorderProcessingPreview() {
    AppTheme(dynamicColor = false) {
        ProcessingContent()
    }
}

@Preview(showBackground = true, name = "MeetingRecorder â€“ Permission Denied")
@Composable
private fun MeetingRecorderPermissionDeniedPreview() {
    AppTheme(dynamicColor = false) {
        PermissionDeniedContent(onOpenSettings = {})
    }
}

@Preview(showBackground = true, name = "MeetingRecorder â€“ Error")
@Composable
private fun MeetingRecorderErrorPreview() {
    AppTheme(dynamicColor = false) {
        ErrorContent(
            message = "Failed to start recording session. Please try again.",
            onRetry = {}
        )
    }
}
