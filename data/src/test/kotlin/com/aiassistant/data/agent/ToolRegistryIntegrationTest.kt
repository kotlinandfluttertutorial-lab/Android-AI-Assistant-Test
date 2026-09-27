/*
 * Integration tests for ToolRegistry with real tool instances.
 * Tests: tool registration, permission filtering, listTools via gateway.
 */
package com.aiassistant.data.agent

import com.aiassistant.data.agent.tools.CalculatorTool
import com.aiassistant.data.agent.tools.DateTimeTool
import com.aiassistant.data.agent.tools.WebSearchTool
import com.aiassistant.domain.agent.DefaultToolRegistry
import com.aiassistant.domain.agent.ToolPermission
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.Before
import org.junit.Test

class ToolRegistryIntegrationTest {

    private lateinit var registry: DefaultToolRegistry

    @Before
    fun setUp() {
        registry = DefaultToolRegistry().apply {
            register(CalculatorTool())
            register(DateTimeTool())
            register(WebSearchTool())
        }
    }

    // ── Registration ──────────────────────────────────────────────────────────

    @Test fun `three tools registered`() = registry.size shouldBe 3

    @Test fun `calculator accessible by name`() =
        registry.get("calculator").schema.name shouldBe "calculator"

    @Test fun `datetime accessible by name`() =
        registry.get("datetime").schema.name shouldBe "datetime"

    @Test fun `web_search accessible by name`() =
        registry.get("web_search").schema.name shouldBe "web_search"

    // ── Permission filtering ──────────────────────────────────────────────────

    @Test fun `COMPUTE permission returns only calculator`() {
        val found = registry.findByPermission(setOf(ToolPermission.COMPUTE))
        found shouldHaveSize 1
        found[0].schema.name shouldBe "calculator"
    }

    @Test fun `READ_DATETIME permission returns only datetime`() {
        val found = registry.findByPermission(setOf(ToolPermission.READ_DATETIME))
        found shouldHaveSize 1
        found[0].schema.name shouldBe "datetime"
    }

    @Test fun `NETWORK_ACCESS permission returns only web_search`() {
        val found = registry.findByPermission(setOf(ToolPermission.NETWORK_ACCESS))
        found shouldHaveSize 1
        found[0].schema.name shouldBe "web_search"
    }

    @Test fun `READ_DOCUMENTS permission returns nothing`() {
        registry.findByPermission(setOf(ToolPermission.READ_DOCUMENTS)) shouldHaveSize 0
    }

    @Test fun `empty permission set returns all tools`() {
        registry.findByPermission(emptySet()) shouldHaveSize 3
    }

    // ── listTools via AgentGateway ────────────────────────────────────────────

    @Test fun `AgentGateway listTools returns schemas for all registered tools`() {
        val mockChat = mockk<ChatAgent>(relaxed = true)
        val mockCode = mockk<CodeAgent>(relaxed = true)
        val mockRag = mockk<RagAgent>(relaxed = true)
        val mockPdf = mockk<PdfAgent>(relaxed = true)
        val toolAgent = ToolAgent(registry)
        val gateway = AgentGateway(mockChat, mockCode, mockRag, mockPdf, toolAgent, registry)

        val schemas = gateway.listTools()
        schemas shouldHaveSize 3
        val names = schemas.map { it.name }.toSet()
        (names.contains("calculator")).shouldBeTrue()
        (names.contains("datetime")).shouldBeTrue()
        (names.contains("web_search")).shouldBeTrue()
    }

    @Test fun `each tool schema has non-blank name and description`() {
        registry.list().forEach { tool ->
            tool.schema.name.isNotBlank().shouldBeTrue()
            tool.schema.description.isNotBlank().shouldBeTrue()
        }
    }

    @Test fun `each tool declares at least one permission`() {
        registry.list().forEach { tool ->
            tool.schema.requiredPermissions.isNotEmpty().shouldBeTrue()
        }
    }
}
