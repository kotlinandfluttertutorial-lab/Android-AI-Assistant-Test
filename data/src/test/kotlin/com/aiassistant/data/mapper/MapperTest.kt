/**
 * MapperTest.kt — data module
 *
 * Purpose: Unit tests for all data-module mapper extension functions.
 *          Covers ConversationMapper, MessageMapper, DocumentMapper, NoteMapper, MemoryMapper,
 *          ProductivityMapper, and SemanticSearchMapper.
 *
 * Architecture: data module — unit tests (pure JVM, no Android framework).
 *               Mapper functions are pure; no mocking required.
 *
 * Test toolchain:
 * - Kotest DescribeSpec — test structure and assertions
 *
 * Requirements covered: 10.1, 10.3, 13.1, 13.4, 13.5, 4.1, 4.10, 7.3, 36.1, 36.3
 */
package com.aiassistant.data.mapper

import com.aiassistant.core.database.entity.CalendarEventEntity
import com.aiassistant.core.database.entity.ConversationEntity
import com.aiassistant.core.database.entity.DocumentEntity
import com.aiassistant.core.database.entity.HabitDefinitionEntity
import com.aiassistant.core.database.entity.HabitEntryEntity
import com.aiassistant.core.database.entity.MessageEntity
import com.aiassistant.core.database.entity.NoteEntity
import com.aiassistant.core.database.entity.ReminderEntity
import com.aiassistant.core.database.entity.TodoItemEntity
import com.aiassistant.data.remote.document.DocumentDto
import com.aiassistant.data.remote.memory.MemoryDto
import com.aiassistant.data.remote.message.MessageDto
import com.aiassistant.data.remote.note.NoteDto
import com.aiassistant.data.remote.productivity.CalendarEventDto
import com.aiassistant.data.remote.productivity.HabitDefinitionDto
import com.aiassistant.data.remote.productivity.HabitEntryDto
import com.aiassistant.data.remote.productivity.ReminderDto
import com.aiassistant.data.remote.productivity.TodoItemDto
import com.aiassistant.data.remote.search.SemanticSearchResultDto
import com.aiassistant.domain.model.CalendarEvent
import com.aiassistant.domain.model.CalendarEventSource
import com.aiassistant.domain.model.Conversation
import com.aiassistant.domain.model.Document
import com.aiassistant.domain.model.HabitDefinition
import com.aiassistant.domain.model.HabitEntry
import com.aiassistant.domain.model.HabitRecurrence
import com.aiassistant.domain.model.IngestionStatus
import com.aiassistant.domain.model.MemoryType
import com.aiassistant.domain.model.Message
import com.aiassistant.domain.model.Note
import com.aiassistant.domain.model.Priority
import com.aiassistant.domain.model.Reminder
import com.aiassistant.domain.model.SemanticSearchResult
import com.aiassistant.domain.model.SyncStatus
import com.aiassistant.domain.model.TodoItem
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class MapperTest :
    DescribeSpec({

        // ─── ConversationMapper ───────────────────────────────────────────────────

        describe("ConversationMapper") {

            describe("ConversationEntity.toDomain()") {
                it("maps all fields correctly") {
                    val entity = ConversationEntity(
                        id = "conv-1",
                        userId = "user-1",
                        title = "Test Conversation",
                        isPinned = true,
                        isDeleted = false,
                        provider = "openai",
                        createdAt = 1_000_000L,
                        updatedAt = 2_000_000L
                    )

                    val domain = entity.toDomain()

                    domain.id shouldBe "conv-1"
                    domain.userId shouldBe "user-1"
                    domain.title shouldBe "Test Conversation"
                    domain.isPinned shouldBe true
                    domain.isDeleted shouldBe false
                    domain.provider shouldBe "openai"
                    domain.createdAt shouldBe Instant.ofEpochMilli(1_000_000L)
                    domain.updatedAt shouldBe Instant.ofEpochMilli(2_000_000L)
                }

                it("preserves isDeleted=true (soft-delete round-trip)") {
                    val entity = ConversationEntity(
                        id = "conv-deleted",
                        userId = "user-1",
                        title = "Deleted",
                        isPinned = false,
                        isDeleted = true,
                        provider = "gemini",
                        createdAt = 100L,
                        updatedAt = 200L
                    )
                    entity.toDomain().isDeleted shouldBe true
                }
            }

            describe("Conversation.toEntity()") {
                it("maps all fields correctly and converts Instant back to epoch ms") {
                    val conversation = Conversation(
                        id = "conv-2",
                        userId = "user-2",
                        title = "Round-trip",
                        isPinned = false,
                        isDeleted = false,
                        provider = "claude",
                        createdAt = Instant.ofEpochMilli(3_000_000L),
                        updatedAt = Instant.ofEpochMilli(4_000_000L)
                    )

                    val entity = conversation.toEntity()

                    entity.id shouldBe "conv-2"
                    entity.userId shouldBe "user-2"
                    entity.title shouldBe "Round-trip"
                    entity.isPinned shouldBe false
                    entity.isDeleted shouldBe false
                    entity.provider shouldBe "claude"
                    entity.createdAt shouldBe 3_000_000L
                    entity.updatedAt shouldBe 4_000_000L
                }
            }

            describe("round-trip: entity -> domain -> entity") {
                it("produces equivalent entity after round-trip") {
                    val original = ConversationEntity(
                        id = "rt-1",
                        userId = "rt-user",
                        title = "Round-trip test",
                        isPinned = true,
                        isDeleted = false,
                        provider = "ollama",
                        createdAt = 5_000_000L,
                        updatedAt = 6_000_000L
                    )

                    val roundTripped = original.toDomain().toEntity()

                    roundTripped.id shouldBe original.id
                    roundTripped.userId shouldBe original.userId
                    roundTripped.title shouldBe original.title
                    roundTripped.isPinned shouldBe original.isPinned
                    roundTripped.isDeleted shouldBe original.isDeleted
                    roundTripped.provider shouldBe original.provider
                    roundTripped.createdAt shouldBe original.createdAt
                    roundTripped.updatedAt shouldBe original.updatedAt
                }
            }
        }

        // ─── MessageMapper ────────────────────────────────────────────────────────

        describe("MessageMapper") {

            describe("MessageEntity.toDomain()") {
                it("maps all fields correctly") {
                    val entity = MessageEntity(
                        id = "msg-1",
                        conversationId = "conv-1",
                        role = "user",
                        content = "Hello AI",
                        inputTokens = 10,
                        outputTokens = 0,
                        provider = "openai",
                        syncStatus = "pending",
                        createdAt = 1_000_000L
                    )

                    val domain = entity.toDomain()

                    domain.id shouldBe "msg-1"
                    domain.conversationId shouldBe "conv-1"
                    domain.role shouldBe "user"
                    domain.content shouldBe "Hello AI"
                    domain.inputTokens shouldBe 10
                    domain.outputTokens shouldBe 0
                    domain.provider shouldBe "openai"
                    domain.syncStatus shouldBe "pending"
                    domain.createdAt shouldBe Instant.ofEpochMilli(1_000_000L)
                }
            }

            describe("Message.toEntity()") {
                it("uses provided syncStatus override") {
                    val message = Message(
                        id = "msg-2",
                        conversationId = "conv-2",
                        role = "assistant",
                        content = "AI response",
                        inputTokens = 20,
                        outputTokens = 50,
                        provider = "gemini",
                        syncStatus = "pending",
                        createdAt = Instant.ofEpochMilli(2_000_000L)
                    )

                    val entity = message.toEntity(syncStatus = "synced")

                    entity.syncStatus shouldBe "synced"
                    entity.id shouldBe "msg-2"
                    entity.content shouldBe "AI response"
                    entity.inputTokens shouldBe 20
                    entity.outputTokens shouldBe 50
                    entity.createdAt shouldBe 2_000_000L
                }

                it("defaults to message's own syncStatus when not overridden") {
                    val message = Message(
                        id = "msg-3",
                        conversationId = "conv-3",
                        role = "user",
                        content = "text",
                        inputTokens = 5,
                        outputTokens = 0,
                        provider = "claude",
                        syncStatus = "failed",
                        createdAt = Instant.ofEpochMilli(3_000_000L)
                    )

                    val entity = message.toEntity()

                    entity.syncStatus shouldBe "failed"
                }
            }

            describe("MessageDto.toEntity()") {
                it("always sets syncStatus=synced (server-authoritative)") {
                    val dto = MessageDto(
                        id = "msg-dto-1",
                        conversationId = "conv-1",
                        role = "assistant",
                        content = "Server content",
                        inputTokens = 30,
                        outputTokens = 80,
                        provider = "openai",
                        createdAt = 4_000_000L
                    )

                    val entity = dto.toEntity(conversationId = "conv-1")

                    entity.syncStatus shouldBe "synced"
                    entity.content shouldBe "Server content"
                    entity.inputTokens shouldBe 30
                    entity.outputTokens shouldBe 80
                }

                it("uses explicit conversationId when dto conversationId is empty") {
                    val dto = MessageDto(
                        id = "msg-dto-2",
                        conversationId = "",
                        role = "assistant",
                        content = "text",
                        inputTokens = 0,
                        outputTokens = 5,
                        provider = "openai",
                        createdAt = 5_000_000L
                    )

                    val entity = dto.toEntity(conversationId = "explicit-conv")

                    entity.conversationId shouldBe "explicit-conv"
                }
            }

            describe("MessageDto.toDomain()") {
                it("maps to domain with syncStatus=synced") {
                    val dto = MessageDto(
                        id = "msg-dto-3",
                        conversationId = "conv-dto",
                        role = "assistant",
                        content = "Domain content",
                        inputTokens = 15,
                        outputTokens = 40,
                        provider = "claude",
                        createdAt = 6_000_000L
                    )

                    val domain = dto.toDomain()

                    domain.syncStatus shouldBe "synced"
                    domain.id shouldBe "msg-dto-3"
                    domain.content shouldBe "Domain content"
                    domain.createdAt shouldBe Instant.ofEpochMilli(6_000_000L)
                }
            }
        }

        // ─── DocumentMapper ───────────────────────────────────────────────────────

        describe("DocumentMapper") {

            describe("DocumentEntity.toDomain()") {
                it("maps all fields and converts ingestionStatus string to enum") {
                    val entity = DocumentEntity(
                        id = "doc-1",
                        userId = "user-1",
                        fileName = "report.pdf",
                        mimeType = "application/pdf",
                        sizeBytes = 1_048_576L,
                        ingestionStatus = "ready",
                        jobId = "job-abc",
                        pageCount = 10,
                        createdAt = 1_000_000L
                    )

                    val domain = entity.toDomain()

                    domain.id shouldBe "doc-1"
                    domain.fileName shouldBe "report.pdf"
                    domain.mimeType shouldBe "application/pdf"
                    domain.sizeBytes shouldBe 1_048_576L
                    domain.ingestionStatus shouldBe IngestionStatus.READY
                    domain.jobId shouldBe "job-abc"
                    domain.pageCount shouldBe 10
                    domain.createdAt shouldBe 1_000_000L
                }

                it("maps pending ingestion status correctly") {
                    val entity = DocumentEntity(
                        id = "doc-pending",
                        userId = "user-1",
                        fileName = "file.txt",
                        mimeType = "text/plain",
                        sizeBytes = 100L,
                        ingestionStatus = "pending",
                        jobId = null,
                        pageCount = null,
                        createdAt = 200L
                    )
                    entity.toDomain().ingestionStatus shouldBe IngestionStatus.PENDING
                }
            }

            describe("Document.toEntity()") {
                it("converts IngestionStatus enum back to string") {
                    val document = Document(
                        id = "doc-2",
                        userId = "user-2",
                        fileName = "notes.docx",
                        mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        sizeBytes = 512L,
                        ingestionStatus = IngestionStatus.PROCESSING,
                        jobId = "job-xyz",
                        pageCount = null,
                        createdAt = 2_000_000L
                    )

                    val entity = document.toEntity()

                    entity.ingestionStatus shouldBe "processing"
                    entity.id shouldBe "doc-2"
                    entity.fileName shouldBe "notes.docx"
                    entity.jobId shouldBe "job-xyz"
                }
            }

            describe("DocumentDto.toEntity()") {
                it("maps all DTO fields to entity") {
                    val dto = DocumentDto(
                        id = "doc-dto-1",
                        fileName = "analysis.md",
                        mimeType = "text/markdown",
                        sizeBytes = 2_048L,
                        ingestionStatus = "failed",
                        pageCount = 0,
                        createdAt = "2026-07-30T04:11:29Z"
                    )

                    val entity = dto.toEntity("user-dto")

                    entity.id shouldBe "doc-dto-1"
                    entity.ingestionStatus shouldBe "failed"
                    entity.userId shouldBe "user-dto"
                    entity.pageCount shouldBe 0
                }
            }

            describe("DocumentDto.toDomain()") {
                it("maps ingestionStatus string to IngestionStatus enum") {
                    val dto = DocumentDto(
                        id = "doc-dto-2",
                        fileName = "data.csv",
                        mimeType = "text/csv",
                        sizeBytes = 4_096L,
                        ingestionStatus = "ready",
                        pageCount = 5,
                        createdAt = "2026-07-30T04:11:29Z"
                    )

                    val domain = dto.toEntity("user-dto").toDomain()

                    domain.ingestionStatus shouldBe IngestionStatus.READY
                    domain.id shouldBe "doc-dto-2"
                    domain.fileName shouldBe "data.csv"
                }
            }
        }

        // ─── NoteMapper ───────────────────────────────────────────────────────────

        describe("NoteMapper") {

            describe("NoteEntity.toDomain()") {
                it("deserializes JSON tags string to List<String>") {
                    val entity = NoteEntity(
                        id = "note-1",
                        userId = "user-1",
                        title = "My Note",
                        content = "Content here",
                        tags = """["kotlin","android"]""",
                        syncStatus = "synced",
                        createdAt = 1_000_000L,
                        updatedAt = 2_000_000L
                    )

                    val domain = entity.toDomain()

                    domain.id shouldBe "note-1"
                    domain.title shouldBe "My Note"
                    domain.tags shouldBe listOf("kotlin", "android")
                    domain.syncStatus shouldBe SyncStatus.SYNCED
                }

                it("returns empty list for invalid JSON tags") {
                    val entity = NoteEntity(
                        id = "note-2",
                        userId = "user-1",
                        title = "Bad Tags",
                        content = "text",
                        tags = "not-valid-json",
                        syncStatus = "pending",
                        createdAt = 100L,
                        updatedAt = 200L
                    )

                    entity.toDomain().tags shouldBe emptyList()
                }

                it("returns empty list for empty JSON array") {
                    val entity = NoteEntity(
                        id = "note-3",
                        userId = "user-1",
                        title = "No Tags",
                        content = "text",
                        tags = "[]",
                        syncStatus = "synced",
                        createdAt = 300L,
                        updatedAt = 400L
                    )

                    entity.toDomain().tags shouldBe emptyList()
                }
            }

            describe("Note.toEntity()") {
                it("serializes List<String> tags to JSON string") {
                    val note = Note(
                        id = "note-4",
                        userId = "user-2",
                        title = "Tagged Note",
                        content = "body",
                        tags = listOf("work", "important"),
                        syncStatus = SyncStatus.PENDING,
                        createdAt = 5_000_000L,
                        updatedAt = 6_000_000L
                    )

                    val entity = note.toEntity()

                    entity.id shouldBe "note-4"
                    entity.syncStatus shouldBe "pending"
                    // Tags should be a valid JSON array
                    entity.tags shouldBe """["work","important"]"""
                }
            }

            describe("NoteDto.toDomain()") {
                it("maps all fields correctly") {
                    val dto = NoteDto(
                        id = "note-dto-1",
                        userId = "user-dto",
                        title = "DTO Note",
                        content = "DTO content",
                        tags = listOf("dto", "test"),
                        syncStatus = "synced",
                        createdAt = 7_000_000L,
                        updatedAt = 8_000_000L
                    )

                    val domain = dto.toDomain()

                    domain.id shouldBe "note-dto-1"
                    domain.title shouldBe "DTO Note"
                    domain.tags shouldBe listOf("dto", "test")
                    domain.syncStatus shouldBe SyncStatus.SYNCED
                }
            }

            describe("NoteDto.toEntity()") {
                it("serializes tags from DTO to JSON string") {
                    val dto = NoteDto(
                        id = "note-dto-2",
                        userId = "user-dto",
                        title = "DTO Entity Note",
                        content = "entity body",
                        tags = listOf("entity"),
                        syncStatus = "pending",
                        createdAt = 9_000_000L,
                        updatedAt = 10_000_000L
                    )

                    val entity = dto.toEntity()

                    entity.id shouldBe "note-dto-2"
                    entity.syncStatus shouldBe "pending"
                    entity.tags shouldBe """["entity"]"""
                }
            }

            describe("encodeTags / decodeTags") {
                it("round-trips a non-empty tag list") {
                    val tags = listOf("alpha", "beta", "gamma")
                    decodeTags(encodeTags(tags)) shouldBe tags
                }

                it("round-trips an empty tag list") {
                    decodeTags(encodeTags(emptyList())) shouldBe emptyList()
                }
            }
        }

        // ─── MemoryMapper ─────────────────────────────────────────────────────────

        describe("MemoryMapper") {

            describe("MemoryDto.toDomain()") {
                it("maps all fields and converts memoryType string to enum") {
                    val dto = MemoryDto(
                        id = "mem-1",
                        userId = "user-1",
                        content = "User prefers dark mode",
                        memoryType = "preference",
                        createdAt = 1_000_000L
                    )

                    val domain = dto.toDomain()

                    domain.id shouldBe "mem-1"
                    domain.userId shouldBe "user-1"
                    domain.content shouldBe "User prefers dark mode"
                    domain.memoryType shouldBe MemoryType.PREFERENCE
                    domain.createdAt shouldBe 1_000_000L
                }

                it("maps fact memory type") {
                    val dto = MemoryDto(
                        id = "mem-2",
                        userId = "user-2",
                        content = "User is a Kotlin developer",
                        memoryType = "fact",
                        createdAt = 2_000_000L
                    )

                    dto.toDomain().memoryType shouldBe MemoryType.FACT
                }

                it("maps style memory type") {
                    val dto = MemoryDto(
                        id = "mem-3",
                        userId = "user-3",
                        content = "User writes concise prose",
                        memoryType = "style",
                        createdAt = 3_000_000L
                    )

                    dto.toDomain().memoryType shouldBe MemoryType.STYLE
                }
            }
        }

        // ─── ProductivityMapper ───────────────────────────────────────────────────

        describe("ProductivityMapper") {

            // ── TodoItem ──────────────────────────────────────────────────────────

            describe("TodoItemEntity.toDomain()") {
                it("maps all fields and decodes JSON tags") {
                    val entity = TodoItemEntity(
                        id = "todo-1", userId = "user-1", title = "Buy milk",
                        description = "At the store", isCompleted = false,
                        dueDate = 9_000_000L, priority = "high",
                        tags = """["grocery","errand"]""",
                        syncStatus = "synced", createdAt = 1_000_000L, updatedAt = 2_000_000L
                    )

                    val domain = entity.toDomain()

                    domain.id shouldBe "todo-1"
                    domain.title shouldBe "Buy milk"
                    domain.priority shouldBe Priority.HIGH
                    domain.tags shouldBe listOf("grocery", "errand")
                    domain.syncStatus shouldBe SyncStatus.SYNCED
                    domain.dueDate shouldBe 9_000_000L
                }

                it("falls back to MEDIUM priority for unknown value") {
                    val entity = TodoItemEntity(
                        id = "todo-x", userId = "u", title = "t", description = "",
                        isCompleted = false, dueDate = null, priority = "critical",
                        tags = "[]", syncStatus = "pending", createdAt = 1L, updatedAt = 2L
                    )
                    entity.toDomain().priority shouldBe Priority.MEDIUM
                }

                it("maps LOW priority correctly") {
                    val entity = TodoItemEntity(
                        id = "todo-low", userId = "u", title = "t", description = "",
                        isCompleted = false, dueDate = null, priority = "low",
                        tags = "[]", syncStatus = "synced", createdAt = 1L, updatedAt = 2L
                    )
                    entity.toDomain().priority shouldBe Priority.LOW
                }
            }

            describe("TodoItem.toEntity()") {
                it("encodes tags to JSON and preserves all fields") {
                    val domain = TodoItem(
                        id = "todo-2", userId = "user-2", title = "Read book",
                        description = "finish chapter 3", isCompleted = true,
                        dueDate = null, priority = Priority.LOW,
                        tags = listOf("personal"), syncStatus = SyncStatus.PENDING,
                        createdAt = 3_000_000L, updatedAt = 4_000_000L
                    )

                    val entity = domain.toEntity()

                    entity.id shouldBe "todo-2"
                    entity.priority shouldBe "low"
                    entity.tags shouldBe """["personal"]"""
                    entity.syncStatus shouldBe "pending"
                    entity.isCompleted shouldBe true
                }

                it("allows syncStatus override") {
                    val domain = TodoItem(
                        id = "todo-3", userId = "u", title = "t", description = "",
                        isCompleted = false, dueDate = null, priority = Priority.MEDIUM,
                        tags = emptyList(), syncStatus = SyncStatus.PENDING,
                        createdAt = 1L, updatedAt = 2L
                    )
                    domain.toEntity(syncStatus = "synced").syncStatus shouldBe "synced"
                }
            }

            describe("TodoItemDto.toDomain()") {
                it("maps DTO with List<String> tags directly to domain") {
                    val dto = TodoItemDto(
                        id = "todo-dto-1", userId = "u", title = "Fix bug",
                        description = "critical issue", isCompleted = false,
                        dueDate = null, priority = "high",
                        tags = listOf("bug", "kotlin"),
                        syncStatus = "synced", createdAt = 5L, updatedAt = 6L
                    )

                    val domain = dto.toDomain()

                    domain.priority shouldBe Priority.HIGH
                    domain.tags shouldBe listOf("bug", "kotlin")
                    domain.syncStatus shouldBe SyncStatus.SYNCED
                }
            }

            describe("TodoItemDto.toEntity()") {
                it("encodes DTO tags to JSON string in entity") {
                    val dto = TodoItemDto(
                        id = "todo-dto-2", userId = "u", title = "t", description = "",
                        isCompleted = false, dueDate = null, priority = "medium",
                        tags = listOf("work"), syncStatus = "pending",
                        createdAt = 7L, updatedAt = 8L
                    )

                    val entity = dto.toEntity()

                    entity.tags shouldBe """["work"]"""
                    entity.syncStatus shouldBe "pending"
                }
            }

            // ── CalendarEvent ─────────────────────────────────────────────────────

            describe("CalendarEventEntity.toDomain()") {
                it("maps all fields including source and syncStatus enums") {
                    val entity = CalendarEventEntity(
                        id = "event-1", userId = "u", title = "Stand-up",
                        description = "Daily sync", startTime = 10_000L, endTime = 11_000L,
                        location = "Zoom", isAllDay = false, source = "local",
                        syncStatus = "synced", createdAt = 1L, updatedAt = 2L
                    )

                    val domain = entity.toDomain()

                    domain.id shouldBe "event-1"
                    domain.title shouldBe "Stand-up"
                    domain.source shouldBe CalendarEventSource.LOCAL
                    domain.syncStatus shouldBe SyncStatus.SYNCED
                    domain.location shouldBe "Zoom"
                }

                it("maps google_calendar source correctly") {
                    val entity = CalendarEventEntity(
                        id = "event-2", userId = "u", title = "Sync",
                        description = "", startTime = 1L, endTime = 2L,
                        location = null, isAllDay = false, source = "google_calendar",
                        syncStatus = "pending", createdAt = 1L, updatedAt = 2L
                    )
                    entity.toDomain().source shouldBe CalendarEventSource.GOOGLE_CALENDAR
                }
            }

            describe("CalendarEvent.toEntity()") {
                it("converts enum values back to strings") {
                    val domain = CalendarEvent(
                        id = "event-3", userId = "u", title = "Sprint review",
                        description = "", startTime = 100L, endTime = 200L,
                        location = null, isAllDay = true,
                        source = CalendarEventSource.GOOGLE_CALENDAR,
                        syncStatus = SyncStatus.PENDING, createdAt = 1L, updatedAt = 2L
                    )

                    val entity = domain.toEntity()

                    entity.source shouldBe "google_calendar"
                    entity.syncStatus shouldBe "pending"
                    entity.isAllDay shouldBe true
                }
            }

            describe("CalendarEventDto.toDomain()") {
                it("maps DTO to domain with correct enum conversions") {
                    val dto = CalendarEventDto(
                        id = "event-dto-1", userId = "u", title = "Review",
                        description = "", startTime = 500L, endTime = 600L,
                        location = null, isAllDay = false, source = "local",
                        syncStatus = "synced", createdAt = 1L, updatedAt = 2L
                    )
                    val domain = dto.toDomain()
                    domain.source shouldBe CalendarEventSource.LOCAL
                    domain.syncStatus shouldBe SyncStatus.SYNCED
                }
            }

            describe("CalendarEventDto.toEntity()") {
                it("preserves raw string values in entity") {
                    val dto = CalendarEventDto(
                        id = "event-dto-2", userId = "u", title = "t",
                        description = "", startTime = 1L, endTime = 2L,
                        location = null, isAllDay = false, source = "google_calendar",
                        syncStatus = "pending", createdAt = 1L, updatedAt = 2L
                    )
                    val entity = dto.toEntity()
                    entity.source shouldBe "google_calendar"
                    entity.syncStatus shouldBe "pending"
                }
            }

            // ── Reminder ──────────────────────────────────────────────────────────

            describe("ReminderEntity.toDomain()") {
                it("maps all fields correctly") {
                    val entity = ReminderEntity(
                        id = "rem-1", userId = "u", title = "Take pills",
                        triggerTime = 50_000L, recurrenceRule = "FREQ=DAILY",
                        linkedTodoId = null, isCompleted = false,
                        syncStatus = "pending", createdAt = 1L, updatedAt = 2L
                    )

                    val domain = entity.toDomain()

                    domain.id shouldBe "rem-1"
                    domain.title shouldBe "Take pills"
                    domain.recurrenceRule shouldBe "FREQ=DAILY"
                    domain.syncStatus shouldBe SyncStatus.PENDING
                }

                it("maps isCompleted=true") {
                    val entity = ReminderEntity(
                        id = "rem-2", userId = "u", title = "t",
                        triggerTime = 1L, recurrenceRule = null,
                        linkedTodoId = "todo-1", isCompleted = true,
                        syncStatus = "synced", createdAt = 1L, updatedAt = 2L
                    )
                    entity.toDomain().isCompleted shouldBe true
                }
            }

            describe("Reminder.toEntity()") {
                it("converts domain to entity preserving all fields") {
                    val domain = Reminder(
                        id = "rem-3", userId = "u", title = "Exercise",
                        triggerTime = 70_000L, recurrenceRule = null,
                        linkedTodoId = null, isCompleted = false,
                        syncStatus = SyncStatus.SYNCED, createdAt = 1L, updatedAt = 2L
                    )
                    val entity = domain.toEntity()
                    entity.syncStatus shouldBe "synced"
                    entity.id shouldBe "rem-3"
                }

                it("allows syncStatus override") {
                    val domain = Reminder(
                        id = "rem-4",
                        userId = "u",
                        title = "t",
                        triggerTime = 1L,
                        syncStatus = SyncStatus.PENDING,
                        createdAt = 1L,
                        updatedAt = 2L
                    )
                    domain.toEntity(syncStatus = "failed").syncStatus shouldBe "failed"
                }
            }

            describe("ReminderDto.toDomain()") {
                it("maps DTO to domain with syncStatus enum") {
                    val dto = ReminderDto(
                        id = "rem-dto-1", userId = "u", title = "Meeting prep",
                        triggerTime = 80_000L, recurrenceRule = null,
                        linkedTodoId = null, isCompleted = false,
                        syncStatus = "synced", createdAt = 1L, updatedAt = 2L
                    )
                    val domain = dto.toDomain()
                    domain.syncStatus shouldBe SyncStatus.SYNCED
                }
            }

            describe("ReminderDto.toEntity()") {
                it("preserves raw syncStatus string in entity") {
                    val dto = ReminderDto(
                        id = "rem-dto-2", userId = "u", title = "t",
                        triggerTime = 1L, recurrenceRule = null,
                        linkedTodoId = null, isCompleted = false,
                        syncStatus = "pending", createdAt = 1L, updatedAt = 2L
                    )
                    dto.toEntity().syncStatus shouldBe "pending"
                }
            }

            // ── HabitDefinition ───────────────────────────────────────────────────

            describe("HabitDefinitionEntity.toDomain()") {
                it("maps daily recurrence correctly") {
                    val entity = HabitDefinitionEntity(
                        id = "habit-1",
                        userId = "u",
                        name = "Morning run",
                        description = "5km daily",
                        recurrence = "daily",
                        targetFrequency = 1,
                        createdAt = 1L,
                        updatedAt = 2L
                    )
                    val domain = entity.toDomain()
                    domain.recurrence shouldBe HabitRecurrence.DAILY
                    domain.targetFrequency shouldBe 1
                }

                it("maps weekly recurrence correctly") {
                    val entity = HabitDefinitionEntity(
                        id = "habit-2",
                        userId = "u",
                        name = "Gym",
                        description = "",
                        recurrence = "weekly",
                        targetFrequency = 3,
                        createdAt = 1L,
                        updatedAt = 2L
                    )
                    entity.toDomain().recurrence shouldBe HabitRecurrence.WEEKLY
                }

                it("falls back to DAILY for unknown recurrence value") {
                    val entity = HabitDefinitionEntity(
                        id = "habit-x",
                        userId = "u",
                        name = "t",
                        description = "",
                        recurrence = "monthly",
                        targetFrequency = 1,
                        createdAt = 1L,
                        updatedAt = 2L
                    )
                    entity.toDomain().recurrence shouldBe HabitRecurrence.DAILY
                }
            }

            describe("HabitDefinition.toEntity()") {
                it("converts recurrence enum to string value") {
                    val domain = HabitDefinition(
                        id = "habit-3",
                        userId = "u",
                        name = "Meditate",
                        description = "",
                        recurrence = HabitRecurrence.WEEKLY,
                        targetFrequency = 5,
                        createdAt = 1L,
                        updatedAt = 2L
                    )
                    domain.toEntity().recurrence shouldBe "weekly"
                }
            }

            describe("HabitDefinitionDto.toDomain()") {
                it("maps DTO to domain with recurrence enum conversion") {
                    val dto = HabitDefinitionDto(
                        id = "habit-dto-1",
                        userId = "u",
                        name = "Read",
                        description = "30 min",
                        recurrence = "daily",
                        targetFrequency = 1,
                        createdAt = 1L,
                        updatedAt = 2L
                    )
                    dto.toDomain().recurrence shouldBe HabitRecurrence.DAILY
                }
            }

            describe("HabitDefinitionDto.toEntity()") {
                it("preserves raw recurrence string in entity") {
                    val dto = HabitDefinitionDto(
                        id = "habit-dto-2",
                        userId = "u",
                        name = "t",
                        description = "",
                        recurrence = "weekly",
                        targetFrequency = 2,
                        createdAt = 1L,
                        updatedAt = 2L
                    )
                    dto.toEntity().recurrence shouldBe "weekly"
                }
            }

            // ── HabitEntry ────────────────────────────────────────────────────────

            describe("HabitEntryEntity.toDomain()") {
                it("maps all fields including nullable note") {
                    val entity = HabitEntryEntity(
                        id = "entry-1",
                        habitId = "habit-1",
                        userId = "u",
                        completedAt = 10_000L,
                        note = "Felt great"
                    )
                    val domain = entity.toDomain()
                    domain.id shouldBe "entry-1"
                    domain.habitId shouldBe "habit-1"
                    domain.completedAt shouldBe 10_000L
                    domain.note shouldBe "Felt great"
                }

                it("maps null note correctly") {
                    val entity = HabitEntryEntity(
                        id = "entry-2",
                        habitId = "habit-1",
                        userId = "u",
                        completedAt = 1L,
                        note = null
                    )
                    entity.toDomain().note shouldBe null
                }
            }

            describe("HabitEntry.toEntity()") {
                it("maps domain to entity preserving all fields") {
                    val domain = HabitEntry(
                        id = "entry-3",
                        habitId = "habit-2",
                        userId = "u",
                        completedAt = 20_000L,
                        note = null
                    )
                    val entity = domain.toEntity()
                    entity.id shouldBe "entry-3"
                    entity.note shouldBe null
                }
            }

            describe("HabitEntryDto.toDomain()") {
                it("maps DTO to domain correctly") {
                    val dto = HabitEntryDto(
                        id = "entry-dto-1",
                        habitId = "habit-1",
                        userId = "u",
                        completedAt = 30_000L,
                        note = "Good session"
                    )
                    val domain = dto.toDomain()
                    domain.id shouldBe "entry-dto-1"
                    domain.note shouldBe "Good session"
                }
            }

            describe("HabitEntryDto.toEntity()") {
                it("maps DTO to entity correctly") {
                    val dto = HabitEntryDto(
                        id = "entry-dto-2",
                        habitId = "habit-2",
                        userId = "u",
                        completedAt = 40_000L,
                        note = null
                    )
                    val entity = dto.toEntity()
                    entity.habitId shouldBe "habit-2"
                    entity.note shouldBe null
                }
            }
        }

        // ─── SemanticSearchMapper ─────────────────────────────────────────────────

        describe("SemanticSearchMapper") {

            describe("SemanticSearchResultDto.toDomain()") {
                it("maps 'conversation' sourceType to CONVERSATION") {
                    val dto = SemanticSearchResultDto(
                        sourceType = "conversation",
                        sourceName = "Chat with AI",
                        excerpt = "We discussed Kotlin coroutines.",
                        relevanceScore = 0.95f,
                        deepLink = "app://chat/conv-1"
                    )
                    val domain = dto.toDomain()
                    domain.sourceType shouldBe SemanticSearchResult.SourceType.CONVERSATION
                    domain.sourceName shouldBe "Chat with AI"
                    domain.deepLinkUri shouldBe "app://chat/conv-1"
                    domain.relevanceScore shouldBe 0.95f
                }

                it("maps 'note' sourceType to NOTE") {
                    val dto = SemanticSearchResultDto(
                        sourceType = "note",
                        sourceName = "Meeting Notes",
                        excerpt = "Q3 targets discussed.",
                        relevanceScore = 0.88f,
                        deepLink = "app://notes/n-1"
                    )
                    dto.toDomain().sourceType shouldBe SemanticSearchResult.SourceType.NOTE
                }

                it("maps 'document' sourceType to DOCUMENT") {
                    val dto = SemanticSearchResultDto(
                        sourceType = "document",
                        sourceName = "Architecture.pdf",
                        excerpt = "Clean architecture overview.",
                        relevanceScore = 0.80f,
                        deepLink = "app://docs/d-1"
                    )
                    dto.toDomain().sourceType shouldBe SemanticSearchResult.SourceType.DOCUMENT
                }

                it("maps 'memory' sourceType to MEMORY") {
                    val dto = SemanticSearchResultDto(
                        sourceType = "memory",
                        sourceName = "User Preference",
                        excerpt = "Prefers dark mode.",
                        relevanceScore = 0.70f,
                        deepLink = "app://memory/m-1"
                    )
                    dto.toDomain().sourceType shouldBe SemanticSearchResult.SourceType.MEMORY
                }

                it("maps unknown sourceType to CONVERSATION fallback") {
                    val dto = SemanticSearchResultDto(
                        sourceType = "calendar_event",
                        sourceName = "Unknown",
                        excerpt = "Some content.",
                        relevanceScore = 0.50f,
                        deepLink = "app://unknown"
                    )
                    dto.toDomain().sourceType shouldBe SemanticSearchResult.SourceType.CONVERSATION
                }

                it("is case-insensitive for sourceType mapping") {
                    val dto = SemanticSearchResultDto(
                        sourceType = "NOTE",
                        sourceName = "Upper Case",
                        excerpt = "Content.",
                        relevanceScore = 0.60f,
                        deepLink = "app://notes/n-2"
                    )
                    dto.toDomain().sourceType shouldBe SemanticSearchResult.SourceType.NOTE
                }

                it("truncates excerpt to 300 characters defensively") {
                    val longExcerpt = "A".repeat(350)
                    val dto = SemanticSearchResultDto(
                        sourceType = "note",
                        sourceName = "Big Note",
                        excerpt = longExcerpt,
                        relevanceScore = 0.75f,
                        deepLink = "app://notes/n-3"
                    )
                    dto.toDomain().excerpt.length shouldBe 300
                }

                it("preserves excerpt shorter than 300 characters unchanged") {
                    val shortExcerpt = "Short excerpt."
                    val dto = SemanticSearchResultDto(
                        sourceType = "note",
                        sourceName = "Short Note",
                        excerpt = shortExcerpt,
                        relevanceScore = 0.90f,
                        deepLink = "app://notes/n-4"
                    )
                    dto.toDomain().excerpt shouldBe shortExcerpt
                }
            }
        }
    })
