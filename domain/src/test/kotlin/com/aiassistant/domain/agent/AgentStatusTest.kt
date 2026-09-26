/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentStatusTest.kt
 * Purpose    : Unit tests for AgentStatus lifecycle and transition rules.
 *
 * Architecture Layer : Domain — agent sub-package (test)
 * Pattern Used       : JUnit4 + Kotest assertions
 *
 * Dependencies: junit, kotest-assertions-core
 * ============================================================
 */

package com.aiassistant.domain.agent

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import org.junit.Test

class AgentStatusTest {

    // ── isTerminal ──────────────────────────────────────────────────────────

    @Test
    fun `COMPLETED is terminal`() = AgentStatus.COMPLETED.isTerminal.shouldBeTrue()

    @Test
    fun `PARTIAL is terminal`() = AgentStatus.PARTIAL.isTerminal.shouldBeTrue()

    @Test
    fun `FAILED is terminal`() = AgentStatus.FAILED.isTerminal.shouldBeTrue()

    @Test
    fun `CANCELLED is terminal`() = AgentStatus.CANCELLED.isTerminal.shouldBeTrue()

    @Test
    fun `REQUESTED is not terminal`() = AgentStatus.REQUESTED.isTerminal.shouldBeFalse()

    @Test
    fun `STARTED is not terminal`() = AgentStatus.STARTED.isTerminal.shouldBeFalse()

    @Test
    fun `RUNNING is not terminal`() = AgentStatus.RUNNING.isTerminal.shouldBeFalse()

    @Test
    fun `WAITING is not terminal`() = AgentStatus.WAITING.isTerminal.shouldBeFalse()

    // ── isSuccess ───────────────────────────────────────────────────────────

    @Test
    fun `COMPLETED is success`() = AgentStatus.COMPLETED.isSuccess.shouldBeTrue()

    @Test
    fun `PARTIAL is success`() = AgentStatus.PARTIAL.isSuccess.shouldBeTrue()

    @Test
    fun `FAILED is not success`() = AgentStatus.FAILED.isSuccess.shouldBeFalse()

    @Test
    fun `CANCELLED is not success`() = AgentStatus.CANCELLED.isSuccess.shouldBeFalse()

    @Test
    fun `RUNNING is not success`() = AgentStatus.RUNNING.isSuccess.shouldBeFalse()

    // ── canTransitionTo — valid forward transitions ─────────────────────────

    @Test
    fun `REQUESTED can transition to STARTED`() =
        AgentStatus.REQUESTED.canTransitionTo(AgentStatus.STARTED).shouldBeTrue()

    @Test
    fun `REQUESTED can transition to CANCELLED`() =
        AgentStatus.REQUESTED.canTransitionTo(AgentStatus.CANCELLED).shouldBeTrue()

    @Test
    fun `STARTED can transition to RUNNING`() =
        AgentStatus.STARTED.canTransitionTo(AgentStatus.RUNNING).shouldBeTrue()

    @Test
    fun `STARTED can transition to WAITING`() =
        AgentStatus.STARTED.canTransitionTo(AgentStatus.WAITING).shouldBeTrue()

    @Test
    fun `STARTED can transition to FAILED`() =
        AgentStatus.STARTED.canTransitionTo(AgentStatus.FAILED).shouldBeTrue()

    @Test
    fun `STARTED can transition to CANCELLED`() =
        AgentStatus.STARTED.canTransitionTo(AgentStatus.CANCELLED).shouldBeTrue()

    @Test
    fun `RUNNING can transition to WAITING`() =
        AgentStatus.RUNNING.canTransitionTo(AgentStatus.WAITING).shouldBeTrue()

    @Test
    fun `RUNNING can transition to COMPLETED`() =
        AgentStatus.RUNNING.canTransitionTo(AgentStatus.COMPLETED).shouldBeTrue()

    @Test
    fun `RUNNING can transition to PARTIAL`() =
        AgentStatus.RUNNING.canTransitionTo(AgentStatus.PARTIAL).shouldBeTrue()

    @Test
    fun `RUNNING can transition to FAILED`() =
        AgentStatus.RUNNING.canTransitionTo(AgentStatus.FAILED).shouldBeTrue()

    @Test
    fun `RUNNING can transition to CANCELLED`() =
        AgentStatus.RUNNING.canTransitionTo(AgentStatus.CANCELLED).shouldBeTrue()

    @Test
    fun `WAITING can transition to RUNNING`() =
        AgentStatus.WAITING.canTransitionTo(AgentStatus.RUNNING).shouldBeTrue()

    @Test
    fun `WAITING can transition to FAILED`() =
        AgentStatus.WAITING.canTransitionTo(AgentStatus.FAILED).shouldBeTrue()

    @Test
    fun `WAITING can transition to CANCELLED`() =
        AgentStatus.WAITING.canTransitionTo(AgentStatus.CANCELLED).shouldBeTrue()

    // ── canTransitionTo — invalid transitions ────────────────────────────────

    @Test
    fun `REQUESTED cannot transition to RUNNING directly`() =
        AgentStatus.REQUESTED.canTransitionTo(AgentStatus.RUNNING).shouldBeFalse()

    @Test
    fun `REQUESTED cannot transition to COMPLETED`() =
        AgentStatus.REQUESTED.canTransitionTo(AgentStatus.COMPLETED).shouldBeFalse()

    @Test
    fun `COMPLETED cannot transition to anything`() {
        AgentStatus.entries.forEach { next ->
            AgentStatus.COMPLETED.canTransitionTo(next).shouldBeFalse()
        }
    }

    @Test
    fun `FAILED cannot transition to anything`() {
        AgentStatus.entries.forEach { next ->
            AgentStatus.FAILED.canTransitionTo(next).shouldBeFalse()
        }
    }

    @Test
    fun `CANCELLED cannot transition to anything`() {
        AgentStatus.entries.forEach { next ->
            AgentStatus.CANCELLED.canTransitionTo(next).shouldBeFalse()
        }
    }

    @Test
    fun `PARTIAL cannot transition to anything`() {
        AgentStatus.entries.forEach { next ->
            AgentStatus.PARTIAL.canTransitionTo(next).shouldBeFalse()
        }
    }
}
