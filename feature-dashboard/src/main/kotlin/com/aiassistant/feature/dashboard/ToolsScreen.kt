/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-dashboard
 * File       : ToolsScreen.kt
 * Purpose    : AI Tools Library screen — the "AI Tools" bottom-nav tab.
 *              Displays a search bar, a featured-tool banner, and a 2-column
 *              grid of specialized AI tool cards.
 *
 * Architecture Layer : feature-dashboard — Compose UI layer.
 *                      Stateless: all navigation is delegated via
 *                      [onNavigateToTool] callback.  No ViewModel needed
 *                      for Phase C (static tool list); a ToolsViewModel
 *                      will be added in Phase D when search filtering and
 *                      recently-used tools are introduced.
 *
 * Design reference   : ToolsScreen.tsx
 *                      - Header: h2 "AI Tools" + subtitle
 *                      - SearchInputBar (non-interactive in design — editable here)
 *                      - FeaturedBannerCard: rose gradient "Resume Builder AI"
 *                      - 2-column grid of tool cards (12 items)
 *
 * Design decisions   :
 * - The tool list is defined as a local constant (same 12 items from Stitch).
 *   Search filtering is wired to a local `query` state so the UI is immediately
 *   interactive without a ViewModel — consistent with the principle of not
 *   adding abstraction before it's needed.
 * - [QuickActionFixedGrid] (non-lazy, 2-column) is used instead of [LazyVerticalGrid]
 *   because ToolsScreen is itself inside a [verticalScroll] parent — nested
 *   lazy layouts would cause a measurement exception.
 * - [SearchInputBar] with [SearchInputBarMode.EDITABLE] enables live filtering.
 * - Tool cards navigate via [onNavigateToTool] so the screen itself doesn't
 *   import any other feature module's route constants.
 *
 * Requirements       : 23.1, 24.1
 * ============================================================
 */
package com.aiassistant.feature.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import com.aiassistant.core.ui.AppIcons
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.StitchColors
import com.aiassistant.core.ui.components.FeaturedBannerCard
import com.aiassistant.core.ui.components.QuickAction
import com.aiassistant.core.ui.components.QuickActionFixedGrid
import com.aiassistant.core.ui.components.SearchInputBar
import com.aiassistant.core.ui.components.SearchInputBarMode
import com.aiassistant.core.ui.spacing

// ── Tool data model ───────────────────────────────────────────────────────────

/**
 * Represents a single AI tool card in the grid.
 *
 * [route] is the navigation destination string the host activity will navigate
 * to when this card is tapped — the screen itself never imports route constants.
 */
private data class ToolItem(
    val id: String,
    val label: String,
    val description: String,
    val icon: ImageVector,
    val iconBg: Color,
    val iconTint: Color,
    val route: String,
)

// ── 12-item tool list (matches ToolsScreen.tsx exactly) ──────────────────────

private val ALL_TOOLS = listOf(
    ToolItem("summarizer",   "Summarizer",     "Condense any text",            AppIcons.Documents.Citation,          StitchColors.qaChat,      StitchColors.qaIconChat,      "chat/list"),
    ToolItem("translator",   "Translator",     "100+ languages",               AppIcons.Settings.About,              StitchColors.qaVoice,     StitchColors.qaIconVoice,     "translator"),
    ToolItem("grammar",      "Grammar Check",  "Fix errors instantly",         AppIcons.Chat.Regenerate,             StitchColors.qaImage,     StitchColors.qaIconImage,     "chat/list"),
    ToolItem("resume",       "Resume Builder", "Stand out from the crowd",     AppIcons.Documents.Document,          StitchColors.qaPdf,       StitchColors.qaIconPdf,       "resume"),
    ToolItem("email",        "Email Writer",   "Professional emails fast",     AppIcons.Chat.Share,                  StitchColors.qaTools,     StitchColors.qaIconTools,     "email"),
    ToolItem("meeting",      "Meeting Notes",  "Transcribe & summarize",       AppIcons.Chat.Mic,                    StitchColors.qaVoice,     StitchColors.qaIconVoice,     "voice"),
    ToolItem("todo",         "To-Do Generator","From text to tasks",           AppIcons.Status.Success,              StitchColors.qaImage,     StitchColors.qaIconImage,     "chat/list"),
    ToolItem("code",         "Code Generator", "Build anything with AI",       AppIcons.Chat.Code,                   StitchColors.qaCode,      StitchColors.qaIconCode,      "code"),
    ToolItem("image",        "Image Creator",  "Generate stunning visuals",    AppIcons.Chat.Camera,                 StitchColors.qaTools,     StitchColors.qaIconTools,     "camera"),
    ToolItem("data",         "Data Analyzer",  "Insights from your data",      AppIcons.Documents.Source,            StitchColors.qaChat,      StitchColors.qaIconChat,      "rag/documents"),
    ToolItem("research",     "Research Helper","Deep dive any topic",          AppIcons.Chat.Search,                 StitchColors.qaVoice,     StitchColors.qaIconVoice,     "chat/list"),
    ToolItem("finance",      "Finance Advisor","Smart money decisions",        AppIcons.Status.Info,                 StitchColors.qaImage,     StitchColors.qaIconImage,     "chat/list"),
)

// ── Screen ────────────────────────────────────────────────────────────────────

/**
 * AI Tools Library screen.
 *
 * @param onNavigateToTool Called with a route string when a tool card is tapped.
 *                         The host (app module) translates the route string into
 *                         a `navController.navigate()` call.
 * @param modifier         Optional modifier for the screen root.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    onNavigateToTool: (route: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val displayedTools = if (searchQuery.isBlank()) {
        ALL_TOOLS
    } else {
        ALL_TOOLS.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
                it.description.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "AI Tools",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = "Specialized AI for every task",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Search bar ────────────────────────────────────────────────────
            SearchInputBar(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "Search tools…",
                mode = SearchInputBarMode.EDITABLE,
                onClear = { searchQuery = "" },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = MaterialTheme.spacing.screenEdge,
                        vertical = MaterialTheme.spacing.sm,
                    ),
            )

            // ── Featured banner — only show when not filtering ─────────────
            if (searchQuery.isBlank()) {
                FeaturedBannerCard(
                    headline = "Resume Builder AI",
                    body = "Create ATS-optimized resumes tailored to any job description in minutes.",
                    gradientStart = StitchColors.toolsFeaturedStart,
                    gradientEnd   = StitchColors.toolsFeaturedEnd,
                    tag = "Featured",
                    ctaLabel = "Try now →",
                    onCtaClick = { onNavigateToTool("resume") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = MaterialTheme.spacing.screenEdge,
                            end = MaterialTheme.spacing.screenEdge,
                            bottom = MaterialTheme.spacing.md,
                        ),
                )
            }

            // ── Section header ────────────────────────────────────────────────
            Text(
                text = if (searchQuery.isBlank()) "All Tools" else "Search results",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    horizontal = MaterialTheme.spacing.screenEdge,
                    vertical = MaterialTheme.spacing.xs,
                ),
            )

            // ── 2-column tools grid ───────────────────────────────────────────
            if (displayedTools.isEmpty()) {
                // Empty search state
                Text(
                    text = "No tools match \"$searchQuery\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = MaterialTheme.spacing.screenEdge,
                        vertical = MaterialTheme.spacing.lg,
                    ),
                )
            } else {
                QuickActionFixedGrid(
                    actions = displayedTools.map { tool ->
                        QuickAction(
                            id    = tool.id,
                            label = tool.label,
                            icon  = tool.icon,
                            iconBg   = tool.iconBg,
                            iconTint = tool.iconTint,
                            actionDescription = tool.description,
                        )
                    },
                    onActionClick = { id ->
                        ALL_TOOLS.find { it.id == id }?.let { onNavigateToTool(it.route) }
                    },
                    columns = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.screenEdge),
                )
            }

            Spacer(Modifier.height(MaterialTheme.spacing.xl))
        }
    }
}

// ── Preview ───────────────────────────────────────────────────────────────────

@Preview(showBackground = true, name = "ToolsScreen — all tools")
@Composable
private fun ToolsScreenPreview() {
    AppTheme(dynamicColor = false) {
        ToolsScreen()
    }
}
