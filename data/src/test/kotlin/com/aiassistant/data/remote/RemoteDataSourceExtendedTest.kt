/**
 * RemoteDataSourceExtendedTest.kt — data module
 *
 * Purpose: Unit tests for the remote data source wrappers not covered by RemoteDataSourceTest.kt:
 *   - [ResumeRemoteDataSource]         — generateResume, generateCoverLetter, generateEmail, correctGrammar
 *   - [TranslationRemoteDataSource]    — translateText
 *   - [SemanticSearchRemoteDataSource] — search
 *   - [CodeRemoteDataSource]           — analyzeCode (all SupportedLanguage + CodeAction mappings)
 *   - [SuggestionRemoteDataSource]     — getSuggestions (NoteContext, CalendarEventContext,
 *                                        ConversationContext, unknown type filtering)
 *   - [PersonaRemoteDataSource]        — getPersonas, createPersona, updatePersona, deletePersona
 *
 * Every test exercises the safeApiCall error-mapping branches (401 → Unauthorized,
 * 403 → Forbidden, 422 → ValidationError, 4xx → ValidationError, 5xx → ServerError,
 * IOException → NetworkError) as well as the happy-path Success branch.
 *
 * Architecture: data module — pure JVM unit tests. MockK is used to mock Retrofit service
 *               interfaces; no real network calls are made.
 *
 * Test toolchain:
 * - Kotest DescribeSpec  — test structure
 * - MockK                — mock API service interfaces
 * - kotlinx.coroutines.test — runTest
 *
 * Requirements covered: 14.1, 14.2, 14.4, 14.5, 10.5, 12.1–12.4, 33.1–33.3, 36.1, 36.3, 32.1
 */
package com.aiassistant.data.remote

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.data.remote.code.CodeAnalysisRequestDto
import com.aiassistant.data.remote.code.CodeAnalysisResponseDto
import com.aiassistant.data.remote.code.CodeApiService
import com.aiassistant.data.remote.code.CodeRemoteDataSource
import com.aiassistant.data.remote.persona.PersonaApiService
import com.aiassistant.data.remote.persona.PersonaCreateRequest
import com.aiassistant.data.remote.persona.PersonaListResponse
import com.aiassistant.data.remote.persona.PersonaRemoteDataSource
import com.aiassistant.data.remote.persona.PersonaResponse
import com.aiassistant.data.remote.persona.PersonaUpdateRequest
import com.aiassistant.data.remote.resume.CoverLetterGenerateRequest
import com.aiassistant.data.remote.resume.CoverLetterGenerateResponse
import com.aiassistant.data.remote.resume.EmailGenerateRequest
import com.aiassistant.data.remote.resume.EmailGenerateResponse
import com.aiassistant.data.remote.resume.GrammarCorrectRequest
import com.aiassistant.data.remote.resume.GrammarCorrectResponse
import com.aiassistant.data.remote.resume.ResumeApiService
import com.aiassistant.data.remote.resume.ResumeGenerateRequest
import com.aiassistant.data.remote.resume.ResumeGenerateResponse
import com.aiassistant.data.remote.resume.ResumeRemoteDataSource
import com.aiassistant.data.remote.search.SemanticSearchApiService
import com.aiassistant.data.remote.search.SemanticSearchRemoteDataSource
import com.aiassistant.data.remote.search.SemanticSearchResponseDto
import com.aiassistant.data.remote.search.SemanticSearchResultDto
import com.aiassistant.data.remote.suggestion.SuggestionApiService
import com.aiassistant.data.remote.suggestion.SuggestionRemoteDataSource
import com.aiassistant.data.remote.suggestion.SuggestionResponseItem
import com.aiassistant.data.remote.suggestion.SuggestionsResponse
import com.aiassistant.data.remote.translator.TranslationApiService
import com.aiassistant.data.remote.translator.TranslationRemoteDataSource
import com.aiassistant.data.remote.translator.TranslationResponse
import com.aiassistant.data.repository.TestDispatcherProvider
import com.aiassistant.domain.model.CodeAction
import com.aiassistant.domain.model.CodeAnalysisRequest
import com.aiassistant.domain.model.ScreenContext
import com.aiassistant.domain.model.SuggestionType
import com.aiassistant.domain.model.SupportedLanguage
import com.aiassistant.domain.model.TargetScreenType
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

// ─── Shared HTTP exception helper ─────────────────────────────────────────────

private fun httpException(code: Int): HttpException {
    val body = "{}".toResponseBody(null)
    val response = Response.error<Any>(code, body)
    return HttpException(response)
}

// ─── ResumeRemoteDataSource ───────────────────────────────────────────────────

class ResumeRemoteDataSourceTest :
    DescribeSpec({

        val api: ResumeApiService = mockk()
        val dispatchers = TestDispatcherProvider()
        lateinit var dataSource: ResumeRemoteDataSource

        beforeEach {
            clearAllMocks()
            dataSource = ResumeRemoteDataSource(api, dispatchers)
        }

        describe("generateResume()") {
            it("returns Success with resume markdown on success") {
                runTest {
                    coEvery {
                        api.generateResume(ResumeGenerateRequest("my history", "job desc"))
                    } returns ResumeGenerateResponse("# Resume\n\n...")

                    val result = dataSource.generateResume("my history", "job desc")

                    result shouldBe ApiResult.Success("# Resume\n\n...")
                }
            }

            it("maps HTTP 401 to Unauthorized") {
                runTest {
                    coEvery { api.generateResume(any()) } throws httpException(401)

                    val result = dataSource.generateResume("h", "d")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Unauthorized>()
                }
            }

            it("maps HTTP 403 to Forbidden") {
                runTest {
                    coEvery { api.generateResume(any()) } throws httpException(403)

                    val result = dataSource.generateResume("h", "d")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Forbidden>()
                }
            }

            it("maps HTTP 422 to ValidationError") {
                runTest {
                    coEvery { api.generateResume(any()) } throws httpException(422)

                    val result = dataSource.generateResume("", "")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
            }

            it("maps HTTP 400 to ValidationError") {
                runTest {
                    coEvery { api.generateResume(any()) } throws httpException(400)

                    val result = dataSource.generateResume("h", "d")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
            }

            it("maps HTTP 500 to ServerError") {
                runTest {
                    coEvery { api.generateResume(any()) } throws httpException(500)

                    val result = dataSource.generateResume("h", "d")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ServerError>()
                }
            }

            it("maps IOException to NetworkError") {
                runTest {
                    coEvery { api.generateResume(any()) } throws IOException("timeout")

                    val result = dataSource.generateResume("h", "d")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.NetworkError>()
                }
            }
        }

        describe("generateCoverLetter()") {
            it("returns Success with cover letter text") {
                runTest {
                    coEvery {
                        api.generateCoverLetter(CoverLetterGenerateRequest("history", "job"))
                    } returns CoverLetterGenerateResponse("Dear Hiring Manager...")

                    val result = dataSource.generateCoverLetter("history", "job")

                    result shouldBe ApiResult.Success("Dear Hiring Manager...")
                }
            }

            it("maps HTTP 500 to ServerError") {
                runTest {
                    coEvery { api.generateCoverLetter(any()) } throws httpException(500)

                    val result = dataSource.generateCoverLetter("h", "j")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ServerError>()
                }
            }

            it("maps IOException to NetworkError") {
                runTest {
                    coEvery { api.generateCoverLetter(any()) } throws IOException("connection reset")

                    val result = dataSource.generateCoverLetter("h", "j")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.NetworkError>()
                }
            }
        }

        describe("generateEmail()") {
            it("returns Success with email text") {
                runTest {
                    coEvery {
                        api.generateEmail(EmailGenerateRequest("context text", "follow up"))
                    } returns EmailGenerateResponse("Subject: Follow Up\n\nDear...")

                    val result = dataSource.generateEmail("context text", "follow up")

                    result shouldBe ApiResult.Success("Subject: Follow Up\n\nDear...")
                }
            }

            it("maps HTTP 403 to Forbidden") {
                runTest {
                    coEvery { api.generateEmail(any()) } throws httpException(403)

                    val result = dataSource.generateEmail("c", "i")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Forbidden>()
                }
            }

            it("maps IOException to NetworkError") {
                runTest {
                    coEvery { api.generateEmail(any()) } throws IOException("socket closed")

                    val result = dataSource.generateEmail("c", "i")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.NetworkError>()
                }
            }
        }

        describe("correctGrammar()") {
            it("returns Success with corrected text") {
                runTest {
                    coEvery {
                        api.correctGrammar(GrammarCorrectRequest("Their going to the store"))
                    } returns GrammarCorrectResponse("They're going to the store")

                    val result = dataSource.correctGrammar("Their going to the store")

                    result shouldBe ApiResult.Success("They're going to the store")
                }
            }

            it("maps HTTP 500 to ServerError") {
                runTest {
                    coEvery { api.correctGrammar(any()) } throws httpException(500)

                    val result = dataSource.correctGrammar("draft")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ServerError>()
                }
            }
        }
    })

// ─── TranslationRemoteDataSource ─────────────────────────────────────────────

class TranslationRemoteDataSourceTest :
    DescribeSpec({

        val api: TranslationApiService = mockk()
        val dispatchers = TestDispatcherProvider()
        lateinit var dataSource: TranslationRemoteDataSource

        beforeEach {
            clearAllMocks()
            dataSource = TranslationRemoteDataSource(api, dispatchers)
        }

        describe("translateText()") {
            it("returns Success with translated text") {
                runTest {
                    coEvery { api.translateText(any()) } returns TranslationResponse("Bonjour le monde")

                    val result = dataSource.translateText("Hello world", "en", "fr")

                    result shouldBe ApiResult.Success("Bonjour le monde")
                }
            }

            it("maps HTTP 401 to Unauthorized") {
                runTest {
                    coEvery { api.translateText(any()) } throws httpException(401)

                    val result = dataSource.translateText("text", "en", "es")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Unauthorized>()
                }
            }

            it("maps HTTP 403 to Forbidden") {
                runTest {
                    coEvery { api.translateText(any()) } throws httpException(403)

                    val result = dataSource.translateText("text", "en", "es")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Forbidden>()
                }
            }

            it("maps HTTP 422 to ValidationError") {
                runTest {
                    coEvery { api.translateText(any()) } throws httpException(422)

                    val result = dataSource.translateText("", "en", "xx")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
            }

            it("maps HTTP 400 to ValidationError") {
                runTest {
                    coEvery { api.translateText(any()) } throws httpException(400)

                    val result = dataSource.translateText("text", "en", "xx")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
            }

            it("maps HTTP 500 to ServerError") {
                runTest {
                    coEvery { api.translateText(any()) } throws httpException(500)

                    val result = dataSource.translateText("text", "en", "fr")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ServerError>()
                }
            }

            it("maps IOException to NetworkError") {
                runTest {
                    coEvery { api.translateText(any()) } throws IOException("timeout")

                    val result = dataSource.translateText("text", "en", "fr")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.NetworkError>()
                }
            }
        }
    })

// ─── SemanticSearchRemoteDataSource ──────────────────────────────────────────

class SemanticSearchRemoteDataSourceTest :
    DescribeSpec({

        val api: SemanticSearchApiService = mockk()
        val dispatchers = TestDispatcherProvider()
        lateinit var dataSource: SemanticSearchRemoteDataSource

        beforeEach {
            clearAllMocks()
            dataSource = SemanticSearchRemoteDataSource(api, dispatchers)
        }

        describe("search()") {
            it("returns Success with response DTO on success") {
                runTest {
                    val result1 = SemanticSearchResultDto(
                        sourceType = "note",
                        sourceName = "Meeting Notes",
                        excerpt = "Discussed Q3 targets...",
                        relevanceScore = 0.92f,
                        deepLink = "app://notes/note-1"
                    )
                    val response = SemanticSearchResponseDto(results = listOf(result1), total = 1)
                    coEvery { api.search(any()) } returns response

                    val result = dataSource.search("Q3 targets")

                    result.shouldBeInstanceOf<ApiResult.Success<SemanticSearchResponseDto>>()
                    (result as ApiResult.Success).data.total shouldBe 1
                    result.data.results[0].sourceType shouldBe "note"
                }
            }

            it("returns Success with empty results list") {
                runTest {
                    coEvery { api.search(any()) } returns SemanticSearchResponseDto(emptyList(), 0)

                    val result = dataSource.search("nothing here")

                    result.shouldBeInstanceOf<ApiResult.Success<SemanticSearchResponseDto>>()
                    (result as ApiResult.Success).data.results shouldBe emptyList()
                }
            }

            it("maps HTTP 401 to Unauthorized") {
                runTest {
                    coEvery { api.search(any()) } throws httpException(401)

                    val result = dataSource.search("query")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Unauthorized>()
                }
            }

            it("maps HTTP 403 to Forbidden") {
                runTest {
                    coEvery { api.search(any()) } throws httpException(403)

                    val result = dataSource.search("query")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Forbidden>()
                }
            }

            it("maps HTTP 400 to ValidationError") {
                runTest {
                    coEvery { api.search(any()) } throws httpException(400)

                    val result = dataSource.search("")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
            }

            it("maps HTTP 500 to ServerError") {
                runTest {
                    coEvery { api.search(any()) } throws httpException(500)

                    val result = dataSource.search("query")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ServerError>()
                }
            }

            it("maps IOException to NetworkError") {
                runTest {
                    coEvery { api.search(any()) } throws IOException("connection refused")

                    val result = dataSource.search("query")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.NetworkError>()
                }
            }
        }
    })

// ─── CodeRemoteDataSource ─────────────────────────────────────────────────────

class CodeRemoteDataSourceExtendedTest :
    DescribeSpec({

        val api: CodeApiService = mockk()
        val dispatchers = TestDispatcherProvider()
        lateinit var dataSource: CodeRemoteDataSource

        beforeEach {
            clearAllMocks()
            dataSource = CodeRemoteDataSource(api, dispatchers)
        }

        fun fakeResponseDto(languageId: String = "kotlin", action: String = "explain") = CodeAnalysisResponseDto(
            languageId = languageId,
            originalCode = "fun main() {}",
            action = action,
            content = "This is a main function."
        )

        describe("analyzeCode()") {
            it("returns Success and maps result correctly for KOTLIN + EXPLAIN") {
                runTest {
                    coEvery {
                        api.analyzeCode(CodeAnalysisRequestDto("fun main() {}", "kotlin", "explain"))
                    } returns fakeResponseDto("kotlin", "explain")

                    val request = CodeAnalysisRequest(
                        code = "fun main() {}",
                        language = SupportedLanguage.KOTLIN,
                        action = CodeAction.EXPLAIN
                    )
                    val result = dataSource.analyzeCode(request)

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    val data = (result as ApiResult.Success).data
                    data.languageId shouldBe "kotlin"
                    data.action shouldBe CodeAction.EXPLAIN
                    data.content shouldBe "This is a main function."
                }
            }

            it("maps JAVA language to 'java' in request") {
                runTest {
                    coEvery {
                        api.analyzeCode(match { it.languageId == "java" && it.action == "fix_bug" })
                    } returns fakeResponseDto("java", "fix_bug")

                    val result = dataSource.analyzeCode(
                        CodeAnalysisRequest("class Foo {}", SupportedLanguage.JAVA, CodeAction.FIX_BUG)
                    )

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                }
            }

            it("maps PYTHON language to 'python' in request") {
                runTest {
                    coEvery {
                        api.analyzeCode(match { it.languageId == "python" })
                    } returns fakeResponseDto("python", "generate_tests")

                    val result = dataSource.analyzeCode(
                        CodeAnalysisRequest("def foo(): pass", SupportedLanguage.PYTHON, CodeAction.GENERATE_TESTS)
                    )

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                }
            }

            it("maps JAVASCRIPT language to 'javascript' in request") {
                runTest {
                    coEvery {
                        api.analyzeCode(match { it.languageId == "javascript" })
                    } returns fakeResponseDto("javascript", "explain")

                    val result = dataSource.analyzeCode(
                        CodeAnalysisRequest("const x = 1", SupportedLanguage.JAVASCRIPT, CodeAction.EXPLAIN)
                    )

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                }
            }

            it("maps CPP language to 'cpp' in request") {
                runTest {
                    coEvery {
                        api.analyzeCode(match { it.languageId == "cpp" })
                    } returns fakeResponseDto("cpp", "explain")

                    val result = dataSource.analyzeCode(
                        CodeAnalysisRequest("int main(){}", SupportedLanguage.CPP, CodeAction.EXPLAIN)
                    )

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                }
            }

            it("maps SQL language to 'sql' in request") {
                runTest {
                    coEvery {
                        api.analyzeCode(match { it.languageId == "sql" })
                    } returns fakeResponseDto("sql", "explain")

                    val result = dataSource.analyzeCode(
                        CodeAnalysisRequest("SELECT 1", SupportedLanguage.SQL, CodeAction.EXPLAIN)
                    )

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                }
            }

            it("maps HTTP 401 to Unauthorized") {
                runTest {
                    coEvery { api.analyzeCode(any()) } throws httpException(401)

                    val result = dataSource.analyzeCode(
                        CodeAnalysisRequest("code", SupportedLanguage.KOTLIN, CodeAction.EXPLAIN)
                    )

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Unauthorized>()
                }
            }

            it("maps HTTP 403 to Forbidden") {
                runTest {
                    coEvery { api.analyzeCode(any()) } throws httpException(403)

                    val result = dataSource.analyzeCode(
                        CodeAnalysisRequest("code", SupportedLanguage.KOTLIN, CodeAction.EXPLAIN)
                    )

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Forbidden>()
                }
            }

            it("maps HTTP 422 to ValidationError") {
                runTest {
                    coEvery { api.analyzeCode(any()) } throws httpException(422)

                    val result = dataSource.analyzeCode(
                        CodeAnalysisRequest("", SupportedLanguage.KOTLIN, CodeAction.EXPLAIN)
                    )

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
            }

            it("maps HTTP 500 to ServerError") {
                runTest {
                    coEvery { api.analyzeCode(any()) } throws httpException(500)

                    val result = dataSource.analyzeCode(
                        CodeAnalysisRequest("code", SupportedLanguage.KOTLIN, CodeAction.FIX_BUG)
                    )

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ServerError>()
                }
            }

            it("maps IOException to NetworkError") {
                runTest {
                    coEvery { api.analyzeCode(any()) } throws IOException("timeout")

                    val result = dataSource.analyzeCode(
                        CodeAnalysisRequest("code", SupportedLanguage.KOTLIN, CodeAction.EXPLAIN)
                    )

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.NetworkError>()
                }
            }
        }
    })

// ─── SuggestionRemoteDataSource ───────────────────────────────────────────────

class SuggestionRemoteDataSourceTest :
    DescribeSpec({

        val api: SuggestionApiService = mockk()
        val dispatchers = TestDispatcherProvider()
        lateinit var dataSource: SuggestionRemoteDataSource

        beforeEach {
            clearAllMocks()
            dataSource = SuggestionRemoteDataSource(api, dispatchers)
        }

        fun fakeSuggestionItem(
            id: String = "sug-1",
            type: String = "summarize",
            displayText: String = "Summarize this note",
            preFillText: String = "Summarize:",
            targetScreenType: String = "note"
        ) = SuggestionResponseItem(id, type, displayText, preFillText, targetScreenType)

        describe("getSuggestions() — NoteContext") {
            it("returns mapped ContextSuggestion list for NoteContext") {
                runTest {
                    val context = ScreenContext.NoteContext(
                        noteContent = "My long note about quarterly targets...",
                        screenInstanceId = "note-1"
                    )
                    val response = SuggestionsResponse(
                        suggestions = listOf(
                            fakeSuggestionItem("s1", "summarize", "Summarize this note", "", "note"),
                            fakeSuggestionItem("s2", "expand", "Expand this note", "", "note")
                        )
                    )
                    coEvery { api.getContextSuggestions(any()) } returns response

                    val result = dataSource.getSuggestions(context)

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    val suggestions = (result as ApiResult.Success).data
                    suggestions.size shouldBe 2
                    suggestions[0].type shouldBe SuggestionType.SUMMARIZE
                    suggestions[1].type shouldBe SuggestionType.EXPAND
                    suggestions[0].targetScreenType shouldBe TargetScreenType.NOTE
                }
            }

            it("filters out items with unknown suggestion type") {
                runTest {
                    val context = ScreenContext.NoteContext("content", "note-99")
                    val response = SuggestionsResponse(
                        suggestions = listOf(
                            fakeSuggestionItem("s1", "summarize", "Summarize", "", "note"),
                            fakeSuggestionItem("s2", "unknown_future_type", "Mystery", "", "note")
                        )
                    )
                    coEvery { api.getContextSuggestions(any()) } returns response

                    val result = dataSource.getSuggestions(context)

                    val suggestions = (result as ApiResult.Success).data
                    suggestions.size shouldBe 1
                    suggestions[0].type shouldBe SuggestionType.SUMMARIZE
                }
            }

            it("filters out items with unknown target screen type") {
                runTest {
                    val context = ScreenContext.NoteContext("content", "note-99")
                    val response = SuggestionsResponse(
                        suggestions = listOf(
                            fakeSuggestionItem("s1", "summarize", "Summarize", "", "unknown_screen")
                        )
                    )
                    coEvery { api.getContextSuggestions(any()) } returns response

                    val result = dataSource.getSuggestions(context)

                    val suggestions = (result as ApiResult.Success).data
                    suggestions shouldBe emptyList()
                }
            }

            it("returns empty list when backend returns no suggestions") {
                runTest {
                    val context = ScreenContext.NoteContext("short", "note-empty")
                    coEvery { api.getContextSuggestions(any()) } returns SuggestionsResponse(emptyList())

                    val result = dataSource.getSuggestions(context)

                    (result as ApiResult.Success).data shouldBe emptyList()
                }
            }
        }

        describe("getSuggestions() — CalendarEventContext") {
            it("returns suggestions mapped for CalendarEventContext") {
                runTest {
                    val context = ScreenContext.CalendarEventContext(
                        eventId = "event-1",
                        eventTitle = "Q3 Planning",
                        eventDescription = "Quarterly review",
                        attendeeNames = listOf("Alice", "Bob"),
                        screenInstanceId = "event-1"
                    )
                    val response = SuggestionsResponse(
                        suggestions = listOf(
                            fakeSuggestionItem("s1", "draft_agenda", "Draft agenda", "", "calendar_event")
                        )
                    )
                    coEvery { api.getContextSuggestions(match { it.screenType == "calendar" }) } returns response

                    val result = dataSource.getSuggestions(context)

                    val suggestions = (result as ApiResult.Success).data
                    suggestions.size shouldBe 1
                    suggestions[0].type shouldBe SuggestionType.DRAFT_AGENDA
                    suggestions[0].targetScreenType shouldBe TargetScreenType.CALENDAR_EVENT
                }
            }
        }

        describe("getSuggestions() — ConversationContext") {
            it("returns suggestions mapped for ConversationContext") {
                runTest {
                    val context = ScreenContext.ConversationContext(
                        lastMessageContent = "How do I improve my Kotlin skills?",
                        lastMessageAgeMillis = 90_000_000L,
                        screenInstanceId = "conv-1"
                    )
                    val response = SuggestionsResponse(
                        suggestions = listOf(
                            fakeSuggestionItem("s1", "continue_conversation", "Continue", "", "chat_conversation")
                        )
                    )
                    coEvery { api.getContextSuggestions(match { it.screenType == "chat" }) } returns response

                    val result = dataSource.getSuggestions(context)

                    val suggestions = (result as ApiResult.Success).data
                    suggestions.size shouldBe 1
                    suggestions[0].type shouldBe SuggestionType.CONTINUE_CONVERSATION
                    suggestions[0].targetScreenType shouldBe TargetScreenType.CHAT_CONVERSATION
                }
            }
        }

        describe("getSuggestions() — error handling") {
            it("maps HTTP 401 to Unauthorized") {
                runTest {
                    coEvery { api.getContextSuggestions(any()) } throws httpException(401)

                    val result = dataSource.getSuggestions(ScreenContext.NoteContext("text", "n-1"))

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Unauthorized>()
                }
            }

            it("maps HTTP 403 to Forbidden") {
                runTest {
                    coEvery { api.getContextSuggestions(any()) } throws httpException(403)

                    val result = dataSource.getSuggestions(ScreenContext.NoteContext("text", "n-1"))

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Forbidden>()
                }
            }

            it("maps HTTP 422 to ValidationError") {
                runTest {
                    coEvery { api.getContextSuggestions(any()) } throws httpException(422)

                    val result = dataSource.getSuggestions(ScreenContext.NoteContext("text", "n-1"))

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
            }

            it("maps HTTP 500 to ServerError") {
                runTest {
                    coEvery { api.getContextSuggestions(any()) } throws httpException(500)

                    val result = dataSource.getSuggestions(ScreenContext.NoteContext("text", "n-1"))

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ServerError>()
                }
            }

            it("maps IOException to NetworkError") {
                runTest {
                    coEvery { api.getContextSuggestions(any()) } throws IOException("timeout")

                    val result = dataSource.getSuggestions(ScreenContext.NoteContext("text", "n-1"))

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.NetworkError>()
                }
            }
        }
    })

// ─── PersonaRemoteDataSource ──────────────────────────────────────────────────

class PersonaRemoteDataSourceTest :
    DescribeSpec({

        val api: PersonaApiService = mockk()
        val dispatchers = TestDispatcherProvider()
        lateinit var dataSource: PersonaRemoteDataSource

        beforeEach {
            clearAllMocks()
            dataSource = PersonaRemoteDataSource(api, dispatchers)
        }

        fun fakePersonaResponse(id: String = "p-1") = PersonaResponse(
            id = id,
            userId = "user-1",
            name = "Code Helper",
            systemPrompt = "Help with code.",
            tone = "professional",
            scopeDescription = null,
            adminLocked = false,
            allowedRoles = emptyList(),
            createdAt = 1_000_000L,
            updatedAt = 2_000_000L
        )

        describe("getPersonas()") {
            it("returns Success with list extracted from PersonaListResponse.items") {
                runTest {
                    coEvery { api.getPersonas() } returns PersonaListResponse(
                        items = listOf(fakePersonaResponse("p-1"), fakePersonaResponse("p-2")),
                        total = 2
                    )

                    val result = dataSource.getPersonas()

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data.size shouldBe 2
                }
            }

            it("maps HTTP 401 to Unauthorized") {
                runTest {
                    coEvery { api.getPersonas() } throws httpException(401)

                    val result = dataSource.getPersonas()

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Unauthorized>()
                }
            }

            it("maps HTTP 403 to Forbidden") {
                runTest {
                    coEvery { api.getPersonas() } throws httpException(403)

                    val result = dataSource.getPersonas()

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Forbidden>()
                }
            }

            it("maps HTTP 500 to ServerError") {
                runTest {
                    coEvery { api.getPersonas() } throws httpException(500)

                    val result = dataSource.getPersonas()

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ServerError>()
                }
            }

            it("maps IOException to NetworkError") {
                runTest {
                    coEvery { api.getPersonas() } throws IOException("no route to host")

                    val result = dataSource.getPersonas()

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.NetworkError>()
                }
            }
        }

        describe("createPersona()") {
            it("returns Success with created PersonaResponse") {
                runTest {
                    val request = PersonaCreateRequest(
                        name = "New Persona",
                        systemPrompt = "Be helpful.",
                        tone = "casual"
                    )
                    coEvery { api.createPersona(request) } returns fakePersonaResponse("p-new")

                    val result = dataSource.createPersona(request)

                    result.shouldBeInstanceOf<ApiResult.Success<PersonaResponse>>()
                    (result as ApiResult.Success).data.id shouldBe "p-new"
                }
            }

            it("maps HTTP 422 to ValidationError") {
                runTest {
                    coEvery { api.createPersona(any()) } throws httpException(422)

                    val result = dataSource.createPersona(
                        PersonaCreateRequest("", "", "professional")
                    )

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
            }

            it("maps IOException to NetworkError") {
                runTest {
                    coEvery { api.createPersona(any()) } throws IOException("timeout")

                    val result = dataSource.createPersona(
                        PersonaCreateRequest("n", "p", "casual")
                    )

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.NetworkError>()
                }
            }
        }

        describe("updatePersona()") {
            it("returns Success with updated PersonaResponse") {
                runTest {
                    val request = PersonaUpdateRequest(name = "Updated Name")
                    coEvery { api.updatePersona("p-1", request) } returns fakePersonaResponse("p-1")

                    val result = dataSource.updatePersona("p-1", request)

                    result.shouldBeInstanceOf<ApiResult.Success<PersonaResponse>>()
                }
            }

            it("maps HTTP 404 to ValidationError") {
                runTest {
                    coEvery { api.updatePersona(any(), any()) } throws httpException(404)

                    val result = dataSource.updatePersona("missing", PersonaUpdateRequest())

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
            }

            it("maps HTTP 500 to ServerError") {
                runTest {
                    coEvery { api.updatePersona(any(), any()) } throws httpException(500)

                    val result = dataSource.updatePersona("p-1", PersonaUpdateRequest())

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ServerError>()
                }
            }
        }

        describe("deletePersona()") {
            it("returns Success(Unit) on success") {
                runTest {
                    coEvery { api.deletePersona("p-1") } returns Unit

                    val result = dataSource.deletePersona("p-1")

                    result shouldBe ApiResult.Success(Unit)
                }
            }

            it("maps HTTP 403 to Forbidden") {
                runTest {
                    coEvery { api.deletePersona(any()) } throws httpException(403)

                    val result = dataSource.deletePersona("p-locked")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Forbidden>()
                }
            }

            it("maps IOException to NetworkError") {
                runTest {
                    coEvery { api.deletePersona(any()) } throws IOException("connection reset")

                    val result = dataSource.deletePersona("p-1")

                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.NetworkError>()
                }
            }
        }
    })
