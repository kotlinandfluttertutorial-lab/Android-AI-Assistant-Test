/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-history
 * File       : HistoryViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the History feature
 *
 * Architecture Layer : Feature (feature-history)
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
 * Module     : feature-history
 * File       : HistoryViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the History feature
 *
 * Architecture Layer : Feature (feature-history)
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
 * HistoryViewModel.kt
 *
 * Purpose: Manages all UI state and orchestrates use case calls for the conversation
 *          history feature, including listing, searching, exporting, deleting,
 *          renaming, and pinning conversations.
 * Architecture: feature-history â€” MVVM ViewModel; injected via Hilt.
 * Dependencies: domain (GetConversationsUseCase, SearchConversationsUseCase,
 *               ExportConversationUseCase, DeleteConversationUseCase,
 *               ConversationRepository),
 *               core-common (DispatcherProvider, ApiResult),
 *               core-network (ConnectivityObserver),
 *               Paging 3
 *
 * Requirements: 11.1, 11.2, 11.6
 */
package com.aiassistant.feature.history

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
import com.aiassistant.domain.model.ExportFormat
import com.aiassistant.domain.model.GroupedConversations
import com.aiassistant.domain.repository.ConversationRepository
import com.aiassistant.domain.usecase.conversation.DeleteConversationUseCase
import com.aiassistant.domain.usecase.conversation.ExportConversationUseCase
import com.aiassistant.domain.usecase.conversation.GetConversationsUseCase
import com.aiassistant.domain.usecase.conversation.SearchConversationsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the conversation history list and inline search flows.
 *
 * Exposes a [StateFlow] of [HistoryUiState] that composables observe. All blocking
 * work (network calls, database operations) is dispatched on [DispatcherProvider.io].
 *
 * State transitions:
 * - [HistoryUiState.Loading]       â†’ initial / reload
 * - [HistoryUiState.HistoryList]   â†’ data ready
 * - [HistoryUiState.SearchResults] â†’ search active
 * - [HistoryUiState.Exporting]     â†’ export in progress
 * - [HistoryUiState.ExportSuccess] â†’ export done
 * - [HistoryUiState.Error]         â†’ operation failed
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val getConversationsUseCase: GetConversationsUseCase,
    private val searchConversationsUseCase: SearchConversationsUseCase,
    private val exportConversationUseCase: ExportConversationUseCase,
    private val deleteConversationUseCase: DeleteConversationUseCase,
    private val conversationRepository: ConversationRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    // â”€â”€â”€ Offline state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Emits `true` when the device has no network connectivity.
     */
    val isOffline: StateFlow<Boolean> = connectivityObserver.isConnectedFlow
        .map { isConnected -> !isConnected }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = !connectivityObserver.isConnected()
        )

    // â”€â”€â”€ Primary UI state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private val _uiState = MutableStateFlow<HistoryUiState>(HistoryUiState.Loading)

    /** Observable history UI state for composables to collect. */
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    /** Tracks the active collection job so it can be cancelled on new requests. */
    private var activeJob: Job? = null

    // â”€â”€â”€ Upstream conversations flow â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Raw grouped conversations flow from the use case. Used by [pagedConversations].
     */
    private val groupedConversationsFlow = getConversationsUseCase()

    // â”€â”€â”€ Paging 3 â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Paged flat list of [HistoryListItem] objects for the conversation [LazyColumn].
     *
     * Rebuilds the [Pager] whenever the upstream [GroupedConversations] changes, ensuring
     * the UI always shows fresh data. Each page holds at most 20 items (Requirement 17.6).
     */
    val pagedConversations: Flow<PagingData<HistoryListItem>> = groupedConversationsFlow
        .flatMapLatest { result ->
            val items: List<HistoryListItem> = when (result) {
                is ApiResult.Success -> result.data.toFlatList()
                else -> emptyList()
            }
            Pager(
                config = PagingConfig(
                    pageSize = 20,
                    enablePlaceholders = false,
                    maxSize = 20 * 3 // retain at most 3 pages in memory
                ),
                pagingSourceFactory = { HistoryListPagingSource(items) }
            ).flow
        }
        .cachedIn(viewModelScope)

    // â”€â”€â”€ Search query â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * The currently active search query. Updated by [search] and cleared by [clearSearch].
     * Exposed as a plain property rather than a StateFlow so callers that need the
     * current value don't have to collect; the SearchHistory screen reads it once on
     * initial composition.
     */
    var currentSearchQuery: String = ""
        private set

    // â”€â”€â”€ Init â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    init {
        loadHistory()
    }

    // â”€â”€â”€ Public API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Loads all grouped conversations and emits [HistoryUiState.HistoryList].
     *
     * Collects [GetConversationsUseCase] which returns conversations sorted by
     * [Conversation.updatedAt] descending and grouped into date buckets (Requirement 11.1).
     *
     * Cancels any in-flight search or reload job before starting a new one.
     */
    fun loadHistory() {
        currentSearchQuery = ""
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.value = HistoryUiState.Loading
            getConversationsUseCase().collect { result ->
                _uiState.value = when (result) {
                    is ApiResult.Success -> HistoryUiState.HistoryList(
                        groupedConversations = result.data
                    )
                    is ApiResult.Error -> HistoryUiState.Error(result.error.message)
                    is ApiResult.NetworkUnavailable -> {
                        // Show empty list with offline banner (offline-first pattern).
                        val current = _uiState.value
                        if (current is HistoryUiState.HistoryList) {
                            current // keep existing cached data visible
                        } else {
                            HistoryUiState.HistoryList(
                                groupedConversations = GroupedConversations()
                            )
                        }
                    }
                    is ApiResult.Loading -> HistoryUiState.Loading
                }
            }
        }
    }

    /**
     * Searches conversations using the FTS index (Requirement 11.2 â€” 300 ms on device).
     *
     * An empty [query] clears search and delegates to [loadHistory].
     *
     * @param query The full-text search query.
     */
    fun search(query: String) {
        currentSearchQuery = query
        if (query.isBlank()) {
            loadHistory()
            return
        }

        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            searchConversationsUseCase(query).collect { result ->
                _uiState.value = when (result) {
                    is ApiResult.Success -> HistoryUiState.SearchResults(
                        query = query,
                        results = result.data
                    )
                    is ApiResult.Error -> HistoryUiState.Error(result.error.message)
                    is ApiResult.NetworkUnavailable -> HistoryUiState.SearchResults(
                        query = query,
                        results = emptyList()
                    )
                    is ApiResult.Loading -> HistoryUiState.Loading
                }
            }
        }
    }

    /**
     * Clears the active search and returns to [HistoryUiState.HistoryList].
     */
    fun clearSearch() {
        loadHistory()
    }

    /**
     * Exports the conversation identified by [conversationId] in the given [format]
     * (Requirement 11.6).
     *
     * Transitions: [Exporting] â†’ [ExportSuccess] on success, [Error] on failure.
     *
     * @param conversationId The conversation to export.
     * @param format         [ExportFormat.MARKDOWN] or [ExportFormat.PDF].
     */
    fun exportConversation(conversationId: String, format: ExportFormat) {
        viewModelScope.launch {
            _uiState.value = HistoryUiState.Exporting(
                conversationId = conversationId,
                format = format
            )
            val result = withContext(dispatchers.io) {
                exportConversationUseCase(conversationId, format)
            }
            _uiState.value = when (result) {
                is ApiResult.Success -> HistoryUiState.ExportSuccess(
                    filePath = result.data,
                    format = format
                )
                is ApiResult.Error -> HistoryUiState.Error(result.error.message)
                is ApiResult.NetworkUnavailable -> HistoryUiState.Error(
                    "No network connection. Export requires internet access."
                )
                is ApiResult.Loading -> HistoryUiState.Exporting(conversationId, format)
            }
        }
    }

    /**
     * Soft-deletes the conversation identified by [conversationId] then reloads the list.
     *
     * @param conversationId The unique identifier of the conversation to delete.
     */
    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            withContext(dispatchers.io) {
                deleteConversationUseCase(conversationId)
            }
            loadHistory()
        }
    }

    /**
     * Renames the conversation identified by [conversationId] to [newTitle], then
     * reloads the list.
     *
     * @param conversationId The unique identifier of the conversation to rename.
     * @param newTitle       The new human-readable title.
     */
    fun renameConversation(conversationId: String, newTitle: String) {
        viewModelScope.launch {
            withContext(dispatchers.io) {
                conversationRepository.renameConversation(conversationId, newTitle)
            }
            loadHistory()
        }
    }

    /**
     * Pins or unpins the conversation identified by [conversationId], then reloads.
     *
     * @param conversationId The unique identifier of the conversation.
     * @param isPinned       `true` to pin, `false` to unpin.
     */
    fun pinConversation(conversationId: String, isPinned: Boolean) {
        viewModelScope.launch {
            withContext(dispatchers.io) {
                conversationRepository.pinConversation(conversationId, isPinned)
            }
            loadHistory()
        }
    }

    /**
     * Dismisses the [HistoryUiState.ExportSuccess] state and returns to the history list.
     *
     * No-op when the current state is not [HistoryUiState.ExportSuccess].
     */
    fun dismissExportSuccess() {
        if (_uiState.value is HistoryUiState.ExportSuccess) {
            loadHistory()
        }
    }
}

// â”€â”€â”€ PagingSource â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * In-memory [PagingSource] that paginates a pre-built flat list of [HistoryListItem]
 * objects derived from [GroupedConversations].
 *
 * Each page returns at most [PAGE_SIZE] items. Header items are counted like any other
 * row so the page boundaries are consistent.
 *
 * @param items The complete flat list (headers + conversation rows) to paginate.
 */
internal class HistoryListPagingSource(private val items: List<HistoryListItem>) :
    PagingSource<Int, HistoryListItem>() {

    companion object {
        private const val PAGE_SIZE = 20
    }

    override fun getRefreshKey(state: androidx.paging.PagingState<Int, HistoryListItem>): Int? =
        state.anchorPosition?.let { anchor ->
            state.closestPageToPosition(anchor)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchor)?.nextKey?.minus(1)
        }

    override suspend fun load(params: LoadParams<Int>): PagingSource.LoadResult<Int, HistoryListItem> {
        return try {
            val page = params.key ?: 0
            val pageSize = params.loadSize
            val fromIndex = page * pageSize
            if (fromIndex >= items.size) {
                return PagingSource.LoadResult.Page(
                    data = emptyList(),
                    prevKey = if (page == 0) null else page - 1,
                    nextKey = null
                )
            }
            val toIndex = minOf(fromIndex + pageSize, items.size)
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
 * Projects a [GroupedConversations] into a flat [List] of [HistoryListItem], interleaving
 * [HistoryListItem.Header] rows before each non-empty group.
 */
private fun GroupedConversations.toFlatList(): List<HistoryListItem> {
    val result = mutableListOf<HistoryListItem>()

    if (today.isNotEmpty()) {
        result.add(HistoryListItem.Header("Today"))
        today.forEach { result.add(HistoryListItem.ConversationItem(it)) }
    }
    if (yesterday.isNotEmpty()) {
        result.add(HistoryListItem.Header("Yesterday"))
        yesterday.forEach { result.add(HistoryListItem.ConversationItem(it)) }
    }
    if (last7Days.isNotEmpty()) {
        result.add(HistoryListItem.Header("Last 7 Days"))
        last7Days.forEach { result.add(HistoryListItem.ConversationItem(it)) }
    }
    if (older.isNotEmpty()) {
        result.add(HistoryListItem.Header("Older"))
        older.forEach { result.add(HistoryListItem.ConversationItem(it)) }
    }

    return result
}
