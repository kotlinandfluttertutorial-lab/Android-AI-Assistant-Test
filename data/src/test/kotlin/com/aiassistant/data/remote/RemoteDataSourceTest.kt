/**
 * RemoteDataSourceTest.kt — data module
 *
 * Purpose: Unit tests for the remote data source wrappers:
 *   - [DocumentRemoteDataSource] — success, HTTP error, IOException
 *   - [MessageRemoteDataSource] — success, HTTP error, IOException
 *   - [NoteRemoteDataSource] — success, HTTP error, IOException
 *
 * These tests exercise the safeApiCall wrapper's error-mapping branches and confirm
 * that results are correctly wrapped in [ApiResult].
 *
 * Architecture: data module — unit tests (pure JVM). MockK is used to mock the
 *               underlying Retrofit service interfaces so no network calls are made.
 *
 * Test toolchain:
 * - Kotest DescribeSpec  — test structure
 * - MockK                — mocking API service interfaces
 * - kotlinx.coroutines.test — runTest
 *
 * Requirements covered: 4.1, 4.6, 10.2, 10.3, 13.1
 */
package com.aiassistant.data.remote

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.network.model.PaginatedResponse
import com.aiassistant.data.remote.conversation.ConversationApiService
import com.aiassistant.data.remote.conversation.ConversationDto
import com.aiassistant.data.remote.conversation.ConversationRemoteDataSource
import com.aiassistant.data.remote.document.DocumentApiService
import com.aiassistant.data.remote.document.DocumentDto
import com.aiassistant.data.remote.document.DocumentListResponseDto
import com.aiassistant.data.remote.document.DocumentQueryResponse
import com.aiassistant.data.remote.document.DocumentRemoteDataSource
import com.aiassistant.data.remote.document.DocumentUploadResponseDto
import com.aiassistant.data.remote.document.JobStatusDto
import com.aiassistant.data.remote.message.MessageApiService
import com.aiassistant.data.remote.message.MessageDto
import com.aiassistant.data.remote.message.MessageRemoteDataSource
import com.aiassistant.data.remote.message.RegenerateMessageRequest
import com.aiassistant.data.remote.message.SendMessageRequest
import com.aiassistant.data.remote.note.NoteApiService
import com.aiassistant.data.remote.note.NoteDto
import com.aiassistant.data.remote.note.NoteRemoteDataSource
import com.aiassistant.data.remote.note.SaveNoteRequest
import com.aiassistant.data.remote.note.SummarizeNoteResponse
import com.aiassistant.data.remote.productivity.ProductivityApiService
import com.aiassistant.data.remote.productivity.ProductivityRemoteDataSource
import com.aiassistant.data.remote.productivity.TodoItemDto
import com.aiassistant.data.repository.TestDispatcherProvider
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException

// ─── Helpers ──────────────────────────────────────────────────────────────────

private fun httpException(code: Int): HttpException {
    val body = "{}".toResponseBody(null)
    val response = retrofit2.Response.error<Any>(code, body)
    return HttpException(response)
}

private fun fakeDocumentDto(id: String = "doc-1") = DocumentDto(
    id = id,
    fileName = "file.pdf",
    mimeType = "application/pdf",
    sizeBytes = 1024L,
    ingestionStatus = "pending",
    pageCount = null,
    createdAt = "2026-07-30T04:11:29Z"
)

private fun fakeJobStatusDto(status: String = "ready") = JobStatusDto(
    jobId = "job-1",
    documentId = "doc-1",
    status = status,
    errorMessage = null
)

private fun fakeDocumentUploadResponseDto() = DocumentUploadResponseDto(
    documentId = "doc-1",
    jobId = "job-1",
    status = "pending"
)

private fun fakeMessageDto(id: String = "msg-1") = MessageDto(
    id = id,
    conversationId = "conv-1",
    role = "assistant",
    content = "AI response",
    inputTokens = 20,
    outputTokens = 50,
    provider = "openai",
    createdAt = 2_000_000L
)

private fun fakeNoteDto(id: String = "note-1") = NoteDto(
    id = id,
    userId = "user-1",
    title = "My Note",
    content = "Note content",
    tags = listOf("kotlin"),
    syncStatus = "synced",
    createdAt = 1_000_000L,
    updatedAt = 2_000_000L
)

private fun fakeConversationDto(id: String = "conv-1") = ConversationDto(
    id = id,
    userId = "user-1",
    title = "My Conversation",
    isPinned = false,
    isDeleted = false,
    provider = "openai",
    createdAt = 1_000_000L,
    updatedAt = 2_000_000L
)

private fun fakeTodoItemDto(id: String = "todo-1") = TodoItemDto(
    id = id,
    userId = "user-1",
    title = "Buy milk",
    description = "At the store",
    isCompleted = false,
    dueDate = null,
    priority = "medium",
    tags = emptyList(),
    syncStatus = "synced",
    createdAt = 1_000_000L,
    updatedAt = 2_000_000L
)

// ─── DocumentRemoteDataSource tests ──────────────────────────────────────────

class DocumentRemoteDataSourceTest :
    DescribeSpec({

        val api: DocumentApiService = mockk()
        val dispatchers = TestDispatcherProvider()
        lateinit var dataSource: DocumentRemoteDataSource

        beforeEach {
            clearAllMocks()
            dataSource = DocumentRemoteDataSource(api = api, dispatchers = dispatchers)
        }

        describe("getDocuments()") {
            it("returns Success with list of DTOs from DocumentListResponseDto") {
                runTest {
                    val response = DocumentListResponseDto(
                        documents = listOf(fakeDocumentDto("d1"), fakeDocumentDto("d2")),
                        total = 2
                    )
                    coEvery { api.getDocuments() } returns response

                    val result = dataSource.getDocuments()

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data.size shouldBe 2
                }
            }

            it("maps HTTP 401 to Unauthorized") {
                runTest {
                    coEvery { api.getDocuments() } throws httpException(401)

                    val result = dataSource.getDocuments()

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Unauthorized>()
                }
            }

            it("maps HTTP 403 to Forbidden") {
                runTest {
                    coEvery { api.getDocuments() } throws httpException(403)

                    val result = dataSource.getDocuments()

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Forbidden>()
                }
            }

            it("maps HTTP 422 to ValidationError") {
                runTest {
                    coEvery { api.getDocuments() } throws httpException(422)

                    val result = dataSource.getDocuments()

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
            }

            it("maps HTTP 500 to ServerError") {
                runTest {
                    coEvery { api.getDocuments() } throws httpException(500)

                    val result = dataSource.getDocuments()

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ServerError>()
                }
            }

            it("maps IOException to NetworkError") {
                runTest {
                    coEvery { api.getDocuments() } throws java.io.IOException("timeout")

                    val result = dataSource.getDocuments()

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.NetworkError>()
                }
            }
        }

        describe("getJobStatus()") {
            it("returns Success with JobStatusDto") {
                runTest {
                    coEvery { api.getJobStatus("job-abc") } returns fakeJobStatusDto("processing")

                    val result = dataSource.getJobStatus("job-abc")

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data.status shouldBe "processing"
                }
            }

            it("maps HTTP 404 to ValidationError") {
                runTest {
                    coEvery { api.getJobStatus(any()) } throws httpException(404)

                    val result = dataSource.getJobStatus("unknown-job")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
            }
        }

        describe("queryDocument()") {
            it("returns Success with query response") {
                runTest {
                    val queryResponse = DocumentQueryResponse(
                        answer = "The document says X",
                        citations = emptyList(),
                        contextUsed = "Context..."
                    )
                    coEvery {
                        api.queryDocuments(match { it.query == "what is X?" && it.documentIds == listOf("doc-1") })
                    } returns queryResponse

                    val result = dataSource.queryDocument("doc-1", "what is X?")

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data.answer shouldBe "The document says X"
                }
            }

            it("maps HTTP 500 to ServerError") {
                runTest {
                    coEvery { api.queryDocuments(any()) } throws httpException(500)

                    val result = dataSource.queryDocument("doc-1", "query")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ServerError>()
                }
            }
        }

        describe("deleteDocument()") {
            it("returns Success(Unit) on success") {
                runTest {
                    coEvery { api.deleteDocument("doc-1") } returns Unit

                    val result = dataSource.deleteDocument("doc-1")

                    result shouldBe ApiResult.Success(Unit)
                }
            }

            it("maps HTTP 404 to ValidationError") {
                runTest {
                    coEvery { api.deleteDocument(any()) } throws httpException(404)

                    val result = dataSource.deleteDocument("nonexistent")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
            }
        }
    })

// ─── MessageRemoteDataSource tests ───────────────────────────────────────────

class MessageRemoteDataSourceTest :
    DescribeSpec({

        val api: MessageApiService = mockk()
        val dispatchers = TestDispatcherProvider()
        lateinit var dataSource: MessageRemoteDataSource

        beforeEach {
            clearAllMocks()
            dataSource = MessageRemoteDataSource(api = api, dispatchers = dispatchers)
        }

        describe("sendMessage()") {
            it("returns Success with MessageDto") {
                runTest {
                    coEvery {
                        api.sendMessage("conv-1", SendMessageRequest("conv-1", "Hello", "openai"))
                    } returns fakeMessageDto()

                    val result = dataSource.sendMessage("conv-1", "Hello", "openai")

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data.id shouldBe "msg-1"
                }
            }

            it("maps HTTP 401 to Unauthorized") {
                runTest {
                    coEvery { api.sendMessage(any(), any()) } throws httpException(401)

                    val result = dataSource.sendMessage("conv-1", "Hello", "openai")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Unauthorized>()
                }
            }

            it("maps HTTP 403 to Forbidden") {
                runTest {
                    coEvery { api.sendMessage(any(), any()) } throws httpException(403)

                    val result = dataSource.sendMessage("conv-1", "Hello", "openai")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Forbidden>()
                }
            }

            it("maps HTTP 500 to ServerError") {
                runTest {
                    coEvery { api.sendMessage(any(), any()) } throws httpException(500)

                    val result = dataSource.sendMessage("conv-1", "Hello", "openai")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ServerError>()
                }
            }

            it("maps IOException to NetworkError") {
                runTest {
                    coEvery { api.sendMessage(any(), any()) } throws java.io.IOException("network error")

                    val result = dataSource.sendMessage("conv-1", "Hello", "openai")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.NetworkError>()
                }
            }
        }

        describe("regenerateMessage()") {
            it("returns Success with regenerated MessageDto") {
                runTest {
                    coEvery {
                        api.regenerateMessage("conv-1", "msg-orig", RegenerateMessageRequest("msg-orig"))
                    } returns fakeMessageDto(id = "msg-regen")

                    val result = dataSource.regenerateMessage("conv-1", "msg-orig")

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data.id shouldBe "msg-regen"
                }
            }

            it("maps HTTP 404 to ValidationError") {
                runTest {
                    coEvery { api.regenerateMessage(any(), any(), any()) } throws httpException(404)

                    val result = dataSource.regenerateMessage("conv-1", "nonexistent")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
            }
        }

        describe("getMessages()") {
            it("returns Success with list of messages") {
                runTest {
                    val response = PaginatedResponse(
                        items = listOf(fakeMessageDto("m1"), fakeMessageDto("m2")),
                        total = 2
                    )
                    coEvery { api.getMessages("conv-1") } returns response

                    val result = dataSource.getMessages("conv-1")

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data.size shouldBe 2
                }
            }

            it("maps HTTP 500 to ServerError") {
                runTest {
                    coEvery { api.getMessages(any()) } throws httpException(500)

                    val result = dataSource.getMessages("conv-1")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ServerError>()
                }
            }
        }
    })

// ─── NoteRemoteDataSource tests ───────────────────────────────────────────────

class NoteRemoteDataSourceTest :
    DescribeSpec({

        val api: NoteApiService = mockk()
        val dispatchers = TestDispatcherProvider()
        lateinit var dataSource: NoteRemoteDataSource

        beforeEach {
            clearAllMocks()
            dataSource = NoteRemoteDataSource(api = api, dispatchers = dispatchers)
        }

        describe("getNotes()") {
            it("returns Success with list of NoteDto") {
                runTest {
                    val response = PaginatedResponse(
                        items = listOf(fakeNoteDto("n1"), fakeNoteDto("n2")),
                        total = 2
                    )
                    coEvery { api.getNotes() } returns response

                    val result = dataSource.getNotes()

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data.size shouldBe 2
                }
            }

            it("maps HTTP 401 to Unauthorized") {
                runTest {
                    coEvery { api.getNotes() } throws httpException(401)

                    val result = dataSource.getNotes()

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Unauthorized>()
                }
            }

            it("maps HTTP 500 to ServerError") {
                runTest {
                    coEvery { api.getNotes() } throws httpException(500)

                    val result = dataSource.getNotes()

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ServerError>()
                }
            }

            it("maps IOException to NetworkError") {
                runTest {
                    coEvery { api.getNotes() } throws java.io.IOException("network failure")

                    val result = dataSource.getNotes()

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.NetworkError>()
                }
            }
        }

        describe("createNote()") {
            it("returns Success with created NoteDto") {
                runTest {
                    coEvery {
                        api.createNote(SaveNoteRequest("Title", "Body", listOf("tag1")))
                    } returns fakeNoteDto("new-note")

                    val result = dataSource.createNote("Title", "Body", listOf("tag1"))

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data.id shouldBe "new-note"
                }
            }

            it("maps HTTP 422 to ValidationError") {
                runTest {
                    coEvery { api.createNote(any()) } throws httpException(422)

                    val result = dataSource.createNote("", "", emptyList())

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
            }
        }

        describe("updateNote()") {
            it("returns Success on update") {
                runTest {
                    coEvery {
                        api.updateNote("note-1", SaveNoteRequest("Updated", "New body", emptyList()))
                    } returns fakeNoteDto("note-1")

                    val result = dataSource.updateNote("note-1", "Updated", "New body", emptyList())

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                }
            }

            it("maps HTTP 404 to ValidationError") {
                runTest {
                    coEvery { api.updateNote(any(), any()) } throws httpException(404)

                    val result = dataSource.updateNote("nonexistent", "t", "c", emptyList())

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
            }
        }

        describe("deleteNote()") {
            it("returns Success(Unit)") {
                runTest {
                    coEvery { api.deleteNote("note-1") } returns Unit

                    val result = dataSource.deleteNote("note-1")

                    result shouldBe ApiResult.Success(Unit)
                }
            }

            it("maps HTTP 500 to ServerError") {
                runTest {
                    coEvery { api.deleteNote(any()) } throws httpException(500)

                    val result = dataSource.deleteNote("note-1")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ServerError>()
                }
            }
        }

        describe("summarizeNote()") {
            it("returns Success with summary string") {
                runTest {
                    coEvery { api.summarizeNote("note-1") } returns SummarizeNoteResponse("Short summary.")

                    val result = dataSource.summarizeNote("note-1")

                    result shouldBe ApiResult.Success("Short summary.")
                }
            }

            it("maps HTTP 500 to ServerError") {
                runTest {
                    coEvery { api.summarizeNote(any()) } throws httpException(500)

                    val result = dataSource.summarizeNote("note-1")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ServerError>()
                }
            }
        }

        describe("rewriteNote()") {
            it("returns Success with rewritten text") {
                runTest {
                    coEvery { api.rewriteNote("note-1") } returns
                        com.aiassistant.data.remote.note.RewriteNoteResponse("Rewritten text.")

                    val result = dataSource.rewriteNote("note-1")

                    result shouldBe ApiResult.Success("Rewritten text.")
                }
            }

            it("maps IOException to NetworkError") {
                runTest {
                    coEvery { api.rewriteNote(any()) } throws java.io.IOException("timeout")

                    val result = dataSource.rewriteNote("note-1")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.NetworkError>()
                }
            }
        }
    })

// ─── ConversationRemoteDataSource tests ──────────────────────────────────────

class ConversationRemoteDataSourceTest :
    DescribeSpec({

        val api: ConversationApiService = mockk()
        val dispatchers = TestDispatcherProvider()
        lateinit var dataSource: ConversationRemoteDataSource

        beforeEach {
            clearAllMocks()
            dataSource = ConversationRemoteDataSource(api = api, dispatchers = dispatchers)
        }

        describe("getConversations()") {
            it("returns Success with list of conversations from items") {
                runTest {
                    val response = PaginatedResponse(
                        items = listOf(fakeConversationDto("c1"), fakeConversationDto("c2")),
                        total = 2
                    )
                    coEvery { api.getConversations() } returns response

                    val result = dataSource.getConversations()

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data.size shouldBe 2
                }
            }
        }
    })

// ─── ProductivityRemoteDataSource tests ──────────────────────────────────────

class ProductivityRemoteDataSourceTest :
    DescribeSpec({

        val api: ProductivityApiService = mockk()
        val dispatchers = TestDispatcherProvider()
        lateinit var dataSource: ProductivityRemoteDataSource

        beforeEach {
            clearAllMocks()
            dataSource = ProductivityRemoteDataSource(api = api, dispatchers = dispatchers)
        }

        describe("getTodos()") {
            it("returns Success with list of todos from items") {
                runTest {
                    val response = PaginatedResponse(
                        items = listOf(fakeTodoItemDto("t1"), fakeTodoItemDto("t2")),
                        total = 2
                    )
                    coEvery { api.getTodos() } returns response

                    val result = dataSource.getTodos()

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data.size shouldBe 2
                }
            }
        }
    })
