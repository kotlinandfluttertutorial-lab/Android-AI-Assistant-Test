/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-chat
 * File       : ComparisonModeScreen.kt
 * Purpose    : Jetpack Compose screen for the AI Model Comparison Mode feature
 *
 * Architecture Layer : Feature (feature-chat)
 * Pattern Used       : Jetpack Compose Screen
 *
 * Key Concepts:
 *   - Concurrent dispatch to 2–4 LLM providers (Req 30.3)
 *   - Side-scrollable provider panels (Req 30.2)
 *   - Quality scores per panel (Req 30.5)
 *   - "Use This Response" canonical adoption (Req 30.6)
 *   - Error panels for timed-out/failed providers (Req 30.4)
 *   - Disabled control when <2 providers configured (Req 30.8)
 *
 * Requirements: 30.1, 30.2, 30.3, 30.4, 30.5, 30.6, 30.7, 30.8
 * ============================================================
 */
package com.aiassistant.feature.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiassistant.core.ai.LlmProvider
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.components.MarkdownText
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.Message
import java.util.Locale

// ─── Screen entry point ──────────────────────────────────────────────────────

/**
 * Stateful entry point. Collects state from [ComparisonModeViewModel] and delegates
 * rendering to the stateless overload.
 *
 * @param viewModel         The Hilt-provided [ComparisonModeViewModel].
 * @param conversationId    ID of the active conversation.
 * @param configuredProviders Providers that have been configured by the user (from Settings).
 * @param selectedProviders Providers pre-selected for this comparison session.
 * @param onNavigateUp      Called when the user taps the back arrow.
 * @param onResponseAdopted Called with the canonical [Message] when "Use This Response" is tapped.
 */
@Composable
fun ComparisonModeScreen(
    viewModel: ComparisonModeViewModel,
    conversationId: String,
    configuredProviders: List<LlmProvider>,
    selectedProviders: List<LlmProvider>,
    onNavigateUp: () -> Unit,
    onResponseAdopted: (Message) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Initialise availability check on first composition
    androidx.compose.runtime.LaunchedEffect(configuredProviders) {
        viewModel.initialise(configuredProviders)
    }

    ComparisonModeScreenContent(
        uiState = uiState,
        selectedProviders = selectedProviders,
        onDispatch = { prompt ->
            viewModel.dispatchComparison(
                conversationId = conversationId,
                prompt = prompt,
                selectedProviders = selectedProviders
            )
        },
        onUseThisResponse = { providerId ->
            viewModel.useThisResponse(providerId) { message ->
                onResponseAdopted(message)
            }
        },
        onReset = viewModel::reset,
        onNavigateUp = onNavigateUp
    )
}

// ─── Stateless screen ────────────────────────────────────────────────────────

/**
 * Stateless ComparisonMode screen. All state is passed in; side effects communicated
 * via callbacks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComparisonModeScreenContent(
    uiState: ComparisonModeUiState,
    selectedProviders: List<LlmProvider>,
    onDispatch: (String) -> Unit,
    onUseThisResponse: (String) -> Unit,
    onReset: () -> Unit,
    onNavigateUp: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Comparison") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateUp,
                        modifier = Modifier.semantics {
                            contentDescription = "Navigate back"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .imePadding()
        ) {
            if (uiState.panels.isEmpty()) {
                // Prompt entry phase
                PromptInputSection(
                    isAvailable = uiState.isComparisonModeAvailable,
                    unavailableTooltip = uiState.unavailableTooltip,
                    selectedProviders = selectedProviders,
                    onDispatch = onDispatch,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MaterialTheme.spacing.md)
                )
            } else {
                // Results phase: side-scrollable panels (Req 30.2)
                ComparisonPanelRow(
                    panels = uiState.panels,
                    canonicalPanelId = uiState.canonicalPanelId,
                    onUseThisResponse = onUseThisResponse,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )

                // Reset / new comparison
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(MaterialTheme.spacing.sm)
                        .semantics { contentDescription = "Start new comparison" }
                ) {
                    Text("New Comparison")
                }
            }
        }
    }
}

// ─── Prompt input section ────────────────────────────────────────────────────

/**
 * Text field + dispatch button. Disabled when [isAvailable] is false, with a tooltip
 * explaining why (Req 30.8).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptInputSection(
    isAvailable: Boolean,
    unavailableTooltip: String,
    selectedProviders: List<LlmProvider>,
    onDispatch: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var promptText by rememberSaveable { mutableStateOf("") }

    Column(modifier = modifier) {
        // Provider chips summary
        if (selectedProviders.isNotEmpty()) {
            Text(
                text = "Comparing: ${selectedProviders.joinToString(", ") { it.display }}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = MaterialTheme.spacing.xs)
            )
        }

        OutlinedTextField(
            value = promptText,
            onValueChange = { promptText = it },
            label = { Text("Enter your prompt") },
            placeholder = { Text("Ask all models the same question…") },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Comparison prompt input" },
            minLines = 3,
            maxLines = 8,
            enabled = isAvailable
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

        // Dispatch button — wrapped in a tooltip when disabled (Req 30.8)
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = {
                if (!isAvailable) {
                    PlainTooltip { Text(unavailableTooltip) }
                }
            },
            state = rememberTooltipState()
        ) {
            Button(
                onClick = {
                    val trimmed = promptText.trim()
                    if (trimmed.isNotEmpty()) onDispatch(trimmed)
                },
                enabled = isAvailable && promptText.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Compare models" }
            ) {
                Text("Compare Models")
            }
        }

        // Inline explanation when fewer than 2 providers are configured (Req 30.8)
        if (!isAvailable) {
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))
            Text(
                text = unavailableTooltip,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics {
                    contentDescription = unavailableTooltip
                }
            )
        }
    }
}

// ─── Side-scrollable panel row ───────────────────────────────────────────────

/**
 * Horizontal-scrollable row of provider panels (Req 30.2).
 * Each panel is ~320 dp wide so 2 panels fit on most phones and larger screens show more.
 */
@Composable
private fun ComparisonPanelRow(
    panels: List<ProviderPanelState>,
    canonicalPanelId: String?,
    onUseThisResponse: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .horizontalScroll(scrollState)
            .padding(horizontal = MaterialTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
    ) {
        panels.forEach { panel ->
            ProviderPanel(
                panel = panel,
                isAdopted = panel.providerId == canonicalPanelId,
                onUseThisResponse = { onUseThisResponse(panel.providerId) },
                modifier = Modifier
                    .widthIn(min = 300.dp, max = 360.dp)
                    .fillMaxSize()
            )
        }
    }
}

// ─── Single provider panel ───────────────────────────────────────────────────

/**
 * Card containing all per-provider information: name, quality score, token count,
 * latency, cost, response text, and "Use This Response" button (Req 30.2, 30.5, 30.6).
 */
@Composable
private fun ProviderPanel(
    panel: ProviderPanelState,
    isAdopted: Boolean,
    onUseThisResponse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = when {
        isAdopted -> MaterialTheme.colorScheme.primary
        panel.status is ProviderPanelStatus.Error ||
            panel.status is ProviderPanelStatus.Timeout -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        modifier = modifier.semantics {
            contentDescription = "Provider panel: ${panel.providerName}"
        },
        colors = CardDefaults.cardColors(
            containerColor = if (isAdopted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isAdopted) 2.dp else 1.dp,
            color = borderColor
        )
    ) {
        Column(
            modifier = Modifier
                .padding(MaterialTheme.spacing.md)
                .verticalScroll(rememberScrollState())
        ) {
            PanelHeader(panel = panel, isAdopted = isAdopted)
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))
            PanelMetrics(panel = panel)
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))
            PanelResponseBody(panel = panel)
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))
            if (!isAdopted && panel.status is ProviderPanelStatus.Complete) {
                Button(
                    onClick = onUseThisResponse,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Use response from ${panel.providerName}"
                        }
                ) {
                    Text("Use This Response")
                }
            }
            if (isAdopted) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Selected",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

// ─── Panel sub-components ────────────────────────────────────────────────────

/**
 * Panel header: provider name, quality score badge, and status indicator.
 */
@Composable
private fun PanelHeader(panel: ProviderPanelState, isAdopted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = panel.providerName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        when (val status = panel.status) {
            is ProviderPanelStatus.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            }
            is ProviderPanelStatus.Streaming -> {
                LinearProgressIndicator(
                    modifier = Modifier
                        .width(48.dp)
                        .height(4.dp)
                )
            }
            is ProviderPanelStatus.Complete -> {
                panel.qualityScore?.let { score ->
                    QualityScoreBadge(score = score)
                }
            }
            is ProviderPanelStatus.Error -> {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = "Provider error",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
            is ProviderPanelStatus.Timeout -> {
                Icon(
                    imageVector = Icons.Filled.Timer,
                    contentDescription = "Provider timed out",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Quality score badge displaying 0–100 with colour coding (Req 30.5).
 */
@Composable
private fun QualityScoreBadge(score: Int) {
    val color = when {
        score >= 75 -> MaterialTheme.colorScheme.tertiary
        score >= 50 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.15f),
        modifier = Modifier.semantics {
            contentDescription = "Quality score: $score out of 100"
        }
    ) {
        Text(
            text = "$score",
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * Metrics row: token count, latency, estimated cost (Req 30.2).
 */
@Composable
private fun PanelMetrics(panel: ProviderPanelState) {
    val isComplete = panel.status is ProviderPanelStatus.Complete

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
    ) {
        MetricChip(
            label = "Tokens",
            value = if (isComplete) panel.tokenCount.toString() else "—"
        )
        MetricChip(
            label = "Latency",
            value = if (panel.latencyMs >= 0) "${panel.latencyMs} ms" else "—"
        )
        MetricChip(
            label = "Cost",
            value = if (isComplete) {
                "$${String.format(Locale.US, "%.4f", panel.estimatedCostUsd)}"
            } else {
                "—"
            }
        )
    }
}

/**
 * Small metric label + value chip.
 */
@Composable
private fun MetricChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Response body: Markdown-rendered response, error message, or loading/timeout placeholder.
 */
@Composable
private fun PanelResponseBody(panel: ProviderPanelState) {
    when (val status = panel.status) {
        is ProviderPanelStatus.Loading -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))
                    Text(
                        text = "Waiting for first token…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        is ProviderPanelStatus.Streaming -> {
            // Render partial Markdown as tokens stream in (Req 30.2)
            MarkdownText(
                markdown = panel.responseText,
                contentDescription = "Streaming response from ${panel.providerName}: ${panel.responseText}",
                modifier = Modifier.fillMaxWidth()
            )
        }

        is ProviderPanelStatus.Complete -> {
            MarkdownText(
                markdown = panel.responseText,
                contentDescription = "Response from ${panel.providerName}: ${panel.responseText}",
                modifier = Modifier.fillMaxWidth()
            )
        }

        is ProviderPanelStatus.Error -> {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Error from ${panel.providerName}: ${status.message}" }
            ) {
                Row(
                    modifier = Modifier.padding(MaterialTheme.spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        is ProviderPanelStatus.Timeout -> {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription =
                            "${panel.providerName} timed out after 30 seconds"
                    }
            ) {
                Row(
                    modifier = Modifier.padding(MaterialTheme.spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Timer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "No response received within 30 seconds.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

// ─── Previews ────────────────────────────────────────────────────────────────

@Preview(showBackground = true, name = "ComparisonMode — Prompt entry (disabled)")
@Composable
private fun ComparisonModeDisabledPreview() {
    AppTheme(dynamicColor = false) {
        ComparisonModeScreenContent(
            uiState = ComparisonModeUiState(isComparisonModeAvailable = false),
            selectedProviders = emptyList(),
            onDispatch = {},
            onUseThisResponse = {},
            onReset = {},
            onNavigateUp = {}
        )
    }
}

@Preview(showBackground = true, name = "ComparisonMode — Prompt entry (enabled)")
@Composable
private fun ComparisonModeEnabledPreview() {
    AppTheme(dynamicColor = false) {
        ComparisonModeScreenContent(
            uiState = ComparisonModeUiState(isComparisonModeAvailable = true),
            selectedProviders = listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO),
            onDispatch = {},
            onUseThisResponse = {},
            onReset = {},
            onNavigateUp = {}
        )
    }
}

@Preview(showBackground = true, name = "ComparisonMode — Panels with results")
@Composable
private fun ComparisonModeResultsPreview() {
    val panels = listOf(
        ProviderPanelState(
            providerId = "openai_gpt4o",
            providerName = "OpenAI GPT-4o",
            status = ProviderPanelStatus.Complete,
            responseText = "**Kotlin coroutines** simplify async programming.\n\nThey allow you to write sequential-looking code that runs asynchronously.",
            tokenCount = 82,
            latencyMs = 430L,
            estimatedCostUsd = 0.00246,
            qualityScore = 78
        ),
        ProviderPanelState(
            providerId = "gemini_1_5_pro",
            providerName = "Gemini 1.5 Pro",
            status = ProviderPanelStatus.Streaming,
            responseText = "Kotlin coroutines are a language feature...",
            tokenCount = 0,
            latencyMs = 610L
        ),
        ProviderPanelState(
            providerId = "claude_3_5_sonnet",
            providerName = "Claude 3.5 Sonnet",
            status = ProviderPanelStatus.Error("Rate limit exceeded"),
            responseText = "",
            tokenCount = 0,
            latencyMs = -1L
        )
    )
    AppTheme(dynamicColor = false) {
        ComparisonModeScreenContent(
            uiState = ComparisonModeUiState(
                prompt = "Explain Kotlin coroutines",
                panels = panels,
                isComparisonModeAvailable = true
            ),
            selectedProviders = listOf(
                LlmProvider.OPENAI_GPT4O,
                LlmProvider.GEMINI_1_5_PRO,
                LlmProvider.CLAUDE_3_5_SONNET
            ),
            onDispatch = {},
            onUseThisResponse = {},
            onReset = {},
            onNavigateUp = {}
        )
    }
}
