/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : OnDeviceAgent.kt
 * Purpose    : Agent that runs inference entirely on the device using the
 *              existing on-device Gemma engine — making ZERO network calls.
 *
 * Architecture Layer : Data — agent sub-package
 * Pattern Used       : Adapter (implements Agent from domain)
 *
 * Key Concepts:
 *   - Depends only on [OnDeviceInferencePort] (domain interface) — never on
 *     feature-on-device-ai directly (preserves dependency direction)
 *   - Declares [AgentCapability.ON_DEVICE_INFERENCE] so DefaultAgentRouter
 *     routes requests with that capability here
 *   - Supports all three routing modes (AUTO / LOCAL_ONLY / CLOUD)
 *   - LOCAL_ONLY: never falls back to cloud; emits a clear FAILED event
 *   - Network isolation: OnDeviceInferencePort contract forbids HTTP calls
 *   - Cancellation: Flow cancellation propagates into OnDeviceInferenceClient's
 *     awaitClose block, stopping the active inference job
 *   - Hardware gating is handled upstream by OnDeviceAiInitializer; this agent
 *     only reacts to isReady / error events — it does NOT re-run hardware checks
 *
 * Request metadata keys:
 *   "agent_name"      — explicit routing to this agent (value: "on_device")
 *   "routing_mode"    — "AUTO" | "LOCAL_ONLY" | "CLOUD" (default "AUTO")
 *   "conversation_id" — conversation UUID for logging
 *
 * Streaming protocol:
 *   Started → StatusChanged(RUNNING) → Token × N → Completed | Failed
 *
 * Dependencies:
 *   - domain (OnDeviceInferencePort, OnDeviceStreamEvent, OnDeviceErrorCode,
 *             ModelRoutingMode, Agent, AgentCapability, AgentEvent)
 *
 * Requirements: 31.2 (no network), 31.3 (routing), 31.4 (RAM), 31.8 (offline)
 * ============================================================
 */

package com.aiassistant.data.agent

import com.aiassistant.domain.agent.Agent
import com.aiassistant.domain.agent.AgentCapability
import com.aiassistant.domain.agent.AgentError
import com.aiassistant.domain.agent.AgentEvent
import com.aiassistant.domain.agent.AgentExecution
import com.aiassistant.domain.agent.AgentRequest
import com.aiassistant.domain.agent.AgentResult
import com.aiassistant.domain.agent.AgentStatus
import com.aiassistant.domain.agent.AgentUsage
import com.aiassistant.domain.agent.ModelRoutingMode
import com.aiassistant.domain.agent.OnDeviceErrorCode
import com.aiassistant.domain.agent.OnDeviceInferencePort
import com.aiassistant.domain.agent.OnDeviceStreamEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber

/**
 * [Agent] that runs inference entirely on-device via [OnDeviceInferencePort].
 *
 * ## Network isolation
 * This agent does not import OkHttp, Retrofit, or any networking library.
 * All inference is delegated to [OnDeviceInferencePort], whose contract
 * forbids outbound HTTP connections.
 *
 * ## Routing
 * The routing mode is read from `request.metadata["routing_mode"]`.
 * [ModelRoutingMode.LOCAL_ONLY] prevents silent cloud fallback.
 *
 * ## Cancellation
 * When the collecting coroutine is cancelled (e.g. ViewModel scope destroyed),
 * the Flow produced by [OnDeviceInferencePort.infer] is cancelled.  The port
 * implementation propagates cancellation into [OnDeviceInferenceClient]'s
 * `awaitClose` block, stopping the active inference job.
 *
 * ## Hardware gating
 * Hardware checks (RAM ≥ 4 GB, NPU/GPU presence) are performed by
 * [com.aiassistant.feature.ondeviceai.OnDeviceAiInitializer] at startup and
 * cached.  This agent does NOT re-run hardware checks on every request.
 */
@Singleton
class OnDeviceAgent @Inject constructor(
    private val inferencePort: OnDeviceInferencePort,
) : Agent {

    override val name: String = NAME

    override val description: String =
        "On-device inference agent. Runs Gemma locally with zero network calls. " +
            "Supports AUTO, LOCAL_ONLY, and CLOUD routing modes."

    override val capabilities: Set<AgentCapability> = setOf(
        AgentCapability.ON_DEVICE_INFERENCE,
        AgentCapability.TEXT_GENERATION,
        AgentCapability.STREAMING,
    )

    /**
     * Returns `true` when:
     * - An explicit `agent_name` metadata key targets this agent, OR
     * - The request declares [AgentCapability.ON_DEVICE_INFERENCE], OR
     * - The routing mode in metadata is [ModelRoutingMode.LOCAL_ONLY].
     *
     * Returns `false` for [ModelRoutingMode.CLOUD] requests so cloud-only
     * requests route to [ChatAgent] rather than here.
     */
    override fun canHandle(request: AgentRequest): Boolean {
        val explicitName = request.metadata[METADATA_AGENT_NAME]
        if (!explicitName.isNullOrBlank()) return explicitName == name

        val mode = ModelRoutingMode.fromName(request.metadata[METADATA_ROUTING_MODE])
        if (mode == ModelRoutingMode.CLOUD) return false

        return AgentCapability.ON_DEVICE_INFERENCE in request.capabilities ||
            mode == ModelRoutingMode.LOCAL_ONLY
    }

    override fun execute(
        request: AgentRequest,
        execution: AgentExecution,
    ): Flow<AgentEvent> = flow {

        emit(AgentEvent.Started(execution.executionId, name))
        emit(AgentEvent.StatusChanged(execution.executionId, AgentStatus.RUNNING))

        val routingMode = ModelRoutingMode.fromName(request.metadata[METADATA_ROUTING_MODE])
        val conversationId = request.conversationId
            ?: request.metadata[METADATA_CONVERSATION_ID]

        Timber.d(
            "OnDeviceAgent: executing (mode=%s, ready=%s, convId=%s)",
            routingMode, inferencePort.isReady, conversationId,
        )

        var accumulated = ""
        var inputTokens = 0
        var outputTokens = 0
        var errored = false

        inferencePort.infer(
            prompt = request.input,
            conversationId = conversationId,
            routingMode = routingMode,
        ).collect { event ->
            when (event) {
                is OnDeviceStreamEvent.Token -> {
                    accumulated += event.text
                    emit(AgentEvent.Token(event.text))
                }

                is OnDeviceStreamEvent.Done -> {
                    inputTokens = event.inputTokens
                    outputTokens = event.outputTokens
                    // Flow will complete; Completed is emitted below
                }

                is OnDeviceStreamEvent.Error -> {
                    errored = true
                    val errorCode = mapErrorCode(event.code, routingMode)
                    Timber.w("OnDeviceAgent: inference error [%s]: %s", event.code, event.message)
                    emit(
                        AgentEvent.Failed(
                            AgentResult(
                                executionId = execution.executionId,
                                requestId = request.requestId,
                                agentName = name,
                                status = AgentStatus.FAILED,
                                error = AgentError(
                                    code = errorCode,
                                    message = event.message,
                                ),
                                metadata = mapOf(
                                    "routing_mode" to routingMode.name,
                                    "on_device_ready" to inferencePort.isReady.toString(),
                                ),
                            )
                        )
                    )
                }
            }
        }

        if (!errored) {
            emit(
                AgentEvent.Completed(
                    AgentResult(
                        executionId = execution.executionId,
                        requestId = request.requestId,
                        agentName = name,
                        status = AgentStatus.COMPLETED,
                        content = accumulated.takeIf { it.isNotBlank() },
                        usage = AgentUsage(
                            inputTokens = inputTokens,
                            outputTokens = outputTokens,
                        ),
                        metadata = mapOf(
                            "routing_mode" to routingMode.name,
                            "on_device_ready" to inferencePort.isReady.toString(),
                            "provider" to "on_device",
                        ),
                    )
                )
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Maps [OnDeviceErrorCode] to a string code for [AgentError].
     * LOCAL_ONLY failures use a dedicated code so callers can distinguish
     * "could not run on-device" from "device not capable at all".
     */
    private fun mapErrorCode(
        code: OnDeviceErrorCode,
        routingMode: ModelRoutingMode,
    ): String = when (code) {
        OnDeviceErrorCode.LOCAL_ONLY_UNAVAILABLE -> "LOCAL_ONLY_UNAVAILABLE"
        OnDeviceErrorCode.MODEL_NOT_READY -> "ON_DEVICE_MODEL_NOT_READY"
        OnDeviceErrorCode.INSUFFICIENT_MEMORY -> "ON_DEVICE_INSUFFICIENT_MEMORY"
        OnDeviceErrorCode.CANCELLED -> "ON_DEVICE_CANCELLED"
        OnDeviceErrorCode.UNKNOWN -> if (routingMode == ModelRoutingMode.LOCAL_ONLY) {
            "LOCAL_ONLY_ERROR"
        } else {
            "ON_DEVICE_ERROR"
        }
    }

    companion object {
        const val NAME = "on_device"

        /** Metadata key for explicit agent routing. */
        const val METADATA_AGENT_NAME = "agent_name"

        /** Metadata key for [ModelRoutingMode]. Values: "AUTO", "LOCAL_ONLY", "CLOUD". */
        const val METADATA_ROUTING_MODE = "routing_mode"

        /** Metadata key for conversation ID (alternative to AgentRequest.conversationId). */
        const val METADATA_CONVERSATION_ID = "conversation_id"
    }
}
