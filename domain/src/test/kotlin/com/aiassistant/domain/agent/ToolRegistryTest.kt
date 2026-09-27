/*
 * Domain unit tests for Tool types and DefaultToolRegistry.
 */
package com.aiassistant.domain.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ToolRegistryTest {

    private fun registry() = DefaultToolRegistry()

    private fun stubTool(
        name: String,
        vararg permissions: ToolPermission,
        requiresConfirmation: Boolean = false,
    ) = object : Tool {
        override val schema = ToolSchema(
            name = name,
            displayName = name,
            description = "stub",
            requiredPermissions = setOf(*permissions),
            requiresConfirmation = requiresConfirmation,
        )
        override fun validate(args: Map<String, String>) {}
        override suspend fun execute(args: Map<String, String>, userId: String) =
            ToolResult(toolName = name, success = true, output = "ok")
    }

    @Test fun `empty registry is empty`() = registry().isEmpty.shouldBeTrue()
    @Test fun `register adds tool`() { val r = registry(); r.register(stubTool("a")); r.size shouldBe 1 }
    @Test fun `register replaces duplicate name`() {
        val r = registry(); r.register(stubTool("a")); r.register(stubTool("a")); r.size shouldBe 1
    }
    @Test fun `get returns registered tool`() {
        val r = registry(); val t = stubTool("calc"); r.register(t); r.get("calc") shouldBe t
    }
    @Test fun `get throws for unknown name`() { shouldThrow<ToolNotFoundException> { registry().get("x") } }
    @Test fun `getOrNull returns null for unknown`() { registry().getOrNull("x") shouldBe null }
    @Test fun `unregister removes tool`() { val r = registry(); r.register(stubTool("a")); r.unregister("a"); r.isEmpty.shouldBeTrue() }
    @Test fun `unregister unknown is noop`() { registry().unregister("nope") }
    @Test fun `list returns all tools`() {
        val r = registry(); r.register(stubTool("a")); r.register(stubTool("b"))
        r.list() shouldHaveSize 2
    }
    @Test fun `findByPermission empty set returns all`() {
        val r = registry(); r.register(stubTool("a", ToolPermission.COMPUTE))
        r.findByPermission(emptySet()) shouldHaveSize 1
    }
    @Test fun `findByPermission filters correctly`() {
        val r = registry()
        r.register(stubTool("calc", ToolPermission.COMPUTE))
        r.register(stubTool("search", ToolPermission.READ_DOCUMENTS))
        r.findByPermission(setOf(ToolPermission.COMPUTE)) shouldHaveSize 1
        r.findByPermission(setOf(ToolPermission.COMPUTE))[0].schema.name shouldBe "calc"
    }
    @Test fun `findByPermission returns empty when caller lacks required permission`() {
        val r = registry(); r.register(stubTool("net", ToolPermission.NETWORK_ACCESS))
        r.findByPermission(setOf(ToolPermission.COMPUTE)).shouldBeEmpty()
    }
    @Test fun `ToolSchema validates name not blank`() {
        shouldThrow<IllegalArgumentException> { ToolSchema(name = "", displayName = "A", description = "x") }
    }
    @Test fun `ToolSchema validates timeout positive`() {
        shouldThrow<IllegalArgumentException> {
            ToolSchema(name = "x", displayName = "X", description = "d", timeoutMs = 0)
        }
    }
    @Test fun `ToolResult isEmpty when output blank`() {
        ToolResult(toolName = "t", success = true, output = "  ").isEmpty.shouldBeTrue()
        ToolResult(toolName = "t", success = true, output = "hi").isEmpty.shouldBeFalse()
    }
    @Test fun `ToolValidationError message includes field and reason`() {
        val e = ToolValidationError("calc", "expression", "is required")
        e.message!!.contains("expression").shouldBeTrue()
        e.message!!.contains("is required").shouldBeTrue()
    }
    @Test fun `ToolPermissionDeniedError message includes permission`() {
        val e = ToolPermissionDeniedError("calc", ToolPermission.COMPUTE)
        e.message!!.contains("COMPUTE").shouldBeTrue()
    }
    @Test fun `requiresConfirmation schema flag preserved`() {
        val t = stubTool("write", requiresConfirmation = true)
        t.schema.requiresConfirmation.shouldBeTrue()
    }
}
