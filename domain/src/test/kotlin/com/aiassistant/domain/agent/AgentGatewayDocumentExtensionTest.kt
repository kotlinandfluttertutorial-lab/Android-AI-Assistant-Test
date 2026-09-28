/*
 * Domain unit tests for AgentGatewayDocumentExtension interface existence
 * and AgentGatewayCodeExtension.
 */
package com.aiassistant.domain.agent

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.Test

class AgentGatewayDocumentExtensionTest {

    @Test
    fun `AgentGatewayDocumentExtension is a domain interface`() {
        val clazz = AgentGatewayDocumentExtension::class.java
        clazz.isInterface.shouldBeTrue()
    }

    @Test
    fun `AgentGatewayDocumentExtension declares executePdf method`() {
        val methods = AgentGatewayDocumentExtension::class.java.declaredMethods
        val names = methods.map { it.name }
        names.contains("executePdf").shouldBeTrue()
    }

    @Test
    fun `AgentGatewayDocumentExtension declares executeTool method`() {
        val methods = AgentGatewayDocumentExtension::class.java.declaredMethods
        val names = methods.map { it.name }
        names.contains("executeTool").shouldBeTrue()
    }

    @Test
    fun `AgentGatewayDocumentExtension declares listTools method`() {
        val methods = AgentGatewayDocumentExtension::class.java.declaredMethods
        val names = methods.map { it.name }
        names.contains("listTools").shouldBeTrue()
    }

    @Test
    fun `ToolPermission enum has expected security-relevant values`() {
        val names = ToolPermission.entries.map { it.name }.toSet()
        names.contains("READ_DOCUMENTS").shouldBeTrue()
        names.contains("COMPUTE").shouldBeTrue()
        names.contains("NETWORK_ACCESS").shouldBeTrue()
        names.contains("WRITE_EXTERNAL").shouldBeTrue()
        names.contains("READ_DATETIME").shouldBeTrue()
        names.contains("READ_CONVERSATIONS").shouldBeTrue()
        // Must not have unrestricted shell or filesystem permissions
        names.contains("SHELL_EXECUTION").shouldBe(false)
        names.contains("FILESYSTEM_ACCESS").shouldBe(false)
    }

    @Test
    fun `ToolPermission count is exactly 6 — no accidental additions`() {
        ToolPermission.entries.size shouldBe 6
    }
}
