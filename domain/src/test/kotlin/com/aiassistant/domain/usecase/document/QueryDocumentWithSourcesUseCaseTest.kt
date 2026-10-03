/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain (test)
 * File       : QueryDocumentWithSourcesUseCaseTest.kt
 * Purpose    : Unit tests for QueryDocumentWithSourcesUseCase.
 *
 * Covers:
 *   - Blank question → ValidationError returned, repository NOT called
 *   - Whitespace-only question → same as blank
 *   - Valid question → repository.ragQuery() called with trimmed text
 *   - documentIds=null → passed through as null
 *   - documentIds=empty list → normalized to null before repository call
 *   - topK parameter forwarded correctly
 *   - Success result passes through unchanged
 *   - Error result passes through unchanged
 *   - NetworkUnavailable passes through unchanged
 *
 * Style: JUnit4 + MockK (matches project conventions)
 * ============================================================
 */

package com.aiassistant.domain.usecase.document

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.RagAnswer
import com.aiassistant.domain.model.RagCitationSource
import com.aiassistant.domain.repository.DocumentRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class QueryDocumentWithSourcesUseCaseTest {

    private val repository = mockk<DocumentRepository>()
    private lateinit var useCase: QueryDocumentWithSourcesUseCase

    @Before
    fun setUp() {
        useCase = QueryDocumentWithSourcesUseCase(repository)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeRagAnswer(
        answer: String = "This is the answer.",
        sourceCount: Int = 2,
    ) = RagAnswer(
        answer = answer,
        sources = List(sourceCount) { i ->
            RagCitationSource(
                documentName = "doc_$i.pdf",
                pageNumber = i + 1,
                excerpt = "Relevant excerpt from page ${i + 1}.",
                score = 0.9f - i * 0.1f,
            )
        },
        requestId = UUID.randomUUID().toString(),
    )

    // ── Blank / empty question ────────────────────────────────────────────────

    @Test
    fun `blank question returns ValidationError without calling repository`() = runTest {
        val result = useCase(question = "")
        assertTrue(result is ApiResult.Error)
        val error = (result as ApiResult.Error).error
        assertTrue(error is DomainError.ValidationError)
        coVerify(exactly = 0) { repository.ragQuery(any(), any(), any()) }
    }

    @Test
    fun `whitespace-only question returns ValidationError without calling repository`() = runTest {
        val result = useCase(question = "   \t\n  ")
        assertTrue(result is ApiResult.Error)
        assertTrue((result as ApiResult.Error).error is DomainError.ValidationError)
        coVerify(exactly = 0) { repository.ragQuery(any(), any(), any()) }
    }

    @Test
    fun `ValidationError has question field in fields map`() = runTest {
        val result = useCase(question = "")
        val error = (result as ApiResult.Error).error as DomainError.ValidationError
        assertTrue(error.fields.containsKey(QueryDocumentWithSourcesUseCase.FIELD_QUESTION))
    }

    // ── Successful delegation ─────────────────────────────────────────────────

    @Test
    fun `valid question delegates to repository with trimmed text`() = runTest {
        val expected = makeRagAnswer()
        coEvery { repository.ragQuery(any(), any(), any()) } returns ApiResult.Success(expected)

        useCase(question = "  What is the revenue? ")

        coVerify(exactly = 1) { repository.ragQuery("What is the revenue?", any(), any()) }
    }

    @Test
    fun `null documentIds passed through to repository`() = runTest {
        coEvery { repository.ragQuery(any(), any(), any()) } returns ApiResult.Success(makeRagAnswer())

        useCase(question = "How much?", documentIds = null)

        coVerify { repository.ragQuery(any(), null, any()) }
    }

    @Test
    fun `empty documentIds list normalized to null`() = runTest {
        coEvery { repository.ragQuery(any(), any(), any()) } returns ApiResult.Success(makeRagAnswer())

        useCase(question = "How much?", documentIds = emptyList())

        coVerify { repository.ragQuery(any(), null, any()) }
    }

    @Test
    fun `non-empty documentIds passed through unchanged`() = runTest {
        val ids = listOf("doc-1", "doc-2")
        coEvery { repository.ragQuery(any(), any(), any()) } returns ApiResult.Success(makeRagAnswer())

        useCase(question = "How much?", documentIds = ids)

        coVerify { repository.ragQuery(any(), ids, any()) }
    }

    @Test
    fun `topK parameter forwarded to repository`() = runTest {
        coEvery { repository.ragQuery(any(), any(), any()) } returns ApiResult.Success(makeRagAnswer())

        useCase(question = "Q?", topK = 10)

        coVerify { repository.ragQuery(any(), any(), 10) }
    }

    @Test
    fun `default topK is 5`() = runTest {
        coEvery { repository.ragQuery(any(), any(), any()) } returns ApiResult.Success(makeRagAnswer())

        useCase(question = "Q?")

        coVerify { repository.ragQuery(any(), any(), 5) }
    }

    // ── Result pass-through ───────────────────────────────────────────────────

    @Test
    fun `success result passed through with all fields intact`() = runTest {
        val expected = makeRagAnswer("Detailed answer.", sourceCount = 3)
        coEvery { repository.ragQuery(any(), any(), any()) } returns ApiResult.Success(expected)

        val result = useCase(question = "Tell me about X")

        assertTrue(result is ApiResult.Success)
        val answer = (result as ApiResult.Success).data
        assertEquals("Detailed answer.", answer.answer)
        assertEquals(3, answer.sources.size)
        assertEquals("doc_0.pdf", answer.sources[0].documentName)
        assertEquals(1, answer.sources[0].pageNumber)
        assertEquals("Relevant excerpt from page 1.", answer.sources[0].excerpt)
        assertEquals(0.9f, answer.sources[0].score, 0.001f)
        assertTrue(answer.hasSources)
    }

    @Test
    fun `error result passed through unchanged`() = runTest {
        val domainError = DomainError.ServerError("Backend failure", httpStatusCode = 500)
        coEvery { repository.ragQuery(any(), any(), any()) } returns ApiResult.Error(domainError)

        val result = useCase(question = "Q?")

        assertTrue(result is ApiResult.Error)
        assertEquals(domainError, (result as ApiResult.Error).error)
    }

    @Test
    fun `network unavailable passed through`() = runTest {
        coEvery { repository.ragQuery(any(), any(), any()) } returns ApiResult.NetworkUnavailable

        val result = useCase(question = "Q?")

        assertTrue(result is ApiResult.NetworkUnavailable)
    }

    // ── RagAnswer model ───────────────────────────────────────────────────────

    @Test
    fun `RagAnswer hasSources is true when sources are present`() {
        val answer = makeRagAnswer(sourceCount = 1)
        assertTrue(answer.hasSources)
    }

    @Test
    fun `RagAnswer hasSources is false when sources are empty`() {
        val answer = makeRagAnswer(sourceCount = 0)
        assertFalse(answer.hasSources)
    }
}
