/*
 * Unit tests for the data/mcp package.
 *
 * Covers: MCPToolModel, MCPParamDescriptor, MCPParamType, error types,
 * DefaultMCPRegistry, DefaultMCPValidator, DefaultMCPExecutor, MCPServer.
 *
 * All dependencies (MCPClient) are faked — no network, no production credentials.
 */
package com.aiassistant.data.mcp

import com.aiassistant.domain.agent.MCPClient
import com.aiassistant.domain.agent.MCPInvocationResult
import com.aiassistant.domain.model.MCPTool
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

// ── Fakes ─────────────────────────────────────────────────────────────────────

private class FakeMCPClient(
    private val tools: List<MCPTool> = emptyList(),
    private val result: MCPInvocationResult? = null,
    private val throwOnInvoke: Boolean = false,
    private val throwOnDiscover: Boolean = false,
) : MCPClient {
    var invokeCallCount = 0
    var lastInvokedTool: String = ""

    override suspend fun discoverTools(): List<MCPTool> {
        if (throwOnDiscover) throw RuntimeException("network failure")
        return tools
    }

    override suspend fun invoke(
        toolName: String,
        params: Map<String, String>,
        userId: String,
        confirmed: Boolean,
    ): MCPInvocationResult {
        if (throwOnInvoke) throw RuntimeException("backend error")
        invokeCallCount++
        lastInvokedTool = toolName
        return result ?: MCPInvocationResult(
            toolName = toolName,
            success = true,
            output = "ok",
        )
    }

    override fun isRegistered(toolName: String): Boolean =
        tools.any { it.name == toolName }
}

private fun tool(
    name: String = "echo",
    displayName: String = "Echo",
    description: String = "Echoes params.",
    requiresConfirmation: Boolean = false,
) = MCPTool(name, displayName, description, requiresConfirmation)

private fun model(
    name: String = "echo",
    displayName: String = "Echo",
    description: String = "Echoes.",
    requiresConfirmation: Boolean = false,
    timeoutMs: Long = 5_000L,
    vararg params: MCPParamDescriptor,
) = MCPToolModel(
    name = name,
    displayName = displayName,
    description = description,
    paramDescriptors = params.toList(),
    requiresConfirmation = requiresConfirmation,
    timeoutMs = timeoutMs,
)

private fun param(
    name: String = "message",
    required: Boolean = false,
    allowed: List<String> = emptyList(),
) = MCPParamDescriptor(name = name, required = required, allowedValues = allowed)

private const val USER = "user-1"

// ── MCPParamDescriptor ────────────────────────────────────────────────────────

class MCPParamDescriptorTest {

    @Test fun `valid construction`() {
        val p = MCPParamDescriptor(name = "query", type = MCPParamType.STRING, required = true)
        p.name shouldBe "query"
        p.required.shouldBeTrue()
    }

    @Test fun `blank name raises`() {
        assertThrows<IllegalArgumentException> { MCPParamDescriptor(name = "  ") }
    }

    @Test fun `default type is STRING`() {
        MCPParamDescriptor(name = "x").type shouldBe MCPParamType.STRING
    }
}

// ── MCPToolModel ──────────────────────────────────────────────────────────────

class MCPToolModelTest {

    @Test fun `valid construction`() {
        val m = model()
        m.name shouldBe "echo"
    }

    @Test fun `blank name raises`() {
        assertThrows<IllegalArgumentException> {
            MCPToolModel(name = "  ", displayName = "E", description = "D")
        }
    }

    @Test fun `negative timeout raises`() {
        assertThrows<IllegalArgumentException> {
            MCPToolModel(name = "t", displayName = "T", description = "D", timeoutMs = -1L)
        }
    }

    @Test fun `zero timeout allowed`() {
        MCPToolModel(name = "t", displayName = "T", description = "D", timeoutMs = 0L).timeoutMs shouldBe 0L
    }

    @Test fun `requiredParams returns only required param names`() {
        val m = model(
            params = arrayOf(
                MCPParamDescriptor("owner", required = true),
                MCPParamDescriptor("repo", required = true),
                MCPParamDescriptor("limit", required = false),
            )
        )
        m.requiredParams shouldBe listOf("owner", "repo")
    }

    @Test fun `paramNames returns all param names`() {
        val m = model(params = arrayOf(MCPParamDescriptor("a"), MCPParamDescriptor("b")))
        m.paramNames.sorted() shouldBe listOf("a", "b")
    }

    @Test fun `toMCPTool converts correctly`() {
        val m = model(name = "github", requiresConfirmation = true)
        val t = m.toMCPTool()
        t.name shouldBe "github"
        t.requiresConfirmation.shouldBeTrue()
        t.isAvailable.shouldBeTrue()
    }

    @Test fun `fromMCPTool builds model from domain entity`() {
        val t = tool(name = "slack", requiresConfirmation = false)
        val m = MCPToolModel.fromMCPTool(t)
        m.name shouldBe "slack"
        m.paramDescriptors.shouldBeEmpty()
    }
}

// ── Error types ───────────────────────────────────────────────────────────────

class MCPErrorTypesTest {

    @Test fun `MCPValidationException with param name`() {
        val e = MCPValidationException("github", "action", "is required")
        e.toolName shouldBe "github"
        e.paramName shouldBe "action"
        e.message!! shouldContain "action"
        e.message!! shouldContain "is required"
    }

    @Test fun `MCPValidationException without param name`() {
        val e = MCPValidationException("tool", null, "bad input")
        (e.paramName == null).shouldBeTrue()
        e.message!! shouldContain "bad input"
    }

    @Test fun `MCPExecutionException attributes`() {
        val e = MCPExecutionException("tool", "Something failed", retryable = true)
        e.safeMessage shouldBe "Something failed"
        e.retryable.shouldBeTrue()
    }

    @Test fun `MCPTimeoutException inherits MCPExecutionException`() {
        val e = MCPTimeoutException("tool", 5000L)
        (e is MCPExecutionException).shouldBeTrue()
        e.timeoutMs shouldBe 5000L
        e.retryable.shouldBeTrue()
        e.message!! shouldContain "5000"
    }
}

// ── DefaultMCPRegistry ────────────────────────────────────────────────────────

class DefaultMCPRegistryTest {

    private fun registry(
        tools: List<MCPTool> = emptyList(),
        allowed: Set<String>? = null,
        strict: Boolean = false,
    ) = DefaultMCPRegistry(
        client = FakeMCPClient(tools = tools),
        allowedTools = allowed,
        strictDuplicates = strict,
    )

    // ── Registration ─────────────────────────────────────────────────────────

    @Test fun `register adds model`() {
        val r = registry()
        r.register(model("echo"))
        r.isRegistered("echo").shouldBeTrue()
    }

    @Test fun `register replaces duplicate by default`() {
        val r = registry()
        r.register(model("echo", description = "v1"))
        r.register(model("echo", description = "v2"))
        r.getModel("echo")?.description shouldBe "v2"
    }

    @Test fun `register duplicate raises in strict mode`() {
        val r = registry(strict = true)
        r.register(model("echo"))
        assertThrows<IllegalStateException> { r.register(model("echo")) }
    }

    @Test fun `unregister removes model`() {
        val r = registry()
        r.register(model("echo"))
        r.unregister("echo").shouldBeTrue()
        r.isRegistered("echo").shouldBeFalse()
    }

    @Test fun `unregister absent returns false`() {
        registry().unregister("ghost").shouldBeFalse()
    }

    @Test fun `size reflects registrations`() {
        val r = registry()
        r.size shouldBe 0
        r.register(model("a"))
        r.register(model("b"))
        r.size shouldBe 2
    }

    // ── Allowlist ─────────────────────────────────────────────────────────────

    @Test fun `null allowlist allows all`() {
        val r = registry(allowed = null)
        r.register(model("echo"))
        r.isAllowed("echo").shouldBeTrue()
    }

    @Test fun `wildcard allowlist allows all`() {
        val r = registry(allowed = setOf("*"))
        r.register(model("echo"))
        r.isAllowed("echo").shouldBeTrue()
    }

    @Test fun `explicit allowlist restricts tools`() {
        val r = registry(allowed = setOf("echo"))
        r.register(model("echo"))
        r.register(model("slack"))
        r.isAllowed("echo").shouldBeTrue()
        r.isAllowed("slack").shouldBeFalse()
    }

    @Test fun `isAllowed returns false for unregistered tool`() {
        registry().isAllowed("ghost").shouldBeFalse()
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    @Test fun `refresh adds tools from backend`() = runTest {
        val r = DefaultMCPRegistry(
            client = FakeMCPClient(tools = listOf(tool("github"), tool("slack"))),
        )
        r.refresh()
        r.isRegistered("github").shouldBeTrue()
        r.isRegistered("slack").shouldBeTrue()
    }

    @Test fun `refresh gracefully handles backend failure`() = runTest {
        val r = DefaultMCPRegistry(
            client = FakeMCPClient(throwOnDiscover = true),
        )
        r.register(model("local"))
        r.refresh()  // must not throw
        r.isRegistered("local").shouldBeTrue()
    }

    @Test fun `refresh respects allowlist`() = runTest {
        val r = DefaultMCPRegistry(
            client = FakeMCPClient(tools = listOf(tool("github"), tool("slack"))),
            allowedTools = setOf("github"),
        )
        r.refresh()
        r.isRegistered("github").shouldBeTrue()
        r.isRegistered("slack").shouldBeFalse()
    }

    // ── Listing ───────────────────────────────────────────────────────────────

    @Test fun `listAll returns all registered names sorted`() {
        val r = registry()
        r.register(model("z"))
        r.register(model("a"))
        r.register(model("m"))
        r.listAll() shouldBe listOf("a", "m", "z")
    }

    @Test fun `listAllowed filters by allowlist`() {
        val r = registry(allowed = setOf("a", "m"))
        r.register(model("a"))
        r.register(model("m"))
        r.register(model("z"))
        r.listAllowed() shouldBe listOf("a", "m")
    }
}

// ── DefaultMCPValidator ───────────────────────────────────────────────────────

class DefaultMCPValidatorTest {

    private val v = DefaultMCPValidator()

    // ── Required params ───────────────────────────────────────────────────────

    @Test fun `required param present passes`() {
        val m = model(params = arrayOf(MCPParamDescriptor("action", required = true)))
        v.validate(mapOf("action" to "list"), m)  // must not throw
    }

    @Test fun `required param absent raises`() {
        val m = model(params = arrayOf(MCPParamDescriptor("action", required = true)))
        val e = assertThrows<MCPValidationException> { v.validate(emptyMap(), m) }
        e.paramName shouldBe "action"
        e.reason shouldContain "required"
    }

    @Test fun `optional param absent passes`() {
        val m = model(params = arrayOf(MCPParamDescriptor("limit", required = false)))
        v.validate(emptyMap(), m)  // must not throw
    }

    // ── Allowed values ────────────────────────────────────────────────────────

    @Test fun `allowed value passes`() {
        val m = model(params = arrayOf(MCPParamDescriptor("action", allowedValues = listOf("read", "write"))))
        v.validate(mapOf("action" to "read"), m)  // must not throw
    }

    @Test fun `disallowed value raises`() {
        val m = model(params = arrayOf(MCPParamDescriptor("action", allowedValues = listOf("read", "write"))))
        val e = assertThrows<MCPValidationException> { v.validate(mapOf("action" to "delete"), m) }
        e.paramName shouldBe "action"
        e.reason shouldContain "delete"
    }

    // ── validateResult ────────────────────────────────────────────────────────

    @Test fun `successful result passes`() {
        val r = MCPInvocationResult(toolName = "echo", success = true, output = "ok")
        v.validateResult(r, "echo")  // must not throw
    }

    @Test fun `failure with error message passes`() {
        val r = MCPInvocationResult(toolName = "echo", success = false, error = "Not found")
        v.validateResult(r, "echo")  // must not throw
    }

    @Test fun `failure without error message raises`() {
        val r = MCPInvocationResult(toolName = "echo", success = false, error = "")
        assertThrows<MCPValidationException> { v.validateResult(r, "echo") }
    }

    @Test fun `confirmation required result passes`() {
        val r = MCPInvocationResult(toolName = "echo", success = false, requiresConfirmation = true)
        v.validateResult(r, "echo")  // must not throw — confirmation is expected
    }
}

// ── DefaultMCPExecutor ────────────────────────────────────────────────────────

class DefaultMCPExecutorTest {

    private fun executor(
        tools: List<MCPTool> = emptyList(),
        models: List<MCPToolModel> = emptyList(),
        allowed: Set<String>? = null,
        clientResult: MCPInvocationResult? = null,
        throwOnInvoke: Boolean = false,
    ): DefaultMCPExecutor {
        val client = FakeMCPClient(tools = tools, result = clientResult, throwOnInvoke = throwOnInvoke)
        val registry = DefaultMCPRegistry(client = client, allowedTools = allowed)
        models.forEach { registry.register(it) }
        return DefaultMCPExecutor(registry = registry, client = client)
    }

    // ── Allowlist ─────────────────────────────────────────────────────────────

    @Test fun `allowlist rejection returns failure`() = runTest {
        val ex = executor(models = listOf(model("echo")), allowed = setOf("slack"))
        val r = ex.execute("echo", mapOf("message" to "hi"), USER)
        r.invocationResult.success.shouldBeFalse()
        r.invocationResult.error shouldContain "allowlist"
    }

    @Test fun `allowlist rejection does not expose internal details`() = runTest {
        val ex = executor(models = listOf(model("echo")), allowed = setOf("slack"))
        val r = ex.execute("echo", emptyMap(), USER)
        r.invocationResult.error shouldNotContain "Traceback"
        r.invocationResult.error shouldNotContain "Exception"
    }

    @Test fun `allowlist rejection reraises when configured`() = runTest {
        val client = FakeMCPClient()
        val registry = DefaultMCPRegistry(client = client, allowedTools = setOf("slack"))
        registry.register(model("echo"))
        val ex = DefaultMCPExecutor(registry = registry, client = client)
        assertThrows<MCPExecutionException> {
            ex.execute("echo", emptyMap(), USER, reraiseErrors = true)
        }
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test fun `missing required param returns failure`() = runTest {
        val m = model("echo", params = arrayOf(MCPParamDescriptor("message", required = true)))
        val ex = executor(models = listOf(m))
        val r = ex.execute("echo", emptyMap(), USER)
        r.invocationResult.success.shouldBeFalse()
        r.validationPassed.shouldBeFalse()
    }

    @Test fun `valid params reach client`() = runTest {
        val client = FakeMCPClient(
            result = MCPInvocationResult(toolName = "echo", success = true, output = "ok")
        )
        val registry = DefaultMCPRegistry(client = client)
        registry.register(model("echo", params = arrayOf(MCPParamDescriptor("message", required = true))))
        val ex = DefaultMCPExecutor(registry = registry, client = client)
        val r = ex.execute("echo", mapOf("message" to "hello"), USER)
        r.invocationResult.success.shouldBeTrue()
        client.invokeCallCount shouldBe 1
    }

    // ── Confirmation gate ─────────────────────────────────────────────────────

    @Test fun `unconfirmed write tool returns requiresConfirmation`() = runTest {
        val m = model("github_write", requiresConfirmation = true)
        val ex = executor(models = listOf(m))
        val r = ex.execute("github_write", emptyMap(), USER, confirmed = false)
        r.invocationResult.requiresConfirmation.shouldBeTrue()
        r.invocationResult.success.shouldBeFalse()
    }

    @Test fun `confirmed write tool proceeds to client`() = runTest {
        val client = FakeMCPClient(
            result = MCPInvocationResult(toolName = "github_write", success = true, output = "created")
        )
        val registry = DefaultMCPRegistry(client = client)
        registry.register(model("github_write", requiresConfirmation = true))
        val ex = DefaultMCPExecutor(registry = registry, client = client)
        val r = ex.execute("github_write", emptyMap(), USER, confirmed = true)
        r.invocationResult.success.shouldBeTrue()
        client.invokeCallCount shouldBe 1
    }

    // ── Client failure ────────────────────────────────────────────────────────

    @Test fun `client exception returns safe failure`() = runTest {
        val ex = executor(models = listOf(model("echo")), throwOnInvoke = true)
        val r = ex.execute("echo", mapOf("message" to "hi"), USER)
        r.invocationResult.success.shouldBeFalse()
        r.invocationResult.error shouldNotContain "backend error"
    }

    @Test fun `client exception does not expose internal details`() = runTest {
        val ex = executor(models = listOf(model("echo")), throwOnInvoke = true)
        val r = ex.execute("echo", mapOf("message" to "hi"), USER)
        r.invocationResult.error shouldNotContain "Traceback"
        r.invocationResult.error shouldNotContain "RuntimeException"
    }

    // ── Success ───────────────────────────────────────────────────────────────

    @Test fun `successful invocation returns success result`() = runTest {
        val client = FakeMCPClient(
            result = MCPInvocationResult(toolName = "echo", success = true, output = "pong")
        )
        val registry = DefaultMCPRegistry(client = client)
        registry.register(model("echo"))
        val ex = DefaultMCPExecutor(registry = registry, client = client)
        val r = ex.execute("echo", emptyMap(), USER)
        r.invocationResult.success.shouldBeTrue()
        r.invocationResult.output shouldBe "pong"
    }

    @Test fun `executor passes userId to client`() = runTest {
        var capturedUserId = ""
        val client = object : MCPClient {
            override suspend fun discoverTools() = emptyList<MCPTool>()
            override fun isRegistered(toolName: String) = false
            override suspend fun invoke(
                toolName: String, params: Map<String, String>,
                userId: String, confirmed: Boolean,
            ): MCPInvocationResult {
                capturedUserId = userId
                return MCPInvocationResult(toolName = toolName, success = true, output = "ok")
            }
        }
        val registry = DefaultMCPRegistry(client = client)
        registry.register(model("echo"))
        val ex = DefaultMCPExecutor(registry = registry, client = client)
        ex.execute("echo", emptyMap(), "alice-123")
        capturedUserId shouldBe "alice-123"
    }
}

// ── MCPServer ─────────────────────────────────────────────────────────────────

class MCPServerTest {

    private fun server(
        tools: List<MCPTool> = emptyList(),
        allowed: Set<String>? = null,
        result: MCPInvocationResult? = null,
    ): MCPServer {
        val client = FakeMCPClient(tools = tools, result = result)
        return MCPServer.create(client = client, allowedTools = allowed)
    }

    @Test fun `create returns MCPServer`() {
        server() shouldNotBe null
    }

    @Test fun `register adds tool`() {
        val s = server()
        s.register(model("echo"))
        s.isAllowed("echo").shouldBeTrue()
    }

    @Test fun `unregister removes tool`() {
        val s = server()
        s.register(model("echo"))
        s.unregister("echo").shouldBeTrue()
        s.isAllowed("echo").shouldBeFalse()
    }

    @Test fun `listAllowed returns allowed tools sorted`() {
        val s = server()
        s.register(model("z"))
        s.register(model("a"))
        s.listAllowed() shouldBe listOf("a", "z")
    }

    @Test fun `discoverTools returns MCPTool list`() {
        val s = server()
        s.register(model("github", "GitHub", "Read GitHub."))
        val tools = s.discoverTools()
        tools shouldHaveSize 1
        tools[0].name shouldBe "github"
    }

    @Test fun `refresh populates registry from backend`() = runTest {
        val s = MCPServer.create(
            client = FakeMCPClient(tools = listOf(tool("github"), tool("slack"))),
        )
        s.refresh()
        s.isAllowed("github").shouldBeTrue()
        s.isAllowed("slack").shouldBeTrue()
    }

    @Test fun `execute returns success for allowed tool`() = runTest {
        val s = server(
            result = MCPInvocationResult(toolName = "echo", success = true, output = "hi")
        )
        s.register(model("echo"))
        val result = s.execute("echo", emptyMap(), USER)
        result.success.shouldBeTrue()
    }

    @Test fun `execute returns failure for unknown tool`() = runTest {
        val s = server()
        val result = s.execute("ghost", emptyMap(), USER)
        result.success.shouldBeFalse()
    }

    @Test fun `execute never exposes internal errors`() = runTest {
        val client = FakeMCPClient(throwOnInvoke = true)
        val s = MCPServer.create(client = client)
        s.register(model("echo"))
        val result = s.execute("echo", mapOf("message" to "x"), USER)
        result.success.shouldBeFalse()
        result.error shouldNotContain "backend error"
        result.error shouldNotContain "RuntimeException"
    }

    @Test fun `allowlist blocks non-listed tools`() = runTest {
        val s = server(allowed = setOf("slack"))
        s.register(model("echo"))
        val result = s.execute("echo", emptyMap(), USER)
        result.success.shouldBeFalse()
    }
}
