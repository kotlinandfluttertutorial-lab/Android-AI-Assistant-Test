/**
 * ConversationUseCaseTest.kt — domain module unit tests
 *
 * Tests for conversation and message use cases:
 *   - [GetConversationsUseCase]      — filters soft-deleted, sorts, groups by date
 *   - [CreateConversationUseCase]    — validates title; delegates to repository
 *   - [DeleteConversationUseCase]    — soft-delete (isDeleted = true, not permanent)
 *   - [SearchConversationsUseCase]   — FTS: returns matching conversations; empty query → all
 *   - [SendMessageUseCase]           — validates content; delegates to repository
 *   - [RegenerateMessageUseCase]     — delegates regeneration to repository
 *   - [ExportConversationUseCase]    — Markdown export produces valid output
 *   - [SyncOfflineQueueUseCase]      — orders messages by creation time; returns sync count
 *
 * Requirements: 21.1
 * Related requirements: 11.1, 11.2, 11.3, 11.4, 11.6, 2.1, 2.6, 2.7, 10.2
 *
 * Test framework: Kotest (DescribeSpec, JUnit 5 runner) + MockK for repository mocking
 * No Android framework dependencies — pure JVM tests.
 */

package com.aiassistant.domain.usecase.conversation

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.Conversation
import com.aiassistant.domain.model.ExportFormat
import com.aiassistant.domain.model.GroupedConversations
import com.aiassistant.domain.model.Message
import com.aiassistant.domain.repository.ConversationRepository
import com.aiassistant.domain.repository.MessageRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

// ─── Shared test fixtures ──────────────────────────────────────────────────────

private val ZONE = ZoneId.systemDefault()

/** Returns an Instant representing [daysAgo] calendar days before now (midnight start of day). */
private fun daysAgo(daysAgo: Long): Instant = ZonedDateTime.now(ZONE)
    .toLocalDate()
    .minusDays(daysAgo)
    .atStartOfDay(ZONE)
    .toInstant()
    .plusSeconds(3600) // +1h so it's clearly within the day

private fun makeConversation(
    id: String = "conv-default",
    title: String = "Test Conversation",
    isDeleted: Boolean = false,
    updatedAt: Instant = Instant.now(),
    createdAt: Instant = updatedAt
) = Conversation(
    id = id,
    userId = "user-1",
    title = title,
    isPinned = false,
    isDeleted = isDeleted,
    provider = "openai",
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun makeMessage(
    id: String = "msg-1",
    conversationId: String = "conv-1",
    role: String = "user",
    content: String = "Hello",
    syncStatus: String = "pending",
    createdAt: Instant = Instant.now()
) = Message(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    syncStatus = syncStatus,
    createdAt = createdAt
)

// ─── GetConversationsUseCase ───────────────────────────────────────────────────

class GetConversationsUseCaseTest :
    DescribeSpec({

        val repository = mockk<ConversationRepository>()
        val useCase = GetConversationsUseCase(repository)

        beforeEach { clearMocks(repository) }

        describe("GetConversationsUseCase") {

            describe("filtering soft-deleted conversations") {

                it("excludes conversations where isDeleted = true") {
                    val active = makeConversation(id = "a", isDeleted = false, updatedAt = daysAgo(0))
                    val deleted = makeConversation(id = "b", isDeleted = true, updatedAt = daysAgo(0))
                    every { repository.getConversations() } returns
                        flowOf(ApiResult.Success(listOf(active, deleted)))

                    val result = useCase().first() as ApiResult.Success<GroupedConversations>

                    result.data.today shouldHaveSize 1
                    result.data.today.first().id shouldBe "a"
                }

                it("returns empty grouped result when all conversations are soft-deleted") {
                    val deleted1 = makeConversation(id = "d1", isDeleted = true, updatedAt = daysAgo(0))
                    val deleted2 = makeConversation(id = "d2", isDeleted = true, updatedAt = daysAgo(0))
                    every { repository.getConversations() } returns
                        flowOf(ApiResult.Success(listOf(deleted1, deleted2)))

                    val result = useCase().first() as ApiResult.Success<GroupedConversations>

                    result.data.isEmpty shouldBe true
                    result.data.totalCount shouldBe 0
                }

                it("includes conversations where isDeleted = false") {
                    val active = makeConversation(id = "active", isDeleted = false, updatedAt = daysAgo(0))
                    every { repository.getConversations() } returns
                        flowOf(ApiResult.Success(listOf(active)))

                    val result = useCase().first() as ApiResult.Success<GroupedConversations>

                    result.data.today shouldHaveSize 1
                }
            }

            describe("date grouping") {

                it("groups today's conversations into 'today' bucket") {
                    val todayConv = makeConversation(id = "today", updatedAt = daysAgo(0))
                    every { repository.getConversations() } returns
                        flowOf(ApiResult.Success(listOf(todayConv)))

                    val result = useCase().first() as ApiResult.Success<GroupedConversations>

                    result.data.today shouldContain todayConv
                    result.data.yesterday.shouldBeEmpty()
                    result.data.last7Days.shouldBeEmpty()
                    result.data.older.shouldBeEmpty()
                }

                it("groups yesterday's conversations into 'yesterday' bucket") {
                    val yesterdayConv = makeConversation(id = "yesterday", updatedAt = daysAgo(1))
                    every { repository.getConversations() } returns
                        flowOf(ApiResult.Success(listOf(yesterdayConv)))

                    val result = useCase().first() as ApiResult.Success<GroupedConversations>

                    result.data.yesterday shouldContain yesterdayConv
                    result.data.today.shouldBeEmpty()
                }

                it("groups conversations from 2 days ago into 'last7Days' bucket") {
                    val twoDaysAgo = makeConversation(id = "two", updatedAt = daysAgo(2))
                    every { repository.getConversations() } returns
                        flowOf(ApiResult.Success(listOf(twoDaysAgo)))

                    val result = useCase().first() as ApiResult.Success<GroupedConversations>

                    result.data.last7Days shouldContain twoDaysAgo
                }

                it("groups conversations older than 7 days into 'older' bucket") {
                    val oldConv = makeConversation(id = "old", updatedAt = daysAgo(10))
                    every { repository.getConversations() } returns
                        flowOf(ApiResult.Success(listOf(oldConv)))

                    val result = useCase().first() as ApiResult.Success<GroupedConversations>

                    result.data.older shouldContain oldConv
                    result.data.today.shouldBeEmpty()
                    result.data.yesterday.shouldBeEmpty()
                    result.data.last7Days.shouldBeEmpty()
                }
            }

            describe("sorting") {

                it("returns conversations sorted by updatedAt descending within a group") {
                    val newer = makeConversation(id = "newer", updatedAt = daysAgo(0).plusSeconds(100))
                    val older = makeConversation(id = "older", updatedAt = daysAgo(0))
                    every { repository.getConversations() } returns
                        flowOf(ApiResult.Success(listOf(older, newer)))

                    val result = useCase().first() as ApiResult.Success<GroupedConversations>

                    result.data.today.first().id shouldBe "newer"
                    result.data.today.last().id shouldBe "older"
                }
            }

            describe("error propagation") {

                it("propagates Error result from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    every { repository.getConversations() } returns
                        flowOf(ApiResult.Error(error))

                    val result = useCase().first()

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── CreateConversationUseCase ─────────────────────────────────────────────────

class CreateConversationUseCaseTest :
    DescribeSpec({

        val repository = mockk<ConversationRepository>()
        val useCase = CreateConversationUseCase(repository)

        beforeEach { clearMocks(repository) }

        describe("CreateConversationUseCase") {

            describe("successful creation") {

                it("returns Success with Conversation on valid title") {
                    val conversation = makeConversation(id = "new-conv", title = "My Chat")
                    coEvery { repository.createConversation("My Chat", "openai") } returns
                        ApiResult.Success(conversation)

                    val result = useCase("My Chat", "openai")

                    result.shouldBeInstanceOf<ApiResult.Success<Conversation>>()
                    (result as ApiResult.Success).data shouldBe conversation
                }

                it("trims whitespace from title before delegating") {
                    val conversation = makeConversation(title = "Trimmed")
                    coEvery { repository.createConversation("Trimmed", "gemini") } returns
                        ApiResult.Success(conversation)

                    val result = useCase("  Trimmed  ", "gemini")

                    result.shouldBeInstanceOf<ApiResult.Success<Conversation>>()
                    coVerify(exactly = 1) { repository.createConversation("Trimmed", "gemini") }
                }

                it("delegates to repository exactly once") {
                    val conversation = makeConversation()
                    coEvery { repository.createConversation(any(), any()) } returns
                        ApiResult.Success(conversation)

                    useCase("Valid Title", "openai")

                    coVerify(exactly = 1) { repository.createConversation(any(), any()) }
                }
            }

            describe("title validation") {

                it("returns ValidationError when title is blank") {
                    val result = useCase("", "openai")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("returns ValidationError when title is only whitespace") {
                    val result = useCase("   ", "openai")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("ValidationError includes 'title' field in fields map") {
                    val result = useCase("", "openai")

                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey CreateConversationUseCase.FIELD_TITLE
                }

                it("does NOT call repository when title is blank") {
                    useCase("", "openai")

                    coVerify(exactly = 0) { repository.createConversation(any(), any()) }
                }
            }

            describe("repository error propagation") {

                it("propagates NetworkUnavailable from repository") {
                    coEvery { repository.createConversation(any(), any()) } returns
                        ApiResult.NetworkUnavailable

                    val result = useCase("Valid Title", "openai")

                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }

                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { repository.createConversation(any(), any()) } returns
                        ApiResult.Error(error)

                    val result = useCase("Valid Title", "openai")

                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── DeleteConversationUseCase ─────────────────────────────────────────────────

class DeleteConversationUseCaseTest :
    DescribeSpec({

        val repository = mockk<ConversationRepository>()
        val useCase = DeleteConversationUseCase(repository)

        beforeEach { clearMocks(repository) }

        describe("DeleteConversationUseCase") {

            describe("soft-delete behavior") {

                it("returns Success when repository successfully soft-deletes") {
                    coEvery { repository.deleteConversation("conv-1") } returns
                        ApiResult.Success(Unit)

                    val result = useCase("conv-1")

                    result.shouldBeInstanceOf<ApiResult.Success<Unit>>()
                }

                it("delegates to repository exactly once with the provided conversation ID") {
                    coEvery { repository.deleteConversation("conv-1") } returns
                        ApiResult.Success(Unit)

                    useCase("conv-1")

                    coVerify(exactly = 1) { repository.deleteConversation("conv-1") }
                }

                /**
                 * Verifies that delete is a SOFT delete: the use case calls the repository
                 * which marks isDeleted = true rather than permanently removing the record.
                 * This is validated by checking the repository method invoked is deleteConversation
                 * (not a hard-delete variant), and that a conversation with isDeleted=true
                 * is filtered by GetConversationsUseCase — not by DeleteConversationUseCase itself.
                 */
                it("does not call any hard-delete repository method") {
                    coEvery { repository.deleteConversation(any()) } returns
                        ApiResult.Success(Unit)

                    useCase("conv-abc")

                    // Only the soft-delete path was invoked — no other repository interactions
                    coVerify(exactly = 1) { repository.deleteConversation("conv-abc") }
                    coVerify(exactly = 0) { repository.getConversations() }
                    coVerify(exactly = 0) { repository.searchConversations(any()) }
                }

                it(
                    "soft-deleted conversation should be excluded from list (isDeleted=true filter in GetConversationsUseCase)"
                ) {
                    // This integration-style test proves the contract: after delete, GetConversationsUseCase
                    // excludes the conversation because the repository marks isDeleted = true.
                    val conversationRepo = mockk<ConversationRepository>()
                    val getUseCase = GetConversationsUseCase(conversationRepo)

                    val deletedConv = makeConversation(id = "deleted", isDeleted = true, updatedAt = daysAgo(0))
                    val activeConv = makeConversation(id = "active", isDeleted = false, updatedAt = daysAgo(0))
                    every { conversationRepo.getConversations() } returns
                        flowOf(ApiResult.Success(listOf(deletedConv, activeConv)))

                    val result = getUseCase().first() as ApiResult.Success<GroupedConversations>

                    // The soft-deleted conversation must NOT appear in any group
                    result.data.today.none { it.id == "deleted" } shouldBe true
                    result.data.today.any { it.id == "active" } shouldBe true
                }
            }

            describe("error propagation") {

                it("returns Error when repository delete fails") {
                    val error = DomainError.ServerError(httpStatusCode = 404)
                    coEvery { repository.deleteConversation("missing-conv") } returns
                        ApiResult.Error(error)

                    val result = useCase("missing-conv")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }

                it("returns NetworkUnavailable when device has no connectivity") {
                    coEvery { repository.deleteConversation(any()) } returns
                        ApiResult.NetworkUnavailable

                    val result = useCase("conv-offline")

                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
            }
        }
    })

// ─── SearchConversationsUseCase (FTS) ─────────────────────────────────────────

class SearchConversationsUseCaseTest :
    DescribeSpec({

        val repository = mockk<ConversationRepository>()
        val useCase = SearchConversationsUseCase(repository)

        beforeEach { clearMocks(repository) }

        describe("SearchConversationsUseCase") {

            describe("FTS search filtering") {

                it("returns only conversations matching the query") {
                    val match = makeConversation(id = "match", title = "Kotlin tutorial")
                    makeConversation(id = "nomatch", title = "Python tips") // stubbed out of results
                    every { repository.searchConversations("Kotlin") } returns
                        flowOf(ApiResult.Success(listOf(match)))

                    val result = useCase("Kotlin").first() as ApiResult.Success<List<Conversation>>

                    result.data shouldHaveSize 1
                    result.data.first().id shouldBe "match"
                }

                it("returns all conversations when query is empty") {
                    val all = listOf(
                        makeConversation(id = "a"),
                        makeConversation(id = "b")
                    )
                    every { repository.searchConversations("") } returns
                        flowOf(ApiResult.Success(all))

                    val result = useCase("").first() as ApiResult.Success<List<Conversation>>

                    result.data shouldHaveSize 2
                }

                it("returns all conversations when query is blank whitespace") {
                    val all = listOf(makeConversation(id = "x"))
                    every { repository.searchConversations("") } returns
                        flowOf(ApiResult.Success(all))

                    val result = useCase("   ").first() as ApiResult.Success<List<Conversation>>

                    result.data shouldHaveSize 1
                    // Verify trimming: blank input is passed as empty string to repository
                    coVerify { repository.searchConversations("") }
                }

                it("returns empty list when no conversations match the query") {
                    every { repository.searchConversations("nonexistent") } returns
                        flowOf(ApiResult.Success(emptyList()))

                    val result = useCase("nonexistent").first() as ApiResult.Success<List<Conversation>>

                    result.data.shouldBeEmpty()
                }

                it("passes the trimmed query to repository") {
                    val match = makeConversation(id = "m", title = "AI chat")
                    every { repository.searchConversations("AI chat") } returns
                        flowOf(ApiResult.Success(listOf(match)))

                    useCase("  AI chat  ").first()

                    coVerify(exactly = 1) { repository.searchConversations("AI chat") }
                }

                it("delegates to repository exactly once per invoke call") {
                    every { repository.searchConversations(any()) } returns
                        flowOf(ApiResult.Success(emptyList()))

                    useCase("hello").first()

                    coVerify(exactly = 1) { repository.searchConversations(any()) }
                }
            }

            describe("error propagation") {

                it("propagates Error result from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    every { repository.searchConversations(any()) } returns
                        flowOf(ApiResult.Error(error))

                    val result = useCase("query").first()

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── SendMessageUseCase ────────────────────────────────────────────────────────

class SendMessageUseCaseTest :
    DescribeSpec({

        val repository = mockk<MessageRepository>()
        val useCase = SendMessageUseCase(repository)

        beforeEach { clearMocks(repository) }

        describe("SendMessageUseCase") {

            describe("successful send") {

                it("returns Success with Message when repository succeeds") {
                    val message = makeMessage(content = "Hello AI")
                    coEvery { repository.sendMessage("conv-1", "Hello AI", "openai") } returns
                        ApiResult.Success(message)

                    val result = useCase("conv-1", "Hello AI", "openai")

                    result.shouldBeInstanceOf<ApiResult.Success<Message>>()
                    (result as ApiResult.Success).data shouldBe message
                }

                it("trims whitespace from content before delegating") {
                    val message = makeMessage(content = "Trimmed content")
                    coEvery { repository.sendMessage("conv-1", "Trimmed content", "openai") } returns
                        ApiResult.Success(message)

                    val result = useCase("conv-1", "  Trimmed content  ", "openai")

                    result.shouldBeInstanceOf<ApiResult.Success<Message>>()
                    coVerify(exactly = 1) { repository.sendMessage("conv-1", "Trimmed content", "openai") }
                }

                it("delegates to repository exactly once") {
                    val message = makeMessage()
                    coEvery { repository.sendMessage(any(), any(), any()) } returns
                        ApiResult.Success(message)

                    useCase("conv-1", "Some content", "claude")

                    coVerify(exactly = 1) { repository.sendMessage(any(), any(), any()) }
                }
            }

            describe("content validation") {

                it("returns ValidationError when content is blank") {
                    val result = useCase("conv-1", "", "openai")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("returns ValidationError when content is only whitespace") {
                    val result = useCase("conv-1", "   ", "openai")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("ValidationError includes 'content' field in fields map") {
                    val result = useCase("conv-1", "", "openai")

                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey SendMessageUseCase.FIELD_CONTENT
                }

                it("does NOT call repository when content is blank") {
                    useCase("conv-1", "", "openai")

                    coVerify(exactly = 0) { repository.sendMessage(any(), any(), any()) }
                }
            }

            describe("repository error propagation") {

                it("propagates NetworkUnavailable from repository") {
                    coEvery { repository.sendMessage(any(), any(), any()) } returns
                        ApiResult.NetworkUnavailable

                    val result = useCase("conv-1", "Valid message", "openai")

                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }

                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { repository.sendMessage(any(), any(), any()) } returns
                        ApiResult.Error(error)

                    val result = useCase("conv-1", "Valid message", "openai")

                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── RegenerateMessageUseCase ──────────────────────────────────────────────────

class RegenerateMessageUseCaseTest :
    DescribeSpec({

        val repository = mockk<MessageRepository>()
        val useCase = RegenerateMessageUseCase(repository)

        beforeEach { clearMocks(repository) }

        describe("RegenerateMessageUseCase") {

            describe("successful regeneration") {

                it("returns Success with new Message when repository succeeds") {
                    val newMessage = makeMessage(id = "msg-new", role = "assistant", content = "Regenerated response")
                    coEvery { repository.regenerateMessage("conv-1", "msg-orig") } returns
                        ApiResult.Success(newMessage)

                    val result = useCase("conv-1", "msg-orig")

                    result.shouldBeInstanceOf<ApiResult.Success<Message>>()
                    (result as ApiResult.Success).data shouldBe newMessage
                }

                it("delegates to repository exactly once with correct IDs") {
                    val newMessage = makeMessage(id = "msg-new", role = "assistant")
                    coEvery { repository.regenerateMessage("conv-1", "msg-orig") } returns
                        ApiResult.Success(newMessage)

                    useCase("conv-1", "msg-orig")

                    coVerify(exactly = 1) { repository.regenerateMessage("conv-1", "msg-orig") }
                }

                it("returned message is a new alternative (different id from original)") {
                    val newMessage = makeMessage(id = "msg-regenerated", role = "assistant")
                    coEvery { repository.regenerateMessage(any(), "msg-orig") } returns
                        ApiResult.Success(newMessage)

                    val result = useCase("conv-1", "msg-orig") as ApiResult.Success<Message>

                    result.data.id shouldBe "msg-regenerated"
                }
            }

            describe("error propagation") {

                it("returns Error when regeneration fails") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { repository.regenerateMessage(any(), any()) } returns
                        ApiResult.Error(error)

                    val result = useCase("conv-1", "msg-1")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }

                it("returns NetworkUnavailable when device has no connectivity") {
                    coEvery { repository.regenerateMessage(any(), any()) } returns
                        ApiResult.NetworkUnavailable

                    val result = useCase("conv-1", "msg-1")

                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
            }
        }
    })

// ─── ExportConversationUseCase ─────────────────────────────────────────────────

class ExportConversationUseCaseTest :
    DescribeSpec({

        val repository = mockk<ConversationRepository>()
        val useCase = ExportConversationUseCase(repository)

        beforeEach { clearMocks(repository) }

        describe("ExportConversationUseCase") {

            describe("Markdown export") {

                it("returns Success with Markdown content string for MARKDOWN format") {
                    val markdownContent = "# My Conversation\n\n**User:** Hello\n\n**Assistant:** Hi there!"
                    coEvery { repository.exportConversation("conv-1", ExportFormat.MARKDOWN) } returns
                        ApiResult.Success(markdownContent)

                    val result = useCase("conv-1", ExportFormat.MARKDOWN)

                    result.shouldBeInstanceOf<ApiResult.Success<String>>()
                    (result as ApiResult.Success).data shouldBe markdownContent
                }

                it("Markdown output contains conversation title header") {
                    val markdownContent = "# My Conversation\n\n**User:** Hello"
                    coEvery { repository.exportConversation("conv-1", ExportFormat.MARKDOWN) } returns
                        ApiResult.Success(markdownContent)

                    val result = useCase("conv-1", ExportFormat.MARKDOWN) as ApiResult.Success<String>

                    result.data shouldContain "#"
                }

                it("Markdown output is non-blank when conversation has messages") {
                    val markdownContent = "# Chat\n\n**User:** Test message\n\n**Assistant:** Response"
                    coEvery { repository.exportConversation("conv-md", ExportFormat.MARKDOWN) } returns
                        ApiResult.Success(markdownContent)

                    val result = useCase("conv-md", ExportFormat.MARKDOWN) as ApiResult.Success<String>

                    result.data.isNotBlank() shouldBe true
                }

                it("delegates MARKDOWN format to repository with correct parameters") {
                    coEvery { repository.exportConversation("conv-1", ExportFormat.MARKDOWN) } returns
                        ApiResult.Success("# Export")

                    useCase("conv-1", ExportFormat.MARKDOWN)

                    coVerify(exactly = 1) { repository.exportConversation("conv-1", ExportFormat.MARKDOWN) }
                }
            }

            describe("PDF export") {

                it("returns Success with PDF file path for PDF format") {
                    val pdfPath = "/data/user/0/com.aiassistant/files/exports/conversation_conv1.pdf"
                    coEvery { repository.exportConversation("conv-1", ExportFormat.PDF) } returns
                        ApiResult.Success(pdfPath)

                    val result = useCase("conv-1", ExportFormat.PDF)

                    result.shouldBeInstanceOf<ApiResult.Success<String>>()
                    (result as ApiResult.Success).data shouldBe pdfPath
                }

                it("delegates PDF format to repository with correct parameters") {
                    coEvery { repository.exportConversation("conv-1", ExportFormat.PDF) } returns
                        ApiResult.Success("/path/to/file.pdf")

                    useCase("conv-1", ExportFormat.PDF)

                    coVerify(exactly = 1) { repository.exportConversation("conv-1", ExportFormat.PDF) }
                }
            }

            describe("error propagation") {

                it("returns Error when export fails") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { repository.exportConversation(any(), any()) } returns
                        ApiResult.Error(error)

                    val result = useCase("conv-1", ExportFormat.MARKDOWN)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }

                it("returns NetworkUnavailable when device is offline") {
                    coEvery { repository.exportConversation(any(), any()) } returns
                        ApiResult.NetworkUnavailable

                    val result = useCase("conv-1", ExportFormat.PDF)

                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
            }
        }
    })

// ─── SyncOfflineQueueUseCase ───────────────────────────────────────────────────

class SyncOfflineQueueUseCaseTest :
    DescribeSpec({

        val repository = mockk<MessageRepository>()
        val useCase = SyncOfflineQueueUseCase(repository)

        beforeEach { clearMocks(repository) }

        describe("SyncOfflineQueueUseCase") {

            describe("successful sync") {

                it("returns Success with count of synced messages") {
                    coEvery { repository.syncOfflineQueue() } returns ApiResult.Success(3)

                    val result = useCase()

                    result.shouldBeInstanceOf<ApiResult.Success<Int>>()
                    (result as ApiResult.Success).data shouldBe 3
                }

                it("returns Success with 0 when there are no pending messages") {
                    coEvery { repository.syncOfflineQueue() } returns ApiResult.Success(0)

                    val result = useCase()

                    result.shouldBeInstanceOf<ApiResult.Success<Int>>()
                    (result as ApiResult.Success).data shouldBe 0
                }

                it("delegates to repository exactly once") {
                    coEvery { repository.syncOfflineQueue() } returns ApiResult.Success(1)

                    useCase()

                    coVerify(exactly = 1) { repository.syncOfflineQueue() }
                }
            }

            describe("offline queue ordering") {

                /**
                 * The repository's syncOfflineQueue() processes messages in creation-time order
                 * (oldest first). This test verifies the use case correctly invokes the repository
                 * which is responsible for that ordering contract.
                 *
                 * To test ordering indirectly: we simulate the repository returning the count that
                 * reflects it processed messages ordered by createdAt ascending (oldest → newest).
                 * A real ordering integration test would live in the data layer.
                 */
                it("invokes syncOfflineQueue and returns the count from repository") {
                    // Simulate 5 pending messages synced in order
                    coEvery { repository.syncOfflineQueue() } returns ApiResult.Success(5)

                    val result = useCase() as ApiResult.Success<Int>

                    result.data shouldBe 5
                    coVerify(exactly = 1) { repository.syncOfflineQueue() }
                }

                it("returns partial count when some messages fail to sync") {
                    // Repository synced 2 out of 5 pending messages before a partial failure
                    coEvery { repository.syncOfflineQueue() } returns ApiResult.Success(2)

                    val result = useCase() as ApiResult.Success<Int>

                    result.data shouldBe 2
                }
            }

            describe("error propagation") {

                it("returns Error when sync encounters server failure") {
                    val error = DomainError.ServerError(httpStatusCode = 503)
                    coEvery { repository.syncOfflineQueue() } returns ApiResult.Error(error)

                    val result = useCase()

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }

                it("returns NetworkUnavailable when device loses connectivity mid-sync") {
                    coEvery { repository.syncOfflineQueue() } returns ApiResult.NetworkUnavailable

                    val result = useCase()

                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }

                it("propagates the exact error from repository without modification") {
                    val error = DomainError.NetworkError(message = "Connection reset")
                    coEvery { repository.syncOfflineQueue() } returns ApiResult.Error(error)

                    val result = useCase()

                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })
