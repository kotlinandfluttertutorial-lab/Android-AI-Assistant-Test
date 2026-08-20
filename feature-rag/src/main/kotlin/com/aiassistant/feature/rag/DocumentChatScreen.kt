/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-rag
 * File       : DocumentChatScreen.kt
 * Purpose    : Compose UI screen for the DocumentChat feature
 *
 * Architecture Layer : Feature (feature-rag)
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
 * Module     : feature-rag
 * File       : DocumentChatScreen.kt
 * Purpose    : Compose UI screen for the DocumentChat feature
 *
 * Architecture Layer : Feature (feature-rag)
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
 * DocumentChatScreen.kt
 *
 * Purpose: Jetpack Compose screen for RAG-augmented document queries.
 *          Shows a query input field, the AI response rendered as Markdown,
 *          and a citation list with source document name and page number.
 * Architecture: feature-rag â€” Compose UI layer; state driven by [DocumentChatViewModel].
 * Dependencies: Compose Material 3, core-ui (MarkdownText, LoadingIndicator,
 *               ErrorBanner, AppTheme)
 *
 * Requirements: 4.6, 4.7
 */
package com.aiassistant.feature.rag

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.components.LoadingIndicator
import com.aiassistant.core.ui.components.LoadingIndicatorStyle
import com.aiassistant.core.ui.components.MarkdownText
import com.aiassistant.core.ui.spacing

// â”€â”€â”€ Screen entry point â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Stateful entry point for the DocumentChat screen. Collects state from
 * [DocumentChatViewModel] and delegates rendering to the stateless overload.
 *
 * @param viewModel   The Hilt-provided [DocumentChatViewModel].
 * @param onNavigateUp Callback invoked when the user presses the back button in the
 *                    top app bar.
 */
@Composable
fun DocumentChatScreen(viewModel: DocumentChatViewModel, onNavigateUp: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DocumentChatScreenContent(
        uiState = uiState,
        onSubmitQuery = viewModel::submitQuery,
        onReset = viewModel::resetToIdle,
        onNavigateUp = onNavigateUp
    )
}

// â”€â”€â”€ Stateless screen â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Stateless DocumentChat screen. All state is passed in; side-effects are communicated
 * via callbacks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DocumentChatScreenContent(
    uiState: DocumentChatUiState,
    onSubmitQuery: (String) -> Unit,
    onReset: () -> Unit,
    onNavigateUp: () -> Unit
) {
    val documentFileName = when (uiState) {
        is DocumentChatUiState.Idle -> uiState.documentFileName
        is DocumentChatUiState.Loading -> uiState.documentFileName
        is DocumentChatUiState.Success -> uiState.documentFileName
        is DocumentChatUiState.Error -> uiState.documentFileName
    }

    // Persist the draft query across recompositions (restored on process death).
    var queryDraft by rememberSaveable { mutableStateOf("") }

    // Pre-fill the query field with the last query on error so the user can retry easily.
    LaunchedEffect(uiState) {
        if (uiState is DocumentChatUiState.Error && uiState.lastQuery.isNotEmpty()) {
            queryDraft = uiState.lastQuery
        }
    }

    val listState = rememberLazyListState()

    // Scroll to the top of the response when a new result arrives.
    LaunchedEffect(uiState) {
        if (uiState is DocumentChatUiState.Success) {
            listState.animateScrollToItem(0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = documentFileName.ifEmpty { "Document Chat" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateUp,
                        modifier = Modifier.semantics {
                            contentDescription = "Navigate back to document list"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            // Query input sits at the bottom of the screen so it's always accessible
            // and the IME doesn't obscure it.
            QueryInputBar(
                query = queryDraft,
                onQueryChange = { queryDraft = it },
                onSubmit = {
                    onSubmitQuery(queryDraft)
                    queryDraft = ""
                },
                isLoading = uiState is DocumentChatUiState.Loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (uiState) {
                is DocumentChatUiState.Idle -> IdleContent()

                is DocumentChatUiState.Loading -> LoadingContent(query = uiState.query)

                is DocumentChatUiState.Success -> SuccessContent(
                    exchange = uiState.exchange,
                    listState = listState,
                    onAskAnother = onReset
                )

                is DocumentChatUiState.Error -> ErrorContent(
                    message = uiState.message,
                    onRetry = { onSubmitQuery(uiState.lastQuery) }
                )
            }
        }
    }
}

// â”€â”€â”€ Body content states â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/** Shown when no query has been submitted yet. */
@Composable
private fun IdleContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(MaterialTheme.spacing.md),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
        ) {
            Icon(
                imageVector = Icons.Filled.FormatQuote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(64.dp)
            )
            Text(
                text = "Ask a question about this document",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "The AI will retrieve the most relevant sections and cite its sources.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Shown while the RAG pipeline is processing the query. */
@Composable
private fun LoadingContent(query: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(MaterialTheme.spacing.md),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)
        ) {
            LoadingIndicator(
                style = LoadingIndicatorStyle.DOTS,
                contentDescription = "Searching document for: $query"
            )
            Text(
                text = "Searching documentâ€¦",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Shown when the RAG pipeline returns a cited response.
 *
 * Layout:
 * 1. User query bubble (right-aligned card)
 * 2. AI response rendered as Markdown
 * 3. Citations section â€” each citation shows document name + page number
 * 4. "Ask another question" action button
 */
@Composable
private fun SuccessContent(
    exchange: RAGExchange,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onAskAnother: () -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = MaterialTheme.spacing.md,
            vertical = MaterialTheme.spacing.sm
        ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
    ) {
        // â”€â”€ User query â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        item(key = "user_query") {
            UserQueryBubble(query = exchange.userQuery)
        }

        // â”€â”€ AI response â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        item(key = "ai_response") {
            AIResponseCard(responseMarkdown = exchange.aiResponse)
        }

        // â”€â”€ Citations section â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        if (exchange.citations.isNotEmpty()) {
            item(key = "citations_header") {
                HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.spacing.xs))
                Text(
                    text = "Sources",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MaterialTheme.spacing.xs)
                )
            }

            items(
                items = exchange.citations,
                key = { citation -> "${citation.documentName}:${citation.pageNumber}" }
            ) { citation ->
                CitationItem(citation = citation)
            }
        }

        // â”€â”€ Ask another question â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        item(key = "ask_another") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MaterialTheme.spacing.sm),
                contentAlignment = Alignment.CenterEnd
            ) {
                TextButton(
                    onClick = onAskAnother,
                    modifier = Modifier.semantics {
                        contentDescription = "Ask another question about this document"
                    }
                ) {
                    Text(
                        text = "Ask another question",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

/** Shown when the RAG query fails. */
@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(MaterialTheme.spacing.md),
        contentAlignment = Alignment.Center
    ) {
        ErrorBanner(
            message = message,
            onRetry = onRetry,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// â”€â”€â”€ Sub-composables â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Right-aligned bubble showing the user's query text.
 *
 * Uses a primary-container fill (matching [ChatBubble] for visual consistency) without
 * depending on it â€” keeps the citation-specific layout self-contained.
 */
@Composable
private fun UserQueryBubble(query: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Your question: $query" },
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(MaterialTheme.spacing.sm)) {
                Text(
                    text = "You",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))
                Text(
                    text = query,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

/**
 * Card rendering the AI-generated response as Markdown.
 *
 * Surface variant fill distinguishes the AI response from the user query (and from
 * citation cards below).
 */
@Composable
private fun AIResponseCard(responseMarkdown: String) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "AI response" },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(MaterialTheme.spacing.md)) {
                Text(
                    text = "Assistant",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))
                MarkdownText(
                    markdown = responseMarkdown,
                    contentDescription = responseMarkdown,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * A single citation row showing the source document name and page number.
 *
 * Layout: [Article icon] Â· [document name] Â· page [N]
 *
 * Requirement 4.7: every RAG response includes citations referencing the source
 * Document name and page number for each retrieved Chunk.
 */
@Composable
private fun CitationItem(citation: Citation) {
    val pageLabel = citation.pageNumber?.let { "page $it" } ?: "unknown page"
    val a11yLabel = "Source: ${citation.documentName}, $pageLabel"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.spacing.xs)
            .semantics { contentDescription = a11yLabel },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Article,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = citation.documentName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (citation.pageNumber != null) {
            Text(
                text = "p. ${citation.pageNumber}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// â”€â”€â”€ Query input bar â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Persistent bottom input bar with a text field and send button.
 *
 * The send button is disabled while [isLoading] is true so the user cannot submit
 * another query while one is already in flight.
 *
 * @param query         Current draft text in the input field.
 * @param onQueryChange Callback invoked on every keystroke to update [query].
 * @param onSubmit      Callback invoked when the user taps send or presses the IME
 *                      action button.
 * @param isLoading     Whether to disable the send button and show a spinner inside it.
 * @param modifier      Optional [Modifier] applied to the root [Surface].
 */
@Composable
private fun QueryInputBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.md,
                    vertical = MaterialTheme.spacing.sm
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Query input" },
                placeholder = {
                    Text(
                        text = "Ask about this documentâ€¦",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                singleLine = false,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = { if (!isLoading && query.isNotBlank()) onSubmit() }
                ),
                enabled = !isLoading,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = MaterialTheme.shapes.medium
            )

            // Send / Loading button
            IconButton(
                onClick = onSubmit,
                enabled = !isLoading && query.isNotBlank(),
                modifier = Modifier.semantics {
                    contentDescription = if (isLoading) "Waiting for response" else "Submit query"
                }
            ) {
                if (isLoading) {
                    LoadingIndicator(
                        style = LoadingIndicatorStyle.CIRCULAR,
                        contentDescription = "Processing query",
                        size = 20.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        tint = if (query.isNotBlank()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }
            }
        }
    }
}

// â”€â”€â”€ Previews â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

private val previewCitations = listOf(
    Citation(documentName = "annual_report.pdf", pageNumber = 5),
    Citation(documentName = "annual_report.pdf", pageNumber = 12),
    Citation(documentName = "product_spec.docx", pageNumber = 3)
)

private val previewExchange = RAGExchange(
    userQuery = "What were the key revenue drivers in Q3?",
    aiResponse = """
        Based on the document, the key revenue drivers in Q3 were:
        
        1. **Cloud services** grew 34% YoY, contributing \$2.1B in revenue [1]
        2. **Enterprise software licences** maintained steady growth at 12% [2]
        3. **Professional services** expanded into APAC markets [2]
        
        The company also noted that *currency headwinds* reduced reported revenue by approximately 3% [3].
    """.trimIndent(),
    citations = previewCitations
)

@Preview(showBackground = true, name = "DocumentChat â€“ Idle")
@Composable
private fun DocumentChatIdlePreview() {
    AppTheme(dynamicColor = false) {
        DocumentChatScreenContent(
            uiState = DocumentChatUiState.Idle(documentFileName = "annual_report.pdf"),
            onSubmitQuery = {},
            onReset = {},
            onNavigateUp = {}
        )
    }
}

@Preview(showBackground = true, name = "DocumentChat â€“ Loading")
@Composable
private fun DocumentChatLoadingPreview() {
    AppTheme(dynamicColor = false) {
        DocumentChatScreenContent(
            uiState = DocumentChatUiState.Loading(
                query = "What were the key revenue drivers in Q3?",
                documentFileName = "annual_report.pdf"
            ),
            onSubmitQuery = {},
            onReset = {},
            onNavigateUp = {}
        )
    }
}

@Preview(showBackground = true, name = "DocumentChat â€“ Success with citations")
@Composable
private fun DocumentChatSuccessPreview() {
    AppTheme(dynamicColor = false) {
        DocumentChatScreenContent(
            uiState = DocumentChatUiState.Success(
                exchange = previewExchange,
                documentFileName = "annual_report.pdf"
            ),
            onSubmitQuery = {},
            onReset = {},
            onNavigateUp = {}
        )
    }
}

@Preview(showBackground = true, name = "DocumentChat â€“ Error")
@Composable
private fun DocumentChatErrorPreview() {
    AppTheme(dynamicColor = false) {
        DocumentChatScreenContent(
            uiState = DocumentChatUiState.Error(
                message = "Failed to query document. Please check your connection and try again.",
                lastQuery = "What were the key revenue drivers in Q3?",
                documentFileName = "annual_report.pdf"
            ),
            onSubmitQuery = {},
            onReset = {},
            onNavigateUp = {}
        )
    }
}

@Preview(showBackground = true, name = "DocumentChat â€“ Success no citations")
@Composable
private fun DocumentChatSuccessNoCitationsPreview() {
    AppTheme(dynamicColor = false) {
        DocumentChatScreenContent(
            uiState = DocumentChatUiState.Success(
                exchange = RAGExchange(
                    userQuery = "Summarise this document",
                    aiResponse = "This document covers the annual financial results for FY2024.",
                    citations = emptyList()
                ),
                documentFileName = "annual_report.pdf"
            ),
            onSubmitQuery = {},
            onReset = {},
            onNavigateUp = {}
        )
    }
}
