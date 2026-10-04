/*
 * Domain unit tests for MCPClient interface and its value objects:
 * MCPInvocationResult, MCPClientException.
 */
package com.aiassistant.domain.agent

import com.aiassistant.domain.model.MCPTool
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

// ── Minimal stub ──────────────────────────────────────────────────────────────

private class FakeMCPClient(
    private val tools: List<MCPTool> = emptyList(),
    private val resultByTool: Map<String, MCPInvocationResult> = emptyMap(),
) : MCPClient {

    override suspend fun discoverTools(): List<MCPTool> = tools

    override suspend fun invoke(
        toolName: String,
        params: Map<String, String>,
        userId: String,
        confirmed: Boolean,
    ): MCPInvocationResult {
        val registered = tools.firstOrNull { it.name == toolName }
            ?: return MCPInvocationResult(toolName = toolName, success = false, error = "Tool not found.")

        if (registered.requiresConfirmation && !confirmed) {
            return MCPInvocationResult(
                toolName = toolName,
                success = false,
                requiresConfirmation = true,
            )
        }

        return resultByTool[toolName]
            ?: MCPInvocationResult(toolName = toolName, success = true, output = "ok")
    }

    override fun isRegistered(toolName: String): Boolean =
        tools.any { it.name == toolName }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun tool(
    name: String,
    requiresConfirmation: Boolean = false,
    isAvailable: Boolean = true,
) = MCPTool(
    name = name,
    displayName = name.replaceFirstChar { it.uppercase() },
    description = "$name connector",
    requiresConfirmation = requiresConfirmation,
    isAvailable = isAvailable,
)

// ── Tests ─────────────────────────────────────────────────────────────────────

class MCPClientTest {

    // ── MCPInvocationResult ───────────────────────────────────────────────────

    @Test fun `MCPInvocationResult successful result`() {
        val r = MCPInvocationResult(toolName = "github", success = true, output = "Issue created")
        r.success.shouldBeTrue()
        r.isEmpty.shouldBeFalse()
        r.requiresConfirmation.shouldBeFalse()
    }

    @Test fun `MCPInvocationResult failure result`() {
        val r = MCPInvocationResult(toolName = "github", success = false, error = "Auth failed")
        r.success.shouldBeFalse()
        r.error shouldBe "Auth failed"
    }

    @Test fun `MCPInvocationResult isEmpty when output blank`() {
        MCPInvocationResult(toolName = "t", success = false).isEmpty.shouldBeTrue()
        MCPInvocationResult(toolName = "t", success = true, output = "data").isEmpty.shouldBeFalse()
    }

    @Test fun `MCPInvocationResult requiresConfirmation flag`() {
        val r = MCPInvocationResult(toolName = "gmail", success = false, requiresConfirmation = true)
        r.requiresConfirmation.shouldBeTrue()
    }

    @Test fun `MCPInvocationResult stores metadata`() {
        val r = MCPInvocationResult(
            toolName = "github",
            success = true,
            output = "ok",
            metadata = mapOf("issueUrl" to "https://github.com/issues/1"),
        )
        r.metadata["issueUrl"] shouldBe "https://github.com/issues/1"
    }

    // ── MCPClientException ────────────────────────────────────────────────────

    @Test fun `MCPClientException carries retryable flag`() {
        val e = MCPClientException("network error", retryable = true)
        e.retryable.shouldBeTrue()
        e.message shouldBe "network error"
    }

    @Test fun `MCPClientException not retryable by default`() {
        MCPClientException("server error").retryable.shouldBeFalse()
    }

    @Test fun `MCPClientException preserves cause`() {
        val cause = RuntimeException("root")
        val e = MCPClientException("wrapper", cause = cause)
        e.cause shouldBe cause
    }

    // ── Interface contract ────────────────────────────────────────────────────

    @Test fun `discoverTools returns empty list when no tools registered`() = runTest {
        FakeMCPClient().discoverTools().shouldBeEmpty()
    }

    @Test fun `discoverTools returns all registered tools`() = runTest {
        val tools = listOf(tool("github"), tool("gmail"), tool("slack"))
        FakeMCPClient(tools).discoverTools() shouldHaveSize 3
    }

    @Test fun `isRegistered returns true for known tool`() {
        FakeMCPClient(listOf(tool("github"))).isRegistered("github").shouldBeTrue()
    }

    @Test fun `isRegistered returns false for unknown tool`() {
        FakeMCPClient(listOf(tool("github"))).isRegistered("jira").shouldBeFalse()
    }

    @Test fun `invoke unknown tool returns failure result`() = runTest {
        val r = FakeMCPClient().invoke("unknown", emptyMap(), "u1")
        r.success.shouldBeFalse()
        r.error shouldBe "Tool not found."
    }

    @Test fun `invoke tool that requires confirmation without confirmed flag`() = runTest {
        val tools = listOf(tool("gmail", requiresConfirmation = true))
        val r = FakeMCPClient(tools).invoke("gmail", emptyMap(), "u1", confirmed = false)
        r.success.shouldBeFalse()
        r.requiresConfirmation.shouldBeTrue()
    }

    @Test fun `invoke tool that requires confirmation with confirmed true executes`() = runTest {
        val tools = listOf(tool("gmail", requiresConfirmation = true))
        val r = FakeMCPClient(tools).invoke("gmail", emptyMap(), "u1", confirmed = true)
        r.success.shouldBeTrue()
    }

    @Test fun `invoke tool without confirmation requirement executes directly`() = runTest {
        val tools = listOf(tool("slack", requiresConfirmation = false))
        val r = FakeMCPClient(tools).invoke("slack", mapOf("message" to "hi"), "u1")
        r.success.shouldBeTrue()
        r.toolName shouldBe "slack"
    }

    @Test fun `invoke returns preconfigured result when set`() = runTest {
        val tools = listOf(tool("jira"))
        val expected = MCPInvocationResult(
            toolName = "jira",
            success = true,
            output = "Ticket PROJ-42 created",
        )
        val r = FakeMCPClient(tools, mapOf("jira" to expected)).invoke("jira", emptyMap(), "u1")
        r.output shouldBe "Ticket PROJ-42 created"
    }

    @Test fun `MCPTool domain model preserves all fields`() {
        val t = tool("github", requiresConfirmation = true)
        t.name shouldBe "github"
        t.requiresConfirmation.shouldBeTrue()
        t.isAvailable.shouldBeTrue()
    }

    @Test fun `stub satisfies interface at compile time`() {
        val c: MCPClient = FakeMCPClient()
        c shouldBe c
    }
}
