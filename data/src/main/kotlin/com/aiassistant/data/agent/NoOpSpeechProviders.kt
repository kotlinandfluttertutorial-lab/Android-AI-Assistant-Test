/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : NoOpSpeechProviders.kt
 * Purpose    : No-op implementations of SpeechToTextProvider and
 *              TextToSpeechProvider that satisfy the Hilt DI graph.
 *
 *              The real platform-backed implementations are in
 *              VoiceAssistantManager (composable-owned). These stubs
 *              keep the Singleton component valid until a proper
 *              Android-platform adapter is wired into the graph.
 *
 * Architecture Layer : Data — agent sub-package
 * Pattern Used       : Null Object / Stub
 * ============================================================
 */

package com.aiassistant.data.agent

import com.aiassistant.domain.agent.SpeechToTextProvider
import com.aiassistant.domain.agent.SttEvent
import com.aiassistant.domain.agent.TextToSpeechProvider
import com.aiassistant.domain.agent.TtsEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * No-op [SpeechToTextProvider] that reports unavailability.
 *
 * [isAvailable] is `false` so [VoiceAgent] will emit an appropriate
 * error event rather than silently hanging.  Replace this binding with
 * a real Android STT adapter when the voice feature is wired end-to-end.
 */
@Singleton
class NoOpSpeechToTextProvider @Inject constructor() : SpeechToTextProvider {

    override val isAvailable: Boolean = false

    override fun listen(languageTag: String): Flow<SttEvent> = emptyFlow()
}

/**
 * No-op [TextToSpeechProvider] that reports unavailability.
 *
 * [isAvailable] is `false` so callers will skip the TTS phase gracefully.
 * Replace this binding with a real Android TTS adapter when ready.
 */
@Singleton
class NoOpTextToSpeechProvider @Inject constructor() : TextToSpeechProvider {

    override val isAvailable: Boolean = false

    override fun speak(text: String, languageTag: String): Flow<TtsEvent> = emptyFlow()

    override fun stop() = Unit
}
