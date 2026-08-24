/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : HabitInsightsScreen.kt
 * Purpose    : Compose UI screen for the HabitInsights feature
 *
 * Architecture Layer : Feature (feature-productivity)
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
 * Module     : feature-productivity
 * File       : HabitInsightsScreen.kt
 * Purpose    : Compose UI screen for the HabitInsights feature
 *
 * Architecture Layer : Feature (feature-productivity)
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
 * HabitInsightsScreen.kt
 *
 * Purpose: Compose screen displaying AI-generated insights for a tracked habit,
 *          covering completion patterns, best/worst days, and streak predictions.
 *
 * Architecture: feature-productivity â€” Compose UI layer; stateless composable driven
 *               by HabitUiState from HabitViewModel.
 * Dependencies: core-ui (ErrorBanner, MaterialTheme.spacing), HabitUiState.
 *
 * Design decisions:
 * - Stateless composable: all state + callbacks passed as parameters.
 * - Loading state shows CircularProgressIndicator + "Generating insightsâ€¦" text.
 * - Empty state (no insights text, not loading) shows prompt to fetch insights.
 * - Insights text rendered in a scrollable Card.
 * - All interactive elements carry contentDescription (Requirements 23.1, 23.4).
 *
 * Requirements: 13.1, 19.1
 */
package com.aiassistant.feature.productivity.habit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.spacing

/**
 * Habit insights screen composable.
 *
 * @param uiState  Current state from [HabitViewModel].
 * @param onBack   Invoked when the user taps the back navigation arrow.
 * @param onRetry  Invoked when the user taps "Get Insights" in the empty state.
 */
@Composable
fun HabitInsightsScreen(uiState: HabitUiState, onBack: () -> Unit, onRetry: () -> Unit) {
    // â”€â”€ Resolve insights state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    val insightsState = uiState as? HabitUiState.HabitInsights
    val habitName = insightsState?.habit?.name ?: ""

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Habit Insights")
                        if (habitName.isNotBlank()) {
                            Text(
                                text = habitName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                // â”€â”€ Error state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                uiState is HabitUiState.Error -> {
                    ErrorBanner(
                        message = uiState.message,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MaterialTheme.spacing.md)
                    )
                }

                // â”€â”€ Loading state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                insightsState?.isLoading == true -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics {
                                contentDescription = "Generating insights"
                            }
                        )
                        Text(
                            text = "Generating insightsâ€¦",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = MaterialTheme.spacing.sm)
                        )
                    }
                }

                // â”€â”€ Insights available â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                insightsState != null && insightsState.insightsText.isNotBlank() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(MaterialTheme.spacing.md),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "Habit insights for $habitName" },
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Text(
                                text = insightsState.insightsText,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(MaterialTheme.spacing.md)
                            )
                        }
                    }
                }

                // â”€â”€ Empty state (no insights, not loading) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                insightsState != null -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Insights,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "No insights yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = MaterialTheme.spacing.sm)
                        )
                        Button(
                            onClick = onRetry,
                            modifier = Modifier
                                .padding(top = MaterialTheme.spacing.md)
                                .semantics { contentDescription = "Get AI habit insights" }
                        ) {
                            Text("Get Insights")
                        }
                    }
                }

                // â”€â”€ Fallback (state not yet HabitInsights â€” should not occur) â”€â”€
                else -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics {
                                contentDescription = "Loading"
                            }
                        )
                    }
                }
            }
        }
    }
}
