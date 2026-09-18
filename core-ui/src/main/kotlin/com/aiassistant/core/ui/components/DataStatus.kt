/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : components/DataStatus.kt
 * Purpose    : DataStatus model — communicates whether displayed data is
 *              from the local cache, currently syncing, and when it was
 *              last updated.
 *
 *              Used by ChatListUiState, HistoryUiState, and any screen that
 *              shows data that may lag the server.
 *
 * Architecture Layer : Core-UI — shared design system model.
 *                      Imported by feature modules and ViewModels.
 *                      NOT domain layer — this is a presentation-layer concern.
 *
 * Requirements       : 26.1, 26.2
 * ============================================================
 */
package com.aiassistant.core.ui.components

import androidx.compose.runtime.Immutable
import java.time.Instant

/**
 * Describes the freshness of the data currently displayed on a screen.
 *
 * @param isFromCache   `true` when the data was loaded from the local Room
 *                      database and a fresh network fetch has not yet returned.
 * @param isSyncing     `true` while a network refresh is in-flight.
 * @param lastUpdated   The [Instant] the data was last successfully loaded
 *                      from the network.  `null` if the data has never been
 *                      synced (first-time load still in progress).
 */
@Immutable
data class DataStatus(
    val isFromCache: Boolean = false,
    val isSyncing: Boolean = false,
    val lastUpdated: Instant? = null
) {
    companion object {
        /** Default status — live, not syncing, no timestamp. */
        val Live = DataStatus(isFromCache = false, isSyncing = false, lastUpdated = null)

        /** Cache-only status before the first network sync. */
        val CachedNoSync = DataStatus(isFromCache = true, isSyncing = false, lastUpdated = null)
    }
}
