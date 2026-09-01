package com.aiassistant.feature.rag

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Gradient circular send button used in DocumentQueryInputBar
 */
@Composable
internal fun DocumentSendButton(
    canSend: Boolean,
    isQuerying: Boolean,
    gradientStart: Color,
    gradientEnd: Color,
    onSend: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (canSend) {
                    Brush.linearGradient(listOf(gradientStart, gradientEnd))
                } else {
                    Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = { if (canSend) onSend() },
            enabled = canSend,
            modifier = Modifier
                .matchParentSize()
                .semantics { contentDescription = "Send query" }
        ) {
            if (isQuerying) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    tint = if (canSend) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
