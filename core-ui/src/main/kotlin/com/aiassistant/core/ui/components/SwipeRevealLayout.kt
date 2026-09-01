/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : components/SwipeRevealLayout.kt
 * Purpose    : Horizontally swipeable container that reveals a row of action
 *              buttons behind the foreground content.  Used on ChatListScreen
 *              conversation rows and TicketCard rows to expose pin / delete /
 *              archive actions without a dropdown menu.
 *
 * Architecture Layer : Core-UI — shared composable.
 *                      Consumed by feature-chat, feature-productivity, and any
 *                      other feature that needs swipe-to-action rows.
 *                      Never imported from domain or data layers.
 *
 * Dependencies       : Compose animation, Compose foundation (draggable),
 *                      core-ui motion (reduced motion).
 *
 * Design Decision    : Uses a custom horizontal drag implementation rather than
 *                      SwipeToDismissBox because we want to *reveal* actions (not
 *                      dismiss).  The content slides left by a fixed [revealWidth]
 *                      amount, capped so the foreground never fully exits the
 *                      screen.  A spring-back animation returns the content to the
 *                      closed position when the user releases without tapping an
 *                      action.  When reduced motion is active the offset is applied
 *                      instantly with no animation.
 *
 * Requirements       : 24.3 (motion), 23.1 (accessibility — action buttons are
 *                      always reachable via long-press callback on the row itself)
 * ============================================================
 */
package com.aiassistant.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.motion.LocalReducedMotionEnabled
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * A composable that reveals [actions] by sliding [content] to the left when the user
 * swipes horizontally.
 *
 * The content slides at most [revealWidth] dp.  Releasing the swipe without tapping
 * an action springs the content back to the closed position.
 *
 * **Accessibility note:** All actions in [actions] must have `contentDescription` set.
 * The row's long-press callback (if the parent provides one) should also expose the
 * same actions to users who cannot use the swipe gesture.
 *
 * @param modifier      Applied to the outer [Box] container.
 * @param revealWidth   How far the content slides to reveal the actions (default 160 dp).
 * @param actions       Row of action composables (e.g. icon buttons) revealed on swipe.
 * @param content       The foreground content that slides.
 */
@Composable
fun SwipeRevealLayout(
    modifier: Modifier = Modifier,
    revealWidth: Dp = 160.dp,
    actions: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    val reducedMotion = LocalReducedMotionEnabled.current
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }

    Box(modifier = modifier) {
        // ── Action buttons (rendered behind content) ──────────────────────
        Row(
            modifier = Modifier
                .matchParentSize()
                .align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions()
        }

        // ── Foreground content ─────────────────────────────────────────────
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .swipeRevealPointerInput(
                    offsetX = offsetX,
                    revealWidth = revealWidth,
                    reducedMotion = reducedMotion,
                    scope = scope
                )
        ) {
            content()
        }
    }
}

// Helper to extract pointerInput handling and reduce function complexity for detekt
private fun androidx.compose.ui.Modifier.swipeRevealPointerInput(
    offsetX: Animatable<Float, *>,
    revealWidth: Dp,
    reducedMotion: Boolean,
    scope: kotlinx.coroutines.CoroutineScope
): androidx.compose.ui.Modifier = this.pointerInput(Unit) {
    detectHorizontalDragGestures(
        onHorizontalDrag = { _, dragAmount ->
            scope.launch {
                val newOffset = (offsetX.value + dragAmount).coerceIn(-revealWidth.toPx(), 0f)
                offsetX.snapTo(newOffset)
            }
        },
        onDragEnd = {
            scope.launch {
                val threshold = -revealWidth.toPx() / 2f
                val target = if (offsetX.value < threshold) -revealWidth.toPx() else 0f
                if (reducedMotion) {
                    offsetX.snapTo(target)
                } else {
                    offsetX.animateTo(
                        targetValue = target,
                        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f)
                    )
                }
            }
        },
        onDragCancel = {
            scope.launch {
                if (reducedMotion) offsetX.snapTo(0f) else offsetX.animateTo(0f, spring())
            }
        }
    )
}
