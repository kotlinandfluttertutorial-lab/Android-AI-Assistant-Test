/*
 * Unit tests for WebSearchTool stub.
 */
package com.aiassistant.data.agent

import com.aiassistant.data.agent.tools.WebSearchTool
import com.aiassistant.domain.agent.ToolPermission
import com.aiassistant.domain.agent.ToolValidationError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WebSearchToolTest {

    private val tool = WebSearchTool()

    @Test fun `name is web_search`() = tool.schema.name shouldBe "web_search"
    @Test fun `requires NETWORK_ACCESS`() =
        (ToolPermission.NETWORK_ACCESS in tool.schema.requiredPermissions).shouldBeTrue()

    @Test fun `missing query throws validation error`() {
        shouldThrow<ToolValidationError> { tool.validate(emptyMap()) }
    }

    @Test fun `blank query throws validation error`() {
        shouldThrow<ToolValidationError> { tool.validate(mapOf("query" to "")) }
    }

    @Test fun `query too long throws validation error`() {
        shouldThrow<ToolValidationError> { tool.validate(mapOf("query" to "x".repeat(501))) }
    }

    @Test fun `invalid num_results non-integer throws`() {
        shouldThrow<ToolValidationError> {
            tool.validate(mapOf("query" to "test", "num_results" to "abc"))
        }
    }

    @Test fun `num_results out of range throws`() {
        shouldThrow<ToolValidationError> {
            tool.validate(mapOf("query" to "test", "num_results" to "11"))
        }
    }

    @Test fun `valid query validates without error`() {
        tool.validate(mapOf("query" to "Kotlin coroutines tutorial"))
    }

    @Test fun `stub returns failure result with clear message`() = runTest {
        val r = tool.execute(mapOf("query" to "latest news"), "u1")
        // Stub is not configured — success=false with informative error
        r.success shouldBe false
        r.error?.contains("not yet configured", ignoreCase = true).shouldBeTrue()
    }

    @Test fun `stub metadata carries query and stub flag`() = runTest {
        val r = tool.execute(mapOf("query" to "search this"), "u1")
        r.metadata["stub"] shouldBe "true"
        r.metadata["query"] shouldBe "search this"
    }
}
