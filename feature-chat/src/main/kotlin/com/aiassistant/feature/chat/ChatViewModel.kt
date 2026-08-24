/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-chat
 * File       : ChatViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Chat feature
 *
 * Architecture Layer : Feature (feature-chat)
 * Pattern Used       : MVVM ViewModel
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
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Chat feature
 *
 * Architecture Layer : Feature (feature-chat)
 * Pattern Used       : MVVM ViewModel
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
 * Purpose: Manages all UI state for the conversation list screen. Combines grouped
 *          conversations, offline status, search results and exposes Paging 3 data.
 * Architecture: feature-chat â€” MVVM ViewModel; injected via Hilt.
 * Dependencies: domain use cases, ConversationRepository, ConnectivityObserver,
 *               core-common (DispatcherProvider, ApiResult), Paging 3
 *
 * Requirements: 11.1, 11.3, 11.5, 10.4, 17.6
 */
package com.aiassistant.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.domain.model.Conversation
import com.aiassistant.domain.model.GroupedConversations
import com.aiassistant.domain.repository.ConversationRepository
import com.aiassistant.domain.usecase.conversation.CreateConversationUseCase
import com.aiassistant.domain.usecase.conversation.DeleteConversationUseCase
import com.aiassistant.domain.usecase.conversation.GetConversationsUseCase
import com.aiassistant.domain.usecase.conversation.SearchConversationsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the conversation list screen.
 *
 * Exposes a [StateFlow] of [ChatListUiState] plus a Paging 3 [Flow] of [PagingData].
 * All blocking I/O is dispatched on [DispatcherProvider.io].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val getConversationsUseCase: GetConversationsUseCase,
    private val createConversationUseCase: CreateConversationUseCase,
    private val deleteConversationUseCase: DeleteConversationUseCase,
    private val searchConversationsUseCase: SearchConversationsUseCase,
    private val conversationRepository: ConversationRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    // â”€â”€â”€ Offline state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Emits `true` when the device has no network connectivity (inverted from
     * [ConnectivityObserver.isConnectedFlow]).
     */
    val isOffline: StateFlow<Boolean> = connectivityObserver.isConnectedFlow
        .map { isConnected -> !isConnected }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = !connectivityObserver.isConnected()
        )

    // â”€â”€â”€ Search â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private val _searchQuery = MutableStateFlow("")

    /** Current search query string. Empty string means "show all conversations". */
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * FTS search results, updated whenever [_searchQuery] changes.
     * Empty list when the query is blank (all conversations are shown via [pagedConversations]).
     */
    val searchResults: StateFlow<List<Conversation>> = _searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) {
                flowOf(emptyList())
            } else {
                searchConversationsUseCase(query).map { result ->
                    when (result) {
                        is ApiResult.Success -> result.data
                        else -> emptyList()
                    }
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList()
        )

    // â”€â”€â”€ Grouped conversations â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** Internal flow of the latest [GroupedConversations] for use in paging. */
    private val groupedConversationsFlow = getConversationsUseCase()

    // ──────────────────────────────────────────────────────────────────────────

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
                val grouped = result.data
                if (grouped.isEmpty) {
                    ChatListUiState.Empty(isOffline = offline)
                } else {
                    ChatListUiState.Success(
                        groupedConversations = grouped,
                        isOffline = offline
                    )
                }
            }
            is ApiResult.Error -> ChatListUiState.Error(
                message = result.error.message
            )
            is ApiResult.NetworkUnavailable -> {
                // Still show any cached data as Empty so the user sees the offline banner
                ChatListUiState.Empty(isOffline = true)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ChatListUiState.Loading
    )

    // â”€â”€â”€ Paging 3 â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Paged flat list of [ChatListItem] objects for the [LazyColumn].
     *
     * Rebuilds the [Pager] whenever the upstream [GroupedConversations] changes, ensuring
     * the UI always shows the latest data. Each page holds at most 20 items.
     */
    val pagedConversations: Flow<PagingData<ChatListItem>> = groupedConversationsFlow
        .flatMapLatest { result ->
            val items = when (result) {
                is ApiResult.Success -> result.data.toFlatList()
                else -> emptyList()
            }
            Pager(
                config = PagingConfig(
                    pageSize = 20,
                    enablePlaceholders = false,
                    maxSize = 20 * 3 // keep at most 3 pages in memory
                ),
                pagingSourceFactory = { GroupedConversationsPagingSource(items) }
            ).flow
        }
        .cachedIn(viewModelScope)

    // â”€â”€â”€ Public actions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Pins or unpins a conversation.
     *
     * @param conversationId The unique identifier of the target conversation.
     * @param isPinned       `true` to pin, `false` to unpin.
     */
    fun pinConversation(conversationId: String, isPinned: Boolean) {
        viewModelScope.launch {
            withContext(dispatchers.io) {
                conversationRepository.pinConversation(conversationId, isPinned)
            }
        }
    }

    /**
     * Renames a conversation.
     *
     * @param conversationId The unique identifier of the target conversation.
     * @param newTitle       The new title to apply.
     */
    fun renameConversation(conversationId: String, newTitle: String) {
        viewModelScope.launch {
            withContext(dispatchers.io) {
                conversationRepository.renameConversation(conversationId, newTitle.trim())
            }
        }
    }

    /**
     * Soft-deletes a conversation.
     *
     * @param conversationId The unique identifier of the target conversation.
     */
    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            withContext(dispatchers.io) {
                deleteConversationUseCase(conversationId)
            }
        }
    }

    /**
     * Creates a new conversation and navigates to it when creation succeeds.
     *
     * @param title    The desired title for the new conversation.
     * @param provider The LLM provider identifier (e.g., "openai", "gemini").
     */
    fun createConversation(title: String, provider: String) {
        viewModelScope.launch {
            withContext(dispatchers.io) {
                createConversationUseCase(title, provider)
            }
        }
    }

    /**
     * Updates the active search query, triggering [searchResults] to re-emit.
     *
     * @param query The new search query. Pass an empty string to clear the search.
     */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }
}

// â”€â”€â”€ PagingSource â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * A simple in-memory [PagingSource] that paginates a pre-built flat list of
 * [ChatListItem] objects derived from [GroupedConversations].
 *
 * The key type is [Int] (page index). Each page returns a slice of up to
 * [ITEMS_PER_PAGE] items from [items].
 *
 * @param items The complete flat list (headers + conversation rows) to paginate.
 */
class GroupedConversationsPagingSource(private val items: List<ChatListItem>) : PagingSource<Int, ChatListItem>() {

    override fun getRefreshKey(state: androidx.paging.PagingState<Int, ChatListItem>): Int? =
        state.anchorPosition?.let { anchor ->
            state.closestPageToPosition(anchor)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchor)?.nextKey?.minus(1)
        }

    override suspend fun load(params: LoadParams<Int>): PagingSource.LoadResult<Int, ChatListItem> {
        return try {
            val page = params.key ?: 0
            val pageSize = params.loadSize
            val fromIndex = page * pageSize
            val toIndex = minOf(fromIndex + pageSize, items.size)

            if (fromIndex > items.size) {
                return PagingSource.LoadResult.Page(
                    data = emptyList(),
                    prevKey = if (page == 0) null else page - 1,
                    nextKey = null
                )
            }

            val slice = items.subList(fromIndex, toIndex)
            PagingSource.LoadResult.Page(
                data = slice,
                prevKey = if (page == 0) null else page - 1,
                nextKey = if (toIndex >= items.size) null else page + 1
            )
        } catch (e: Exception) {
            PagingSource.LoadResult.Error(e)
        }
    }
}

// â”€â”€â”€ Extension helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Projects a [GroupedConversations] into a flat [List] of [ChatListItem], interleaving
 * section [ChatListItem.Header] rows before each non-empty group.
 */
private fun GroupedConversations.toFlatList(): List<ChatListItem> {
    val result = mutableListOf<ChatListItem>()

    if (today.isNotEmpty()) {
        result.add(ChatListItem.Header("Today"))
        today.forEach { result.add(ChatListItem.ConversationItem(it)) }
    }
    if (yesterday.isNotEmpty()) {
        result.add(ChatListItem.Header("Yesterday"))
        yesterday.forEach { result.add(ChatListItem.ConversationItem(it)) }
    }
    if (last7Days.isNotEmpty()) {
        result.add(ChatListItem.Header("Last 7 Days"))
        last7Days.forEach { result.add(ChatListItem.ConversationItem(it)) }
    }
    if (older.isNotEmpty()) {
        result.add(ChatListItem.Header("Older"))
        older.forEach { result.add(ChatListItem.ConversationItem(it)) }
    }

    return result
}
