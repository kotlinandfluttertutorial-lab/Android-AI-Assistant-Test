/**
 * LlmProvider.kt
 *
 * Purpose: Canonical enum of all LLM provider options selectable by the user.
 *          Moved to core-ai so feature modules (feature-chat, feature-settings,
 *          feature-on-device-ai) can all depend on this shared definition without
 *          creating forbidden feature→feature dependencies (Requirement 19.2).
 *
 * Architecture: core-ai — shared AI infrastructure layer.
 *
 * @param id      The canonical identifier string stored in User.activeProvider.
 * @param display The human-readable label shown in the provider selector.
 */
package com.aiassistant.core.ai

/**
 * The provider id string used to identify on-device inference in MessagePayload
 * and DataStore settings. Defined here alongside [LlmProvider] so both can be
 * co-located and feature modules can reference the constant from core-ai.
 */
const val ON_DEVICE_PROVIDER_ID = "on_device"

/**
 * Represents the known LLM provider options the user can select in the Settings screen.
 *
 * [ON_DEVICE] is only shown in the provider selector when the device meets the NPU/GPU
 * threshold defined in Requirement 31.1. SettingsUiState.Settings.availableProviders
 * is filtered accordingly before rendering.
 */
enum class LlmProvider(val id: String, val display: String) {
    OPENAI_GPT4O("openai_gpt4o", "OpenAI GPT-4o"),
    GEMINI_1_5_PRO("gemini_1_5_pro", "Gemini 1.5 Pro"),
    CLAUDE_3_5_SONNET("claude_3_5_sonnet", "Claude 3.5 Sonnet"),
    OLLAMA("ollama", "Ollama (self-hosted)"),
    LLAMA_3X("llama_3x", "Llama 3.x"),
    MISTRAL("mistral", "Mistral"),

    /**
     * On-device inference provider. Only shown in the Settings screen when
     * OnDeviceCapabilityAvailability.isAvailable is true (Requirement 31.1).
     */
    ON_DEVICE(ON_DEVICE_PROVIDER_ID, "On-Device (Private)");

    companion object {
        /**
         * Returns the [LlmProvider] matching [id], or [OPENAI_GPT4O] as the safe default
         * when no match is found.
         */
        fun fromId(id: String): LlmProvider = entries.firstOrNull { it.id == id } ?: OPENAI_GPT4O
    }
}
