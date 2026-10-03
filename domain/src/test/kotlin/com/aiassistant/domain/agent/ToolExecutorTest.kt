/*
 * Domain unit tests for ToolExecutor interface and its value objects:
 * ToolExecutionRequest, ToolExecutionResponse.
 *
 * Also covers the full security pipeline enforced by the executor:
 * lookup → validate → permission check → execute.
 */
package com.aiassistant.domain.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun stubTool(
    name: String,
    vararg permissions: ToolPermission,
    output: String = "result",
    throwValidation: Boolean = false,
) = object : Tool {
    override val schema = ToolSchema(
        name = name,
        displayName = name,
        description = "stub",
        requiredPermissions = setOf(*permissions),
    )
    override fun validate(args: Map<String, String>) {
        if (throwValidation) throw ToolValidationError(name, "expr", "invalid")
    }
    override suspend fun execute(args: Map<String, String>, userId: String) =
        ToolResult(toolName = name, success = true, output = output)
}

// ── Minimal stub executor implementing the full security pipeline ─────────────

private class DefaultExecutor(tools: List<Tool> = emptyList()) : ToolExecutor {
    private val registry: DefaultToolRegistry = DefaultToolRegistry().also {
        tools.forEach { t -> it.register(t) }
    }

    override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResponse {
        val tool = registry.getOrNull(request.toolName)
            ?: return ToolExecutionResponse.failure(request.toolName, "Tool not found.")

        // Step 1: Validate arguments
        try {
            tool.validate(request.args)
        } catch (e: ToolValidationError) {
            return ToolExecutionResponse.failure(request.toolName, e.message ?: "Validation failed.")
        }

        // Step 2: Permission check
        val missing = tool.schema.requiredPermissions - request.callerPermissions
        if (missing.isNotEmpty()) {
            return ToolExecutionResponse.failure(
                request.toolName,
                "Permission denied: ${missing.first()} required.",
            )
        }

        // Step 3: Execute
        val result = tool.execute(request.args, request.userId)
        return ToolExecutionResponse(
            toolName = result.toolName,
            success = result.success,
            output = result.output ?: "",
            error = result.error ?: "",
        )
    }

    override fun isAvailable(toolName: String) = registry.getOrNull(toolName) != null
    override fun listTools() = registry.list().map { it.schema.name }.sorted()
}

// ── Tests ─────────────────────────────────────────────────────────────────────

class ToolExecutorTest {

    // ── ToolExecutionRequest ──────────────────────────────────────────────────

    @Test fun `ToolExecutionRequest valid construction`() {
        val r = ToolExecutionRequest(
            toolName = "calculator",
            args = mapOf("expression" to "1+1"),
            userId = "u1",
        )
        r.toolName shouldBe "calculator"
        r.timeoutMs shouldBe 0L
    }

    @Test fun `ToolExecutionRequest blank toolName raises`() {
        shouldThrow<IllegalArgumentException> {
            ToolExecutionRequest(toolName = "  ", args = emptyMap(), userId = "u1")
        }
    }

    @Test fun `ToolExecutionRequest blank userId raises`() {
        shouldThrow<IllegalArgumentException> {
            ToolExecutionRequest(toolName = "calc", args = emptyMap(), userId = "")
        }
    }

    @Test fun `ToolExecutionRequest negative timeoutMs raises`() {
        shouldThrow<IllegalArgumentException> {
            ToolExecutionRequest(
                toolName = "calc", args = emptyMap(),
                userId = "u1", timeoutMs = -1L,
            )
        }
    }

    @Test fun `ToolExecutionRequest zero timeoutMs is allowed`() {
        val r = ToolExecutionRequest(toolName = "calc", args = emptyMap(), userId = "u1", timeoutMs = 0L)
        r.timeoutMs shouldBe 0L
    }

    // ── ToolExecutionResponse ─────────────────────────────────────────────────

    @Test fun `ToolExecutionResponse success result`() {
        val r = ToolExecutionResponse(toolName = "calc", success = true, output = "42")
        r.success.shouldBeTrue()
        r.isEmpty.shouldBeFalse()
    }

    @Test fun `ToolExecutionResponse isEmpty when output blank`() {
        ToolExecutionResponse(toolName = "t", success = false).isEmpty.shouldBeTrue()
        ToolExecutionResponse(toolName = "t", success = true, output = "x").isEmpty.shouldBeFalse()
    }

    @Test fun `ToolExecutionResponse failure factory`() {
        val r = ToolExecutionResponse.failure("calc", "division by zero", durationMs = 10L)
        r.success.shouldBeFalse()
        r.error shouldBe "division by zero"
        r.output shouldBe ""
        r.durationMs shouldBe 10L
    }

    @Test fun `ToolExecutionResponse failure factory empty output`() {
        val r = ToolExecutionResponse.failure("t", "err")
        r.isEmpty.shouldBeTrue()
    }

    // ── Interface security pipeline ───────────────────────────────────────────

    @Test fun `execute returns failure for unknown tool`() = runTest {
        val r = DefaultExecutor().execute(
            ToolExecutionRequest("unknown", emptyMap(), "u1")
        )
        r.success.shouldBeFalse()
        r.error shouldBe "Tool not found."
    }

    @Test fun `execute returns failure when validation throws`() = runTest {
        val exec = DefaultExecutor(listOf(stubTool("calc", throwValidation = true)))
        val r = exec.execute(
            ToolExecutionRequest(
                toolName = "calc",
                args = mapOf("expression" to "bad"),
                userId = "u1",
                callerPermissions = emptySet(),
            )
        )
        r.success.shouldBeFalse()
        r.error.isNotBlank().shouldBeTrue()
    }

    @Test fun `execute returns failure when caller lacks required permission`() = runTest {
        val exec = DefaultExecutor(listOf(stubTool("net", ToolPermission.NETWORK_ACCESS)))
        val r = exec.execute(
            ToolExecutionRequest(
                toolName = "net",
                args = emptyMap(),
                userId = "u1",
                callerPermissions = setOf(ToolPermission.COMPUTE), // missing NETWORK_ACCESS
            )
        )
        r.success.shouldBeFalse()
        r.error.contains("Permission denied").shouldBeTrue()
    }

    @Test fun `execute succeeds with correct permissions`() = runTest {
        val exec = DefaultExecutor(listOf(stubTool("calc", ToolPermission.COMPUTE, output = "42")))
        val r = exec.execute(
            ToolExecutionRequest(
                toolName = "calc",
                args = mapOf("expression" to "6*7"),
                userId = "u1",
                callerPermissions = setOf(ToolPermission.COMPUTE),
            )
        )
        r.success.shouldBeTrue()
        r.output shouldBe "42"
    }

    @Test fun `execute succeeds with no required permissions`() = runTest {
        val exec = DefaultExecutor(listOf(stubTool("date", output = "2026-10-01")))
        val r = exec.execute(
            ToolExecutionRequest("date", emptyMap(), "u1", callerPermissions = emptySet())
        )
        r.success.shouldBeTrue()
        r.output shouldBe "2026-10-01"
    }

    @Test fun `execute never throws — errors returned as response`() = runTest {
        val exec = DefaultExecutor()
        val r = exec.execute(ToolExecutionRequest("boom", emptyMap(), "u1"))
        r.success.shouldBeFalse()
        // No exception was thrown
    }

    // ── isAvailable and listTools ─────────────────────────────────────────────

    @Test fun `isAvailable returns true for registered tool`() {
        DefaultExecutor(listOf(stubTool("calc")))
            .isAvailable("calc").shouldBeTrue()
    }

    @Test fun `isAvailable returns false for unregistered tool`() {
        DefaultExecutor().isAvailable("calc").shouldBeFalse()
    }

    @Test fun `listTools returns sorted tool names`() {
        val exec = DefaultExecutor(listOf(stubTool("c"), stubTool("a"), stubTool("b")))
        exec.listTools() shouldBe listOf("a", "b", "c")
    }

    @Test fun `listTools empty when no tools`() {
        DefaultExecutor().listTools().shouldBeEmpty()
    }

    @Test fun `stub satisfies interface at compile time`() {
        val ex: ToolExecutor = DefaultExecutor()
        ex shouldBe ex
    }
}
