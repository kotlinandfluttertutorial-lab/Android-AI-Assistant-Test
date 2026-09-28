/*
 * Data layer unit tests for ToolAgent — security pipeline, timeout, errors, permissions.
 */
package com.aiassistant.data.agent

import com.aiassistant.domain.agent.AgentEvent
import com.aiassistant.domain.agent.AgentExecution
import com.aiassistant.domain.agent.AgentRequest
import com.aiassistant.domain.agent.AgentStatus
import com.aiassistant.domain.agent.DefaultToolRegistry
import com.aiassistant.domain.agent.Tool
import com.aiassistant.domain.agent.ToolPermission
import com.aiassistant.domain.agent.ToolResult
import com.aiassistant.domain.agent.ToolSchema
import com.aiassistant.domain.agent.ToolValidationError
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class ToolAgentTest {

    private lateinit var registry: DefaultToolRegistry
    private lateinit var agent: ToolAgent

    @Before fun setUp() { registry = DefaultToolRegistry(); agent = ToolAgent(registry) }

    private fun req(
        toolName: String = "calculator",
        args: Map<String, String> = mapOf("tool_args.expression" to "2+2"),
        permissions: String = "COMPUTE",
        confirmed: Boolean = false,
    ) = AgentRequest(
        userId = "user-1",
        input = "run tool",
        metadata = buildMap {
            put(ToolAgent.METADATA_TOOL_NAME, toolName)
            put(ToolAgent.METADATA_PERMISSIONS, permissions)
            if (confirmed) put(ToolAgent.METADATA_CONFIRMED, "true")
            putAll(args)
        },
    )

    private fun exec(r: AgentRequest = req()) = AgentExecution(request = r, agentName = ToolAgent.NAME)

    private fun successTool(name: String, vararg perms: ToolPermission) = object : Tool {
        override val schema = ToolSchema(name = name, displayName = name, description = "ok",
            requiredPermissions = setOf(*perms))
        override fun validate(a: Map<String, String>) {}
        override suspend fun execute(a: Map<String, String>, u: String) =
            ToolResult(toolName = name, success = true, output = "result-$name")
    }

    private fun failTool(name: String) = object : Tool {
        override val schema = ToolSchema(name = name, displayName = name, description = "fails")
        override fun validate(a: Map<String, String>) {}
        override suspend fun execute(a: Map<String, String>, u: String) =
            ToolResult(toolName = name, success = false, error = "deliberate failure")
    }

    private fun confirmTool(name: String) = object : Tool {
        override val schema = ToolSchema(name = name, displayName = name, description = "write",
            requiresConfirmation = true)
        override fun validate(a: Map<String, String>) {}
        override suspend fun execute(a: Map<String, String>, u: String) =
            ToolResult(toolName = name, success = true, output = "written")
    }

    private fun timeoutTool(name: String) = object : Tool {
        override val schema = ToolSchema(name = name, displayName = name, description = "slow",
            timeoutMs = 50L)
        override fun validate(a: Map<String, String>) {}
        override suspend fun execute(a: Map<String, String>, u: String): ToolResult {
            delay(5_000L); return ToolResult(toolName = name, success = true, output = "never")
        }
    }

    private fun validationFailTool(name: String) = object : Tool {
        override val schema = ToolSchema(name = name, displayName = name, description = "strict")
        override fun validate(a: Map<String, String>) {
            throw ToolValidationError(name, "arg", "is required")
        }
        override suspend fun execute(a: Map<String, String>, u: String) =
            ToolResult(toolName = name, success = true, output = "ok")
    }

    // ── Capabilities / metadata ───────────────────────────────────────────────

    @Test fun `agent name`() { agent.name shouldBe "tool-executor" }
    @Test fun `declares TOOL_USE`() { (com.aiassistant.domain.agent.AgentCapability.TOOL_USE in agent.capabilities).shouldBeTrue() }

    // ── Missing tool name ─────────────────────────────────────────────────────

    @Test fun `missing tool_name emits MISSING_TOOL_NAME`() = runTest {
        val r = AgentRequest(userId = "u1", input = "x", metadata = emptyMap())
        val events = agent.execute(r, AgentExecution(request = r, agentName = ToolAgent.NAME)).toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().first()
        failed.result.error?.code shouldBe "MISSING_TOOL_NAME"
    }

    // ── Unknown tool ──────────────────────────────────────────────────────────

    @Test fun `unknown tool emits TOOL_NOT_FOUND`() = runTest {
        val events = agent.execute(req("unknown_tool"), exec()).toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().first()
        failed.result.error?.code shouldBe "TOOL_NOT_FOUND"
    }

    // ── Successful tool call ──────────────────────────────────────────────────

    @Test fun `successful tool call emits ToolStarted, ToolCompleted, Completed`() = runTest {
        registry.register(successTool("calculator", ToolPermission.COMPUTE))
        val events = agent.execute(req(), exec()).toList()
        events.filterIsInstance<AgentEvent.ToolStarted>().size shouldBe 1
        events.filterIsInstance<AgentEvent.ToolCompleted>().first().toolName shouldBe "calculator"
        val completed = events.filterIsInstance<AgentEvent.Completed>().first()
        completed.result.status shouldBe AgentStatus.COMPLETED
        completed.result.content shouldBe "result-calculator"
    }

    // ── Permission denied ─────────────────────────────────────────────────────

    @Test fun `missing permission emits PERMISSION_DENIED`() = runTest {
        registry.register(successTool("nettool", ToolPermission.NETWORK_ACCESS))
        // caller only grants COMPUTE — not NETWORK_ACCESS
        val events = agent.execute(req("nettool", permissions = "COMPUTE"), exec()).toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().first()
        failed.result.error?.code shouldBe "PERMISSION_DENIED"
    }

    @Test fun `correct permissions succeed`() = runTest {
        registry.register(successTool("nettool", ToolPermission.NETWORK_ACCESS))
        val events = agent.execute(req("nettool", permissions = "NETWORK_ACCESS"), exec()).toList()
        events.filterIsInstance<AgentEvent.Completed>().size shouldBe 1
    }

    // ── Invalid arguments ─────────────────────────────────────────────────────

    @Test fun `validation failure emits INVALID_ARGUMENTS`() = runTest {
        registry.register(validationFailTool("strict"))
        val events = agent.execute(req("strict"), exec()).toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().first()
        failed.result.error?.code shouldBe "INVALID_ARGUMENTS"
    }

    // ── Confirmation gate ─────────────────────────────────────────────────────

    @Test fun `unconfirmed write tool emits ToolConfirmationRequired`() = runTest {
        registry.register(confirmTool("writer"))
        val events = agent.execute(req("writer"), exec()).toList()
        events.filterIsInstance<AgentEvent.ToolConfirmationRequired>().size shouldBe 1
        // Result is PARTIAL (awaiting confirmation), not FAILED
        val completed = events.filterIsInstance<AgentEvent.Completed>().firstOrNull()
        completed?.result?.status shouldBe AgentStatus.PARTIAL
    }

    @Test fun `confirmed write tool executes successfully`() = runTest {
        registry.register(confirmTool("writer"))
        val events = agent.execute(req("writer", confirmed = true), exec()).toList()
        events.filterIsInstance<AgentEvent.Completed>().first().result.status shouldBe AgentStatus.COMPLETED
    }

    // ── Timeout ──────────────────────────────────────────────────────────────

    @Test fun `tool timeout emits TOOL_TIMEOUT`() = runTest {
        registry.register(timeoutTool("slow"))
        val events = agent.execute(req("slow"), exec()).toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().first()
        failed.result.error?.code shouldBe "TOOL_TIMEOUT"
    }

    // ── Tool error ────────────────────────────────────────────────────────────

    @Test fun `tool returning false success emits TOOL_ERROR`() = runTest {
        registry.register(failTool("failer"))
        val events = agent.execute(req("failer"), exec()).toList()
        events.filterIsInstance<AgentEvent.ToolFailed>().first().toolName shouldBe "failer"
        val failed = events.filterIsInstance<AgentEvent.Failed>().first()
        failed.result.error?.code shouldBe "TOOL_ERROR"
    }
}
