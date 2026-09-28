/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : CodeAgentActionTest.kt
 * Purpose    : Unit tests for CodeAgentAction enum and RagCitation models.
 * ============================================================
 */
package com.aiassistant.domain.agent

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.Test

class CodeAgentActionTest {

    @Test
    fun `fromApiValue returns correct action for each value`() {
        CodeAgentAction.fromApiValue("explain") shouldBe CodeAgentAction.EXPLAIN
        CodeAgentAction.fromApiValue("fix_bug") shouldBe CodeAgentAction.FIX_BUG
        CodeAgentAction.fromApiValue("generate_tests") shouldBe CodeAgentAction.GENERATE_TESTS
        CodeAgentAction.fromApiValue("generate") shouldBe CodeAgentAction.GENERATE
        CodeAgentAction.fromApiValue("refactor") shouldBe CodeAgentAction.REFACTOR
        CodeAgentAction.fromApiValue("review") shouldBe CodeAgentAction.REVIEW
    }

    @Test
    fun `fromApiValue is case-insensitive`() {
        CodeAgentAction.fromApiValue("EXPLAIN") shouldBe CodeAgentAction.EXPLAIN
        CodeAgentAction.fromApiValue("Fix_Bug") shouldBe CodeAgentAction.FIX_BUG
    }

    @Test
    fun `fromApiValue defaults to EXPLAIN for unknown value`() {
        CodeAgentAction.fromApiValue("unknown_action") shouldBe CodeAgentAction.EXPLAIN
        CodeAgentAction.fromApiValue("") shouldBe CodeAgentAction.EXPLAIN
    }

    @Test
    fun `apiValue returns correct string`() {
        CodeAgentAction.EXPLAIN.apiValue shouldBe "explain"
        CodeAgentAction.FIX_BUG.apiValue shouldBe "fix_bug"
        CodeAgentAction.GENERATE_TESTS.apiValue shouldBe "generate_tests"
        CodeAgentAction.GENERATE.apiValue shouldBe "generate"
        CodeAgentAction.REFACTOR.apiValue shouldBe "refactor"
        CodeAgentAction.REVIEW.apiValue shouldBe "review"
    }

    @Test
    fun `all 6 actions are present`() {
        CodeAgentAction.entries.size shouldBe 6
    }

    @Test
    fun `RagCitation defaults`() {
        val c = RagCitation(documentName = "doc.pdf", pageNumber = 1, chunkIndex = 0)
        c.citationType shouldBe "page"
        c.excerpt shouldBe ""
        c.score shouldBe 0f
    }

    @Test
    fun `RagQueryResult holds answer and citations`() {
        val c = RagCitation("doc.pdf", 1, 0, excerpt = "sample text", score = 0.9f)
        val r = RagQueryResult(answer = "The answer", citations = listOf(c))
        r.answer shouldBe "The answer"
        r.citations.size shouldBe 1
        r.citations[0].score shouldBe 0.9f
    }

    @Test
    fun `AgentGatewayCodeExtension is a domain interface`() {
        // Verify the interface constant is accessible and the class exists in domain
        val fqn = AgentGatewayCodeExtension::class.java.name
        (fqn.contains("domain")).shouldBeTrue()
    }
}
