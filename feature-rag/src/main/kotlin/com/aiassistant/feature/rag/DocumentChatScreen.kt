/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-rag
 * File       : DocumentChatScreen.kt
 * Purpose    : Redesigned RAG document chat screen (Task 50.5) with:
 *              - SourcesPanel: collapsible citation list below each answer
 *              - Blockquote styling: 4 dp gradient left-border on assistant bubbles
 *              - Gradient pill send button matching ChatDetailScreen style
 *              - core-ui TypingIndicator during RAG processing
 *
 * Architecture Layer : Feature (feature-rag) — Compose UI layer.
 *                      State driven by DocumentChatViewModel; maps existing
 *                      Idle/Loading/Success/Error states to chat UI.
 *
 * Dependencies       : core-ui (AppColors, AppType, MarkdownText, ErrorBanner,
 *                      TypingIndicator, spacing, elevation),
 *                      domain (Citation, RAGExchange).
 *
 * Requirements       : 4.6, 4.7
 * ============================================================
 */
package com.aiassistant.feature.rag

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiassistant.core.ui.AppColors
import com.aiassistant.core.ui.AppType
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.components.MarkdownText
import com.aiassistant.core.ui.elevation
import com.aiassistant.core.ui.motion.TypingIndicator
import com.aiassistant.core.ui.spacing

// ── Screen entry point ────────────────────────────────────────────────────────

@Composable
fun DocumentChatScreen(
    viewModel: DocumentChatViewModel,
    onNavigateUp: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DocumentChatScreenContent(
        uiState = uiState,
        onSubmitQuery = viewModel::submitQuery,
        onRetry = { viewModel.submitQuery((uiState as? DocumentChatUiState.Error)?.lastQuery ?: "") },
        onNavigateUp = onNavigateUp,
    )
}

// ── Stateless content ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DocumentChatScreenContent(
    uiState: DocumentChatUiState,
    onSubmitQuery: (String) -> Unit,
    onRetry: () -> Unit,
    onNavigateUp: () -> Unit,
) {
    val listState = rememberLazyListState()
    val isLoading = uiState is DocumentChatUiState.Loading
    val hasResult = uiState is DocumentChatUiState.Success

    LaunchedEffect(uiState) {
        if (hasResult) listState.animateScrollToItem(0)
    }

    Scaffold(
        topBar = {
            val docName = when (uiState) {
                is DocumentChatUiState.Idle -> uiState.documentFileName
                is DocumentChatUiState.Loading -> uiState.documentFileName
                is DocumentChatUiState.Success -> uiState.documentFileName
                is DocumentChatUiState.Error -> uiState.documentFileName
            }.ifBlank { "Document Q&A" }
            TopAppBar(
                title = { Text(docName, maxLines = 1) },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateUp,
                        modifier = Modifier.semantics { contentDescription = "Navigate back" },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        bottomBar = {
            DocumentQueryInputBar(
                isQuerying = isLoading,
                onSendQuery = onSubmitQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding(),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Error banner
            if (uiState is DocumentChatUiState.Error) {
                ErrorBanner(
                    message = uiState.message,
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            when {
                // ── Idle: empty state ──────────────────────────────────────
                uiState is DocumentChatUiState.Idle -> {
                    Box(
                        Modifier.fillMaxSize().padding(MaterialTheme.spacing.lg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Ask a question about this document.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics {
                                contentDescription = "Ask a question about this document"
                            },
                        )
                    }
                }

                // ── Loading: typing indicator ──────────────────────────────
                uiState is DocumentChatUiState.Loading -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = MaterialTheme.spacing.sm,
                            vertical = MaterialTheme.spacing.sm,
                        ),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                    ) {
                        // Show submitted query as user bubble while waiting
                        item(key = "query") {
                            UserQueryBubble(text = uiState.query)
                        }
                        item(key = "typing") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(
                                    start = MaterialTheme.spacing.sm,
                                    top = MaterialTheme.spacing.xs,
                                ),
                                horizontalArrangement = Arrangement.Start,
                            ) {
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.semantics {
                                        contentDescription = "Searching document, please wait"
                                    },
                                ) {
                                    TypingIndicator(
                                        modifier = Modifier.padding(
                                            horizontal = MaterialTheme.spacing.md,
                                            vertical = MaterialTheme.spacing.sm,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Success: Q&A pair ──────────────────────────────────────
                uiState is DocumentChatUiState.Success -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = MaterialTheme.spacing.sm,
                            vertical = MaterialTheme.spacing.sm,
                        ),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                    ) {
                        item(key = "userQuery") {
                            UserQueryBubble(text = uiState.exchange.userQuery)
                        }
                        item(key = "answer") {
                            AssistantAnswerBubble(
                                text = uiState.exchange.aiResponse,
                                citations = uiState.exchange.citations,
                            )
                        }
                    }
                }

                // ── Error: handled by banner above, show empty list ────────
                else -> {
                    Box(Modifier.fillMaxSize()) { /* ErrorBanner shown above */ }
                }
            }
        }
    }
}

// ── User query bubble ─────────────────────────────────────────────────────────

@Composable
private fun UserQueryBubble(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = MaterialTheme.spacing.xl, end = MaterialTheme.spacing.xs),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = 18.dp, bottomEnd = 4.dp,
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { contentDescription = "You: $text" },
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.padding(
                    horizontal = MaterialTheme.spacing.md,
                    vertical = MaterialTheme.spacing.sm,
                ),
            )
        }
    }
}

// ── Assistant answer bubble with blockquote + SourcesPanel ────────────────────

/**
 * Assistant answer with:
 * - surfaceTonal2 card background
 * - 4 dp gradient left-border accent stripe ("blockquote" styling)
 * - Collapsible [SourcesPanel] listing citation cards
 */
@Composable
private fun AssistantAnswerBubble(
    text: String,
    citations: List<Citation>,
) {
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark) AppColors.surfaceTonal2Dark else AppColors.surfaceTonal2Light
    val accentColor = if (isDark) AppColors.gradientStartDark else AppColors.gradientStartLight

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = MaterialTheme.spacing.xl),
    ) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Assistant answer: $text" },
            elevation = CardDefaults.elevatedCardElevation(
                defaultElevation = MaterialTheme.elevation.low,
            ),
            colors = CardDefaults.elevatedCardColors(containerColor = cardColor),
            shape = RoundedCornerShape(
                topStart = 4.dp, topEnd = 18.dp,
                bottomStart = 18.dp, bottomEnd = 18.dp,
            ),
        ) {
            Column(
                modifier = Modifier.padding(
                    // Offset right of the 4dp blockquote stripe
                    start = MaterialTheme.spacing.md + 4.dp,
                    end = MaterialTheme.spacing.md,
                    top = MaterialTheme.spacing.sm,
                    bottom = MaterialTheme.spacing.sm,
                ),
            ) {
                MarkdownText(
                    markdown = text,
                    contentDescription = text,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (citations.isNotEmpty()) {
                    Spacer(Modifier.height(MaterialTheme.spacing.sm))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(MaterialTheme.spacing.xs))
                    SourcesPanel(citations = citations)
                }
            }
        }

        // 4 dp blockquote accent stripe overlaid on the card's left edge
        Box(
            modifier = Modifier
                .width(4.dp)
                .matchParentSize()
                .clip(
                    RoundedCornerShape(topStart = 4.dp, bottomStart = 18.dp)
                )
                .background(accentColor),
        )
    }
}

// ── SourcesPanel ──────────────────────────────────────────────────────────────

/**
 * Collapsible citation panel.  Collapsed by default; a "Show sources (N)" button expands it.
 */
@Composable
private fun SourcesPanel(
    citations: List<Citation>,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.semantics {
                contentDescription = if (expanded) "Hide sources"
                else "Show ${citations.size} source${if (citations.size != 1) "s" else ""}"
            },
            contentPadding = PaddingValues(horizontal = MaterialTheme.spacing.xs, vertical = 0.dp),
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (expanded) "Hide sources" else "Show sources (${citations.size})",
                style = MaterialTheme.typography.labelMedium,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                modifier = Modifier.padding(top = MaterialTheme.spacing.xs),
            ) {
                citations.forEach { citation ->
                    CitationCard(citation = citation)
                }
            }
        }
    }
}

// ── Citation card ─────────────────────────────────────────────────────────────

@Composable
private fun CitationCard(citation: Citation) {
    val isDark = isSystemInDarkTheme()
    val bgColor = if (isDark) AppColors.surfaceTonal3Dark else AppColors.surfaceTonal3Light

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription =
                    "Source: ${citation.documentName}" +
                        (citation.pageNumber?.let { ", page $it" } ?: "")
            },
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = bgColor),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.sm),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.FormatQuote,
                contentDescription = null,
                modifier = Modifier.size(14.dp).padding(top = 2.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column {
                Text(
                    text = buildString {
                        append(citation.documentName)
                        citation.pageNumber?.let { append(" · p. $it") }
                    },
                    style = AppType.sectionLabel,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ── Query input bar ───────────────────────────────────────────────────────────

@Composable
private fun DocumentQueryInputBar(
    isQuerying: Boolean,
    onSendQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val isDark = isSystemInDarkTheme()
    val gradientStart = if (isDark) AppColors.gradientStartDark else AppColors.gradientStartLight
    val gradientEnd   = if (isDark) AppColors.gradientEndDark   else AppColors.gradientEndLight
    val canSend = query.isNotBlank() && !isQuerying

    Surface(modifier = modifier, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.sm,
                    vertical = MaterialTheme.spacing.xs,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(28.dp))
                    .semantics { contentDescription = "Ask about this document" },
                placeholder = { Text("Ask about this document…") },
                enabled = !isQuerying,
                singleLine = false,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                ),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                shape = RoundedCornerShape(28.dp),
            )

            Spacer(Modifier.width(MaterialTheme.spacing.xs))

            // Gradient send button
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (canSend)
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(gradientStart, gradientEnd)
                            )
                        else
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    MaterialTheme.colorScheme.surfaceVariant,
                                )
                            ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(
                    onClick = {
                        val trimmed = query.trim()
                        if (trimmed.isNotEmpty()) { onSendQuery(trimmed); query = "" }
                    },
                    enabled = canSend,
                    modifier = Modifier
                        .matchParentSize()
                        .semantics { contentDescription = "Send query" },
                ) {
                    if (isQuerying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            tint = if (canSend) Color.White
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
