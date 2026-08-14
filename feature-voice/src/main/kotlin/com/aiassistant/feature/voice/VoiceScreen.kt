/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-voice
 * File       : VoiceScreen.kt
 * Purpose    : Compose UI screen for the Voice feature
 *
 * Architecture Layer : Feature (feature-voice)
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
 * Module     : feature-voice
 * File       : VoiceScreen.kt
 * Purpose    : Compose UI screen for the Voice feature
 *
 * Architecture Layer : Feature (feature-voice)
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
 * VoiceScreen.kt
 *
 * Purpose: Jetpack Compose screen for the Voice Assistant feature. Renders UI based on
 *          VoiceUiState and coordinates with VoiceAssistantManager for STT/TTS hardware.
 * Architecture: feature-voice â€” UI layer; observes VoiceViewModel state and drives
 *               VoiceAssistantManager. Navigation is driven by LaunchedEffect on uiState.
 * Dependencies: VoiceViewModel (Hilt), VoiceAssistantManager (remembered in composition),
 *               core-ui (ErrorBanner, AppTheme, spacing tokens), Compose Material 3,
 *               android.Manifest.permission.RECORD_AUDIO
 *
 * Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6
 *
 * Design decisions:
 * - VoiceAssistantManager is created via `remember { VoiceAssistantManager(context) }`
 *   and released in `DisposableEffect` â€” no context leaks into the ViewModel.
 * - RECORD_AUDIO permission is requested using ActivityResultContracts.RequestPermission
 *   launched when the user taps the FAB and the permission is not yet granted.
 * - Wake word / hands-free mode is implemented as a continuous listening loop: when the
 *   toggle is on, after each onResults the screen immediately restarts recognition
 *   (Requirement 5.5 â€” no true hotword; device-specific API not universally available).
 * - All interactive elements carry contentDescription for TalkBack (Requirement 23.1).
 * - Colors use Material 3 colorScheme tokens; no hardcoded values ensuring 4.5:1 contrast.
 */
package com.aiassistant.feature.voice

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.spacing

/**
 * Entry-point composable for the Voice Assistant screen.
 *
 * @param viewModel      Hilt-injected [VoiceViewModel].
 * @param onNavigateBack Called when the user presses the back button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(onNavigateBack: () -> Unit = {}, viewModel: VoiceViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // â”€â”€â”€ VoiceAssistantManager lifecycle â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    val manager = remember { VoiceAssistantManager(context) }

    DisposableEffect(Unit) {
        onDispose { manager.release() }
    }

    // â”€â”€â”€ Microphone permission launcher â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

    // When we transition to Listening, start the microphone.
    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is VoiceUiState.Listening -> {
                manager.startListening(
                    onPartialResult = { partial -> viewModel.onPartialSpeechResult(partial) },
                    onFinalResult = { transcript -> viewModel.onSpeechResult(transcript) },
                    onError = { errorCode -> viewModel.onSpeechError(errorCode) }
                )
            }
            is VoiceUiState.Speaking -> {
                manager.speak(
                    text = state.responseText,
                    onDone = {
                        viewModel.onSpeakingComplete()
                    }
                )
            }
            is VoiceUiState.Idle -> {
                // If wake word / hands-free mode is enabled, auto-start listening.
                if (state.isWakeWordEnabled) {
                    viewModel.startListening()
                }
            }
            else -> Unit
        }
    }

    // â”€â”€â”€ Scaffold â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Voice Assistant") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.semantics {
                            contentDescription = "Navigate back"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MicOff,
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
                is VoiceUiState.Idle -> IdleContent(
                    state = state,
                    onTapToSpeak = {
                        viewModel.requestPermission()
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onWakeWordToggle = { enabled -> viewModel.setWakeWordEnabled(enabled) }
                )

                is VoiceUiState.RequestingPermission -> {
                    // Trigger permission dialog on initial RequestingPermission state.
                    LaunchedEffect(Unit) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    LoadingContent(message = "Requesting microphone permissionâ€¦")
                }

                is VoiceUiState.PermissionDenied -> PermissionDeniedContent(
                    onOpenSettings = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    }
                )

                is VoiceUiState.Listening -> ListeningContent(
                    onStop = {
                        manager.stopListening()
                        viewModel.reset()
                    }
                )

                is VoiceUiState.Transcribing -> TranscribingContent(
                    partialTranscript = state.partialTranscript
                )

                is VoiceUiState.Speaking -> SpeakingContent(
                    responseText = state.responseText,
                    onInterrupt = {
                        manager.stopSpeaking()
                        viewModel.stopSpeaking()
                        // stopSpeaking() transitions to Listening which triggers LaunchedEffect
                        // to call manager.startListening() automatically.
                    }
                )

                is VoiceUiState.Error -> ErrorContent(
                    message = state.message,
                    onRetry = { viewModel.reset() }
                )
            }
        }
    }
}

// â”€â”€â”€ State-specific content composables â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun IdleContent(state: VoiceUiState.Idle, onTapToSpeak: () -> Unit, onWakeWordToggle: (Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MaterialTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        FloatingActionButton(
            onClick = onTapToSpeak,
            modifier = Modifier
                .size(80.dp)
                .semantics { contentDescription = "Tap to speak" },
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
            text = "Tap to speak",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        if (state.isWakeWordSupported) {
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xl))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Hands-free mode",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Continuously listen for voice input",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.isWakeWordEnabled,
                    onCheckedChange = onWakeWordToggle,
                    modifier = Modifier.semantics {
                        contentDescription = if (state.isWakeWordEnabled) {
                            "Hands-free mode on, tap to turn off"
                        } else {
                            "Hands-free mode off, tap to turn on"
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ListeningContent(onStop: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic_scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MaterialTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = "Listening",
            modifier = Modifier
                .size(80.dp)
                .scale(scale),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))

        Text(
            text = "Listeningâ€¦",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.xl))

        OutlinedButton(
            onClick = onStop,
            modifier = Modifier.semantics { contentDescription = "Stop listening" }
        ) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(MaterialTheme.spacing.xs))
            Text(text = "Stop")
        }
    }
}

@Composable
private fun TranscribingContent(partialTranscript: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MaterialTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = "Processing speech" }
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))

        Text(
            text = "Recognising speechâ€¦",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        if (partialTranscript.isNotEmpty()) {
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))

            Text(
                text = partialTranscript,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Partial transcript: $partialTranscript" }
            )
        }
    }
}

@Composable
private fun SpeakingContent(responseText: String, onInterrupt: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "speaker_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "speaker_alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MaterialTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.VolumeUp,
            contentDescription = "Speaking",
            modifier = Modifier
                .size(64.dp)
                .semantics { contentDescription = "Assistant is speaking" },
            tint = Color(
                red = MaterialTheme.colorScheme.primary.red,
                green = MaterialTheme.colorScheme.primary.green,
                blue = MaterialTheme.colorScheme.primary.blue,
                alpha = alpha
            )
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))

        Text(
            text = responseText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .semantics { contentDescription = "Assistant response: $responseText" }
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.xl))

        OutlinedButton(
            onClick = onInterrupt,
            modifier = Modifier.semantics {
                contentDescription = "Interrupt and speak"
            }
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(MaterialTheme.spacing.xs))
            Text(text = "Interrupt")
        }
    }
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
            message = "Microphone permission is required for voice input. " +
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

@Composable
private fun LoadingContent(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MaterialTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.semantics { contentDescription = message }
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// â”€â”€â”€ Previews â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Preview(showBackground = true, name = "VoiceScreen â€“ Idle")
@Composable
private fun VoiceScreenIdlePreview() {
    AppTheme(dynamicColor = false) {
        IdleContent(
            state = VoiceUiState.Idle(isWakeWordSupported = true, isWakeWordEnabled = false),
            onTapToSpeak = {},
            onWakeWordToggle = {}
        )
    }
}

@Preview(showBackground = true, name = "VoiceScreen â€“ Listening")
@Composable
private fun VoiceScreenListeningPreview() {
    AppTheme(dynamicColor = false) {
        ListeningContent(onStop = {})
    }
}

@Preview(showBackground = true, name = "VoiceScreen â€“ Transcribing")
@Composable
private fun VoiceScreenTranscribingPreview() {
    AppTheme(dynamicColor = false) {
        TranscribingContent(partialTranscript = "What is the weather like today")
    }
}

@Preview(showBackground = true, name = "VoiceScreen â€“ Speaking")
@Composable
private fun VoiceScreenSpeakingPreview() {
    AppTheme(dynamicColor = false) {
        SpeakingContent(
            responseText = "The weather in London today is cloudy with a high of 18Â°C.",
            onInterrupt = {}
        )
    }
}

@Preview(showBackground = true, name = "VoiceScreen â€“ Permission Denied")
@Composable
private fun VoiceScreenPermissionDeniedPreview() {
    AppTheme(dynamicColor = false) {
        PermissionDeniedContent(onOpenSettings = {})
    }
}

@Preview(showBackground = true, name = "VoiceScreen â€“ Error")
@Composable
private fun VoiceScreenErrorPreview() {
    AppTheme(dynamicColor = false) {
        ErrorContent(
            message = "No speech detected. Please try again.",
            onRetry = {}
        )
    }
}
