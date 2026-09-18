/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : components/CacheStatusIndicator.kt
 * Purpose    : Compact cache/sync status label shown below screen titles
 *              on ChatListScreen and HistoryListScreen.
 *
 *              Example: "● Cached · Last synced: Today 3:42 PM"
 *
 * Architecture Layer : Core-UI — shared design system.
 * Requirements       : 10.4, 26.1
 * ============================================================
 */
package com.aiassistant.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppIcons
import com.aiassistant.core.ui.AppTypeExtended
import com.aiassistant.core.ui.spacing

/**
 * A compact inline indicator showing whether the displayed data is from the
 * local cache and when it was last synced.
 *
 * @param isFromCache   When `true`, shows "Cached" label instead of "Live".
 * @param isSyncing     When `true`, shows a spinning sync icon.
 * @param lastSynced    Human-readable timestamp string, e.g. "Today 3:42 PM".
 *                      Pass `null` to omit the timestamp.
 * @param modifier      Applied to the root Row.
 */
@Composable
fun CacheStatusIndicator(
    isFromCache: Boolean,
    isSyncing: Boolean,
    lastSynced: String?,
    modifier: Modifier = Modifier
) {
    val label = when {
        isSyncing   -> "Syncing…"
        isFromCache -> buildString {
            append("Cached")
            if (lastSynced != null) append(" · Last synced: $lastSynced")
        }
        else        -> null
    }

    if (label == null) return

    Row(
        modifier = modifier.semantics {
            contentDescription = label
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(
            imageVector = if (isSyncing) AppIcons.Status.Syncing else AppIcons.Documents.Indexed,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(10.dp)
        )
        Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
        Text(
            text = label,
            style = AppTypeExtended.cacheTimestamp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}
