/**
 * DashboardScreen.kt — feature-dashboard module
 *
 * The main AI DevOps Dashboard screen as described in the master plan:
 *
 *   ┌─────────────────────────────────┐
 *   │  AI DevOps Dashboard            │
 *   ├─────────────────────────────────┤
 *   │  🔴 Critical    2  🟡 High  5  │
 *   │  🟢 Medium      1  ⚪ Low   0  │
 *   ├─────────────────────────────────┤
 *   │  AI Error Analysis ▾            │
 *   │  Root cause: Connection pool…   │
 *   │  Confidence: 87%                │
 *   ├─────────────────────────────────┤
 *   │  Recent Incidents               │
 *   │  INC-xxx  DB timeout   HIGH     │
 *   │  INC-yyy  OOM error    MED      │
 *   ├─────────────────────────────────┤
 *   │  DevOps Assistant               │
 *   │  [Ask anything…          ▷]    │
 *   └─────────────────────────────────┘
 *
 * Accessibility:
 *   - All interactive elements have contentDescriptions
 *   - Error and loading states are announced via semantics
 *   - Contrast ratios follow Material 3 requirements
 *
 * Phase 14 — Android AI DevOps Dashboard
 */
package com.aiassistant.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.components.OfflineBanner
import com.aiassistant.domain.model.Incident
import com.aiassistant.feature.dashboard.components.AiAnalysisCard
import com.aiassistant.feature.dashboard.components.DevOpsChatCard
import com.aiassistant.feature.dashboard.components.IncidentListItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onIncidentClick: (String) -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState   by viewModel.uiState.collectAsState()
    val chatState by viewModel.chatState.collectAsState()
    val isOffline by viewModel.isOffline.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI DevOps Dashboard") },
                actions = {
                    IconButton(
                        onClick = viewModel::refresh,
                        modifier = Modifier.semantics { contentDescription = "Refresh dashboard" },
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Offline banner (persistent at top)
            if (isOffline) {
                OfflineBanner(modifier = Modifier.fillMaxWidth())
            }

            when (val state = uiState) {
                is DashboardUiState.Loading -> {
                    Box(
                        modifier          = Modifier.fillMaxSize(),
                        contentAlignment  = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics {
                                contentDescription = "Loading dashboard"
                            }
                        )
                    }
                }

                is DashboardUiState.Error -> {
                    ErrorBanner(
                        message   = state.message,
                        onRetry   = viewModel::refresh,
                        modifier  = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    )
                }

                is DashboardUiState.Content -> {
                    PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh    = viewModel::refresh,
                        modifier     = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(
                            modifier              = Modifier.fillMaxSize(),
                            verticalArrangement   = Arrangement.spacedBy(12.dp),
                            contentPadding        = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 16.dp,
                                vertical   = 12.dp,
                            ),
                        ) {
                            // ── Incident count chips ──────────────────────────
                            item(key = "counts") {
                                IncidentCountsRow(counts = state.counts)
                            }

                            // ── AI Error Analysis card ────────────────────────
                            state.aiAnalysis?.let { analysis ->
                                item(key = "analysis") {
                                    AiAnalysisCard(analysis = analysis)
                                }
                            }

                            // ── Recent Incidents header ───────────────────────
                            item(key = "incidents_header") {
                                Text(
                                    text  = "Recent Incidents (${state.incidents.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            if (state.incidents.isEmpty()) {
                                item(key = "incidents_empty") {
                                    Text(
                                        text  = "No incidents in the last 20 records.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.semantics {
                                            contentDescription = "No incidents found"
                                        },
                                    )
                                }
                            } else {
                                items(
                                    items = state.incidents,
                                    key   = { it.id },
                                ) { incident ->
                                    IncidentListItem(
                                        incident = incident,
                                        onClick  = { onIncidentClick(incident.id) },
                                    )
                                }
                            }

                            // ── DevOps Assistant ──────────────────────────────
                            item(key = "chat") {
                                DevOpsChatCard(
                                    chatState = chatState,
                                    onSubmit  = viewModel::askQuestion,
                                    onClear   = viewModel::clearChat,
                                )
                            }

                            item(key = "bottom_spacer") { Spacer(Modifier.height(8.dp)) }
                        }
                    }
                }
            }
        }
    }
}

// ── Incident count summary row ─────────────────────────────────────────────────

@Composable
private fun IncidentCountsRow(counts: IncidentCounts, modifier: Modifier = Modifier) {
    Row(
        modifier              = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CountChip(
            label  = "Critical",
            count  = counts.critical,
            color  = MaterialTheme.colorScheme.error,
            onColor = MaterialTheme.colorScheme.onError,
            modifier = Modifier.weight(1f),
        )
        CountChip(
            label  = "High",
            count  = counts.high,
            color  = MaterialTheme.colorScheme.errorContainer,
            onColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        CountChip(
            label  = "Medium",
            count  = counts.medium,
            color  = androidx.compose.ui.graphics.Color(0xFFFFDDB3),
            onColor = androidx.compose.ui.graphics.Color(0xFF5B2D00),
            modifier = Modifier.weight(1f),
        )
        CountChip(
            label  = "Open",
            count  = counts.open,
            color  = MaterialTheme.colorScheme.primaryContainer,
            onColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CountChip(
    label:    String,
    count:    Int,
    color:    androidx.compose.ui.graphics.Color,
    onColor:  androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.semantics {
            contentDescription = "$count $label incidents"
        },
        colors   = CardDefaults.cardColors(containerColor = color),
    ) {
        Column(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalAlignment   = Alignment.CenterHorizontally,
        ) {
            Text(
                text       = count.toString(),
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color      = onColor,
            )
            Text(
                text  = label,
                style = MaterialTheme.typography.labelSmall,
                color = onColor,
            )
        }
    }
}
