/**
 * AiAnalysisCard.kt — feature-dashboard module
 *
 * Expandable card showing the Phase 10 AI error analysis result:
 * summary, likely root cause, confidence bar, and recommended fix.
 *
 * Phase 14 — Android AI DevOps Dashboard
 */
package com.aiassistant.feature.dashboard.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aiassistant.domain.model.AiAnalysis

@Composable
fun AiAnalysisCard(analysis: AiAnalysis, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val confidencePct = (analysis.confidence * 100).toInt()
    val isLowConfidence = analysis.confidence < 0.6

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isLowConfidence) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            AiAnalysisHeader(
                expanded = expanded,
                onToggle = { expanded = !expanded }
            )

            // Summary always visible
            Spacer(Modifier.height(8.dp))
            Text(
                text = analysis.summary,
                style = MaterialTheme.typography.bodyMedium
            )

            ConfidenceSection(
                confidence = analysis.confidence,
                isLowConfidence = isLowConfidence,
                confidencePct = confidencePct
            )

            // Low confidence warning
            analysis.lowConfidenceWarning?.let { warning ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Expanded details
            AnimatedVisibility(visible = expanded) {
                AnalysisDetails(analysis = analysis)
            }
        }
    }
}

@Composable
private fun AiAnalysisHeader(
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .semantics {
                contentDescription = "AI Analysis, tap to ${if (expanded) "collapse" else "expand"}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Psychology,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "AI Error Analysis",
                style = MaterialTheme.typography.titleSmall
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null
        )
    }
}

@Composable
private fun ConfidenceSection(
    confidence: Double,
    isLowConfidence: Boolean,
    confidencePct: Int
) {
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Confidence: $confidencePct%",
        style = MaterialTheme.typography.labelMedium,
        color = if (isLowConfidence) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        }
    )
    Spacer(Modifier.height(4.dp))
    LinearProgressIndicator(
        progress = { confidence.toFloat().coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Confidence $confidencePct percent" },
        color = if (isLowConfidence) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        }
    )
}

@Composable
private fun AnalysisDetails(analysis: AiAnalysis) {
    Column {
        Spacer(Modifier.height(12.dp))

        // Likely root cause
        Text(
            text = "Likely Root Cause",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = analysis.likelyRootCause,
            style = MaterialTheme.typography.bodySmall
        )

        // Recommended fix
        if (analysis.recommendedFix.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Recommended Fix",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = analysis.recommendedFix,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Events analysed
        if (analysis.eventsAnalysed > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${analysis.eventsAnalysed} events analysed · ${analysis.relatedDocs.size} docs retrieved",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
