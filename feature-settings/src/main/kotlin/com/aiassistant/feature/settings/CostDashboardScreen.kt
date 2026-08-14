/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-settings
 * File       : CostDashboardScreen.kt
 * Purpose    : Compose UI for the AI Cost Dashboard screen
 *
 * Architecture Layer : Feature (feature-settings) — Presentation
 * Pattern Used       : Jetpack Compose, Material3, Canvas bar chart
 *
 * Key Concepts:
 *   - Shows monthly summary: total tokens, total cost, bar chart of daily costs
 *   - Persistent banner for triggered spending alerts (dismissible only by user)
 *   - Inline error on 4th alert attempt
 *   - Loading state up to 10 seconds before showing error
 *   - Breakdowns by feature, LLM provider, and calendar day
 *
 * Requirements: 34.1, 34.2, 34.3, 34.4, 34.5, 34.6
 * ============================================================
 */

package com.aiassistant.feature.settings

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.CostSummary
import com.aiassistant.domain.model.DailyCostRow
import com.aiassistant.domain.model.SpendingAlert
import java.util.Locale

// ─── Root screen ──────────────────────────────────────────────────────────────

/**
 * Root composable for the AI Cost Dashboard screen.
 *
 * @param uiState            Current state emitted by [CostDashboardViewModel].
 * @param onNavigateUp       Pops the back stack.
 * @param onAddAlert         Called with the threshold USD value.
 * @param onDeleteAlert      Called with the alert ID to delete.
 * @param onDismissBanner    Called with the alert ID to dismiss the persistent banner.
 * @param onClearAlertError  Called to clear the inline alert-limit error.
 * @param onRetry            Called to reload data after error.
 *
 * Requirements: 34.1, 34.2, 34.3, 34.4, 34.5, 34.6
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun costDashboardScreen(
    uiState: CostDashboardUiState,
    onNavigateUp: () -> Unit,
    onAddAlert: (Double) -> Unit,
    onDeleteAlert: (String) -> Unit,
    onDismissBanner: (String) -> Unit,
    onClearAlertError: () -> Unit,
    onRetry: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Cost Dashboard") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateUp,
                        modifier = Modifier.semantics {
                            contentDescription = "Navigate back from Cost Dashboard"
                        }
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { innerPadding ->
        when (uiState) {
            is CostDashboardUiState.Loading -> {
                dashboardLoadingContent(modifier = Modifier.padding(innerPadding))
            }
            is CostDashboardUiState.Ready -> {
                dashboardReadyContent(
                    state = uiState,
                    onAddAlert = onAddAlert,
                    onDeleteAlert = onDeleteAlert,
                    onDismissBanner = onDismissBanner,
                    onClearAlertError = onClearAlertError,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            is CostDashboardUiState.Error -> {
                dashboardErrorContent(
                    message = uiState.message,
                    onRetry = onRetry,
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}

// ─── Loading ──────────────────────────────────────────────────────────────────

@Composable
private fun dashboardLoadingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Loading cost dashboard" }
            )
            Text(
                text = "Loading cost data…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─── Error ────────────────────────────────────────────────────────────────────

@Composable
private fun dashboardErrorContent(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(spacing.md),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(spacing.sm))
        Text(
            text = "Unable to load cost data",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(spacing.md))
        Button(
            onClick = onRetry,
            modifier = Modifier.semantics { contentDescription = "Retry loading cost dashboard" }
        ) {
            Text("Retry")
        }
    }
}

// ─── Ready content ────────────────────────────────────────────────────────────

@Composable
private fun dashboardReadyContent(
    state: CostDashboardUiState.Ready,
    onAddAlert: (Double) -> Unit,
    onDeleteAlert: (String) -> Unit,
    onDismissBanner: (String) -> Unit,
    onClearAlertError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = MaterialTheme.spacing
    var showAddAlertDialog by remember { mutableStateOf(false) }

    if (showAddAlertDialog) {
        addAlertDialog(
            onDismiss = { showAddAlertDialog = false },
            onConfirm = { threshold ->
                showAddAlertDialog = false
                onAddAlert(threshold)
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        // Persistent spending-alert banners (Requirement 34.6)
        state.triggeredBanners.forEach { alert ->
            spendingAlertBanner(
                alert = alert,
                onDismiss = { onDismissBanner(alert.id) }
            )
        }

        // Monthly summary card
        monthlySummaryCard(costSummary = state.costSummary)

        // Daily cost bar chart for the current month
        dailyCostBarChart(rows = state.costSummary.rows)

        // Breakdown by feature
        if (state.costSummary.rows.isNotEmpty()) {
            breakdownCard(
                title = "Breakdown by Feature",
                items = state.costSummary.rows
                    .groupBy { it.feature }
                    .map { (feature, rows) ->
                        feature to rows.sumOf { it.costUsd }
                    }
                    .sortedByDescending { it.second }
            )

            breakdownCard(
                title = "Breakdown by Provider",
                items = state.costSummary.rows
                    .groupBy { it.provider }
                    .map { (provider, rows) ->
                        provider to rows.sumOf { it.costUsd }
                    }
                    .sortedByDescending { it.second }
            )
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(spacing.md),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No usage data for the last 90 days.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Spending alerts section
        spendingAlertsSection(
            alerts = state.alerts,
            alertLimitError = state.alertLimitError,
            isAdding = state.isAddingAlert,
            deletingAlertId = state.isDeletingAlertId,
            onAddClick = {
                onClearAlertError()
                showAddAlertDialog = true
            },
            onDeleteAlert = onDeleteAlert
        )

        Spacer(Modifier.height(spacing.lg))
    }
}

// ─── Persistent alert banner ──────────────────────────────────────────────────

/**
 * Persistent banner shown when a spending alert threshold has been crossed.
 *
 * Displays the threshold amount, the current accumulated cost when it triggered,
 * and the date it was crossed. Remains until the user explicitly dismisses it
 * (Requirement 34.6).
 */
@Composable
private fun spendingAlertBanner(alert: SpendingAlert, onDismiss: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription =
                    "Spending alert: \$${String.format(Locale.US, "%.2f", alert.thresholdUsd)} threshold crossed"
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.sm),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = spacing.sm, top = 2.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Spending Alert Triggered",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Your daily AI cost has reached your " +
                        "\$${String.format(Locale.US, "%.2f", alert.thresholdUsd)} threshold.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                if (alert.triggeredAt != null) {
                    Text(
                        text = "Triggered: ${alert.triggeredAt}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                    )
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(24.dp)
                    .semantics { contentDescription = "Dismiss spending alert banner" }
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

// ─── Monthly summary card ──────────────────────────────────────────────────────

@Composable
private fun monthlySummaryCard(costSummary: CostSummary) {
    val spacing = MaterialTheme.spacing
    val currentMonth = java.time.LocalDate.now().format(
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")
    )
    val monthRows = costSummary.rows.filter { it.day.startsWith(currentMonth) }
    val monthTokens = monthRows.sumOf { it.inputTokens + it.outputTokens }
    val monthCost = monthRows.sumOf { it.costUsd }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(spacing.md)) {
            Text(
                text = "This Month",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(spacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                summaryMetric(
                    label = "Total Tokens",
                    value = "%,d".format(monthTokens),
                    modifier = Modifier.weight(1f)
                )
                summaryMetric(
                    label = "Estimated Cost",
                    value = "\$${String.format(Locale.US, "%.4f", monthCost)}",
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(spacing.xs))
            Divider()
            Spacer(Modifier.height(spacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                summaryMetric(
                    label = "90-Day Tokens",
                    value = "%,d".format(costSummary.totalInputTokens + costSummary.totalOutputTokens),
                    modifier = Modifier.weight(1f)
                )
                summaryMetric(
                    label = "90-Day Cost",
                    value = "\$${String.format(Locale.US, "%.4f", costSummary.totalCostUsd)}",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun summaryMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

// ─── Daily cost bar chart ──────────────────────────────────────────────────────

/**
 * A simple Canvas-based bar chart showing daily cost for the current month.
 *
 * Requirements: 34.3
 */
@Composable
private fun dailyCostBarChart(rows: List<DailyCostRow>) {
    val spacing = MaterialTheme.spacing
    val currentMonth = java.time.LocalDate.now().format(
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")
    )
    val dailyCosts: Map<String, Double> = rows
        .filter { it.day.startsWith(currentMonth) }
        .groupBy { it.day }
        .mapValues { (_, dayRows) -> dayRows.sumOf { it.costUsd } }

    if (dailyCosts.isEmpty()) return

    val sortedDays = dailyCosts.keys.sorted()
    val maxCost = dailyCosts.values.maxOrNull() ?: 1.0
    val barColor = MaterialTheme.colorScheme.primary

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(spacing.md)) {
            Text(
                text = "Daily Cost — Current Month",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(spacing.sm))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .semantics { contentDescription = "Bar chart of daily AI costs for the current month" }
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val barCount = sortedDays.size
                if (barCount == 0) return@Canvas

                val barWidth = (canvasWidth / barCount) * 0.7f
                val gap = (canvasWidth / barCount) * 0.3f

                sortedDays.forEachIndexed { index, day ->
                    val cost = dailyCosts[day] ?: 0.0
                    val normalizedHeight = if (maxCost > 0) (cost / maxCost * canvasHeight).toFloat() else 0f
                    val left = index * (barWidth + gap)
                    val top = canvasHeight - normalizedHeight

                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(left, top),
                        size = Size(barWidth, normalizedHeight),
                        cornerRadius = CornerRadius(3f, 3f)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Max: \$${String.format(Locale.US, "%.4f", maxCost)} | " +
                    "Days shown: ${sortedDays.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─── Breakdown card ───────────────────────────────────────────────────────────

@Composable
private fun breakdownCard(title: String, items: List<Pair<String, Double>>) {
    val spacing = MaterialTheme.spacing
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(spacing.md)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(spacing.sm))
            items.forEach { (label, cost) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = label.replaceFirstChar { it.uppercaseChar() },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "\$${String.format(Locale.US, "%.6f", cost)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

// ─── Spending alerts section ──────────────────────────────────────────────────

@Composable
private fun spendingAlertsSection(
    alerts: List<SpendingAlert>,
    alertLimitError: String?,
    isAdding: Boolean,
    deletingAlertId: String?,
    onAddClick: () -> Unit,
    onDeleteAlert: (String) -> Unit
) {
    val spacing = MaterialTheme.spacing
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Spending Alerts (${alerts.size}/3)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                if (isAdding) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    TextButton(
                        onClick = onAddClick,
                        enabled = alerts.size < 3,
                        modifier = Modifier.semantics { contentDescription = "Add spending alert" }
                    ) {
                        Text("+ Add Alert")
                    }
                }
            }

            // Inline error for 4th alert attempt (Requirement 34.5)
            if (alertLimitError != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = alertLimitError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Alert limit error: $alertLimitError" }
                )
            }

            if (alerts.isEmpty()) {
                Spacer(Modifier.height(spacing.sm))
                Text(
                    text = "No spending alerts configured. Tap '+ Add Alert' to set a threshold.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(Modifier.height(spacing.sm))
                alerts.forEach { alert ->
                    alertRow(
                        alert = alert,
                        isDeleting = deletingAlertId == alert.id,
                        onDelete = { onDeleteAlert(alert.id) }
                    )
                    if (alert != alerts.last()) {
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun alertRow(alert: SpendingAlert, isDeleting: Boolean, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics {
                contentDescription = "Spending alert: \$${
                    String.format(Locale.US, "%.2f", alert.thresholdUsd)
                } threshold${if (alert.isTriggered) " — triggered" else ""}"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "\$${String.format(Locale.US, "%.2f", alert.thresholdUsd)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (alert.isTriggered) {
                Text(
                    text = "⚠ Threshold crossed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (isDeleting) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        } else {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.semantics {
                    contentDescription = "Delete spending alert \$${
                        String.format(Locale.US, "%.2f", alert.thresholdUsd)
                    }"
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ─── Add alert dialog ─────────────────────────────────────────────────────────

@Composable
private fun addAlertDialog(onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var thresholdText by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Spending Alert") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Enter a threshold (USD). You'll receive an in-app notification when " +
                        "your daily AI cost reaches this amount.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = thresholdText,
                    onValueChange = {
                        thresholdText = it
                        validationError = null
                    },
                    label = { Text("Threshold (USD, min \$0.01, max \$999.99)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = validationError != null,
                    supportingText = if (validationError != null) {
                        { Text(validationError!!, color = MaterialTheme.colorScheme.error) }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Threshold amount input" }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = thresholdText.toDoubleOrNull()
                    when {
                        value == null -> validationError = "Please enter a valid number."
                        value < 0.01 -> validationError = "Minimum threshold is \$0.01."
                        value > 999.99 -> validationError = "Maximum threshold is \$999.99."
                        else -> onConfirm(value)
                    }
                },
                modifier = Modifier.semantics { contentDescription = "Confirm add alert" }
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
