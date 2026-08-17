/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : GetConversationsUseCase.kt
 * Purpose    : Encapsulates the 'GetConversations' business operation
 *
 * Architecture Layer : Domain
 * Pattern Used       : Clean Architecture Use Case
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
 * GetConversationsUseCase.kt
 *
 * Purpose: Retrieves all conversations for the authenticated user, sorted by last-modified
 *          date descending and grouped into date categories for display.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), domain repository (ConversationRepository),
 *               domain models (Conversation, GroupedConversations)
 *
 * Requirements: 11.1, 11.5
 *
 * Design decisions:
 * - Sorting and grouping are performed in the domain layer so the data layer stays
 *   focused on storage/retrieval and the UI layer receives ready-to-render data.
 * - Uses java.time.LocalDate and ZoneId.systemDefault() for date comparison; this is
 *   pure JVM API â€” no Android framework dependency.
 * - Returns Flow<ApiResult<GroupedConversations>> so the UI can react to both loading
 *   state and incremental database updates.
 */

package com.aiassistant.domain.usecase.conversation

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.Conversation
import com.aiassistant.domain.model.GroupedConversations
import com.aiassistant.domain.repository.ConversationRepository
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Use case for retrieving and grouping the authenticated user's conversations.
 *
 * THE AI_Assistant SHALL display a paginated list of all Conversations sorted by
 * last-modified date (Requirement 11.1) and SHALL group Conversations by date category:
 * Today, Yesterday, Last 7 Days, and Older (Requirement 11.5).
 *
 * Conversations are sorted by [Conversation.updatedAt] descending within each group.
 * Soft-deleted conversations ([Conversation.isDeleted] = true) are excluded.
 *
 * @param conversationRepository Repository providing the conversation data stream.
 */
class GetConversationsUseCase @Inject constructor(private val conversationRepository: ConversationRepository) {

    /**
     * Executes the use case.
     *
     * Subscribes to the repository's conversation stream, sorts conversations by
     * [Conversation.updatedAt] descending, filters out soft-deleted entries, then
     * partitions them into the four date groups using the device's system time zone.
     *
     * @return Cold [Flow] emitting [ApiResult.Success] with a [GroupedConversations]
     *         instance, or an error variant when the data layer fails.
     */
    operator fun invoke(): Flow<ApiResult<GroupedConversations>> =
        conversationRepository.getConversations().map { result ->
            result.map { conversations ->
                groupConversations(
                    conversations
                        .filter { !it.isDeleted }
                        .sortedByDescending { it.updatedAt }
                )
            }
        }

    // ─── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Partitions [conversations] (already sorted by [Conversation.updatedAt] descending)
     * into four date-based buckets relative to the current calendar day in the system
     * default time zone.
     *
     * Category boundaries (inclusive on the left, exclusive on the right):
     * - **Today**     : updatedAt date == today
     * - **Yesterday** : updatedAt date == today - 1 day
     * - **Last7Days** : updatedAt date in (today - 6 days â€¦ today - 2 days) â€” i.e. within
     *                   the past 7 days but not today or yesterday
     * - **Older**     : updatedAt date < today - 6 days
     */
    private fun groupConversations(conversations: List<Conversation>): GroupedConversations {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        val sevenDaysAgo = today.minusDays(7)

        val todayList = mutableListOf<Conversation>()
        val yesterdayList = mutableListOf<Conversation>()
        val last7DaysList = mutableListOf<Conversation>()
        val olderList = mutableListOf<Conversation>()

        for (conversation in conversations) {
            val updatedDate = conversation.updatedAt
                .atZone(zone)
                .toLocalDate()

            when {
                updatedDate == today -> todayList.add(conversation)
                updatedDate == yesterday -> yesterdayList.add(conversation)
                updatedDate.isAfter(sevenDaysAgo) -> last7DaysList.add(conversation)
                else -> olderList.add(conversation)
            }
        }

        return GroupedConversations(
            today = todayList,
            yesterday = yesterdayList,
            last7Days = last7DaysList,
            older = olderList
        )
    }
}
