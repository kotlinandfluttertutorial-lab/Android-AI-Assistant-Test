/**
 * RemediationCard.kt — feature-dashboard module
 *
 * Displays the AI-recommended remediation actions for an incident with
 * Approve and Reject buttons for each action.
 *
 * Human-in-the-loop safety:
 *   - Each action shows its risk tier (LOW / MEDIUM / HIGH) with colour coding
 *   - HIGH risk actions show an additional warning before the approve button
 *   - Approving only records the decision — NO automated execution happens
 *   - The engineer must execute the action manually using the params shown
 *
 * Phase 15 — AIOps
 */
package com.aiassistant.feature.dashboard.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Data passed to the composable ─────────────────────────────────────────────

data class RemediationActionUiModel(
    val id: String,
    val rank: Int,
    val title: String,
    val actionType: String,
    val riskTier: String, // "LOW" | "MEDIUM" | "HIGH"
    val reasoning: String,
    val confidence: Double?,
    val status: String, // "RECOMMENDED" | "APPROVED" | "REJECTED"
    val reviewedBy: String?
)

// ── Main composable ────────────────────────────────────────────────────────────

/**
 * Card showing all remediation actions for an incident with approve/reject controls.
 *
 * @param actions        List of recommended actions (ranked).
 * @param incidentTitle  Short incident title shown in the card header.
 * @param lowConfidenceWarning  Non-null when AI confidence < 0.6.
 * @param onApprove      Called with action ID when the user approves.
 * @param onReject       Called with action ID when the user rejects.
 */
@Composable
fun RemediationCard(
    actions: List<RemediationActionUiModel>,
    incidentTitle: String,
    lowConfidenceWarning: String? = null,
    onApprove: (actionId: String) -> Unit,
    onReject: (actionId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (actions.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Remediation Actions",
                    style = MaterialTheme.typography.titleSmall
                )
            }

            // Low confidence warning
            lowConfidenceWarning?.let { warning ->
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                        .padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Warning,
                        contentDescription = "Low confidence warning",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Safety notice
            Text(
                text = "⚠️ Approval records your decision. No automated execution happens. Execute manually using the params shown.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            // Action rows
            actions.sortedBy { it.rank }.forEach { action ->
                RemediationActionRow(
                    action = action,
                    onApprove = { onApprove(action.id) },
                    onReject = { onReject(action.id) }
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ── Single action row ─────────────────────────────────────────────────────────

@Composable
private fun RemediationActionRow(
    action: RemediationActionUiModel,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showReasoning by rememberSaveable { mutableStateOf(false) }

    val (tierBg, tierFg) = when (action.riskTier.uppercase()) {
        "HIGH" -> Pair(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError)
        "MEDIUM" -> Pair(Color(0xFFFFDDB3), Color(0xFF5B2D00))
        else -> Pair(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
    }

    val isTerminal = action.status in listOf("APPROVED", "REJECTED")
    val statusColor = when (action.status) {
        "APPROVED" -> MaterialTheme.colorScheme.primary
        "REJECTED" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp)
    ) {
        // Title row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "#${action.rank}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = action.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }
            // Risk tier badge
            Text(
                text = action.riskTier,
                color = tierFg,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(tierBg)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        // Status (if reviewed)
        if (isTerminal) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${action.status}${action.reviewedBy?.let { " by $it" } ?: ""}",
                style = MaterialTheme.typography.labelSmall,
                color = statusColor
            )
        }

        // Reasoning (expandable)
        if (action.reasoning.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (showReasoning) "Hide reasoning ▲" else "Show reasoning ▼",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { showReasoning = !showReasoning }
                    .semantics { contentDescription = "Toggle reasoning for ${action.title}" }
                    .padding(vertical = 2.dp)
            )
            AnimatedVisibility(visible = showReasoning) {
                Text(
                    text = action.reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Approve / Reject buttons (only when RECOMMENDED)
        if (!isTerminal) {
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onApprove,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Approve ${action.title}" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Approve", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Reject ${action.title}" }
                ) {
                    Icon(
                        Icons.Outlined.Cancel,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Reject", style = MaterialTheme.typography.labelMedium)
                }
            }

            // HIGH risk extra warning
            if (action.riskTier.uppercase() == "HIGH") {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "⚠️ HIGH RISK — verify the previous state is stable before approving.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
