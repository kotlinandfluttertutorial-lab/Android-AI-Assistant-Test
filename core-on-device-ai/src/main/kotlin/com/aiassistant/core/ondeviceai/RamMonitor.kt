/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-ai
 * File       : RamMonitor.kt
 * Purpose    : Polls available RAM during on-device inference and emits a signal
 *              when it drops below the 512 MB safety threshold so callers can
 *              cancel the in-progress inference and switch to the cloud provider.
 *
 * Architecture Layer : Feature (feature-on-device-ai)
 * Pattern Used       : Service / Reactive monitoring
 *
 * Key Concepts:
 *   - Polls ActivityManager.MemoryInfo on a configurable interval
 *   - Emits RamEvent.BelowThreshold once when RAM first crosses below 512 MB
 *   - Flow-based API; callers combine this with the inference Flow
 *
 * Dependencies:
 *   - android.app.ActivityManager
 *   - kotlinx.coroutines.flow
 *
 * Requirements: 31.4
 * ============================================================
 */

package com.aiassistant.feature.ondeviceai

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/** 512 MB in bytes — the minimum safe free RAM threshold during inference (Requirement 31.4). */
const val RAM_THRESHOLD_BYTES = 512L * 1024L * 1024L

/** Polling interval in milliseconds. */
private const val POLL_INTERVAL_MS = 500L

/** Events emitted by [RamMonitor.observe]. */
sealed class RamEvent {
    /** RAM is currently sufficient for inference. */
    data class Sufficient(val availableBytes: Long) : RamEvent()

    /**
     * Available RAM has fallen below [RAM_THRESHOLD_BYTES].
     * The caller should cancel the current inference request.
     */
    data class BelowThreshold(val availableBytes: Long) : RamEvent()
}

/**
 * Observes free RAM during on-device inference.
 *
 * Usage in [OnDeviceInferenceClient]:
 * ```kotlin
 * ramMonitor.observe()
 *     .filter { it is RamEvent.BelowThreshold }
 *     .first()  // suspends until threshold crossed
 *     .let { cancelInference() }
 * ```
 *
 * Requirement: 31.4
 */
@Singleton
class RamMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    /**
     * Returns a [Flow] that emits a [RamEvent] every [pollIntervalMs] milliseconds until
     * the collector cancels.
     *
     * @param pollIntervalMs How often to sample memory (default 500 ms).
     */
    fun observe(pollIntervalMs: Long = POLL_INTERVAL_MS): Flow<RamEvent> = flow {
        while (true) {
            val available = queryAvailableMem()
            val event = if (available < RAM_THRESHOLD_BYTES) {
                RamEvent.BelowThreshold(available)
            } else {
                RamEvent.Sufficient(available)
            }
            emit(event)
            delay(pollIntervalMs)
        }
    }

    /**
     * Returns the current available memory in bytes without starting a polling loop.
     * Useful for a one-shot pre-flight check before starting inference.
     */
    fun availableMemoryBytes(): Long = queryAvailableMem()

    private fun queryAvailableMem(): Long {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.availMem
    }
}
