/*
 * Unit tests for DateTimeTool and DocumentSearchTool.
 */
package com.aiassistant.data.agent

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.data.agent.tools.DateTimeTool
import com.aiassistant.data.agent.tools.DocumentSearchTool
import com.aiassistant.domain.agent.ToolPermission
import com.aiassistant.domain.agent.ToolValidationError
import com.aiassistant.domain.repository.DocumentRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

// â”€â”€ DateTimeTool â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

class DateTimeToolTest {

    private val tool = DateTimeTool()

    @Test fun `name is datetime`() = tool.schema.name shouldBe "datetime"
    @Test fun `requires READ_DATETIME`() =
        (ToolPermission.READ_DATETIME in tool.schema.requiredPermissions).shouldBeTrue()

    @Test fun `iso format returns date-time string`() = runTest {
        val r = tool.execute(mapOf("format" to "iso"), "u1")
        r.success.shouldBeTrue()
        r.output.shouldNotBeNull()
        // ISO format contains 'T' separator
        r.output!!.contains("T").shouldBeTrue()
    }

    @Test fun `date format returns YYYY-MM-DD`() = runTest {
        val r = tool.execute(mapOf("format" to "date"), "u1")
        r.success.shouldBeTrue()
        // YYYY-MM-DD has exactly 2 hyphens
        r.output!!.count { it == '-' } shouldBe 2
    }

    @Test fun `timestamp format returns epoch millis`() = runTest {
        val r = tool.execute(mapOf("format" to "timestamp"), "u1")
        r.success.shouldBeTrue()
        r.output!!.toLongOrNull().shouldNotBeNull()
    }

    @Test fun `default format (no args) returns ISO`() = runTest {
        val r = tool.execute(emptyMap(), "u1")
        r.success.shouldBeTrue()
    }

    @Test fun `UTC timezone is valid`() = runTest {
        val r = tool.execute(mapOf("timezone" to "UTC"), "u1")
        r.success.shouldBeTrue()
    }

    @Test fun `invalid timezone emits validation error`() {
        shouldThrow<ToolValidationError> {
            tool.validate(mapOf("timezone" to "Not/A/Timezone"))
        }
    }

    @Test fun `metadata carries timezone and format`() = runTest {
        val r = tool.execute(mapOf("format" to "date", "timezone" to "UTC"), "u1")
        r.metadata["timezone"] shouldBe "UTC"
        r.metadata["format"] shouldBe "date"
    }
}

// â”€â”€ DocumentSearchTool â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

class DocumentSearchToolTest {

    private val documentRepository: DocumentRepository = mockk()
    private val tool = DocumentSearchTool(documentRepository)

    @Test fun `name is document_search`() = tool.schema.name shouldBe "document_search"
    @Test fun `requires READ_DOCUMENTS`() =
        (ToolPermission.READ_DOCUMENTS in tool.schema.requiredPermissions).shouldBeTrue()

    // â”€â”€ Validation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test fun `missing document_id throws`() {
        shouldThrow<ToolValidationError> {
            tool.validate(mapOf("query" to "hello"))
        }
    }

    @Test fun `blank document_id throws`() {
        shouldThrow<ToolValidationError> {
            tool.validate(mapOf("document_id" to "  ", "query" to "hello"))
        }
    }

    @Test fun `missing query throws`() {
        shouldThrow<ToolValidationError> {
            tool.validate(mapOf("document_id" to "doc-1"))
        }
    }

    @Test fun `blank query throws`() {
        shouldThrow<ToolValidationError> {
            tool.validate(mapOf("document_id" to "doc-1", "query" to ""))
        }
    }

    @Test fun `query exceeding max length throws`() {
        shouldThrow<ToolValidationError> {
            tool.validate(mapOf("document_id" to "doc-1", "query" to "x".repeat(2001)))
        }
    }

    // â”€â”€ Execution â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test fun `successful query returns answer`() = runTest {
        coEvery { documentRepository.queryDocument("doc-1", "What is this?") } returns
            ApiResult.Success("It is a document.")
        val r = tool.execute(mapOf("document_id" to "doc-1", "query" to "What is this?"), "u1")
        r.success.shouldBeTrue()
        r.output shouldBe "It is a document."
    }

    @Test fun `repository error returns failure result`() = runTest {
        coEvery { documentRepository.queryDocument(any(), any()) } returns
            ApiResult.Error(DomainError.ServerError(httpStatusCode = 500))
        val r = tool.execute(mapOf("document_id" to "doc-1", "query" to "q"), "u1")
        r.success shouldBe false
    }

    @Test fun `network unavailable returns failure result`() = runTest {
        coEvery { documentRepository.queryDocument(any(), any()) } returns
            ApiResult.NetworkUnavailable
        val r = tool.execute(mapOf("document_id" to "doc-1", "query" to "q"), "u1")
        r.success shouldBe false
        r.error!!.contains("network", ignoreCase = true).shouldBeTrue()
    }

    @Test fun `document_id forwarded in metadata`() = runTest {
        coEvery { documentRepository.queryDocument("my-doc", any()) } returns
            ApiResult.Success("answer")
        val r = tool.execute(mapOf("document_id" to "my-doc", "query" to "q"), "u1")
        r.metadata["document_id"] shouldBe "my-doc"
    }
}
