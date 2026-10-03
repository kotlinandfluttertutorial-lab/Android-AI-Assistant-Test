/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-voice
 * File       : VoiceScreen.kt
 * Purpose    : Voice Assistant screen — Phase E UI update.
 *
 *              Aligned to Stitch design reference (VoiceScreen.tsx):
 *              - Single unified layout visible in all active states
 *              - 96 dp circular mic button with state-dependent colour
 *                (idle=purple / listening=red / processing=green / speaking=blue)
 *              - 3 expanding pulse rings (listening state only)
 *              - 15-bar animated wave visualizer
 *              - "You said" transcript card
 *              - "AI Response" card (voiceTranscriptBg)
 *              - "Switch to Chat" / "New Session" footer buttons
 *              - Reduced-motion: all animations skipped when enabled
 *
 * Preserved from prior version:
 *              - VoiceAssistantManager lifecycle (remember + DisposableEffect)
 *              - RECORD_AUDIO permission launcher
 *              - All LaunchedEffect side-effects (startListening / speak / wakeword)
 *              - PermissionDenied, Error, Loading helper composables
 *              - All VoiceViewModel callbacks unchanged
 *
 * Architecture Layer : Feature (feature-voice) — Compose UI layer.
 * Requirements       : 5.1–5.6, 23.1, 23.4
 * ============================================================
 */
package com.aiassistant.feature.voice

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.StitchColors
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.motion.LocalReducedMotionEnabled
import com.aiassistant.core.ui.spacing

// ── Entry point ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(onNavigateBack: () -> Unit = {}, viewModel: VoiceViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val manager = remember { VoiceAssistantManager(context) }
    DisposableEffect(Unit) { onDispose { manager.release() } }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) viewModel.onPermissionGranted() else viewModel.onPermissionDenied()
        }
    )

    // State-driven side effects — all preserved from prior version
    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is VoiceUiState.Listening -> {
                manager.startListening(
                    onPartialResult = { partial -> viewModel.onPartialSpeechResult(partial) },
                    onFinalResult   = { transcript -> viewModel.onSpeechResult(transcript) },
                    onError         = { errorCode -> viewModel.onSpeechError(errorCode) }
                )
            }
            is VoiceUiState.Speaking -> {
                manager.speak(text = state.responseText, onDone = { viewModel.onSpeakingComplete() })
            }
            is VoiceUiState.Idle -> {
                if (state.isWakeWordEnabled) viewModel.startListening()
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice Assistant") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.semantics { contentDescription = "Navigate back" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
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
                // Simple overlay states
                is VoiceUiState.RequestingPermission -> {
                    LaunchedEffect(Unit) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    LoadingContent("Requesting microphone permission\u2026")
                }
                is VoiceUiState.PermissionDenied -> PermissionDeniedContent(
                    onOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.fromParts("package", context.packageName, null)
                            }
                        )
                    }
                )
                is VoiceUiState.Error -> ErrorContent(state.message, onRetry = { viewModel.reset() })

                // Unified Stitch-style voice UI for all active states
                else -> {
                    val transcript   = (uiState as? VoiceUiState.Transcribing)?.partialTranscript ?: ""
                    val responseText = (uiState as? VoiceUiState.Speaking)?.responseText ?: ""
                    val idle         = uiState is VoiceUiState.Idle
                    val listening    = uiState is VoiceUiState.Listening
                    val processing   = uiState is VoiceUiState.Transcribing
                    val speaking     = uiState is VoiceUiState.Speaking

                    StitchVoiceContent(
                        isIdle            = idle,
                        isListening       = listening,
                        isProcessing      = processing,
                        isSpeaking        = speaking,
                        transcript        = transcript,
                        responseText      = responseText,
                        wakeWordSupported = (uiState as? VoiceUiState.Idle)?.isWakeWordSupported == true,
                        wakeWordEnabled   = (uiState as? VoiceUiState.Idle)?.isWakeWordEnabled  == true,
                        onMicTap = {
                            if (idle) {
                                viewModel.requestPermission()
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                manager.stopListening()
                                viewModel.reset()
                            }
                        },
                        onWakeWordToggle = { viewModel.setWakeWordEnabled(it) },
                        onSwitchToChat   = onNavigateBack,
                        onNewSession     = {
                            manager.stopListening(); manager.stopSpeaking(); viewModel.reset()
                        },
                        onInterrupt      = { manager.stopSpeaking(); viewModel.stopSpeaking() },
                    )
                }
            }
        }
    }
}

// ── Stitch-style unified voice content ───────────────────────────────────────

/**
 * Single unified composable that renders the Stitch voice UI regardless of state.
 * The mic button colour, pulse rings, wave bars, and footer buttons all derive
 * from the boolean state parameters — no nested when-expressions in the UI.
 */
@Composable
private fun StitchVoiceContent(
    isIdle: Boolean,
    isListening: Boolean,
    isProcessing: Boolean,
    isSpeaking: Boolean,
    transcript: String,
    responseText: String,
    wakeWordSupported: Boolean,
    wakeWordEnabled: Boolean,
    onMicTap: () -> Unit,
    onWakeWordToggle: (Boolean) -> Unit,
    onSwitchToChat: () -> Unit,
    onNewSession: () -> Unit,
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalReducedMotionEnabled.current

    val micColor = when {
        isListening  -> StitchColors.voiceListening
        isProcessing -> StitchColors.voiceProcessing
        isSpeaking   -> StitchColors.voiceSpeaking
        else         -> StitchColors.voiceIdle
    }
    val statusLabel = when {
        isListening  -> "Listening\u2026"
        isProcessing -> "Processing\u2026"
        isSpeaking   -> "Speaking\u2026"
        else         -> "Tap to speak"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(MaterialTheme.spacing.xl))

        Text(
            text  = statusLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = statusLabel },
        )

        Spacer(Modifier.height(MaterialTheme.spacing.xxl))

        // ── Mic button + pulse rings ──────────────────────────────────────────
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
            if (isListening && !reducedMotion) {
                val transition = rememberInfiniteTransition(label = "voicePulse")
                listOf(
                    Triple(0f,   StitchColors.voicePulseRing0, 0),
                    Triple(0.3f, StitchColors.voicePulseRing1, 540),
                    Triple(0.5f, StitchColors.voicePulseRing2, 1080),
                ).forEach { (_, color, delay) ->
                    val scale by transition.animateFloat(
                        initialValue = 1f,
                        targetValue  = 2.5f,
                        animationSpec = infiniteRepeatable(
                            animation  = tween(1800, delayMillis = delay, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Restart,
                        ),
                        label = "pScale$delay",
                    )
                    val alpha by transition.animateFloat(
                        initialValue = 0.5f,
                        targetValue  = 0f,
                        animationSpec = infiniteRepeatable(
                            animation  = tween(1800, delayMillis = delay, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart,
                        ),
                        label = "pAlpha$delay",
                    )
                    Box(
                        modifier = Modifier
                            .size((96 * scale).dp.coerceAtMost(200.dp))
                            .alpha(alpha)
                            .clip(CircleShape)
                            .background(color),
                    )
                }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(micColor)
                    .clickable(onClick = onMicTap)
                    .semantics {
                        contentDescription = if (isIdle) "Tap to start listening" else "Tap to stop"
                    },
            ) {
                Icon(
                    imageVector = if (isListening || isProcessing) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = null,
                    tint   = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        Spacer(Modifier.height(MaterialTheme.spacing.xl))

        // ── 15-bar wave visualizer ────────────────────────────────────────────
        WaveVisualizer(
            active    = isListening || isSpeaking,
            barColor  = if (isSpeaking) StitchColors.voiceSpeaking else StitchColors.voiceListening,
            reducedMotion = reducedMotion,
        )

        Spacer(Modifier.height(MaterialTheme.spacing.xl))

        // ── Transcript card ───────────────────────────────────────────────────
        if (transcript.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth()
                    .semantics { contentDescription = "You said: $transcript" },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(modifier = Modifier.padding(MaterialTheme.spacing.md)) {
                    Text(
                        text = "YOU SAID", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp,
                    )
                    Spacer(Modifier.height(MaterialTheme.spacing.xs))
                    Text(text = transcript, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(MaterialTheme.spacing.md))
        }

        // ── AI response card ──────────────────────────────────────────────────
        if (responseText.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth()
                    .semantics { contentDescription = "AI response: $responseText" },
                shape = RoundedCornerShape(20.dp),
                color = StitchColors.voiceTranscriptBg,
            ) {
                Column(modifier = Modifier.padding(MaterialTheme.spacing.md)) {
                    Text(
                        text = "AI RESPONSE", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp,
                    )
                    Spacer(Modifier.height(MaterialTheme.spacing.xs))
                    Text(
                        text  = responseText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = StitchColors.voiceResponseText,
                    )
                }
            }
            Spacer(Modifier.height(MaterialTheme.spacing.md))
        }

        // ── Wake word toggle (Idle only) ──────────────────────────────────────
        if (isIdle && wakeWordSupported) {
            Spacer(Modifier.height(MaterialTheme.spacing.lg))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Hands-free mode", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Continuously listen for voice input",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = wakeWordEnabled, onCheckedChange = onWakeWordToggle,
                    modifier = Modifier.semantics {
                        contentDescription = if (wakeWordEnabled) "Hands-free on" else "Hands-free off"
                    }
                )
            }
        }

        Spacer(Modifier.weight(1f))

        // ── Footer buttons ────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = MaterialTheme.spacing.xl),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            OutlinedButton(
                onClick = onSwitchToChat,
                modifier = Modifier.weight(1f)
                    .semantics { contentDescription = "Switch to text chat" },
            ) { Text("Switch to Chat") }

            if (responseText.isNotBlank()) {
                Button(
                    onClick = onNewSession,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.weight(1f)
                        .semantics { contentDescription = "Start new voice session" },
                ) { Text("New Session") }
            }
        }
    }
}

// ── Wave bars visualizer ──────────────────────────────────────────────────────

@Composable
private fun WaveVisualizer(
    active: Boolean,
    barColor: Color,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val heights = listOf(0.3f, 0.6f, 0.9f, 0.7f, 1f, 0.8f, 0.5f, 0.9f, 0.6f, 0.4f, 0.7f, 1f, 0.8f, 0.5f, 0.3f)
    val transition = rememberInfiniteTransition(label = "wave")

    Row(
        modifier = modifier.height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        heights.forEachIndexed { i, base ->
            val h by transition.animateFloat(
                initialValue = if (active && !reducedMotion) base else 0.2f,
                targetValue  = if (active && !reducedMotion) (base * 0.5f).coerceAtLeast(0.1f) else 0.2f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 1200
                        base at (i * 80).coerceAtMost(1000) using FastOutSlowInEasing
                        (base * 0.5f).coerceAtLeast(0.1f) at ((i * 80 + 600).coerceAtMost(1200)) using FastOutSlowInEasing
                        base at 1200 using FastOutSlowInEasing
                    },
                    repeatMode = RepeatMode.Restart,
                ),
                label = "bar$i",
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxSize(if (active && !reducedMotion) h else 0.2f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (active) barColor else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

// ── Retained helpers (unchanged) ─────────────────────────────────────────────

@Composable
private fun PermissionDeniedContent(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(MaterialTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ErrorBanner(
            message = "Microphone permission is required for voice input. " +
                "Please grant the permission in app settings.",
            contentDescription = "Microphone permission denied",
        )
        Spacer(Modifier.height(MaterialTheme.spacing.md))
        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.semantics { contentDescription = "Open app settings" }
        ) { Text("Open Settings") }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(MaterialTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ErrorBanner(message = message, onRetry = onRetry)
    }
}

@Composable
private fun LoadingContent(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(MaterialTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = message })
        Spacer(Modifier.height(MaterialTheme.spacing.md))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true, name = "VoiceScreen — Idle")
@Composable
private fun PreviewIdle() {
    AppTheme(dynamicColor = false) {
        StitchVoiceContent(
            isIdle = true, isListening = false, isProcessing = false, isSpeaking = false,
            transcript = "", responseText = "", wakeWordSupported = true, wakeWordEnabled = false,
            onMicTap = {}, onWakeWordToggle = {}, onSwitchToChat = {}, onNewSession = {}, onInterrupt = {},
        )
    }
}

@Preview(showBackground = true, name = "VoiceScreen — Listening")
@Composable
private fun PreviewListening() {
    AppTheme(dynamicColor = false) {
        StitchVoiceContent(
            isIdle = false, isListening = true, isProcessing = false, isSpeaking = false,
            transcript = "What\u2019s the weather in New York today?",
            responseText = "", wakeWordSupported = false, wakeWordEnabled = false,
            onMicTap = {}, onWakeWordToggle = {}, onSwitchToChat = {}, onNewSession = {}, onInterrupt = {},
        )
    }
}

@Preview(showBackground = true, name = "VoiceScreen — Speaking with response")
@Composable
private fun PreviewSpeaking() {
    AppTheme(dynamicColor = false) {
        StitchVoiceContent(
            isIdle = false, isListening = false, isProcessing = false, isSpeaking = true,
            transcript = "What\u2019s the weather in New York today?",
            responseText = "The weather in New York today is partly cloudy with a high of 72\u00b0F.",
            wakeWordSupported = false, wakeWordEnabled = false,
            onMicTap = {}, onWakeWordToggle = {}, onSwitchToChat = {}, onNewSession = {}, onInterrupt = {},
        )
    }
}
