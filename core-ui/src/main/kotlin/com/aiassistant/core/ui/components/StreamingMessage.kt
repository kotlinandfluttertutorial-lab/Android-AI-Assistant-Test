/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : components/StreamingMessage.kt
 * Purpose    : StreamingMessage — shows in-progress AI response with an
 *              animated blinking cursor at the end of the partial text.
 *
 *              Performance note: the cursor blink uses an InfiniteTransition
 *              that is isolated inside this composable. Only the cursor alpha
 *              recomposes — the text itself is stable.
 *
 * Architecture Layer : Core-UI — shared design system.
 * Requirements       : 2.5, 2.10, 15.4
 * ============================================================
 */
package com.aiassistant.core.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.CURSOR_BLINK_MS
import com.aiassistant.core.ui.motion.LocalReducedMotionEnabled

/**
 * Renders a partial AI response with an animated cursor at the end.
 *
 * @param text      The current partial text being streamed. May update rapidly.
 * @param modifier  Applied to the Row container.
 */
@Composable
fun StreamingMessage(
    text: String,
    modifier: Modifier = Modifier
) {
    val reducedMotion = LocalReducedMotionEnabled.current

    Row(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .semantics { contentDescription = "AI is typing: $text" },
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f, fill = false)
        )

        BlinkingCursor(
            reducedMotion = reducedMotion,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun BlinkingCursor(
    reducedMotion: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val cursorAlpha: Float = if (reducedMotion) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "cursorBlink")
        val alpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = CURSOR_BLINK_MS
                    1f at 0 using LinearEasing
                    1f at (CURSOR_BLINK_MS / 2) using LinearEasing
                    0f at (CURSOR_BLINK_MS / 2 + 1) using LinearEasing
                    0f at CURSOR_BLINK_MS using LinearEasing
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "cursorAlpha"
        )
        alpha
    }

    Box(
        modifier = modifier
            .padding(start = 2.dp, bottom = 2.dp)
            .width(2.dp)
            .fillMaxHeight()
            .alpha(cursorAlpha)
            .drawBehind { drawRect(color) }
    )
}

@Preview(showBackground = true)
@Composable
private fun StreamingMessagePreview() {
    AppTheme(dynamicColor = false) {
        StreamingMessage(
            text = "Here is a streaming response in progress",
            modifier = Modifier.padding(16.dp)
        )
    }
}
