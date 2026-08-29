/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-rag
 * File       : OnDeviceRagViewModel.kt
 * Purpose    : Manages UI state for OnDeviceRagChatScreen:
 *              routing decision, on-device query streaming, cloud fallback,
 *              citations, fallback notification banner.
 *
 * Architecture Layer : Feature (feature-on-device-rag) — MVVM ViewModel.
 *                      Delegates all AI logic to domain use cases.
 *                      Never imports from data module directly.
 *
 * Dependencies       : RouteQueryUseCase, OnDeviceQueryUseCase,
 *                      SendMessageUseCase (cloud fallback via existing infra),
 *                      DispatcherProvider
 *
 * Requirements: 35.1, 35.4, 35.5, 35.8, 35.9, 36.5, 36.6, 36.7, 36.8
 * ============================================================
 */
package com.aiassistant.feature.ondevicerag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.ai.ondevicerag.CapabilityBit
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.model.OnDeviceInferencePath
import com.aiassistant.domain.model.OnDevicePathPreference
import com.aiassistant.domain.model.OnDeviceQueryEvent
import com.aiassistant.domain.usecase.ondevicerag.OnDeviceQueryUseCase
import com.aiassistant.domain.usecase.ondevicerag.RouteQueryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnDeviceRagViewModel @Inject constructor(
    private val routeQueryUseCase: RouteQueryUseCase,
    private val onDeviceQueryUseCase: OnDeviceQueryUseCase,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    // Authenticated user id — set by the screen from SavedStateHandle / auth layer
    var userId: String = "default_user"

    // User's explicit path preference — null = auto
    var pathPreference: OnDevicePathPreference? = null

    private val _uiState = MutableStateFlow<OnDeviceRagChatUiState>(OnDeviceRagChatUiState.Idle)
    val uiState: StateFlow<OnDeviceRagChatUiState> = _uiState.asStateFlow()

    // ── Public actions ────────────────────────────────────────────────────

    /**
     * Submits [query] through the routing → inference pipeline.
     *
     * Steps:
     * 1. Evaluate capability bitmask via [RouteQueryUseCase].
     * 2. If ON_DEVICE → stream via [OnDeviceQueryUseCase].
     * 3. If CLOUD → route through existing SendMessageUseCase / AIStreamClient infra
     *    (represented here as a stub — the real wiring is in feature-chat).
     */
    fun submitQuery(query: String) {
        viewModelScope.launch(dispatchers.io) {
            _uiState.value = OnDeviceRagChatUiState.Routing

            // ── 1. Evaluate routing ────────────────────────────────────────
            val bitmask = buildCapabilityBitmask()
            val routingResult = routeQueryUseCase(userId, bitmask, pathPreference)

            val decision = when (routingResult) {
                is ApiResult.Success -> routingResult.data
                else -> {
                    _uiState.value = OnDeviceRagChatUiState.Error(
                        message = "Routing failed. Please try again.",
                        stage = "router",
                    )
                    return@launch
                }
            }

            // ── 2. Route to on-device or cloud ────────────────────────────
            when (decision.path) {
                OnDeviceInferencePath.ON_DEVICE -> runOnDeviceQuery(query, decision.fallbackOccurred)
                OnDeviceInferencePath.CLOUD -> runCloudQuery(query)
            }
        }
    }

    /**
     * "Retry via cloud" action — retries the last query directly against the cloud
     * path, bypassing the routing decision.
     */
    fun retryViaCloud(query: String) {
        viewModelScope.launch(dispatchers.io) {
            runCloudQuery(query)
        }
    }

    /** Resets to idle so the user can start a new query. */
    fun reset() {
        _uiState.value = OnDeviceRagChatUiState.Idle
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private suspend fun runOnDeviceQuery(query: String, fallbackOccurred: Boolean) {
        _uiState.value = OnDeviceRagChatUiState.Searching(
            activePath = OnDeviceInferencePath.ON_DEVICE,
            fallbackBanner = fallbackOccurred,
        )

        var accumulated = ""

        onDeviceQueryUseCase(query, userId)
            .catch { e ->
                _uiState.value = OnDeviceRagChatUiState.Error(
                    message = "On-device query failed: ${e.message}",
                    stage = "generation",
                )
            }
            .collect { event ->
                when (event) {
                    is OnDeviceQueryEvent.Searching -> {
                        // Already in Searching state
                    }
                    is OnDeviceQueryEvent.Token -> {
                        accumulated += event.text
                        _uiState.value = OnDeviceRagChatUiState.Streaming(
                            activePath = OnDeviceInferencePath.ON_DEVICE,
                            accumulatedText = accumulated,
                            fallbackBanner = fallbackOccurred,
                        )
                    }
                    is OnDeviceQueryEvent.Done -> {
                        _uiState.value = OnDeviceRagChatUiState.Done(
                            activePath = OnDeviceInferencePath.ON_DEVICE,
                            responseText = accumulated,
                            citations = event.citations,
                            fallbackBanner = fallbackOccurred,
                        )
                    }
                    is OnDeviceQueryEvent.NoRelevantContent -> {
                        _uiState.value = OnDeviceRagChatUiState.NoRelevantContent
                    }
                    is OnDeviceQueryEvent.Error -> {
                        _uiState.value = OnDeviceRagChatUiState.Error(
                            message = event.message,
                            stage = event.stage,
                            canRetry = true,
                        )
                    }
                }
            }
    }

    private suspend fun runCloudQuery(query: String) {
        // Cloud path — delegates to the existing SendMessageUseCase / AIStreamClient
        // infrastructure used by feature-chat.  Here we set the UI state to indicate
        // cloud routing; the actual streaming is handled by the existing chat infra
        // when integrated at the app level.
        _uiState.value = OnDeviceRagChatUiState.Searching(
            activePath = OnDeviceInferencePath.CLOUD,
            fallbackBanner = false,
        )
        // TODO: Wire to SendMessageUseCase / AIStreamClient when integrating at app level.
        // For now emit a placeholder Done state so the screen is functional.
        _uiState.value = OnDeviceRagChatUiState.Done(
            activePath = OnDeviceInferencePath.CLOUD,
            responseText = "[Cloud response for: \"${query.take(60)}\"]",
            citations = emptyList(),
            fallbackBanner = false,
        )
    }

    /**
     * Builds the 4-bit capability bitmask from live signals.
     *
     * In production each signal is checked via the relevant manager/repository.
     * Overridable in tests by subclassing or providing a test double.
     */
    internal open fun buildCapabilityBitmask(): Int {
        // TODO: Replace stubs with live checks once all components are wired at app level:
        // bit 0 = Gemma model files verified
        // bit 1 = EmbeddingModel.isReady
        // bit 2 = LocalVectorIndex has ≥1 chunk for userId
        // bit 3 = network reachable
        return CapabilityBit.FULLY_CAPABLE // defaults to ON_DEVICE-capable in tests
    }
}
