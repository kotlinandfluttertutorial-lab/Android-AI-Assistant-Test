/*
 * Domain unit tests for Tool domain types: ToolSchema, ToolResult,
 * ToolPermission, ToolValidationError, ToolPermissionDeniedError, ToolTimeoutError.
 */
package com.aiassistant.domain.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class ToolSchemaTest {

    // â”€â”€ ToolSchema validation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test fun `blank name throws`() {
        shouldThrow<IllegalArgumentException> {
            ToolSchema(name = "", displayName = "X", description = "d")
        }
    }

    @Test fun `blank displayName throws`() {
        shouldThrow<IllegalArgumentException> {
            ToolSchema(name = "x", displayName = "", description = "d")
        }
    }

    @Test fun `zero timeout throws`() {
        shouldThrow<IllegalArgumentException> {
            ToolSchema(name = "x", displayName = "X", description = "d", timeoutMs = 0)
        }
    }

    @Test fun `valid schema constructs`() {
        val s = ToolSchema(name = "calculator", displayName = "Calculator", description = "maths")
        s.name shouldBe "calculator"
        s.requiresConfirmation.shouldBeFalse()
        s.requiredPermissions shouldBe emptySet()
        s.timeoutMs shouldBe ToolSchema.DEFAULT_TIMEOUT_MS
    }

    @Test fun `schema with permissions and confirmation`() {
        val s = ToolSchema(
            name = "write",
            displayName = "Write",
            description = "writes",
            requiredPermissions = setOf(ToolPermission.WRITE_EXTERNAL),
            requiresConfirmation = true,
        )
        s.requiresConfirmation.shouldBeTrue()
        (ToolPermission.WRITE_EXTERNAL in s.requiredPermissions).shouldBeTrue()
    }

    // â”€â”€ ToolResult â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test fun `ToolResult isEmpty when output null`() =
        ToolResult(toolName = "t", success = true, output = null).isEmpty.shouldBeTrue()

    @Test fun `ToolResult isEmpty when output blank`() =
        ToolResult(toolName = "t", success = true, output = "  ").isEmpty.shouldBeTrue()

    @Test fun `ToolResult not empty when output present`() =
        ToolResult(toolName = "t", success = true, output = "42").isEmpty.shouldBeFalse()

    @Test fun `ToolResult failure carries error`() {
        val r = ToolResult(toolName = "t", success = false, error = "something went wrong")
        r.success.shouldBeFalse()
        r.error shouldBe "something went wrong"
    }

    // â”€â”€ Error types â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test fun `ToolValidationError message contains field and reason`() {
        val e = ToolValidationError("calc", "expression", "is required")
        e.message!!.shouldContain("expression")
        e.message!!.shouldContain("is required")
        e.toolName shouldBe "calc"
        e.fieldName shouldBe "expression"
    }

    @Test fun `ToolValidationError without field still has message`() {
        val e = ToolValidationError("calc", reason = "something bad")
        e.message!!.shouldContain("something bad")
        e.fieldName shouldBe null
    }

    @Test fun `ToolPermissionDeniedError message contains permission name`() {
        val e = ToolPermissionDeniedError("calc", ToolPermission.COMPUTE)
        e.message!!.shouldContain("COMPUTE")
        e.missingPermission shouldBe ToolPermission.COMPUTE
    }

    @Test fun `ToolTimeoutError message contains timeout value`() {
        val e = ToolTimeoutError("slow", 5_000L)
        e.message!!.shouldContain("5000")
        e.timeoutMs shouldBe 5_000L
    }

    // â”€â”€ ToolPermission â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test fun `all permissions are distinct`() {
        val perms = ToolPermission.entries
        perms.size shouldBe perms.distinctBy { it.name }.size
    }

    @Test fun `READ_DOCUMENTS and COMPUTE are defined`() {
        ToolPermission.entries.map { it.name }
            .containsAll(listOf("READ_DOCUMENTS", "COMPUTE")).shouldBeTrue()
    }
}
