/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentStatusUiModel.kt
 * Purpose    : Maps AgentEvent stream to human-readable status strings
 *              for the Agent UX progress display.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Mapper / value object
 *
 * Key Concepts:
 *   - Pure Kotlin, zero Android/framework dependencies
 *   - AgentStatusUiModel is consumed by ViewModels to drive the status banner
 *   - Maps all 15+ AgentEvent types to user-friendly strings
 *   - Multi-agent workflows are shown as a step list (one per plan step)
 *   - NEVER exposes internal prompt text — only structural status
 *
 * Phase 9 requirement:
 *   "Display: Thinking / Searching documents / Analyzing code / Running tool /
 *    Generating response / Completed"
 *   "For multi-agent: Code Agent ✓ / RAG Agent ✓ / Tool Agent ● / Final response ..."
 *   "Do not expose internal prompts."
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.serialization.Serializable

// ── Status display string ─────────────────────────────────────────────────────

/**
 * Human-readable status labels for the agent progress banner.
 *
 * All values are plain, untranslated English strings. Localisation is the
 * responsibility of the presentation layer (string resources).
 */
object AgentStatusLabel {
    const val THINKING             = "Thinking…"
    const val SEARCHING_DOCUMENTS  = "Searching documents…"
    const val ANALYZING_CODE       = "Analyzing code…"
    const val RUNNING_TOOL         = "Running tool…"
    const val GENERATING_RESPONSE  = "Generating response…"
    const val COMPLETED            = "Completed"
    const val FAILED               = "Something went wrong"
    const val CANCELLED            = "Cancelled"
    const val PROCESSING_IMAGE     = "Analyzing image…"
    const val LISTENING            = "Listening…"
    const val SPEAKING             = "Speaking…"
    const val SEARCHING_WEB        = "Searching the web…"
    const val RUNNING_ON_DEVICE    = "Running on-device…"
    const val RETRIEVING           = "Retrieving information…"
    const val IDLE                 = ""
}

// ── Step status for multi-agent display ──────────────────────────────────────

/**
 * Completion state of a single step in a multi-agent plan.
 */
enum class AgentStepStatus {
    /** Step has not started yet. */
    PENDING,
    /** Step is currently executing (●). */
    IN_PROGRESS,
    /** Step completed successfully (✓). */
    COMPLETED,
    /** Step failed (✗). */
    FAILED,
}

/**
 * One entry in the multi-agent progress list.
 *
 * @param agentName    Display-friendly name for this agent (e.g. "Code Agent").
 * @param agentId      Internal agent identifier (e.g. "code-analysis").
 * @param status       Current [AgentStepStatus].
 * @param durationMs   Elapsed duration for this step, 0 while in progress.
 */
@Serializable
data class AgentStepUiModel(
    val agentName: String,
    val agentId: String,
    val status: AgentStepStatus = AgentStepStatus.PENDING,
    val durationMs: Long = 0L,
) {
    /** Symbol shown next to the step name in the UI. */
    val statusSymbol: String get() = when (status) {
        AgentStepStatus.PENDING     -> "○"
        AgentStepStatus.IN_PROGRESS -> "●"
        AgentStepStatus.COMPLETED   -> "✓"
        AgentStepStatus.FAILED      -> "✗"
    }
}

// ── Top-level UiModel ─────────────────────────────────────────────────────────

/**
 * Complete agent execution status for the progress banner in the chat UI.
 *
 * @param currentStatusLabel  Short status string (see [AgentStatusLabel]).
 * @param isActive            True while the execution is in progress.
 * @param isMultiAgent        True when more than one agent is in the plan.
 * @param steps               Ordered list of [AgentStepUiModel] for multi-agent display.
 * @param currentAgentId      Id of the currently executing agent (for step highlighting).
 * @param elapsedMs           Total elapsed time since execution started.
 */
@Serializable
data class AgentStatusUiModel(
    val currentStatusLabel: String = AgentStatusLabel.IDLE,
    val isActive: Boolean = false,
    val isMultiAgent: Boolean = false,
    val steps: List<AgentStepUiModel> = emptyList(),
    val currentAgentId: String = "",
    val elapsedMs: Long = 0L,
) {
    companion object {
        val IDLE = AgentStatusUiModel()
    }
}

// ── Mapper ───────────────────────────────────────────────────────────────────

/**
 * Maps a stream of [AgentEvent] values to an incrementally updated [AgentStatusUiModel].
 *
 * ## Usage (in a ViewModel)
 * ```kotlin
 * var agentStatus = AgentStatusUiModel.IDLE
 * agentGateway.executeChat(...).collect { event ->
 *     agentStatus = AgentStatusMapper.update(agentStatus, event, mode)
 *     _uiState.update { it.copy(agentStatus = agentStatus) }
 * }
 * ```
 *
 * ## Privacy
 * This mapper NEVER includes the content of agent prompts, user messages, or LLM
 * responses. Only structural metadata (agent names, step counts, durations) appears
 * in the returned [AgentStatusUiModel].
 */
object AgentStatusMapper {

    /**
     * Returns a new [AgentStatusUiModel] reflecting [event].
     *
     * @param current  The existing status model to evolve.
     * @param event    The [AgentEvent] that just arrived.
     * @param mode     The [AgentMode] so status labels can be customised per mode.
     * @param startMs  The epoch-ms when the execution started (for elapsed time).
     */
    fun update(
        current: AgentStatusUiModel,
        event: AgentEvent,
        mode: AgentMode = AgentMode.AUTO,
        startMs: Long = 0L,
    ): AgentStatusUiModel {
        val elapsed = if (startMs > 0L) System.currentTimeMillis() - startMs else current.elapsedMs

        return when (event) {
            is AgentEvent.Started -> current.copy(
                currentStatusLabel = labelForMode(mode, event.agentName),
                isActive = true,
                currentAgentId = event.agentName,
                elapsedMs = elapsed,
                steps = updateStep(current.steps, event.agentName, AgentStepStatus.IN_PROGRESS),
            )

            is AgentEvent.StatusChanged -> current.copy(
                currentStatusLabel = labelForStatus(event.status, mode),
                isActive = !event.status.isTerminal,
                elapsedMs = elapsed,
            )

            is AgentEvent.Thinking -> current.copy(
                currentStatusLabel = AgentStatusLabel.THINKING,
                isActive = true,
                elapsedMs = elapsed,
            )

            is AgentEvent.Token -> current.copy(
                currentStatusLabel = AgentStatusLabel.GENERATING_RESPONSE,
                isActive = true,
                elapsedMs = elapsed,
            )

            is AgentEvent.ToolStarted -> current.copy(
                currentStatusLabel = AgentStatusLabel.RUNNING_TOOL,
                isActive = true,
                elapsedMs = elapsed,
            )

            is AgentEvent.ToolCompleted -> current.copy(
                currentStatusLabel = AgentStatusLabel.GENERATING_RESPONSE,
                isActive = true,
                elapsedMs = elapsed,
            )

            is AgentEvent.ToolFailed -> current.copy(
                currentStatusLabel = AgentStatusLabel.RUNNING_TOOL,
                isActive = true,
                elapsedMs = elapsed,
            )

            is AgentEvent.ToolConfirmationRequired -> current.copy(
                currentStatusLabel = AgentStatusLabel.RUNNING_TOOL,
                isActive = true,
                elapsedMs = elapsed,
            )

            is AgentEvent.RetrievalCompleted -> current.copy(
                currentStatusLabel = labelForRetrievalMode(mode),
                isActive = true,
                elapsedMs = elapsed,
            )

            is AgentEvent.HandoffStarted -> current.copy(
                currentStatusLabel = labelForMode(mode, event.toAgent),
                isActive = true,
                currentAgentId = event.toAgent,
                isMultiAgent = true,
                elapsedMs = elapsed,
                steps = updateStep(
                    updateStep(current.steps, event.fromAgent, AgentStepStatus.COMPLETED, elapsed),
                    event.toAgent, AgentStepStatus.IN_PROGRESS,
                ),
            )

            is AgentEvent.HandoffCompleted -> current.copy(
                elapsedMs = elapsed,
            )

            is AgentEvent.Completed -> current.copy(
                currentStatusLabel = AgentStatusLabel.COMPLETED,
                isActive = false,
                elapsedMs = elapsed,
                steps = markAllCompleted(current.steps),
            )

            is AgentEvent.Failed -> current.copy(
                currentStatusLabel = AgentStatusLabel.FAILED,
                isActive = false,
                elapsedMs = elapsed,
                steps = markCurrentFailed(current.steps, current.currentAgentId),
            )

            is AgentEvent.Cancelled -> current.copy(
                currentStatusLabel = AgentStatusLabel.CANCELLED,
                isActive = false,
                elapsedMs = elapsed,
            )
        }
    }

    /**
     * Initialise a multi-agent [AgentStatusUiModel] from a list of plan step names.
     *
     * Call this when the plan is known before execution starts (e.g. when
     * `metadata[METADATA_KEY_PLAN_STEPS]` is present on the request).
     */
    fun fromPlanSteps(agentNames: List<String>): AgentStatusUiModel {
        if (agentNames.isEmpty()) return AgentStatusUiModel.IDLE
        val steps = agentNames.map { AgentStepUiModel(
            agentName = displayNameForAgent(it),
            agentId = it,
            status = AgentStepStatus.PENDING,
        )}
        return AgentStatusUiModel(
            isActive = false,
            isMultiAgent = agentNames.size > 1,
            steps = steps,
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun labelForMode(mode: AgentMode, agentId: String): String = when {
        agentId == "on_device" || mode == AgentMode.LOCAL -> AgentStatusLabel.RUNNING_ON_DEVICE
        agentId == "voice"     || mode == AgentMode.VOICE -> AgentStatusLabel.LISTENING
        agentId == "image-analysis" || mode == AgentMode.IMAGE -> AgentStatusLabel.PROCESSING_IMAGE
        agentId == "web-search" || mode == AgentMode.RESEARCH -> AgentStatusLabel.SEARCHING_WEB
        agentId == "code-analysis" || mode == AgentMode.CODE -> AgentStatusLabel.ANALYZING_CODE
        agentId == "rag" || mode == AgentMode.DOCUMENT -> AgentStatusLabel.SEARCHING_DOCUMENTS
        else -> AgentStatusLabel.THINKING
    }

    private fun labelForStatus(status: AgentStatus, mode: AgentMode): String = when {
        status == AgentStatus.COMPLETED -> AgentStatusLabel.COMPLETED
        status == AgentStatus.FAILED    -> AgentStatusLabel.FAILED
        status == AgentStatus.CANCELLED -> AgentStatusLabel.CANCELLED
        status == AgentStatus.WAITING   -> AgentStatusLabel.RUNNING_TOOL
        status == AgentStatus.RUNNING   -> AgentStatusLabel.GENERATING_RESPONSE
        else                            -> AgentStatusLabel.THINKING
    }

    private fun labelForRetrievalMode(mode: AgentMode): String = when (mode) {
        AgentMode.RESEARCH -> AgentStatusLabel.SEARCHING_WEB
        AgentMode.CODE     -> AgentStatusLabel.ANALYZING_CODE
        AgentMode.DOCUMENT -> AgentStatusLabel.SEARCHING_DOCUMENTS
        else               -> AgentStatusLabel.RETRIEVING
    }

    private fun updateStep(
        steps: List<AgentStepUiModel>,
        agentId: String,
        newStatus: AgentStepStatus,
        durationMs: Long = 0L,
    ): List<AgentStepUiModel> {
        val existing = steps.any { it.agentId == agentId }
        return if (existing) {
            steps.map { step ->
                if (step.agentId == agentId) {
                    step.copy(status = newStatus, durationMs = if (durationMs > 0) durationMs else step.durationMs)
                } else step
            }
        } else {
            // Add new step dynamically (single-agent or dynamically discovered)
            steps + AgentStepUiModel(
                agentName = displayNameForAgent(agentId),
                agentId = agentId,
                status = newStatus,
                durationMs = durationMs,
            )
        }
    }

    private fun markAllCompleted(steps: List<AgentStepUiModel>): List<AgentStepUiModel> =
        steps.map { if (it.status != AgentStepStatus.FAILED) it.copy(status = AgentStepStatus.COMPLETED) else it }

    private fun markCurrentFailed(steps: List<AgentStepUiModel>, currentAgentId: String): List<AgentStepUiModel> =
        steps.map { if (it.agentId == currentAgentId) it.copy(status = AgentStepStatus.FAILED) else it }

    private fun displayNameForAgent(agentId: String): String = when (agentId) {
        "conversational" -> "Chat Agent"
        "code-analysis"  -> "Code Agent"
        "rag"            -> "RAG Agent"
        "pdf"            -> "PDF Agent"
        "tool-executor"  -> "Tool Agent"
        "web-search"     -> "Web Agent"
        "image-analysis" -> "Image Agent"
        "voice"          -> "Voice Agent"
        "on_device"      -> "Local Agent"
        else             -> agentId.replaceFirstChar { it.uppercase() } + " Agent"
    }
}
