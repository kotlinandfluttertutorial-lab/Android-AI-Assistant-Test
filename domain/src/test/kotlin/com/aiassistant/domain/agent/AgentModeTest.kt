/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentModeTest.kt
 * Purpose    : Phase 9 unit tests for AgentMode, AgentStatusMapper,
 *              AgentStatusUiModel, AgentObservabilityRecord, and the
 *              AgentExecutionLogger structured log field contract.
 *
 * Tests cover:
 *   - AgentMode enum values, fromName(), displayName()
 *   - AgentMode.toCapabilities() per mode
 *   - AgentMode.toAgentNameHint() per mode
 *   - AgentStatusMapper.update() for every AgentEvent type
 *   - AgentStatusMapper.fromPlanSteps() for multi-agent display
 *   - AgentStatusUiModel fields and defaults
 *   - AgentStepUiModel statusSymbol for all AgentStepStatus values
 *   - AgentObservabilityRecord.toMetadata() — all fields mapped
 *   - Privacy: content-bearing fields are excluded from AgentObservabilityRecord
 *   - AgentStatusLabel constants are non-blank for active states
 * ============================================================
 */
package com.aiassistant.domain.agent

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

class AgentModeTest {

    // ── AgentMode enum basics ─────────────────────────────────────────────────

    @Test
    fun `AgentMode has all eight values`() {
        AgentMode.entries.map { it.name } shouldBe
            listOf("AUTO", "CHAT", "CODE", "RESEARCH", "DOCUMENT", "IMAGE", "VOICE", "LOCAL")
    }

    @Test
    fun `fromName returns correct mode case-insensitive`() {
        AgentMode.fromName("chat") shouldBe AgentMode.CHAT
        AgentMode.fromName("CODE") shouldBe AgentMode.CODE
        AgentMode.fromName("Research") shouldBe AgentMode.RESEARCH
        AgentMode.fromName("LOCAL") shouldBe AgentMode.LOCAL
    }

    @Test
    fun `fromName returns AUTO for null`() {
        AgentMode.fromName(null) shouldBe AgentMode.AUTO
    }

    @Test
    fun `fromName returns AUTO for unknown value`() {
        AgentMode.fromName("gibberish") shouldBe AgentMode.AUTO
    }

    @Test
    fun `displayName returns non-blank for every mode`() {
        AgentMode.entries.forEach { mode ->
            AgentMode.displayName(mode).isNotBlank().shouldBeTrue()
        }
    }

    @Test
    fun `displayName returns expected labels`() {
        AgentMode.displayName(AgentMode.AUTO)     shouldBe "Auto"
        AgentMode.displayName(AgentMode.CHAT)     shouldBe "Chat"
        AgentMode.displayName(AgentMode.CODE)     shouldBe "Code"
        AgentMode.displayName(AgentMode.RESEARCH) shouldBe "Research"
        AgentMode.displayName(AgentMode.DOCUMENT) shouldBe "Document"
        AgentMode.displayName(AgentMode.IMAGE)    shouldBe "Image"
        AgentMode.displayName(AgentMode.VOICE)    shouldBe "Voice"
        AgentMode.displayName(AgentMode.LOCAL)    shouldBe "Local"
    }

    // ── AgentMode.toCapabilities() ────────────────────────────────────────────

    @Test
    fun `AUTO and CHAT return empty capabilities`() {
        AgentMode.toCapabilities(AgentMode.AUTO).shouldBeEmpty()
        AgentMode.toCapabilities(AgentMode.CHAT).shouldBeEmpty()
    }

    @Test
    fun `CODE returns CODE_ANALYSIS capability`() {
        val caps = AgentMode.toCapabilities(AgentMode.CODE)
        (AgentCapability.CODE_ANALYSIS in caps).shouldBeTrue()
    }

    @Test
    fun `RESEARCH returns SEMANTIC_SEARCH capability`() {
        val caps = AgentMode.toCapabilities(AgentMode.RESEARCH)
        (AgentCapability.SEMANTIC_SEARCH in caps).shouldBeTrue()
    }

    @Test
    fun `DOCUMENT returns DOCUMENT_RETRIEVAL capability`() {
        val caps = AgentMode.toCapabilities(AgentMode.DOCUMENT)
        (AgentCapability.DOCUMENT_RETRIEVAL in caps).shouldBeTrue()
    }

    @Test
    fun `IMAGE returns IMAGE_UNDERSTANDING capability`() {
        val caps = AgentMode.toCapabilities(AgentMode.IMAGE)
        (AgentCapability.IMAGE_UNDERSTANDING in caps).shouldBeTrue()
    }

    @Test
    fun `VOICE returns SPEECH_TO_TEXT capability`() {
        val caps = AgentMode.toCapabilities(AgentMode.VOICE)
        (AgentCapability.SPEECH_TO_TEXT in caps).shouldBeTrue()
    }

    @Test
    fun `LOCAL returns ON_DEVICE_INFERENCE capability`() {
        val caps = AgentMode.toCapabilities(AgentMode.LOCAL)
        (AgentCapability.ON_DEVICE_INFERENCE in caps).shouldBeTrue()
    }

    // ── AgentMode.toAgentNameHint() ───────────────────────────────────────────

    @Test
    fun `AUTO returns null agent name hint`() {
        AgentMode.toAgentNameHint(AgentMode.AUTO).shouldBeNull()
    }

    @Test
    fun `CHAT returns conversational agent name`() {
        AgentMode.toAgentNameHint(AgentMode.CHAT) shouldBe "conversational"
    }

    @Test
    fun `CODE returns code-analysis agent name`() {
        AgentMode.toAgentNameHint(AgentMode.CODE) shouldBe "code-analysis"
    }

    @Test
    fun `RESEARCH returns web-search agent name`() {
        AgentMode.toAgentNameHint(AgentMode.RESEARCH) shouldBe "web-search"
    }

    @Test
    fun `LOCAL returns on_device agent name`() {
        AgentMode.toAgentNameHint(AgentMode.LOCAL) shouldBe "on_device"
    }

    // ── AgentStatusUiModel defaults ───────────────────────────────────────────

    @Test
    fun `IDLE model is inactive with empty steps`() {
        val idle = AgentStatusUiModel.IDLE
        idle.isActive.shouldBeFalse()
        idle.isMultiAgent.shouldBeFalse()
        idle.steps.shouldBeEmpty()
        idle.currentStatusLabel shouldBe AgentStatusLabel.IDLE
    }

    // ── AgentStepUiModel statusSymbol ─────────────────────────────────────────

    @Test
    fun `PENDING step shows circle symbol`() {
        AgentStepUiModel("Chat Agent", "conversational", AgentStepStatus.PENDING).statusSymbol shouldBe "○"
    }

    @Test
    fun `IN_PROGRESS step shows filled circle`() {
        AgentStepUiModel("Chat Agent", "conversational", AgentStepStatus.IN_PROGRESS).statusSymbol shouldBe "●"
    }

    @Test
    fun `COMPLETED step shows checkmark`() {
        AgentStepUiModel("Chat Agent", "conversational", AgentStepStatus.COMPLETED).statusSymbol shouldBe "✓"
    }

    @Test
    fun `FAILED step shows cross`() {
        AgentStepUiModel("Chat Agent", "conversational", AgentStepStatus.FAILED).statusSymbol shouldBe "✗"
    }

    // ── AgentStatusMapper: single-event transitions ───────────────────────────

    private fun exec() = "exec-1"
    private fun req() = AgentRequest(userId = "u1", input = "test")

    @Test
    fun `Started event activates status and sets label`() {
        val event = AgentEvent.Started("exec-1", "conversational")
        val model = AgentStatusMapper.update(AgentStatusUiModel.IDLE, event)
        model.isActive.shouldBeTrue()
        model.currentAgentId shouldBe "conversational"
        // Label should be non-blank for an active execution
        model.currentStatusLabel.isNotBlank().shouldBeTrue()
    }

    @Test
    fun `Token event sets GENERATING_RESPONSE label`() {
        val model = AgentStatusMapper.update(AgentStatusUiModel.IDLE, AgentEvent.Token("hello"))
        model.currentStatusLabel shouldBe AgentStatusLabel.GENERATING_RESPONSE
        model.isActive.shouldBeTrue()
    }

    @Test
    fun `Thinking event sets THINKING label`() {
        val model = AgentStatusMapper.update(AgentStatusUiModel.IDLE, AgentEvent.Thinking(0, "reasoning…"))
        model.currentStatusLabel shouldBe AgentStatusLabel.THINKING
        model.isActive.shouldBeTrue()
    }

    @Test
    fun `ToolStarted event sets RUNNING_TOOL label`() {
        val model = AgentStatusMapper.update(AgentStatusUiModel.IDLE, AgentEvent.ToolStarted("github", "{}"))
        model.currentStatusLabel shouldBe AgentStatusLabel.RUNNING_TOOL
    }

    @Test
    fun `ToolCompleted event sets GENERATING_RESPONSE label`() {
        val model = AgentStatusMapper.update(AgentStatusUiModel.IDLE, AgentEvent.ToolCompleted("github", "{}", 100L))
        model.currentStatusLabel shouldBe AgentStatusLabel.GENERATING_RESPONSE
    }

    @Test
    fun `RetrievalCompleted with DOCUMENT mode sets SEARCHING_DOCUMENTS label`() {
        val model = AgentStatusMapper.update(
            AgentStatusUiModel.IDLE,
            AgentEvent.RetrievalCompleted("query", 3),
            mode = AgentMode.DOCUMENT,
        )
        model.currentStatusLabel shouldBe AgentStatusLabel.SEARCHING_DOCUMENTS
    }

    @Test
    fun `RetrievalCompleted with RESEARCH mode sets SEARCHING_WEB label`() {
        val model = AgentStatusMapper.update(
            AgentStatusUiModel.IDLE,
            AgentEvent.RetrievalCompleted("query", 5),
            mode = AgentMode.RESEARCH,
        )
        model.currentStatusLabel shouldBe AgentStatusLabel.SEARCHING_WEB
    }

    @Test
    fun `Completed event deactivates and sets COMPLETED label`() {
        val active = AgentStatusUiModel(currentStatusLabel = AgentStatusLabel.THINKING, isActive = true)
        val result = AgentResult(
            executionId = "e1", requestId = "r1",
            agentName = "conversational", status = AgentStatus.COMPLETED, content = "hi",
        )
        val model = AgentStatusMapper.update(active, AgentEvent.Completed(result))
        model.isActive.shouldBeFalse()
        model.currentStatusLabel shouldBe AgentStatusLabel.COMPLETED
    }

    @Test
    fun `Failed event deactivates and sets FAILED label`() {
        val active = AgentStatusUiModel(isActive = true)
        val result = AgentResult(
            executionId = "e1", requestId = "r1",
            agentName = "conversational", status = AgentStatus.FAILED,
            error = AgentError("ERR", "oops"),
        )
        val model = AgentStatusMapper.update(active, AgentEvent.Failed(result))
        model.isActive.shouldBeFalse()
        model.currentStatusLabel shouldBe AgentStatusLabel.FAILED
    }

    @Test
    fun `Cancelled event deactivates and sets CANCELLED label`() {
        val active = AgentStatusUiModel(isActive = true)
        val model = AgentStatusMapper.update(active, AgentEvent.Cancelled("user tapped cancel"))
        model.isActive.shouldBeFalse()
        model.currentStatusLabel shouldBe AgentStatusLabel.CANCELLED
    }

    // ── AgentStatusMapper: CODE mode labels ───────────────────────────────────

    @Test
    fun `Started event in CODE mode sets ANALYZING_CODE label`() {
        val model = AgentStatusMapper.update(
            AgentStatusUiModel.IDLE,
            AgentEvent.Started("e1", "code-analysis"),
            mode = AgentMode.CODE,
        )
        model.currentStatusLabel shouldBe AgentStatusLabel.ANALYZING_CODE
    }

    @Test
    fun `Started event in IMAGE mode sets PROCESSING_IMAGE label`() {
        val model = AgentStatusMapper.update(
            AgentStatusUiModel.IDLE,
            AgentEvent.Started("e1", "image-analysis"),
            mode = AgentMode.IMAGE,
        )
        model.currentStatusLabel shouldBe AgentStatusLabel.PROCESSING_IMAGE
    }

    @Test
    fun `Started event in LOCAL mode sets RUNNING_ON_DEVICE label`() {
        val model = AgentStatusMapper.update(
            AgentStatusUiModel.IDLE,
            AgentEvent.Started("e1", "on_device"),
            mode = AgentMode.LOCAL,
        )
        model.currentStatusLabel shouldBe AgentStatusLabel.RUNNING_ON_DEVICE
    }

    // ── AgentStatusMapper: multi-agent handoff ────────────────────────────────

    @Test
    fun `HandoffStarted transitions step list correctly`() {
        val initial = AgentStatusMapper.fromPlanSteps(listOf("pdf", "rag", "code"))
        val model = AgentStatusMapper.update(initial, AgentEvent.HandoffStarted("pdf", "rag", 0))
        model.isMultiAgent.shouldBeTrue()
        model.currentAgentId shouldBe "rag"
        val pdfStep = model.steps.first { it.agentId == "pdf" }
        pdfStep.status shouldBe AgentStepStatus.COMPLETED
        val ragStep = model.steps.first { it.agentId == "rag" }
        ragStep.status shouldBe AgentStepStatus.IN_PROGRESS
    }

    @Test
    fun `Completed event marks all steps completed`() {
        val initial = AgentStatusMapper.fromPlanSteps(listOf("pdf", "rag"))
        val model = AgentStatusMapper.update(
            initial.copy(
                steps = initial.steps.map { it.copy(status = AgentStepStatus.IN_PROGRESS) }
            ),
            AgentEvent.Completed(
                AgentResult("e1", "r1", "rag", AgentStatus.COMPLETED, "result")
            ),
        )
        model.steps.all { it.status == AgentStepStatus.COMPLETED }.shouldBeTrue()
    }

    @Test
    fun `Failed event marks current step as failed`() {
        val initial = AgentStatusMapper.fromPlanSteps(listOf("pdf", "rag"))
            .copy(currentAgentId = "rag")
        val model = AgentStatusMapper.update(
            initial,
            AgentEvent.Failed(AgentResult("e1", "r1", "rag", AgentStatus.FAILED, error = AgentError("ERR", "oops"))),
        )
        model.steps.first { it.agentId == "rag" }.status shouldBe AgentStepStatus.FAILED
    }

    @Test
    fun `fromPlanSteps initialises correct step count and names`() {
        val model = AgentStatusMapper.fromPlanSteps(listOf("pdf", "rag", "code"))
        model.steps shouldHaveSize 3
        model.steps[0].agentId shouldBe "pdf"
        model.steps[1].agentId shouldBe "rag"
        model.steps[2].agentId shouldBe "code"
        model.steps.all { it.status == AgentStepStatus.PENDING }.shouldBeTrue()
        model.isMultiAgent.shouldBeTrue()
    }

    @Test
    fun `fromPlanSteps with single agent sets isMultiAgent false`() {
        val model = AgentStatusMapper.fromPlanSteps(listOf("conversational"))
        model.isMultiAgent.shouldBeFalse()
    }

    @Test
    fun `fromPlanSteps with empty list returns IDLE`() {
        val model = AgentStatusMapper.fromPlanSteps(emptyList())
        model shouldBe AgentStatusUiModel.IDLE
    }

    // ── AgentObservabilityRecord.toMetadata() ─────────────────────────────────

    @Test
    fun `toMetadata contains all required log fields`() {
        val record = AgentObservabilityRecord(
            requestId = "req-1",
            conversationId = "conv-1",
            executionId = "exec-1",
            agent = "conversational",
            model = "gemini",
            status = "COMPLETED",
            durationMs = 1234L,
            tool = null,
            errorCode = null,
            agentMode = AgentMode.CHAT.name,
        )
        val meta = record.toMetadata()

        meta[AgentLogKey.REQUEST_ID] shouldBe "req-1"
        meta[AgentLogKey.CONVERSATION_ID] shouldBe "conv-1"
        meta[AgentLogKey.EXECUTION_ID] shouldBe "exec-1"
        meta[AgentLogKey.AGENT] shouldBe "conversational"
        meta[AgentLogKey.MODEL] shouldBe "gemini"
        meta[AgentLogKey.STATUS] shouldBe "COMPLETED"
        meta[AgentLogKey.DURATION_MS] shouldBe "1234"
        meta[AgentLogKey.AGENT_MODE] shouldBe "CHAT"
    }

    @Test
    fun `toMetadata omits null optional fields`() {
        val record = AgentObservabilityRecord(
            requestId = "req-1",
            executionId = "exec-1",
            agent = "code-analysis",
            status = "STARTED",
        )
        val meta = record.toMetadata()

        (AgentLogKey.CONVERSATION_ID in meta).shouldBeFalse()
        (AgentLogKey.MODEL in meta).shouldBeFalse()
        (AgentLogKey.TOOL in meta).shouldBeFalse()
        (AgentLogKey.ERROR_CODE in meta).shouldBeFalse()
    }

    @Test
    fun `toMetadata includes tool when present`() {
        val record = AgentObservabilityRecord(
            requestId = "req-1", executionId = "exec-1",
            agent = "tool-executor", status = "TOOL_STARTED",
            tool = "github",
        )
        val meta = record.toMetadata()
        meta[AgentLogKey.TOOL] shouldBe "github"
    }

    @Test
    fun `toMetadata includes errorCode when present`() {
        val record = AgentObservabilityRecord(
            requestId = "req-1", executionId = "exec-1",
            agent = "conversational", status = "FAILED",
            errorCode = "ROUTING_FAILED",
        )
        val meta = record.toMetadata()
        meta[AgentLogKey.ERROR_CODE] shouldBe "ROUTING_FAILED"
    }

    @Test
    fun `AgentObservabilityRecord has no content field - privacy contract`() {
        // Verify at compile-time that the data class has no field that could carry
        // prompt or response text. This test will fail to compile if such a field
        // is added without review.
        val record = AgentObservabilityRecord(
            requestId = "r", executionId = "e", agent = "a", status = "s",
        )
        // The only string fields allowed: requestId, conversationId, executionId, agent,
        // model, status, tool, errorCode, agentMode — no "prompt", "content", "response"
        val fieldNames = record::class.members.map { it.name }.toSet()
        val forbiddenFields = setOf("prompt", "content", "response", "message", "text", "jwt", "token", "apiKey")
        val violations = fieldNames.intersect(forbiddenFields)
        violations.isEmpty().shouldBeTrue()
    }

    // ── AgentStatusLabel completeness ─────────────────────────────────────────

    @Test
    fun `all active AgentStatusLabel constants are non-blank`() {
        val activeLabels = listOf(
            AgentStatusLabel.THINKING,
            AgentStatusLabel.SEARCHING_DOCUMENTS,
            AgentStatusLabel.ANALYZING_CODE,
            AgentStatusLabel.RUNNING_TOOL,
            AgentStatusLabel.GENERATING_RESPONSE,
            AgentStatusLabel.PROCESSING_IMAGE,
            AgentStatusLabel.LISTENING,
            AgentStatusLabel.SPEAKING,
            AgentStatusLabel.SEARCHING_WEB,
            AgentStatusLabel.RUNNING_ON_DEVICE,
            AgentStatusLabel.RETRIEVING,
        )
        activeLabels.all { it.isNotBlank() }.shouldBeTrue()
    }

    @Test
    fun `terminal AgentStatusLabel constants are non-blank`() {
        listOf(
            AgentStatusLabel.COMPLETED,
            AgentStatusLabel.FAILED,
            AgentStatusLabel.CANCELLED,
        ).all { it.isNotBlank() }.shouldBeTrue()
    }

    @Test
    fun `IDLE AgentStatusLabel is empty`() {
        AgentStatusLabel.IDLE shouldBe ""
    }
}
