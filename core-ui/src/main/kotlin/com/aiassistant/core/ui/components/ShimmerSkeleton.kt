/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : components/ShimmerSkeleton.kt
 * Purpose    : Shimmer loading skeleton composables used for history list,
 *              chat list, document list, and settings screens.
 *
 * Architecture Layer : Core-UI — shared design system.
 * Requirements       : 15.1–15.3
 * ============================================================
 */
package com.aiassistant.core.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppColors
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.SHIMMER_DURATION_MS
import com.aiassistant.core.ui.spacing
import androidx.compose.material3.MaterialTheme

/**
 * Draws a single shimmering rectangle — the base building block for skeletons.
 *
 * @param widthFraction Width as a fraction of the parent (0f–1f).
 * @param height        Height in dp.
 * @param modifier      Applied to the outer Box.
 */
@Composable
fun ShimmerBox(
    widthFraction: Float = 1f,
    height: Float = 16f,
    cornerRadius: Float = 8f,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val baseColor      = if (isDark) AppColors.shimmerBaseDark      else AppColors.shimmerBaseLight
    val highlightColor = if (isDark) AppColors.shimmerHighlightDark else AppColors.shimmerHighlightLight

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateX by transition.animateFloat(
        initialValue = -1000f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(SHIMMER_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(baseColor, highlightColor, baseColor),
        start = Offset(translateX, 0f),
        end = Offset(translateX + 600f, 0f)
    )

    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height.dp)
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(shimmerBrush)
    )
}

/**
 * Skeleton row representing a single conversation or history item.
 */
@Composable
fun ConversationSkeletonItem(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.md, vertical = MaterialTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
    ) {
        ShimmerBox(widthFraction = 0.6f, height = 18f)
        Spacer(modifier = Modifier.width(0.dp))
        ShimmerBox(widthFraction = 0.3f, height = 12f)
    }
}

/**
 * Full-page skeleton for the history / chat list loading state.
 * Shows N placeholder rows.
 */
@Composable
fun ConversationListSkeleton(
    itemCount: Int = 8,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Section header skeleton
        ShimmerBox(
            widthFraction = 0.25f,
            height = 12f,
            modifier = Modifier.padding(
                horizontal = MaterialTheme.spacing.md,
                vertical = MaterialTheme.spacing.sm
            )
        )
        repeat(itemCount) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.md, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    ShimmerBox(widthFraction = 0.7f, height = 16f)
                    Spacer(modifier = Modifier.height(4.dp))
                    ShimmerBox(widthFraction = 0.4f, height = 11f)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConversationListSkeletonPreview() {
    AppTheme(dynamicColor = false) {
        ConversationListSkeleton()
    }
}
