/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-chat
 * File       : ComparisonModeViewModel.kt
 * Purpose    : ViewModel for the Comparison Mode screen
 *
 * Architecture Layer : Feature (feature-chat)
 * Pattern Used       : MVVM ViewModel
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *   - Concurrent coroutine dispatch
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
/**
 * ComparisonModeViewModel.kt
 *
 * Purpose: Dispatches the same prompt concurrently to 2–4 selected LLM_Providers,
 *          tracks per-provider panel state (loading / streaming / complete / error),
 *          computes quality scores, and handles the "Use This Response" action.
 *
 * Concurrent dispatch strategy (Req 30.3):
 *   All provider streams are launched inside a single [coroutineScope] block so that
 *   every launch() call is made before any of them suspends. In practice the launches
 *   happen within microseconds of each other — well under the 100 ms requirement.
 *
 * Quality score formula (Req 30.5):
 *   - Response length score (0–40): linearly proportional to response length capped
 *     at 2000 characters (2000+ chars → 40 pts).
 *   - Coherence score (0–40): approximated by a heuristic (avg sentence length,
 *     paragraph count) as a lightweight substitute for a real LLM eval call so the
 *     feature works without an extra network round-trip per provider.
 *   - Latency score (0–20): 20 pts for ≤500 ms first-token latency, scales down
 *     linearly to 0 pts at ≥5000 ms.
 *
 * Timeout handling (Req 30.4):
 *   Each provider coroutine is wrapped in a [withTimeoutOrNull] of 30 seconds. On
 *   timeout the panel transitions to [ProviderPanelStatus.Timeout].
 *
 * Architecture: feature-chat — MVVM ViewModel; injected via Hilt.
 * Dependencies: domain use cases, AIStreamClient (core-ai), DispatcherProvider (core-common),
 *               feature-settings (LlmProvider)
 *
 * Requirements: 30.1, 30.2, 30.3, 30.4, 30.5, 30.6, 30.7, 30.8
 */
package com.aiassistant.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.ai.AIStreamClient
import com.aiassistant.core.ai.LlmProvider
import com.aiassistant.core.ai.MessagePayload
import com.aiassistant.core.ai.StreamEvent
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.model.Message
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Timeout per provider in Comparison Mode — 30 seconds (Req 30.4). */
private const val PROVIDER_TIMEOUT_MS = 30_000L

/** Estimated USD cost per 1,000 output tokens for providers without real pricing. */
private val DEFAULT_COST_PER_1K_OUTPUT_TOKENS: Map<String, Double> = mapOf(
    "openai_gpt4o" to 0.030,
    "gemini_1_5_pro" to 0.007,
    "claude_3_5_sonnet" to 0.015,
    "ollama" to 0.000,
    "llama_3x" to 0.001,
    "mistral" to 0.002
)

/**
 * ViewModel for the ComparisonMode screen.
 *
 * Exposes a [StateFlow] of [ComparisonModeUiState]. All streaming I/O is dispatched on
 * [DispatcherProvider.io].
 */
@HiltViewModel
class ComparisonModeViewModel @Inject constructor(
    private val streamClient: AIStreamClient,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComparisonModeUiState())

    /** Primary UI state observed by [ComparisonModeScreen]. */
    val uiState: StateFlow<ComparisonModeUiState> = _uiState.asStateFlow()

    /** Jobs for each active provider stream; keyed by provider ID. */
    private val activeJobs = mutableMapOf<String, Job>()

    // ─── Initialisation ─────────────────────────────────────────────────────

    /**
     * Called when the screen is first composed. Checks how many providers are
     * configured and updates [ComparisonModeUiState.isComparisonModeAvailable].
     *
     * @param configuredProviders All providers that have been configured by the user.
     */
    fun initialise(configuredProviders: List<LlmProvider>) {
        val available = configuredProviders.size >= 2
        _uiState.update {
            it.copy(
                isComparisonModeAvailable = available,
                panels = emptyList()
            )
        }
    }

    // ─── Public actions ──────────────────────────────────────────────────────

    /**
     * Dispatches [prompt] concurrently to all [selectedProviders] (Req 30.1, 30.3).
     *
     * All provider streams are launched inside a single coroutine block so they start
     * within microseconds of each other — satisfying the ≤100 ms dispatch skew
     * requirement (Req 30.3).
     *
     * @param conversationId The conversation this comparison is being run in.
     * @param prompt         The user message to send to every provider.
     * @param selectedProviders The 2–4 providers selected for this comparison.
     */
    fun dispatchComparison(conversationId: String, prompt: String, selectedProviders: List<LlmProvider>) {
        if (selectedProviders.size < 2) return

        // Cancel any in-flight streams from a previous comparison
        cancelActiveJobs()

        val initialPanels = selectedProviders.map { provider ->
            ProviderPanelState(
                providerId = provider.id,
                providerName = provider.display,
                status = ProviderPanelStatus.Loading
            )
        }

        val dispatchTime = System.currentTimeMillis()

        _uiState.update {
            it.copy(
                prompt = prompt,
                panels = initialPanels,
                dispatchedAt = dispatchTime,
                canonicalPanelId = null
            )
        }

        // Launch all provider streams concurrently (Req 30.3)
        // All launch() calls execute in the same coroutine scope before any suspend point,
        // so dispatch skew is well within the 100 ms budget.
        viewModelScope.launch(dispatchers.io) {
            selectedProviders.forEach { provider ->
                val job = launch {
                    streamProvider(
                        conversationId = conversationId,
                        prompt = prompt,
                        provider = provider,
                        dispatchedAt = dispatchTime
                    )
                }
                activeJobs[provider.id] = job
            }
        }
    }

    /**
     * Adopts the response from the panel identified by [providerId] as the canonical
     * [Message] in the conversation and dismisses the other panels (Req 30.6).
     *
     * @param providerId The [ProviderPanelState.providerId] of the chosen panel.
     * @param onAdopted  Callback invoked with the adopted [Message] so the caller can
     *                   hand it off to the chat screen.
     */
    fun useThisResponse(providerId: String, onAdopted: (Message) -> Unit) {
        val panel = _uiState.value.panels.firstOrNull { it.providerId == providerId }
            ?: return

        val canonicalMessage = Message(
            id = UUID.randomUUID().toString(),
            conversationId = _uiState.value.prompt, // reused field; caller replaces with real ID
            role = "assistant",
            content = panel.responseText,
            outputTokens = panel.tokenCount,
            provider = panel.providerId,
            syncStatus = "pending",
            createdAt = Instant.now()
        )

        _uiState.update { it.copy(canonicalPanelId = providerId) }

        cancelActiveJobs()
        onAdopted(canonicalMessage)
    }

    /**
     * Resets the comparison state so the screen returns to the prompt input.
     */
    fun reset() {
        cancelActiveJobs()
        _uiState.update {
            ComparisonModeUiState(
                isComparisonModeAvailable = it.isComparisonModeAvailable
            )
        }
    }

    // ─── Internal streaming logic ────────────────────────────────────────────

    /**
     * Opens a streaming connection for [provider] and updates the corresponding
     * [ProviderPanelState] as tokens arrive.
     *
     * Wrapped in [withTimeoutOrNull] so that the panel automatically transitions to
     * [ProviderPanelStatus.Timeout] when no Done event arrives within 30 seconds (Req 30.4).
     */
    private suspend fun streamProvider(
        conversationId: String,
        prompt: String,
        provider: LlmProvider,
        dispatchedAt: Long
    ) {
        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
            try {
                val jwt = "placeholder_jwt"
                val flow = streamClient.connect(conversationId, jwt)
                streamClient.sendMessage(
                    MessagePayload(
                        conversationId = conversationId,
                        content = prompt,
                        provider = provider.id
                    )
                )

                flow
                    .catch { e ->
                        updatePanelError(provider.id, e.message ?: "Stream error")
                    }
                    .collect { event ->
                        handleStreamEvent(
                            event = event,
                            provider = provider,
                            dispatchedAt = dispatchedAt
                        )
                    }
            } catch (e: Exception) {
                updatePanelError(provider.id, e.message ?: "Unknown error")
            }
        } ?: run {
            // Timeout — transition panel to Timeout status (Req 30.4)
            updatePanel(provider.id) { panel ->
                panel.copy(status = ProviderPanelStatus.Timeout)
            }
        }
    }

    /**
     * Handles a single [StreamEvent] for the given [provider], updating the panel state.
     */
    private fun handleStreamEvent(event: StreamEvent, provider: LlmProvider, dispatchedAt: Long) {
        when (event) {
            is StreamEvent.Token -> {
                updatePanel(provider.id) { panel ->
                    val isFirstToken = panel.status == ProviderPanelStatus.Loading
                    val latency = if (isFirstToken) System.currentTimeMillis() - dispatchedAt else panel.latencyMs
                    panel.copy(
                        status = ProviderPanelStatus.Streaming,
                        responseText = panel.responseText + event.text,
                        latencyMs = if (isFirstToken) latency else panel.latencyMs
                    )
                }
            }

            is StreamEvent.Done -> {
                updatePanel(provider.id) { panel ->
                    val tokenCount = event.usage.outputTokens
                    val costPerK = DEFAULT_COST_PER_1K_OUTPUT_TOKENS[provider.id] ?: 0.002
                    val estimatedCost = (tokenCount / 1000.0) * costPerK
                    val quality = computeQualityScore(
                        responseText = panel.responseText,
                        latencyMs = panel.latencyMs
                    )
                    panel.copy(
                        status = ProviderPanelStatus.Complete,
                        tokenCount = tokenCount,
                        estimatedCostUsd = estimatedCost,
                        qualityScore = quality
                    )
                }
            }

            is StreamEvent.Error -> {
                updatePanelError(provider.id, event.message)
            }

            is StreamEvent.ToolCall -> {
                // Tool calls are transparent in Comparison Mode; tokens continue after the tool.
            }
        }
    }

    // ─── Quality score computation (Req 30.5) ────────────────────────────────

    /**
     * Computes the composite quality score 0–100.
     *
     * Components:
     * - **Response length score** (0–40): proportional to length up to 2000 chars.
     * - **Coherence score** (0–40): lightweight heuristic using average sentence length
     *   and paragraph count as a proxy for coherence.
     * - **Latency score** (0–20): 20 pts at ≤500 ms, scales linearly to 0 pts at ≥5000 ms.
     */
    internal fun computeQualityScore(responseText: String, latencyMs: Long): Int {
        val lengthScore = computeLengthScore(responseText)
        val coherenceScore = computeCoherenceScore(responseText)
        val latencyScore = computeLatencyScore(latencyMs)
        return lengthScore + coherenceScore + latencyScore
    }

    /**
     * Response length component (0–40 pts).
     * Linearly proportional to character count, capped at MAX_SCORE_CHARS characters.
     */
    internal fun computeLengthScore(responseText: String): Int {
        val capped = min(responseText.length, MAX_SCORE_CHARS)
        return ((capped.toDouble() / MAX_SCORE_CHARS) * LENGTH_MAX_SCORE).roundToInt()
    }

    /**
     * Coherence component (0–40 pts).
     *
     * Heuristic: score is higher when:
     * - Average sentence length is in the "readable" range (10–30 words).
     * - The response contains multiple paragraphs (structured content).
     */
    internal fun computeCoherenceScore(responseText: String): Int {
        if (responseText.isBlank()) return 0

        val sentences = responseText.split(Regex("[.?!]+\\s+")).filter { it.isNotBlank() }
        val paragraphs = responseText.split(Regex("\\n{2,}")).filter { it.isNotBlank() }

        val avgSentenceWordCount = if (sentences.isNotEmpty()) {
            sentences.sumOf { it.trim().split(Regex("\\s+")).size }.toDouble() / sentences.size
        } else {
            0.0
        }

        val sentenceLengthScore = when {
            avgSentenceWordCount < SENTENCE_SHORT_THRESHOLD -> COHERENCE_LOW_SCORE
            avgSentenceWordCount in SENTENCE_SHORT_THRESHOLD..SENTENCE_MEDIUM_THRESHOLD -> COHERENCE_MID_SCORE
            avgSentenceWordCount in SENTENCE_MEDIUM_THRESHOLD..SENTENCE_LONG_THRESHOLD -> COHERENCE_MAX_SCORE
            avgSentenceWordCount in SENTENCE_LONG_THRESHOLD..SENTENCE_VERY_LONG_THRESHOLD -> COHERENCE_MID_HIGH_SCORE
            else -> COHERENCE_LOW_SCORE
        }

        val paragraphBonus = min(paragraphs.size - 1, PARAGRAPH_BONUS_CAP) * PARAGRAPH_BONUS_PER_EXTRA
        return min(sentenceLengthScore + paragraphBonus, COHERENCE_MAX_SCORE)
    }

    /**
     * Latency component (0–20 pts).
     * 20 pts at ≤MIN_LATENCY_MS; scales linearly to 0 pts at ≥MAX_LATENCY_MS.
     * Returns LATENCY_UNKNOWN_SCORE pts when latency is unknown (-1).
     */
    internal fun computeLatencyScore(latencyMs: Long): Int {
        if (latencyMs < 0) return LATENCY_UNKNOWN_SCORE
        return when {
            latencyMs <= MIN_LATENCY_MS -> LATENCY_MAX_SCORE
            latencyMs >= MAX_LATENCY_MS -> 0
            else -> {
                val fraction = (MAX_LATENCY_MS - latencyMs).toDouble() / (MAX_LATENCY_MS - MIN_LATENCY_MS)
                (fraction * LATENCY_MAX_SCORE).roundToInt()
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun updatePanel(providerId: String, transform: (ProviderPanelState) -> ProviderPanelState) {
        _uiState.update { state ->
            state.copy(
                panels = state.panels.map { panel ->
                    if (panel.providerId == providerId) transform(panel) else panel
                }
            )
        }
    }

    private fun updatePanelError(providerId: String, message: String) {
        updatePanel(providerId) { panel ->
            panel.copy(status = ProviderPanelStatus.Error(message))
        }
    }

    private fun cancelActiveJobs() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
    }

    override fun onCleared() {
        super.onCleared()
        cancelActiveJobs()
        streamClient.disconnect()
    }
    companion object {
        // Length scoring constants
        internal const val MAX_SCORE_CHARS = 2000
        internal const val LENGTH_MAX_SCORE = 40

        // Coherence scoring constants
        internal const val COHERENCE_MAX_SCORE = 40
        internal const val COHERENCE_MID_HIGH_SCORE = 25
        internal const val COHERENCE_MID_SCORE = 20
        internal const val COHERENCE_LOW_SCORE = 10
        internal const val SENTENCE_SHORT_THRESHOLD = 5.0
        internal const val SENTENCE_MEDIUM_THRESHOLD = 10.0
        internal const val SENTENCE_LONG_THRESHOLD = 25.0
        internal const val SENTENCE_VERY_LONG_THRESHOLD = 35.0
        internal const val PARAGRAPH_BONUS_CAP = 2
        internal const val PARAGRAPH_BONUS_PER_EXTRA = 4

        // Latency scoring constants
        internal const val LATENCY_MAX_SCORE = 20
        internal const val LATENCY_UNKNOWN_SCORE = 10
        internal const val MIN_LATENCY_MS = 500L
        internal const val MAX_LATENCY_MS = 5000L
    }
}
