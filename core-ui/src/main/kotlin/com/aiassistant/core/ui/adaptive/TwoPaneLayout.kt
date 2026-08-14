/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : TwoPaneLayout.kt
 * Purpose    : TwoPaneLayout — core-ui module component
 *
 * Architecture Layer : Core-UI
 * Pattern Used       : Kotlin Class
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
 * Module     : core-ui
 * File       : TwoPaneLayout.kt
 * Purpose    : TwoPaneLayout — core-ui module component
 *
 * Architecture Layer : Core-UI
 * Pattern Used       : Kotlin Class
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
 * TwoPaneLayout.kt
 *
 * Purpose: Generic two-pane layout for Chat and History screens on tablets.
 * Architecture: core-ui â€” shared design system adaptive layer.
 * Dependencies: Compose Foundation, Material3 WindowSizeClass, WindowSizeUtils.
 * Requirements: 23.3
 */
package com.aiassistant.core.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Renders a two-pane layout on tablets (>=600 dp) and a single-pane layout on phones.
 *
 * Tablet behaviour: Both panes are shown side-by-side. List pane occupies 38% of the
 * width; detail pane takes the remaining 62%.
 *
 * Phone behaviour: Only one pane is shown at a time.
 * - When [showDetailPane] is false, [listPane] is rendered.
 * - When [showDetailPane] is true, [detailPane] is rendered.
 * Back-stack navigation between panes is managed by the calling feature module.
 *
 * @param listPane       The master/list pane composable.
 * @param detailPane     The detail pane composable.
 * @param showDetailPane Whether the detail pane is currently active (phone only).
 * @param windowSizeClass The current [WindowSizeClass] used to select the layout.
 * @param modifier        Optional [Modifier] applied to the root layout.
 */
@Composable
fun TwoPaneLayout(
    listPane: @Composable () -> Unit,
    detailPane: @Composable () -> Unit,
    showDetailPane: Boolean,
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier
) {
    if (windowSizeClass.isTabletLayout) {
        // â”€â”€ Tablet: side-by-side â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        Row(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = 0.38f)
            ) {
                listPane()
            }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
            ) {
                detailPane()
            }
        }
    } else {
        // â”€â”€ Phone: single pane â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        Box(modifier = modifier.fillMaxSize()) {
            if (showDetailPane) {
                detailPane()
            } else {
                listPane()
            }
        }
    }
}
