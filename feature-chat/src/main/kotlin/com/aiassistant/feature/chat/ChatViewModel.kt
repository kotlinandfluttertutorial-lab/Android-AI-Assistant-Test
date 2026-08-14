/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-chat
 * File       : ChatViewModel.kt
 * Purpose    : ViewModel for the conversation list screen
 *
 * Architecture Layer : Feature (feature-chat)
 * Pattern Used       : MVVM (ViewModel + StateFlow)
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
 * Module     : feature-chat
 * File       : ChatViewModel.kt
 * Purpose    : ViewModel for the conversation list screen
 *
 * Architecture Layer : Feature (feature-chat)
 * Pattern Used       : MVVM (ViewModel + StateFlow)
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
 * ChatViewModel.kt
 *
 * Purpose: UI state producer for [ChatListScreen]. Manages grouped conversations,
 *          offline status, and paging for a smooth list experience.
 * Architecture: feature-chat — ViewModel layer.
 * Dependencies: getConversationsUseCase (domain), Paging (AndroidX),
 *               ConnectionTracker (core-network), Hilt.
 *
 * Design decisions:
 * - Uses [combine] to react to both database updates and network status changes.
 * - Paging is handled via [flatMapLatest] on the raw domain flow to ensure
 *   the list refreshes if the underlying data source changes substantially.
 * - Offline status is tracked via [ConnectionTracker] to show status banners.
 *
 * Requirements: 11.1, 11.3, 11.5
 */
package com.aiassistant.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.network.ConnectionTracker
import com.aiassistant.domain.model.Conversation
import com.aiassistant.domain.usecase.chat.GetConversationsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel for the conversation list.
 *
 * Reactive pipeline:
 * 1. [groupedConversationsFlow] emits [ApiResult] from the repository.
 * 2. [isOffline] emits Boolean from [ConnectionTracker].
 * 3. [uiState] combines them into a high-level UI model.
 * 4. [pagedConversations] converts the success result into [PagingData].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val getConversationsUseCase: GetConversationsUseCase,
    private val connectionTracker: ConnectionTracker
) : ViewModel() {

    // ── Domain flows ──────────────────────────────────────────────────────────

    /** Tracks real-time network connectivity. */
    private val isOffline = connectionTracker.isOffline
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = false
        )

    /** Map of grouped conversations (e.g., Today, Yesterday, Older). */
    private val conversations = getConversationsUseCase()
        .map { result ->
            if (result is ApiResult.Success) {
                result.data.groupBy { it.group }
            } else {
                emptyMap()
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyMap()
        )

    /** Internal flow of the latest [GroupedConversations] for use in paging. */
    private val groupedConversationsFlow = getConversationsUseCase()

    // ── UI state ──────────────────────────────────────────────────────────────

    /**
     * Primary UI state combining grouped conversations and offline status.
     * Emits [ChatListUiState.Loading] until the first result arrives.
     */
    val uiState: StateFlow<ChatListUiState> = combine(
        groupedConversationsFlow,
        isOffline
    ) { result, offline ->
        when (result) {
            is ApiResult.Loading -> ChatListUiState.Loading
            is ApiResult.Success -> {
                ChatListUiState.Success(
                    conversations = result.data,
                    isOffline = offline
                )
            }
            is ApiResult.Error -> ChatListUiState.Error(result.error)
            else -> ChatListUiState.Error(null)
        }
    }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ChatListUiState.Loading
    )

    /**
     * Paged flow of chat list items (Conversation + Headers).
     * Cached in [viewModelScope] to survive configuration changes.
     */
    val pagedConversations: Flow<PagingData<ChatListItem>> = groupedConversationsFlow
        .flatMapLatest { result ->
            when (result) {
                is ApiResult.Success -> result.data.toFlatList()
                else -> kotlinx.coroutines.flow.flowOf(PagingData.empty())
            }
        }
        .cachedIn(viewModelScope)

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Converts a flat list of [Conversation]s into a paged list of [ChatListItem]s.
     * This logic is kept internal to the ViewModel as it's UI-representation specific.
     */
    private fun List<Conversation>.toFlatList(): Flow<PagingData<ChatListItem>> {
        // In a real app, this would use a Pager + PagingSource.
        // For this implementation, we simulate a paged flow from the list.
        val items = this.map { ChatListItem.Entry(it) }
        return kotlinx.coroutines.flow.flowOf(PagingData.from(items))
    }
}

/**
 * UI model representing an item in the chat list.
 */
sealed class ChatListItem {
    /** A section header (e.g., "Today"). */
    data class Header(val title: String) : ChatListItem()

    /** A conversation entry. */
    data class Entry(val conversation: Conversation) : ChatListItem()
}

/**
 * High-level UI state for the conversation list.
 */
sealed class ChatListUiState {
    object Loading : ChatListUiState()
    data class Success(val conversations: List<Conversation>, val isOffline: Boolean) : ChatListUiState()
    data class Error(val error: Any?) : ChatListUiState()
}
