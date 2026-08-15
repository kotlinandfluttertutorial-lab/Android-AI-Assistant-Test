/**
 * ConversationDateGroupingPropertyTest.kt — domain module
 *
 * Purpose: Property-based tests for Property 18: Conversation Date Grouping Invariant.
 *          Verifies that [GetConversationsUseCase] correctly groups any list of
 *          [Conversation] objects into exactly one of four mutually exclusive date
 *          categories: Today, Yesterday, Last 7 Days, and Older.
 *
 * Architecture: domain module — unit tests (pure JVM, no Android framework).
 *
 * Test toolchain:
 * - Kotest DescribeSpec + checkAll / Arb — property-based test structure
 * - MockK                               — mocking ConversationRepository
 * - kotlinx.coroutines.flow             — first() to collect single emission
 *
 * **Validates: Requirements 11.5**
 *
 * Requirements covered:
 *   11.5 — THE AI_Assistant SHALL group Conversations by date category: Today, Yesterday,
 *           Last 7 Days, and Older.
 *
 * Properties verified:
 *   P18-1  Every conversation lands in exactly one group (mutual exclusion).
 *   P18-2  No conversation appears in more than one group (no duplicates).
 *   P18-3  Every conversation from the input appears in some group (completeness).
 *   P18-4  Conversations with updatedAt == today land in the "today" group.
 *   P18-5  Conversations with updatedAt == yesterday land in the "yesterday" group.
 *   P18-6  Conversations with updatedAt 2–6 days ago land in the "last7Days" group.
 *   P18-7  Conversations with updatedAt >= 7 days ago land in the "older" group.
 *   P18-8  total count of all groups equals total input size (no conversations lost).
 */

package com.aiassistant.domain.usecase.conversation

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.Conversation
import com.aiassistant.domain.model.GroupedConversations
import com.aiassistant.domain.repository.ConversationRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

// ─── Time-zone helper (kept local for testability) ─────────────────────────────

private val ZONE: ZoneId = ZoneId.systemDefault()

/**
 * Returns the [Instant] at [daysAgo] calendar days before today (within a given day's range).
 * [offsetSeconds] shifts within the same calendar day to exercise intra-day variation.
 */
private fun daysAgoInstant(daysAgo: Long, offsetSeconds: Long = 3_600L): Instant = LocalDate.now(ZONE)
    .minusDays(daysAgo)
    .atStartOfDay(ZONE)
    .toInstant()
    .plusSeconds(offsetSeconds)

// ─── Generators ───────────────────────────────────────────────────────────────

/**
 * Generates a [Conversation] with a stable positional [id] and a random [updatedAt]
 * timestamp. The [id] is derived from the position index so that different items in
 * the same list always have distinct IDs — even after Kotest shrinks other fields.
 *
 * The timestamp range spans from ~730 days in the past to the end of today,
 * covering all four date groups (Today, Yesterday, Last 7 Days, Older) plus
 * edge cases near each boundary. The upper bound is the end of today rather than
 * Instant.now() to avoid timestamps that fall in the future yet are still classified
 * as "today" by the grouping logic, which would make P18-7's "older" assertions fail
 * due to unrelated conversations generated alongside future-dated ones.
 *
 * Soft-deleted conversations are excluded before grouping in the use case, so
 * all generated conversations have [isDeleted] = false to ensure they reach
 * the grouping logic.
 */
private fun arbConversationWithUniqueId(index: Int): Arb<Conversation> = arbitrary {
    // Range: up to 730 days ago. Upper bound: end of today (23:59:59).
    val endOfTodayEpochSecond = LocalDate.now(ZONE).plusDays(1).atStartOfDay(ZONE).toInstant().epochSecond - 1
    val twoYearsAgoEpochSecond = endOfTodayEpochSecond - (730L * 86_400L)

    val epochSecond = Arb.long(twoYearsAgoEpochSecond..endOfTodayEpochSecond).bind()
    val updatedAt = Instant.ofEpochSecond(epochSecond)
    // Use a positional ID that is unique per-index within the list and does not shrink
    // to a value shared by other list elements.
    val id = "conv-$index-$epochSecond"

    Conversation(
        id = id,
        userId = "user-test",
        title = "Conv $id",
        isPinned = false,
        isDeleted = false,
        provider = "openai",
        createdAt = updatedAt,
        updatedAt = updatedAt
    )
}

/**
 * Generates a list of 0..20 conversations with random timestamps and guaranteed
 * unique IDs. Each element uses its list position as part of its ID so Kotest's
 * shrinking cannot collapse two distinct conversations onto the same ID.
 */
private val arbConversationList: Arb<List<Conversation>> = arbitrary {
    val size = Arb.long(0L..20L).bind().toInt()
    (0 until size).map { index -> arbConversationWithUniqueId(index).bind() }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Builds a mock [ConversationRepository] that emits [conversations] on [getConversations].
 */
private fun repositoryWith(conversations: List<Conversation>): ConversationRepository {
    val repo = mockk<ConversationRepository>()
    every { repo.getConversations() } returns
        flowOf(ApiResult.Success(conversations))
    return repo
}

/**
 * Runs [GetConversationsUseCase] with the given [conversations] and returns
 * the resulting [GroupedConversations].
 */
private suspend fun group(conversations: List<Conversation>): GroupedConversations {
    val useCase = GetConversationsUseCase(repositoryWith(conversations))
    val result = useCase().first() as ApiResult.Success<GroupedConversations>
    return result.data
}

/**
 * Returns all four groups as a flat list of (conversationId, groupName) pairs
 * to make duplicate/overlap assertions easy.
 */
private fun GroupedConversations.allAssignments(): List<Pair<String, String>> = today.map { it.id to "today" } +
    yesterday.map { it.id to "yesterday" } +
    last7Days.map { it.id to "last7Days" } +
    older.map { it.id to "older" }

// ─── Property 18: Conversation Date Grouping Invariant ────────────────────────

/**
 * **Validates: Requirements 11.5**
 */
class ConversationDateGroupingPropertyTest :
    DescribeSpec({

        // ── P18-1 & P18-2 — Mutual exclusion: each conversation in exactly one group ──
        describe("P18-1/P18-2 — every conversation appears in exactly one group (mutual exclusion)") {

            it("no conversation id appears in more than one group for any random list") {
                checkAll(iterations = 300, arbConversationList) { conversations ->
                    val grouped = group(conversations)
                    val assignments = grouped.allAssignments()

                    // Each conversation ID must appear exactly once across all four groups
                    val idCounts = assignments.groupingBy { it.first }.eachCount()
                    idCounts.values.forEach { count ->
                        count shouldBe 1
                    }
                }
            }

            it("no single conversation appears in both today and yesterday") {
                checkAll(iterations = 300, arbConversationList) { conversations ->
                    val grouped = group(conversations)
                    val todayIds = grouped.today.map { it.id }.toSet()
                    val yesterdayIds = grouped.yesterday.map { it.id }.toSet()

                    (todayIds intersect yesterdayIds).isEmpty().shouldBeTrue()
                }
            }

            it("no single conversation appears in both today and last7Days") {
                checkAll(iterations = 300, arbConversationList) { conversations ->
                    val grouped = group(conversations)
                    val todayIds = grouped.today.map { it.id }.toSet()
                    val last7DaysIds = grouped.last7Days.map { it.id }.toSet()

                    (todayIds intersect last7DaysIds).isEmpty().shouldBeTrue()
                }
            }

            it("no single conversation appears in both today and older") {
                checkAll(iterations = 300, arbConversationList) { conversations ->
                    val grouped = group(conversations)
                    val todayIds = grouped.today.map { it.id }.toSet()
                    val olderIds = grouped.older.map { it.id }.toSet()

                    (todayIds intersect olderIds).isEmpty().shouldBeTrue()
                }
            }

            it("no single conversation appears in both yesterday and last7Days") {
                checkAll(iterations = 300, arbConversationList) { conversations ->
                    val grouped = group(conversations)
                    val yesterdayIds = grouped.yesterday.map { it.id }.toSet()
                    val last7DaysIds = grouped.last7Days.map { it.id }.toSet()

                    (yesterdayIds intersect last7DaysIds).isEmpty().shouldBeTrue()
                }
            }

            it("no single conversation appears in both yesterday and older") {
                checkAll(iterations = 300, arbConversationList) { conversations ->
                    val grouped = group(conversations)
                    val yesterdayIds = grouped.yesterday.map { it.id }.toSet()
                    val olderIds = grouped.older.map { it.id }.toSet()

                    (yesterdayIds intersect olderIds).isEmpty().shouldBeTrue()
                }
            }

            it("no single conversation appears in both last7Days and older") {
                checkAll(iterations = 300, arbConversationList) { conversations ->
                    val grouped = group(conversations)
                    val last7DaysIds = grouped.last7Days.map { it.id }.toSet()
                    val olderIds = grouped.older.map { it.id }.toSet()

                    (last7DaysIds intersect olderIds).isEmpty().shouldBeTrue()
                }
            }
        }

        // ── P18-3 & P18-8 — Completeness: no conversations lost ───────────────────
        describe("P18-3/P18-8 — every input conversation lands in some group (completeness)") {

            it("total count across all groups equals the number of non-deleted input conversations") {
                checkAll(iterations = 300, arbConversationList) { conversations ->
                    val grouped = group(conversations)

                    // All generated conversations have isDeleted = false, so total must equal input size
                    grouped.totalCount shouldBe conversations.size
                }
            }

            it("every input conversation id appears in exactly one of the four groups") {
                checkAll(iterations = 300, arbConversationList) { conversations ->
                    val grouped = group(conversations)
                    val allGroupedIds =
                        (grouped.today + grouped.yesterday + grouped.last7Days + grouped.older)
                            .map { it.id }
                            .toSet()
                    val inputIds = conversations.map { it.id }.toSet()

                    // The union of all groups must equal the input set
                    allGroupedIds shouldBe inputIds
                }
            }
        }

        // ── P18-4 — Today group correctness ───────────────────────────────────────
        describe("P18-4 — conversations with updatedAt on today's calendar date land in 'today'") {

            it("a conversation updated 0 seconds ago is always in 'today'") {
                val now = Instant.now()
                val todayConv = Conversation(
                    id = "today-conv",
                    userId = "user-1",
                    title = "Today",
                    isPinned = false,
                    isDeleted = false,
                    provider = "openai",
                    createdAt = now,
                    updatedAt = now
                )
                val grouped = group(listOf(todayConv))

                grouped.today shouldContain todayConv
                grouped.yesterday.shouldBeEmpty()
                grouped.last7Days.shouldBeEmpty()
                grouped.older.shouldBeEmpty()
            }

            it("a conversation updated at any time today (start of day to now) is always in 'today'") {
                // Epoch second range: [start of today, current second]
                val startOfToday = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant().epochSecond
                val now = Instant.now().epochSecond

                checkAll(iterations = 200, Arb.long(startOfToday..now)) { epochSecond ->
                    val todayConv = Conversation(
                        id = "today-$epochSecond",
                        userId = "user-1",
                        title = "Today conv",
                        isPinned = false,
                        isDeleted = false,
                        provider = "openai",
                        createdAt = Instant.ofEpochSecond(epochSecond),
                        updatedAt = Instant.ofEpochSecond(epochSecond)
                    )
                    val grouped = group(listOf(todayConv))

                    grouped.today shouldContain todayConv
                    grouped.yesterday.shouldBeEmpty()
                    grouped.last7Days.shouldBeEmpty()
                    grouped.older.shouldBeEmpty()
                }
            }
        }

        // ── P18-5 — Yesterday group correctness ───────────────────────────────────
        describe("P18-5 — conversations with updatedAt on yesterday's calendar date land in 'yesterday'") {

            it("a conversation updated at a fixed point yesterday is always in 'yesterday'") {
                val yesterdayConv = Conversation(
                    id = "yesterday-conv",
                    userId = "user-1",
                    title = "Yesterday",
                    isPinned = false,
                    isDeleted = false,
                    provider = "openai",
                    createdAt = daysAgoInstant(1),
                    updatedAt = daysAgoInstant(1)
                )
                val grouped = group(listOf(yesterdayConv))

                grouped.yesterday shouldContain yesterdayConv
                grouped.today.shouldBeEmpty()
                grouped.last7Days.shouldBeEmpty()
                grouped.older.shouldBeEmpty()
            }

            it("a conversation updated at any time yesterday (full day range) is always in 'yesterday'") {
                val yesterdayStart = LocalDate.now(ZONE).minusDays(1).atStartOfDay(ZONE).toInstant().epochSecond
                val yesterdayEnd = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant().epochSecond - 1

                checkAll(iterations = 200, Arb.long(yesterdayStart..yesterdayEnd)) { epochSecond ->
                    val conv = Conversation(
                        id = "yesterday-$epochSecond",
                        userId = "user-1",
                        title = "Yesterday conv",
                        isPinned = false,
                        isDeleted = false,
                        provider = "openai",
                        createdAt = Instant.ofEpochSecond(epochSecond),
                        updatedAt = Instant.ofEpochSecond(epochSecond)
                    )
                    val grouped = group(listOf(conv))

                    grouped.yesterday shouldContain conv
                    grouped.today.shouldBeEmpty()
                    grouped.last7Days.shouldBeEmpty()
                    grouped.older.shouldBeEmpty()
                }
            }
        }

        // ── P18-6 — Last 7 Days group correctness ─────────────────────────────────
        describe("P18-6 — conversations 2–6 days ago land in 'last7Days'") {

            it("a conversation updated exactly 2 days ago lands in 'last7Days'") {
                val conv = Conversation(
                    id = "two-days-ago",
                    userId = "user-1",
                    title = "Two days ago",
                    isPinned = false,
                    isDeleted = false,
                    provider = "openai",
                    createdAt = daysAgoInstant(2),
                    updatedAt = daysAgoInstant(2)
                )
                val grouped = group(listOf(conv))

                grouped.last7Days shouldContain conv
                grouped.today.shouldBeEmpty()
                grouped.yesterday.shouldBeEmpty()
                grouped.older.shouldBeEmpty()
            }

            it("a conversation updated exactly 6 days ago lands in 'last7Days'") {
                val conv = Conversation(
                    id = "six-days-ago",
                    userId = "user-1",
                    title = "Six days ago",
                    isPinned = false,
                    isDeleted = false,
                    provider = "openai",
                    createdAt = daysAgoInstant(6),
                    updatedAt = daysAgoInstant(6)
                )
                val grouped = group(listOf(conv))

                grouped.last7Days shouldContain conv
                grouped.today.shouldBeEmpty()
                grouped.yesterday.shouldBeEmpty()
                grouped.older.shouldBeEmpty()
            }

            it("conversations 2–6 days ago (full range) always land in 'last7Days'") {
                // Range: start of 6 days ago through end of 2 days ago (i.e. yesterday - 1 day at end of day)
                val sixDaysAgoStart = LocalDate.now(ZONE).minusDays(6).atStartOfDay(ZONE).toInstant().epochSecond
                val twoDaysAgoEnd = LocalDate.now(ZONE).minusDays(1).atStartOfDay(ZONE).toInstant().epochSecond - 1

                checkAll(iterations = 300, Arb.long(sixDaysAgoStart..twoDaysAgoEnd)) { epochSecond ->
                    val conv = Conversation(
                        id = "last7days-$epochSecond",
                        userId = "user-1",
                        title = "Last 7 days conv",
                        isPinned = false,
                        isDeleted = false,
                        provider = "openai",
                        createdAt = Instant.ofEpochSecond(epochSecond),
                        updatedAt = Instant.ofEpochSecond(epochSecond)
                    )
                    val grouped = group(listOf(conv))

                    grouped.last7Days shouldContain conv
                    grouped.today.shouldBeEmpty()
                    grouped.yesterday.shouldBeEmpty()
                    grouped.older.shouldBeEmpty()
                }
            }
        }

        // ── P18-7 — Older group correctness ───────────────────────────────────────
        describe("P18-7 — conversations >= 7 days ago land in 'older'") {

            it("a conversation updated exactly 7 days ago lands in 'older'") {
                val conv = Conversation(
                    id = "seven-days-ago",
                    userId = "user-1",
                    title = "Seven days ago",
                    isPinned = false,
                    isDeleted = false,
                    provider = "openai",
                    createdAt = daysAgoInstant(7),
                    updatedAt = daysAgoInstant(7)
                )
                val grouped = group(listOf(conv))

                grouped.older shouldContain conv
                grouped.today.shouldBeEmpty()
                grouped.yesterday.shouldBeEmpty()
                grouped.last7Days.shouldBeEmpty()
            }

            it("a conversation updated 10 days ago lands in 'older'") {
                val conv = Conversation(
                    id = "ten-days-ago",
                    userId = "user-1",
                    title = "Ten days ago",
                    isPinned = false,
                    isDeleted = false,
                    provider = "openai",
                    createdAt = daysAgoInstant(10),
                    updatedAt = daysAgoInstant(10)
                )
                val grouped = group(listOf(conv))

                grouped.older shouldContain conv
                grouped.today.shouldBeEmpty()
                grouped.yesterday.shouldBeEmpty()
                grouped.last7Days.shouldBeEmpty()
            }

            it("conversations >= 7 days ago (past 2 years) always land in 'older'") {
                // Range: 730 days ago through end of 7 days ago
                val twoYearsAgoStart = Instant.now().epochSecond - (730L * 86_400L)
                val sevenDaysAgoEnd = LocalDate.now(ZONE).minusDays(7).atStartOfDay(ZONE).toInstant().epochSecond - 1

                checkAll(iterations = 300, Arb.long(twoYearsAgoStart..sevenDaysAgoEnd)) { epochSecond ->
                    val conv = Conversation(
                        id = "older-$epochSecond",
                        userId = "user-1",
                        title = "Older conv",
                        isPinned = false,
                        isDeleted = false,
                        provider = "openai",
                        createdAt = Instant.ofEpochSecond(epochSecond),
                        updatedAt = Instant.ofEpochSecond(epochSecond)
                    )
                    val grouped = group(listOf(conv))

                    grouped.older shouldContain conv
                    grouped.today.shouldBeEmpty()
                    grouped.yesterday.shouldBeEmpty()
                    grouped.last7Days.shouldBeEmpty()
                }
            }
        }

        // ── Boundary edge cases ───────────────────────────────────────────────────
        describe("boundary edge cases") {

            it("empty conversation list produces all-empty GroupedConversations") {
                val grouped = group(emptyList())

                grouped.isEmpty shouldBe true
                grouped.totalCount shouldBe 0
                grouped.today.shouldBeEmpty()
                grouped.yesterday.shouldBeEmpty()
                grouped.last7Days.shouldBeEmpty()
                grouped.older.shouldBeEmpty()
            }

            it("mixed list spanning all four groups is split correctly") {
                val convToday = Conversation(
                    id = "mixed-today",
                    userId = "u",
                    title = "T",
                    isPinned = false,
                    isDeleted = false,
                    provider = "openai",
                    createdAt = daysAgoInstant(0),
                    updatedAt = daysAgoInstant(0)
                )
                val convYesterday = Conversation(
                    id = "mixed-yesterday",
                    userId = "u",
                    title = "Y",
                    isPinned = false,
                    isDeleted = false,
                    provider = "openai",
                    createdAt = daysAgoInstant(1),
                    updatedAt = daysAgoInstant(1)
                )
                val convLast7 = Conversation(
                    id = "mixed-last7",
                    userId = "u",
                    title = "L7",
                    isPinned = false,
                    isDeleted = false,
                    provider = "openai",
                    createdAt = daysAgoInstant(4),
                    updatedAt = daysAgoInstant(4)
                )
                val convOlder = Conversation(
                    id = "mixed-older",
                    userId = "u",
                    title = "O",
                    isPinned = false,
                    isDeleted = false,
                    provider = "openai",
                    createdAt = daysAgoInstant(10),
                    updatedAt = daysAgoInstant(10)
                )

                val grouped = group(listOf(convToday, convYesterday, convLast7, convOlder))

                grouped.today shouldContain convToday
                grouped.yesterday shouldContain convYesterday
                grouped.last7Days shouldContain convLast7
                grouped.older shouldContain convOlder
                grouped.totalCount shouldBe 4
            }
        }
    })
