/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-rag
 * File       : BenchmarkScreen.kt
 * Purpose    : Displays on-device inference benchmark results from
 *              BenchmarkOnDeviceUseCase.  Accessible from Settings.
 *              Shows TTFT p50/p95, tokens/sec p50/p95, RAM peak, and
 *              the accelerator used.
 *
 * Architecture Layer : Feature (feature-on-device-rag) — Compose UI layer.
 *
 * Requirements: 32.3, 32.4, 32.5
 * ============================================================
 */
package com.aiassistant.feature.ondevicerag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aiassistant.domain.model.OnDeviceBenchmarkResult

// ── Stateful entry point ─────────────────────────────────────────────────────

@Composable
fun BenchmarkScreen(
    onNavigateUp: () -> Unit,
    viewModel: BenchmarkViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    BenchmarkContent(
        uiState = uiState,
        onRunBenchmark = viewModel::runBenchmark,
        onNavigateUp = onNavigateUp,
        modifier = Modifier.fillMaxSize(),
    )
}

// ── Stateless content composable ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkContent(
    uiState: BenchmarkUiState,
    onRunBenchmark: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("On-Device Benchmark") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateUp,
                        modifier = Modifier.semantics { contentDescription = "Navigate up" },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (uiState) {
                is BenchmarkUiState.Idle -> {
                    Text(
                        text = "Run 10 inference iterations with a 200-token fixed prompt to " +
                            "measure time-to-first-token, throughput, and peak RAM.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onRunBenchmark,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Run benchmark" },
                    ) {
                        Text("Run Benchmark")
                    }
                }

                is BenchmarkUiState.Running -> {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.semantics {
                                    contentDescription = "Benchmark running"
                                }
                            )
                            Text(
                                "Running ${uiState.iteration} / 10 iterations…",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                is BenchmarkUiState.Done -> {
                    BenchmarkResultsTable(result = uiState.result)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onRunBenchmark,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Run benchmark again" },
                    ) {
                        Text("Run Again")
                    }
                }

                is BenchmarkUiState.Error -> {
                    Text(
                        text = uiState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics {
                            contentDescription = "Benchmark error: ${uiState.message}"
                        },
                    )
                    Button(
                        onClick = onRunBenchmark,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

// ── Benchmark results table ───────────────────────────────────────────────────

@Composable
private fun BenchmarkResultsTable(
    result: OnDeviceBenchmarkResult,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Benchmark Results",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))

            // Header row
            BenchmarkTableRow(
                col1 = "Metric",
                col2 = "p50",
                col3 = "p95",
                isHeader = true,
            )
            HorizontalDivider()

            BenchmarkTableRow(
                col1 = "TTFT (ms)",
                col2 = result.ttftMeanMs.toString(),
                col3 = result.ttftP95Ms.toString(),
            )
            BenchmarkTableRow(
                col1 = "Tokens/sec",
                col2 = "%.1f".format(result.tokensPerSecMean),
                col3 = "%.1f".format(result.tokensPerSecP95),
            )
            HorizontalDivider()

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Accelerator", style = MaterialTheme.typography.labelMedium)
                Text(result.accelerator.name, style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Peak RAM (MB)", style = MaterialTheme.typography.labelMedium)
                Text(result.peakRamMb.toString(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun BenchmarkTableRow(
    col1: String,
    col2: String,
    col3: String,
    isHeader: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val style = if (isHeader) {
        MaterialTheme.typography.labelMedium
    } else {
        MaterialTheme.typography.bodySmall
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(col1, style = style, modifier = Modifier.weight(2f))
        Text(col2, style = style, modifier = Modifier.weight(1f))
        Text(col3, style = style, modifier = Modifier.weight(1f))
    }
}
