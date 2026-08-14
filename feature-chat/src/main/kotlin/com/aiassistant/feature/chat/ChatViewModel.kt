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
 *               ConnectivityObserver (core-network), Hilt.
 *
 * Design decisions:
 * - Uses [combine] to react to both database updates and network status changes.
 * - Paging is handled via [flatMapLatest] on the raw domain flow to ensure
 *   the list refreshes if the underlying data source changes substantially.
 * - Offline status is tracked via [ConnectivityObserver] to show status banners.
 *
 * Requirements: 11.1, 11.3, 11.5
 */
package com.aiassistant.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.domain.model.Conversation
import com.aiassistant.domain.model.GroupedConversations
import com.aiassistant.domain.usecase.conversation.GetConversationsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for the conversation list.
 *
 * Reactive pipeline:
 * 1. [groupedConversationsFlow] emits [ApiResult] from the repository.
 * 2. [isOffline] emits Boolean from [ConnectivityObserver].
 * 3. [uiState] combines them into a high-level UI model.
 * 4. [pagedConversations] converts the success result into [PagingData].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val getConversationsUseCase: GetConversationsUseCase,
    private val connectivityObserver: ConnectivityObserver
) : ViewModel() {

    // ── Domain flows ──────────────────────────────────────────────────────────

    /** Tracks real-time network connectivity. */
    private val isOffline = connectivityObserver.isConnectedFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = false
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
                else -> flowOf(PagingData.empty())
            }
        }
        .cachedIn(viewModelScope)

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Converts a [GroupedConversations] instance into a paged list of [ChatListItem]s.
     * Inserts section headers for each non-empty group (Today, Yesterday, etc.).
     */
    private fun GroupedConversations.toFlatList(): Flow<PagingData<ChatListItem>> {
        val items = mutableListOf<ChatListItem>()

        if (today.isNotEmpty()) {
            items.add(ChatListItem.Header("Today"))
            items.addAll(today.map { ChatListItem.Entry(it) })
        }
        if (yesterday.isNotEmpty()) {
            items.add(ChatListItem.Header("Yesterday"))
            items.addAll(yesterday.map { ChatListItem.Entry(it) })
        }
        if (last7Days.isNotEmpty()) {
            items.add(ChatListItem.Header("Last 7 Days"))
            items.addAll(last7Days.map { ChatListItem.Entry(it) })
        }
        if (older.isNotEmpty()) {
            items.add(ChatListItem.Header("Older"))
            items.addAll(older.map { ChatListItem.Entry(it) })
        }

        return flowOf(PagingData.from(items))
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
    data class Success(val conversations: GroupedConversations, val isOffline: Boolean) : ChatListUiState()
    data class Error(val error: Any?) : ChatListUiState()
}
