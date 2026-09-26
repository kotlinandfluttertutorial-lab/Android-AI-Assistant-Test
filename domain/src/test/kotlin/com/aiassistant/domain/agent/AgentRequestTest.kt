/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentRequestTest.kt
 * Purpose    : Unit tests for AgentRequest construction and validation.
 *
 * Architecture Layer : Domain — agent sub-package (test)
 * Pattern Used       : JUnit4 + Kotest assertions
 *
 * Dependencies: junit, kotest-assertions-core
 * ============================================================
 */

package com.aiassistant.domain.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.Test

class AgentRequestTest {

    // ── Happy path ──────────────────────────────────────────────────────────

    @Test
    fun `minimal valid request constructs successfully`() {
        val req = AgentRequest(userId = "user-1", input = "Hello")

        req.userId shouldBe "user-1"
        req.input shouldBe "Hello"
        req.requestId.shouldNotBeBlank()
        req.conversationId.shouldBeNull()
        req.maxSteps shouldBe AgentRequest.DEFAULT_MAX_STEPS
        req.timeoutMs shouldBe AgentRequest.DEFAULT_TIMEOUT_MS
        req.streamingEnabled shouldBe true
        req.capabilities shouldBe emptySet()
        req.metadata shouldBe emptyMap()
    }

    @Test
    fun `all fields can be specified`() {
        val ctx = AgentContext(userId = "user-1")
        val req = AgentRequest(
            requestId = "req-abc",
            userId = "user-1",
            input = "Analyse my code",
            conversationId = "conv-1",
            provider = "gemini",
            capabilities = setOf(AgentCapability.CODE_ANALYSIS),
            context = ctx,
            maxSteps = 5,
            timeoutMs = 30_000L,
            streamingEnabled = false,
            metadata = mapOf("screen" to "CodeScreen"),
        )

        req.requestId shouldBe "req-abc"
        req.conversationId shouldBe "conv-1"
        req.provider shouldBe "gemini"
        req.capabilities shouldBe setOf(AgentCapability.CODE_ANALYSIS)
        req.context.shouldNotBeNull()
        req.maxSteps shouldBe 5
        req.timeoutMs shouldBe 30_000L
        req.streamingEnabled shouldBe false
        req.metadata["screen"] shouldBe "CodeScreen"
    }

    @Test
    fun `two requests with same data but different requestId are not equal`() {
        val a = AgentRequest(userId = "u", input = "hi", requestId = "id-1")
        val b = AgentRequest(userId = "u", input = "hi", requestId = "id-2")
        (a == b) shouldBe false
    }

    // ── Validation: input ───────────────────────────────────────────────────

    @Test
    fun `blank input throws IllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            AgentRequest(userId = "user-1", input = "   ")
        }
    }

    @Test
    fun `empty input throws IllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            AgentRequest(userId = "user-1", input = "")
        }
    }

    // ── Validation: userId ──────────────────────────────────────────────────

    @Test
    fun `blank userId throws IllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            AgentRequest(userId = "  ", input = "Hello")
        }
    }

    @Test
    fun `empty userId throws IllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            AgentRequest(userId = "", input = "Hello")
        }
    }

    // ── Validation: maxSteps ────────────────────────────────────────────────

    @Test
    fun `maxSteps of 1 is valid`() {
        val req = AgentRequest(userId = "u", input = "x", maxSteps = 1)
        req.maxSteps shouldBe 1
    }

    @Test
    fun `maxSteps at limit of 50 is valid`() {
        val req = AgentRequest(userId = "u", input = "x", maxSteps = 50)
        req.maxSteps shouldBe 50
    }

    @Test
    fun `maxSteps of 0 throws IllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            AgentRequest(userId = "u", input = "x", maxSteps = 0)
        }
    }

    @Test
    fun `maxSteps of 51 throws IllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            AgentRequest(userId = "u", input = "x", maxSteps = 51)
        }
    }

    // ── Validation: timeoutMs ───────────────────────────────────────────────

    @Test
    fun `zero timeoutMs throws IllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            AgentRequest(userId = "u", input = "x", timeoutMs = 0L)
        }
    }

    @Test
    fun `negative timeoutMs throws IllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            AgentRequest(userId = "u", input = "x", timeoutMs = -1L)
        }
    }

    // ── Builder ─────────────────────────────────────────────────────────────

    @Test
    fun `Builder produces equivalent request to direct constructor`() {
        val direct = AgentRequest(
            requestId = "r1",
            userId = "u1",
            input = "Do something",
            maxSteps = 3,
        )
        val built = AgentRequest.Builder(userId = "u1", input = "Do something")
            .requestId("r1")
            .maxSteps(3)
            .build()

        built.requestId shouldBe direct.requestId
        built.userId shouldBe direct.userId
        built.input shouldBe direct.input
        built.maxSteps shouldBe direct.maxSteps
    }

    @Test
    fun `Builder validates on build`() {
        shouldThrow<IllegalArgumentException> {
            AgentRequest.Builder(userId = "", input = "x").build()
        }
    }
}
