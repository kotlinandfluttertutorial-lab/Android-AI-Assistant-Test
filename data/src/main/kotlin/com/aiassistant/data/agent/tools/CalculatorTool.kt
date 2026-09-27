/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : tools/CalculatorTool.kt
 * Purpose    : Safe arithmetic calculator -- evaluates numeric expressions
 *              without shell access or arbitrary code execution.
 *
 * Architecture Layer : Data -- agent tools
 * Pattern Used       : Tool implementation
 *
 * Security:
 *  // - Input whitelist: only digits, operators (+-*^%), decimal points,
 *     parentheses, and whitespace are accepted.
 *   - No eval(), no shell, no reflection.
 *   - Division by zero -> safe error message.
 *   - Maximum expression length: 500 characters.
 *
 * Dependencies: domain (Tool, ToolSchema, ToolResult, ToolPermission,
 *               ToolValidationError)
 * ============================================================
 */

package com.aiassistant.data.agent.tools

import com.aiassistant.domain.agent.Tool
import com.aiassistant.domain.agent.ToolPermission
import com.aiassistant.domain.agent.ToolResult
import com.aiassistant.domain.agent.ToolSchema
import com.aiassistant.domain.agent.ToolValidationError
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Evaluates safe arithmetic expressions using a hand-rolled recursive-descent
 * parser.  No external libraries required; no shell execution.
 *
 * Supported operations: +, -, *, /, %, ^ (power), unary minus, parentheses.
 * Supported constants: `pi`, `e`.
 * Supported functions: `sqrt(x)`, `abs(x)`, `floor(x)`, `ceil(x)`, `round(x)`.
 */
@Singleton
class CalculatorTool @Inject constructor() : Tool {

    override val schema = ToolSchema(
        name = NAME,
        displayName = "Calculator",
        description = "Evaluates safe arithmetic expressions (no code execution).",
        parametersSchema = mapOf(
            "expression" to "The arithmetic expression to evaluate (e.g. '2 + 3 * 4')."
        ),
        requiredPermissions = setOf(ToolPermission.COMPUTE),
        timeoutMs = 2_000L,
    )

    override fun validate(args: Map<String, String>) {
        val expr = args["expression"]
            ?: throw ToolValidationError(NAME, "expression", "is required")
        if (expr.isBlank()) throw ToolValidationError(NAME, "expression", "must not be blank")
        if (expr.length > MAX_EXPRESSION_LENGTH)
            throw ToolValidationError(
                NAME, "expression",
                "exceeds maximum length of $MAX_EXPRESSION_LENGTH characters"
            )
        if (!expr.matches(ALLOWED_CHARS))
            throw ToolValidationError(
                NAME, "expression",
                "contains invalid characters -- only digits, operators, parentheses, and whitespace are allowed"
            )
    }

    override suspend fun execute(args: Map<String, String>, userId: String): ToolResult {
        val expression = args["expression"]!!.trim()
        val start = System.currentTimeMillis()

        return try {
            val result = Calculator(expression).evaluate()
            val formatted = if (result == result.toLong().toDouble()) {
                result.toLong().toString()
            } else {
                result.toBigDecimal().stripTrailingZeros().toPlainString()
            }
            ToolResult(
                toolName = NAME,
                success = true,
                output = formatted,
                durationMs = System.currentTimeMillis() - start,
                metadata = mapOf("expression" to expression),
            )
        } catch (e: ArithmeticException) {
            ToolResult(
                toolName = NAME,
                success = false,
                error = "Arithmetic error: ${e.message ?: "division by zero or overflow"}",
                durationMs = System.currentTimeMillis() - start,
            )
        } catch (e: Exception) {
            ToolResult(
                toolName = NAME,
                success = false,
                error = "Could not evaluate expression: ${e.message ?: "invalid syntax"}",
                durationMs = System.currentTimeMillis() - start,
            )
        }
    }

    companion object {
        const val NAME = "calculator"
        private const val MAX_EXPRESSION_LENGTH = 500
        // Allow: digits, operators, parentheses, decimal, whitespace, letters for function names
        private val ALLOWED_CHARS = Regex("[0-9+\\-*/^%().\\s,a-zA-Z_]+")
    }
}

// -- Recursive-descent arithmetic parser --------------------------------------

private class Calculator(private val expr: String) {
    private var pos = 0

    fun evaluate(): Double {
        val result = parseExpression()
        skipSpaces()
        if (pos < expr.length) error("Unexpected character '${expr[pos]}' at position $pos")
        return result
    }

    private fun parseExpression(): Double {
        var result = parseTerm()
        while (pos < expr.length) {
            skipSpaces()
            when {
                peek('+') -> { pos++; result += parseTerm() }
                peek('-') -> { pos++; result -= parseTerm() }
                else -> break
            }
        }
        return result
    }

    private fun parseTerm(): Double {
        var result = parsePower()
        while (pos < expr.length) {
            skipSpaces()
            when {
                peek('*') -> { pos++; result *= parsePower() }
                peek('/') -> {
                    pos++
                    val d = parsePower()
                    if (d == 0.0) throw ArithmeticException("division by zero")
                    result /= d
                }
                peek('%') -> {
                    pos++
                    val d = parsePower()
                    if (d == 0.0) throw ArithmeticException("modulo by zero")
                    result %= d
                }
                else -> break
            }
        }
        return result
    }

    private fun parsePower(): Double {
        val base = parseUnary()
        skipSpaces()
        return if (pos < expr.length && peek('^')) {
            pos++
            Math.pow(base, parseUnary())
        } else base
    }

    private fun parseUnary(): Double {
        skipSpaces()
        return when {
            peek('-') -> { pos++; -parsePrimary() }
            peek('+') -> { pos++; parsePrimary() }
            else -> parsePrimary()
        }
    }

    private fun parsePrimary(): Double {
        skipSpaces()
        if (pos >= expr.length) error("Unexpected end of expression")

        // Parenthesised sub-expression
        if (peek('(')) {
            pos++
            val result = parseExpression()
            skipSpaces()
            if (!peek(')')) error("Missing closing parenthesis")
            pos++
            return result
        }

        // Named constants and functions
        if (expr[pos].isLetter() || expr[pos] == '_') {
            val start = pos
            while (pos < expr.length && (expr[pos].isLetterOrDigit() || expr[pos] == '_')) pos++
            val token = expr.substring(start, pos).lowercase()
            skipSpaces()
            if (peek('(')) {
                pos++
                val arg = parseExpression()
                skipSpaces()
                if (!peek(')')) error("Missing closing parenthesis after function '$token'")
                pos++
                return when (token) {
                    "sqrt" -> if (arg < 0) error("sqrt of negative number") else Math.sqrt(arg)
                    "abs"  -> Math.abs(arg)
                    "floor" -> Math.floor(arg)
                    "ceil"  -> Math.ceil(arg)
                    "round" -> Math.round(arg).toDouble()
                    "log"  -> if (arg <= 0) error("log of non-positive") else Math.log(arg)
                    "sin"  -> Math.sin(arg)
                    "cos"  -> Math.cos(arg)
                    "tan"  -> Math.tan(arg)
                    else   -> error("Unknown function '$token'")
                }
            }
            return when (token) {
                "pi"  -> Math.PI
                "e"   -> Math.E
                else  -> error("Unknown constant '$token'")
            }
        }

        // Numeric literal
        val start = pos
        while (pos < expr.length && (expr[pos].isDigit() || expr[pos] == '.')) pos++
        if (pos == start) error("Expected number at position $pos")
        return expr.substring(start, pos).toDoubleOrNull()
            ?: error("Invalid number at position $start")
    }

    private fun peek(c: Char) = pos < expr.length && expr[pos] == c
    private fun skipSpaces() { while (pos < expr.length && expr[pos].isWhitespace()) pos++ }
}
