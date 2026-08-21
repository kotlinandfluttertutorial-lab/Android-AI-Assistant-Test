/**
 * ContextSuggestionRepositoryImplTest.kt — data module
 *
 * Purpose: Unit tests for [ContextSuggestionRepositoryImpl], which is a stub
 *          with a withTimeoutOrNull(3000L) wrapper. Tests verify:
 *   - NoteContext returns 3 non-empty suggestions
 *   - CalendarEventContext returns 3 non-empty suggestions
 *   - ConversationContext with lastMessageAgeMillis >= 24h returns 1 suggestion
 *   - ConversationContext with lastMessageAgeMillis < 24h returns empty list
 *   - All results are ApiResult.Success
 *
 * Architecture: data module — pure JVM unit tests.
 *
 * Test toolchain:
 * - Kotest DescribeSpec  — test structure
 * - kotlinx.coroutines.test — runTest
 *
 * Requirements covered: 33.1, 33.2, 33.3, 33.6
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.data.remote.suggestion.SuggestionRemoteDataSource
import com.aiassistant.domain.model.ContextSuggestion
import com.aiassistant.domain.model.ScreenContext
import com.aiassistant.domain.model.SuggestionType
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

class ContextSuggestionRepositoryImplTest :
    DescribeSpec({

        val mockRemoteDataSource = mockk<SuggestionRemoteDataSource>()
        val mockConnectivityObserver = mockk<ConnectivityObserver>()
        val repository = ContextSuggestionRepositoryImpl(mockRemoteDataSource, mockConnectivityObserver)

        beforeEach {
            coEvery { mockConnectivityObserver.isConnected() } returns true
            coEvery { mockRemoteDataSource.getSuggestions(any()) } returns ApiResult.Success(
                listOf(
                    ContextSuggestion("Summarize", SuggestionType.SUMMARIZE, "Summarize this note"),
                    ContextSuggestion("Add action items", SuggestionType.ADD_ACTION_ITEMS, "Extract action items"),
                    ContextSuggestion("Expand", SuggestionType.EXPAND, "Expand on this content")
                )
            )
        }

        // ── 24-hour constant ──────────────────────────────────────────────────────
        val twentyFourHoursMs = 24L * 60L * 60L * 1_000L

        describe("getSuggestions() with NoteContext") {

            it("returns ApiResult.Success") {
                runTest {
                    val context = ScreenContext.NoteContext(
                        noteContent = "Meeting notes from standup",
                        screenInstanceId = "note-1"
                    )

                    val result = repository.getSuggestions(context)

                    result.shouldBeInstanceOf<ApiResult.Success<List<ContextSuggestion>>>()
                }
            }

            it("returns 3 suggestions for note context") {
                runTest {
                    val context = ScreenContext.NoteContext(
                        noteContent = "Todo: buy groceries, call dentist, finish report",
                        screenInstanceId = "note-2"
                    )

                    val result = repository.getSuggestions(context)
                    val suggestions = (result as ApiResult.Success).data

                    suggestions shouldHaveSize 3
                }
            }

            it("all suggestions have non-empty displayText") {
                runTest {
                    val context = ScreenContext.NoteContext(
                        noteContent = "Some note content",
                        screenInstanceId = "note-3"
                    )

                    val result = repository.getSuggestions(context)
                    val suggestions = (result as ApiResult.Success).data

                    suggestions.forEach { suggestion ->
                        suggestion.displayText.shouldNotBeEmpty()
                    }
                }
            }

            it("note suggestions include SUMMARIZE, ADD_ACTION_ITEMS, EXPAND types") {
                runTest {
                    val context = ScreenContext.NoteContext(
                        noteContent = "Project planning notes",
                        screenInstanceId = "note-4"
                    )

                    val result = repository.getSuggestions(context)
                    val types = (result as ApiResult.Success).data.map { it.type }.toSet()

                    types shouldBe setOf(
                        SuggestionType.SUMMARIZE,
                        SuggestionType.ADD_ACTION_ITEMS,
                        SuggestionType.EXPAND
                    )
                }
            }
        }

        describe("getSuggestions() with CalendarEventContext") {

            it("returns ApiResult.Success") {
                runTest {
                    val context = ScreenContext.CalendarEventContext(
                        eventId = "event-1",
                        eventTitle = "Q4 Planning",
                        eventDescription = "Annual planning session",
                        attendeeNames = listOf("Alice", "Bob"),
                        screenInstanceId = "event-1"
                    )

                    val result = repository.getSuggestions(context)

                    result.shouldBeInstanceOf<ApiResult.Success<List<ContextSuggestion>>>()
                }
            }

            it("returns 3 suggestions for calendar event context") {
                runTest {
                    val context = ScreenContext.CalendarEventContext(
                        eventId = "event-2",
                        eventTitle = "Team Sync",
                        eventDescription = null,
                        attendeeNames = listOf("Charlie", "Dave", "Eve"),
                        screenInstanceId = "event-2"
                    )

                    val result = repository.getSuggestions(context)
                    val suggestions = (result as ApiResult.Success).data

                    suggestions shouldHaveSize 3
                }
            }

            it("calendar event suggestions include DRAFT_AGENDA, PREP_QUESTIONS, LOOKUP_ATTENDEES types") {
                runTest {
                    val context = ScreenContext.CalendarEventContext(
                        eventId = "event-3",
                        eventTitle = "Board Meeting",
                        eventDescription = "Quarterly review",
                        attendeeNames = listOf("CEO", "CFO"),
                        screenInstanceId = "event-3"
                    )

                    val result = repository.getSuggestions(context)
                    val types = (result as ApiResult.Success).data.map { it.type }.toSet()

                    types shouldBe setOf(
                        SuggestionType.DRAFT_AGENDA,
                        SuggestionType.PREP_QUESTIONS,
                        SuggestionType.LOOKUP_ATTENDEES
                    )
                }
            }
        }

        describe("getSuggestions() with ConversationContext") {

            it("returns 1 suggestion when message age is exactly 24 hours (Requirement 33.3)") {
                runTest {
                    val context = ScreenContext.ConversationContext(
                        lastMessageContent = "Let's pick this up later",
                        lastMessageAgeMillis = twentyFourHoursMs,
                        screenInstanceId = "conv-1"
                    )

                    val result = repository.getSuggestions(context)
                    val suggestions = (result as ApiResult.Success).data

                    suggestions shouldHaveSize 1
                    suggestions[0].type shouldBe SuggestionType.CONTINUE_CONVERSATION
                }
            }

            it("returns 1 suggestion when message age is more than 24 hours") {
                runTest {
                    val context = ScreenContext.ConversationContext(
                        lastMessageContent = "Old message content",
                        lastMessageAgeMillis = twentyFourHoursMs + 3_600_000L, // 25 hours
                        screenInstanceId = "conv-2"
                    )

                    val result = repository.getSuggestions(context)
                    val suggestions = (result as ApiResult.Success).data

                    suggestions shouldHaveSize 1
                }
            }

            it("returns empty list when message age is less than 24 hours (Requirement 33.3)") {
                runTest {
                    val context = ScreenContext.ConversationContext(
                        lastMessageContent = "Recent message",
                        lastMessageAgeMillis = twentyFourHoursMs - 1L, // just under 24h
                        screenInstanceId = "conv-3"
                    )

                    val result = repository.getSuggestions(context)
                    val suggestions = (result as ApiResult.Success).data

                    suggestions.shouldBeEmpty()
                }
            }

            it("returns empty list when message age is zero (fresh conversation)") {
                runTest {
                    val context = ScreenContext.ConversationContext(
                        lastMessageContent = "Just sent",
                        lastMessageAgeMillis = 0L,
                        screenInstanceId = "conv-4"
                    )

                    val result = repository.getSuggestions(context)
                    val suggestions = (result as ApiResult.Success).data

                    suggestions.shouldBeEmpty()
                }
            }

            it("returns ApiResult.Success for both old and recent conversations") {
                runTest {
                    val oldContext = ScreenContext.ConversationContext(
                        lastMessageContent = "Old",
                        lastMessageAgeMillis = twentyFourHoursMs * 2,
                        screenInstanceId = "conv-old"
                    )
                    val newContext = ScreenContext.ConversationContext(
                        lastMessageContent = "New",
                        lastMessageAgeMillis = 60_000L,
                        screenInstanceId = "conv-new"
                    )

                    repository.getSuggestions(oldContext).shouldBeInstanceOf<ApiResult.Success<*>>()
                    repository.getSuggestions(newContext).shouldBeInstanceOf<ApiResult.Success<*>>()
                }
            }
        }
    })
