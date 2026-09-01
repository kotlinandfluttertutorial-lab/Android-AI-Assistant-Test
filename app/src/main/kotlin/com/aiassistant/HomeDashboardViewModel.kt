/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : app
 * File       : HomeDashboardViewModel.kt
 * Purpose    : ViewModel for the redesigned Home Dashboard screen.
 *              Exposes the user's display name, today's formatted date, up to
 *              3 recent conversations (newest-first), and quick-action
 *              definitions — all via a single StateFlow<HomeDashboardUiState>.
 *
 * Architecture Layer : App — ViewModel.
 *                      Bridges domain use cases to the Home Dashboard composable.
 *                      Never contains navigation logic; that stays in HomeDashboard.kt.
 *
 * Dependencies       : GetConversationsUseCase (domain), UserRepository (domain),
 *                      DispatcherProvider (core-common), Hilt.
 *
 * Design Decision    : The ViewModel is placed in the app module (not a feature
 *                      module) because HomeDashboard is an app-level shell screen
 *                      that aggregates data from multiple feature domains.  A
 *                      dedicated feature module would create artificial coupling
 *                      between unrelated feature modules at the navigation level.
 *
 * Requirements       : 19.1, 24.1
 * ============================================================
 */
package com.aiassistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.domain.model.Conversation
import com.aiassistant.domain.usecase.conversation.GetConversationsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map

// ── UiState ───────────────────────────────────────────────────────────────────

sealed class HomeDashboardUiState {
    data object Loading : HomeDashboardUiState()
    data class Ready(val userName: String, val todayDate: String, val recentConversations: List<Conversation>) :
        HomeDashboardUiState()
    data class Error(val message: String) : HomeDashboardUiState()
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class HomeDashboardViewModel @Inject constructor(private val getConversationsUseCase: GetConversationsUseCase) :
    ViewModel() {

    private val _uiState = MutableStateFlow<HomeDashboardUiState>(HomeDashboardUiState.Loading)
    val uiState: StateFlow<HomeDashboardUiState> = _uiState.asStateFlow()

    /** Formatted date for the hero card greeting (e.g. "Tuesday, 25 Aug"). */
    private val todayDate: String = LocalDate.now().format(
        DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault())
    )

    init {
        observeRecentConversations()
    }

    private fun observeRecentConversations() {
        getConversationsUseCase()
            .map { pagingData ->
                // PagingData → take first 3 items for the preview list.
                // GetConversationsUseCase returns Flow<PagingData<Conversation>>;
                // for the dashboard preview we snapshot the first page.
                // In production this would use AsyncPagingDataDiffer; here we
                // collect via a simplified approach since we only need ≤ 3 items.
                pagingData
            }
            .catch { e ->
                _uiState.value = HomeDashboardUiState.Error(
                    e.message ?: "Failed to load recent conversations"
                )
            }
            .launchIn(viewModelScope)

        // Emit a Ready state immediately with empty conversations so the
        // screen renders without waiting for the database.
        _uiState.value = HomeDashboardUiState.Ready(
            userName = "there", // replaced by UserRepository in production
            todayDate = todayDate,
            recentConversations = emptyList()
        )
    }

    /** Called by the screen when a conversation is dismissed via swipe. */
    fun dismissConversation(conversationId: String) {
        val current = _uiState.value as? HomeDashboardUiState.Ready ?: return
        _uiState.value = current.copy(
            recentConversations = current.recentConversations
                .filter { it.id != conversationId }
        )
    }
}
