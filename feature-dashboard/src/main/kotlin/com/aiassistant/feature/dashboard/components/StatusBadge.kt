/**
 * StatusBadge.kt — feature-dashboard module
 *
 * Small coloured chip that shows incident severity or status.
 * Uses the semantic color tokens from core-ui: Error for CRITICAL/HIGH,
 * the new Warning amber for MEDIUM, and Primary for LOW/OPEN.
 *
 * Phase 14 — Android AI DevOps Dashboard
 */
package com.aiassistant.feature.dashboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiassistant.domain.model.IncidentSeverity
import com.aiassistant.domain.model.IncidentStatus

// ── Severity badge ────────────────────────────────────────────────────────────

@Composable
fun SeverityBadge(severity: IncidentSeverity, modifier: Modifier = Modifier) {
    val (bg, fg, label) = when (severity) {
        IncidentSeverity.CRITICAL -> Triple(
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.onError,
            "CRITICAL"
        )
        IncidentSeverity.HIGH -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "HIGH"
        )
        IncidentSeverity.MEDIUM -> Triple(
            Color(0xFFFFDDB3), // Warning90 — amber container
            Color(0xFF5B2D00), // Warning20 — on-warning-container
            "MEDIUM"
        )
        IncidentSeverity.LOW -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            "LOW"
        )
    }
    StatusChip(label = label, bg = bg, fg = fg, modifier = modifier)
}

// ── Status badge ──────────────────────────────────────────────────────────────

@Composable
fun StatusBadge(status: IncidentStatus, modifier: Modifier = Modifier) {
    val (bg, fg, label) = when (status) {
        IncidentStatus.OPEN -> Triple(
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.onError,
            "OPEN"
        )
        IncidentStatus.INVESTIGATING -> Triple(
            Color(0xFFFFDDB3),
            Color(0xFF5B2D00),
            "INVESTIGATING"
        )
        IncidentStatus.RESOLVED -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            "RESOLVED"
        )
        IncidentStatus.DISMISSED -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "DISMISSED"
        )
    }
    StatusChip(label = label, bg = bg, fg = fg, modifier = modifier)
}

// ── Shared chip ───────────────────────────────────────────────────────────────

@Composable
private fun StatusChip(label: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Text(
        text = label,
        color = fg,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
