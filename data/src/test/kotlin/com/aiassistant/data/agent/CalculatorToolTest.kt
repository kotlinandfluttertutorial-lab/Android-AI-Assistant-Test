/*
 * Unit tests for CalculatorTool â€” arithmetic, validation, edge cases, security.
 */
package com.aiassistant.data.agent

import com.aiassistant.data.agent.tools.CalculatorTool
import com.aiassistant.domain.agent.ToolPermission
import com.aiassistant.domain.agent.ToolValidationError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class CalculatorToolTest {

    private val tool = CalculatorTool()

    // â”€â”€ Schema â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test fun `name is calculator`() = tool.schema.name shouldBe "calculator"
    @Test fun `requires COMPUTE permission`() =
        (ToolPermission.COMPUTE in tool.schema.requiredPermissions).shouldBeTrue()
    @Test fun `does not require confirmation`() =
        tool.schema.requiresConfirmation.shouldBe(false)

    // â”€â”€ Validation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test fun `blank expression throws validation error`() {
        shouldThrow<ToolValidationError> { tool.validate(mapOf("expression" to "")) }
    }

    @Test fun `missing expression throws validation error`() {
        shouldThrow<ToolValidationError> { tool.validate(emptyMap()) }
    }

    @Test fun `expression with invalid chars throws validation error`() {
        // Semicolons are not in the allowed character set
        shouldThrow<ToolValidationError> { tool.validate(mapOf("expression" to "1; 2")) }
    }

    @Test fun `oversized expression throws validation error`() {
        shouldThrow<ToolValidationError> {
            tool.validate(mapOf("expression" to "1+".repeat(300)))
        }
    }

    // â”€â”€ Arithmetic â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test fun `addition`() = runTest {
        val r = tool.execute(mapOf("expression" to "2 + 3"), "u1")
        r.success.shouldBeTrue()
        r.output shouldBe "5"
    }

    @Test fun `multiplication`() = runTest {
        val r = tool.execute(mapOf("expression" to "4 * 5"), "u1")
        r.output shouldBe "20"
    }

    @Test fun `division`() = runTest {
        val r = tool.execute(mapOf("expression" to "10 / 4"), "u1")
        r.output shouldBe "2.5"
    }

    @Test fun `order of operations`() = runTest {
        val r = tool.execute(mapOf("expression" to "2 + 3 * 4"), "u1")
        r.output shouldBe "14"
    }

    @Test fun `parentheses`() = runTest {
        val r = tool.execute(mapOf("expression" to "(2 + 3) * 4"), "u1")
        r.output shouldBe "20"
    }

    @Test fun `power`() = runTest {
        val r = tool.execute(mapOf("expression" to "2^10"), "u1")
        r.output shouldBe "1024"
    }

    @Test fun `unary minus`() = runTest {
        val r = tool.execute(mapOf("expression" to "-5 + 10"), "u1")
        r.output shouldBe "5"
    }

    @Test fun `pi constant`() = runTest {
        val r = tool.execute(mapOf("expression" to "pi"), "u1")
        r.success.shouldBeTrue()
        r.output!!.startsWith("3.14").shouldBeTrue()
    }

    @Test fun `sqrt function`() = runTest {
        val r = tool.execute(mapOf("expression" to "sqrt(16)"), "u1")
        r.output shouldBe "4"
    }

    @Test fun `abs function`() = runTest {
        val r = tool.execute(mapOf("expression" to "abs(-7)"), "u1")
        r.output shouldBe "7"
    }

    @Test fun `modulo`() = runTest {
        val r = tool.execute(mapOf("expression" to "10 % 3"), "u1")
        r.output shouldBe "1"
    }

    // â”€â”€ Error paths â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test fun `division by zero returns failure result`() = runTest {
        val r = tool.execute(mapOf("expression" to "5 / 0"), "u1")
        r.success.shouldBe(false)
        r.error!!.shouldContain("division by zero")
    }

    @Test fun `invalid syntax returns failure result`() = runTest {
        val r = tool.execute(mapOf("expression" to "2 ++++ 3"), "u1")
        // Should not throw â€” returns ToolResult(success=false)
        r.success.shouldBe(false)
    }

    @Test fun `integer result has no decimal point`() = runTest {
        val r = tool.execute(mapOf("expression" to "6 / 2"), "u1")
        r.output shouldBe "3"
    }

    // â”€â”€ Security: no shell injection â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test fun `shell-like characters are rejected by validate`() {
        shouldThrow<ToolValidationError> {
            tool.validate(mapOf("expression" to "; ls /"))
        }
    }

    @Test fun `backtick injection rejected`() {
        shouldThrow<ToolValidationError> {
            tool.validate(mapOf("expression" to "`id`"))
        }
    }
}
