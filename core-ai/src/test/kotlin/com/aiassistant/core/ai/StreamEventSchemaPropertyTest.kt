/**
 * StreamEventSchemaPropertyTest.kt
 *
 * Purpose: Property-based tests validating that parseEvent() maps every valid-format
 *          WebSocket frame to exactly one StreamEvent subtype (Property 28) and that
 *          all malformed or unrecognised frames produce StreamEvent.Error.
 * Architecture: core-ai — unit tests (pure JVM, no Android framework).
 * Requirements: 26.5 (event schema conformance)
 *
 * Design decisions:
 * - Uses Kotest PropTest (checkAll) with Arb generators for exhaustive schema coverage.
 * - parseEvent() is `internal` — accessible from within the same Gradle module's tests.
 * - OkHttpClient and DispatcherProvider are mocked; only parseEvent is under test.
 */

package com.aiassistant.core.ai

import com.aiassistant.core.common.DispatcherProvider
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.nonNegativeInt
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient

// ─── Test doubles ─────────────────────────────────────────────────────────────

/** Minimal DispatcherProvider that returns Unconfined for all dispatchers. */
private class TestDispatcherProvider : DispatcherProvider {
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
    override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
    override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
}

/** Four valid `type` strings — used to exclude them in Case H. */
private val VALID_TYPES = setOf("token", "done", "error", "tool_call")

// ─── Helper to escape strings for JSON embedding ──────────────────────────────

/**
 * Escapes a string for safe embedding inside a JSON string literal.
 * Handles backslash, double-quote, and common control characters.
 */
private fun String.jsonEscape(): String = buildString {
    for (ch in this@jsonEscape) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
}

// ─── Property 28: WebSocket Event Schema Conformance ─────────────────────────

/**
 * **Validates: Requirements 26.5**
 *
 * Verifies that parseEvent() maps every valid-format WebSocket frame to exactly one
 * StreamEvent subtype (disjointness), and that all malformed / unrecognised frames
 * produce StreamEvent.Error.
 */
class StreamEventSchemaPropertyTest :
    DescribeSpec({

        // Build the system-under-test once; OkHttpClient is never called in these tests.
        val client = AIStreamClientImpl(
            okHttpClient = mockk<OkHttpClient>(),
            dispatcherProvider = TestDispatcherProvider()
        )

        // ── Case A: valid `token` frames → StreamEvent.Token ──────────────────────
        describe("Case A — valid token frames") {

            it("always parse to StreamEvent.Token with matching text field") {
                checkAll(iterations = 500, Arb.string()) { text ->
                    val escaped = text.jsonEscape()
                    val raw = """{"type":"token","data":"$escaped"}"""
                    val result = client.parseEvent(raw)

                    result.shouldBeInstanceOf<StreamEvent.Token>()
                    (result as StreamEvent.Token).text shouldBe text
                }
            }
        }

        // ── Case B: valid `done` frames → StreamEvent.Done ────────────────────────
        describe("Case B — valid done frames") {

            it("always parse to StreamEvent.Done with correct usage values") {
                checkAll(
                    iterations = 500,
                    Arb.pair(Arb.nonNegativeInt(), Arb.nonNegativeInt())
                ) { (inputTokens, outputTokens) ->
                    val raw = """{"type":"done","usage":{"inputTokens":$inputTokens,"outputTokens":$outputTokens}}"""
                    val result = client.parseEvent(raw)

                    result.shouldBeInstanceOf<StreamEvent.Done>()
                    val done = result as StreamEvent.Done
                    done.usage.inputTokens shouldBe inputTokens
                    done.usage.outputTokens shouldBe outputTokens
                }
            }
        }

        // ── Case C: valid `error` frames → StreamEvent.Error ──────────────────────
        describe("Case C — valid error frames") {

            it("always parse to StreamEvent.Error with matching message field") {
                checkAll(iterations = 500, Arb.string()) { message ->
                    val escaped = message.jsonEscape()
                    val raw = """{"type":"error","message":"$escaped"}"""
                    val result = client.parseEvent(raw)

                    result.shouldBeInstanceOf<StreamEvent.Error>()
                    (result as StreamEvent.Error).message shouldBe message
                }
            }
        }

        // ── Case D: valid `tool_call` frames → StreamEvent.ToolCall ──────────────
        describe("Case D — valid tool_call frames") {

            it("always parse to StreamEvent.ToolCall with matching toolName field") {
                checkAll(
                    iterations = 500,
                    Arb.string().filter { it.isNotBlank() }
                ) { toolName ->
                    val escaped = toolName.jsonEscape()
                    val raw = """{"type":"tool_call","toolName":"$escaped","toolInput":{}}"""
                    val result = client.parseEvent(raw)

                    result.shouldBeInstanceOf<StreamEvent.ToolCall>()
                    (result as StreamEvent.ToolCall).toolName shouldBe toolName
                }
            }
        }

        // ── Case E: disjointness — each valid frame parses to EXACTLY ONE subtype ─
        describe("Case E — disjointness: each valid frame type maps to exactly one subtype") {

            it("token frame is Token and not Done/Error/ToolCall") {
                val result = client.parseEvent("""{"type":"token","data":"hello"}""")
                result.shouldBeInstanceOf<StreamEvent.Token>()
                (result is StreamEvent.Done) shouldBe false
                (result is StreamEvent.Error) shouldBe false
                (result is StreamEvent.ToolCall) shouldBe false
            }

            it("done frame is Done and not Token/Error/ToolCall") {
                val result = client.parseEvent("""{"type":"done","usage":{"inputTokens":1,"outputTokens":2}}""")
                result.shouldBeInstanceOf<StreamEvent.Done>()
                (result is StreamEvent.Token) shouldBe false
                (result is StreamEvent.Error) shouldBe false
                (result is StreamEvent.ToolCall) shouldBe false
            }

            it("error frame is Error and not Token/Done/ToolCall") {
                val result = client.parseEvent("""{"type":"error","message":"oops"}""")
                result.shouldBeInstanceOf<StreamEvent.Error>()
                (result is StreamEvent.Token) shouldBe false
                (result is StreamEvent.Done) shouldBe false
                (result is StreamEvent.ToolCall) shouldBe false
            }

            it("tool_call frame is ToolCall and not Token/Done/Error") {
                val result = client.parseEvent("""{"type":"tool_call","toolName":"github","toolInput":{}}""")
                result.shouldBeInstanceOf<StreamEvent.ToolCall>()
                (result is StreamEvent.Token) shouldBe false
                (result is StreamEvent.Done) shouldBe false
                (result is StreamEvent.Error) shouldBe false
            }
        }

        // ── Case F: malformed / arbitrary strings → StreamEvent.Error ─────────────
        describe("Case F — malformed/arbitrary strings surface as StreamEvent.Error") {

            it("arbitrary strings that do not contain a type field produce StreamEvent.Error") {
                checkAll(
                    iterations = 500,
                    Arb.string().filter { !it.contains("\"type\"") }
                ) { arbitrary ->
                    val result = client.parseEvent(arbitrary)
                    result.shouldBeInstanceOf<StreamEvent.Error>()
                }
            }
        }

        // ── Case G: non-JSON strings → StreamEvent.Error ──────────────────────────
        describe("Case G — non-JSON strings surface as StreamEvent.Error") {

            val nonJsonInputs = listOf(
                "",
                " ",
                "\t",
                "\n",
                "hello",
                "plain text",
                "{",
                "}",
                "{{}",
                "[1,2,3]",
                "null",
                "true",
                "42",
                "\"just a string\""
            )

            nonJsonInputs.forEach { input ->
                it(
                    "input ${input.take(30).replace("\n", "\\n").replace("\t", "\\t").ifEmpty {
                        "<empty>"
                    }.let { "\"$it\"" }} produces StreamEvent.Error"
                ) {
                    val result = client.parseEvent(input)
                    result.shouldBeInstanceOf<StreamEvent.Error>()
                }
            }
        }

        // ── Case H: wrong `type` field values → StreamEvent.Error ─────────────────
        describe("Case H — wrong type field values surface as StreamEvent.Error") {

            it("arbitrary non-matching type strings produce StreamEvent.Error") {
                checkAll(
                    iterations = 500,
                    Arb.string().filter { it !in VALID_TYPES }
                ) { unknownType ->
                    val escaped = unknownType.jsonEscape()
                    val raw = """{"type":"$escaped","data":"x"}"""
                    val result = client.parseEvent(raw)
                    result.shouldBeInstanceOf<StreamEvent.Error>()
                }
            }
        }

        // ── Case I: missing required fields → StreamEvent.Error ───────────────────
        describe("Case I — missing required fields surface as StreamEvent.Error") {

            it("""{"type":"token"} (missing data) → StreamEvent.Error""") {
                val result = client.parseEvent("""{"type":"token"}""")
                result.shouldBeInstanceOf<StreamEvent.Error>()
            }

            it("""{"type":"done"} (missing usage) → StreamEvent.Error""") {
                val result = client.parseEvent("""{"type":"done"}""")
                result.shouldBeInstanceOf<StreamEvent.Error>()
            }

            it("""{"type":"done","usage":{"inputTokens":1}} (missing outputTokens) → StreamEvent.Error""") {
                val result = client.parseEvent("""{"type":"done","usage":{"inputTokens":1}}""")
                result.shouldBeInstanceOf<StreamEvent.Error>()
            }

            it("""{"type":"error"} (missing message) → StreamEvent.Error""") {
                val result = client.parseEvent("""{"type":"error"}""")
                result.shouldBeInstanceOf<StreamEvent.Error>()
            }

            it("""{"type":"tool_call","toolName":"gh"} (missing toolInput) → StreamEvent.Error""") {
                val result = client.parseEvent("""{"type":"tool_call","toolName":"gh"}""")
                result.shouldBeInstanceOf<StreamEvent.Error>()
            }

            it("""{"type":"tool_call","toolInput":{}} (missing toolName) → StreamEvent.Error""") {
                val result = client.parseEvent("""{"type":"tool_call","toolInput":{}}""")
                result.shouldBeInstanceOf<StreamEvent.Error>()
            }
        }

        // ── Nullability / encoding edge cases ────────────────────────────────────
        describe("nullability and encoding edge cases") {

            it("""empty string "" → StreamEvent.Error""") {
                val result = client.parseEvent("")
                result.shouldBeInstanceOf<StreamEvent.Error>()
            }

            it(""""null" → StreamEvent.Error""") {
                val result = client.parseEvent("null")
                result.shouldBeInstanceOf<StreamEvent.Error>()
            }

            it(""""[]" (JSON array) → StreamEvent.Error""") {
                val result = client.parseEvent("[]")
                result.shouldBeInstanceOf<StreamEvent.Error>()
            }

            it("""{"type":"token","data":null} → StreamEvent.Token with text "null" (JsonNull.content is "null")""") {
                // kotlinx.serialization: JsonNull is a JsonPrimitive whose .content == "null".
                // With isLenient=true the parser treats data:null as the string literal "null"
                // rather than a missing field, so parseEvent returns Token("null") not Error.
                val result = client.parseEvent("""{"type":"token","data":null}""")
                result.shouldBeInstanceOf<StreamEvent.Token>()
                (result as StreamEvent.Token).text shouldBe "null"
            }
        }
    })
