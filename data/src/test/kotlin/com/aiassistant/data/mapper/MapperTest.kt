/**
 * MapperTest.kt — data module
 *
 * Purpose: Unit tests for all data-module mapper extension functions.
 *          Covers ConversationMapper, MessageMapper, DocumentMapper, NoteMapper, and MemoryMapper.
 *
 * Architecture: data module — unit tests (pure JVM, no Android framework).
 *               Mapper functions are pure; no mocking required.
 *
 * Test toolchain:
 * - Kotest DescribeSpec — test structure and assertions
 *
 * Requirements covered: 10.1, 10.3, 13.1, 13.4, 13.5, 4.1, 4.10, 7.3
 */
package com.aiassistant.data.mapper

import com.aiassistant.core.database.entity.ConversationEntity
import com.aiassistant.core.database.entity.DocumentEntity
import com.aiassistant.core.database.entity.MessageEntity
import com.aiassistant.core.database.entity.NoteEntity
import com.aiassistant.data.remote.document.DocumentDto
import com.aiassistant.data.remote.memory.MemoryDto
import com.aiassistant.data.remote.message.MessageDto
import com.aiassistant.data.remote.note.NoteDto
import com.aiassistant.domain.model.Conversation
import com.aiassistant.domain.model.Document
import com.aiassistant.domain.model.IngestionStatus
import com.aiassistant.domain.model.MemoryType
import com.aiassistant.domain.model.Message
import com.aiassistant.domain.model.Note
import com.aiassistant.domain.model.SyncStatus
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
    })
