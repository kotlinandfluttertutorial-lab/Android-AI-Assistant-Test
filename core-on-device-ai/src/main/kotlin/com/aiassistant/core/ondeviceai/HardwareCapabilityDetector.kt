/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-ai
 * File       : HardwareCapabilityDetector.kt
 * Purpose    : Detects whether the device has a qualified NPU or dedicated GPU with
 *              ≥4 GB available memory. Used at startup to decide whether to expose
 *              on-device inference as a selectable LLM provider.
 *
 * Architecture Layer : Feature (feature-on-device-ai)
 * Pattern Used       : Service / Utility
 *
 * Key Concepts:
 *   - Queries ActivityManager.MemoryInfo for available RAM
 *   - Uses EGL 1.4 extension strings to detect an NPU/GPU vendor string
 *   - Result is cached so the heavy EGL initialisation runs only once
 *
 * Dependencies:
 *   - android.app.ActivityManager
 *   - android.opengl.EGL14
 *   - javax.inject.Inject (Hilt)
 *
 * Requirements: 31.1
 * ============================================================
 */

package com.aiassistant.feature.ondeviceai

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Threshold in bytes for dedicated GPU/NPU memory required to offer on-device inference.
 * 4 GB expressed in bytes.
 */
private const val REQUIRED_MEMORY_BYTES = 4L * 1024L * 1024L * 1024L // 4 GB

/**
 * Known NPU / AI-accelerator vendor substrings found in EGL_VENDOR or EGL_RENDERER strings.
 * Matching any entry indicates the presence of a dedicated neural processing unit or
 * high-performance GPU capable of running INT4 quantized models efficiently.
 */
private val NPU_GPU_VENDOR_HINTS = listOf(
    "qualcomm",   // Snapdragon Hexagon NPU / Adreno GPU
    "adreno",     // Adreno GPU
    "mali",       // ARM Mali GPU (typically NPU companion)
    "apple",      // Apple ANE (for reference)
    "mediatek",   // MediaTek APU
    "dimensity",  // MediaTek Dimensity NPU
    "nvidia",     // NVIDIA GPU (tablets)
    "arm",        // ARM GPU
    "hexagon",    // Qualcomm Hexagon DSP/NPU
    "npu",        // Generic NPU string
    "neural",     // Generic neural accelerator
)

/**
 * Descriptor of a device's NPU / GPU capability as assessed by [HardwareCapabilityDetector].
 *
 * @param isSupported    True when the device meets both the memory and accelerator criteria.
 * @param availableBytes Available memory visible to the process at detection time.
 * @param vendorInfo     EGL vendor/renderer string(s) discovered, or null if EGL failed.
 */
data class HardwareCapability(
    val isSupported: Boolean,
    val availableBytes: Long,
    val vendorInfo: String?,
)

/**
 * Detects whether the device qualifies for on-device AI inference.
 *
 * Detection runs in two steps:
 * 1. Query [ActivityManager.MemoryInfo.availMem] — must be ≥ 4 GB.
 * 2. Query EGL vendor and renderer extension strings — at least one NPU/GPU hint must
 *    be present (case-insensitive substring match).
 *
 * The result is computed lazily and cached after the first call.
 *
 * Requirement: 31.1
 */
@Singleton
class HardwareCapabilityDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val tag = "HardwareCapabilityDetector"

    /** Cached result; null means not yet evaluated. */
    @Volatile
    private var cached: HardwareCapability? = null

    /**
     * Returns the hardware capability, computing it on the first call.
     *
     * Safe to call from any thread; EGL context is created and destroyed within this function.
     */
    fun detect(): HardwareCapability {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: computeCapability().also { cached = it }
        }
    }

    private fun computeCapability(): HardwareCapability {
        val availableBytes = queryAvailableMemory()
        val vendorInfo = queryEglVendorInfo()

        val memoryOk = availableBytes >= REQUIRED_MEMORY_BYTES
        val acceleratorOk = vendorInfo != null && hasNpuOrGpuHint(vendorInfo)
        val supported = memoryOk && acceleratorOk

        Log.i(
            tag,
            "HW capability: available=${availableBytes / (1024 * 1024)} MB, " +
                "vendor='$vendorInfo', " +
                "memoryOk=$memoryOk, acceleratorOk=$acceleratorOk, supported=$supported",
        )

        return HardwareCapability(
            isSupported = supported,
            availableBytes = availableBytes,
            vendorInfo = vendorInfo,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Memory query
    // ─────────────────────────────────────────────────────────────────────────

    private fun queryAvailableMemory(): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.availMem
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EGL vendor / renderer detection
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Opens a temporary off-screen EGL 1.4 display to read vendor and renderer strings.
     * Returns a combined vendor info string, or null on failure.
     *
     * The display is always terminated before returning to avoid resource leaks.
     */
    private fun queryEglVendorInfo(): String? {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                Log.w(tag, "EGL: no default display available")
                return null
            }

            val versionMajor = IntArray(1)
            val versionMinor = IntArray(1)
            if (!EGL14.eglInitialize(display, versionMajor, 0, versionMinor, 0)) {
                Log.w(tag, "EGL: eglInitialize failed (error ${EGL14.eglGetError()})")
                return null
            }

            val vendor = EGL14.eglQueryString(display, EGL14.EGL_VENDOR) ?: ""
            val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS) ?: ""

            // Also attempt to read the renderer via a minimal surface/context
            val renderer = queryGlRenderer(display)

            buildString {
                if (vendor.isNotBlank()) append(vendor)
                if (renderer != null) {
                    if (isNotEmpty()) append(" | ")
                    append(renderer)
                }
                if (extensions.isNotBlank()) {
                    if (isNotEmpty()) append(" | ")
                    append(extensions)
                }
            }.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(tag, "EGL vendor query failed: ${e.message}")
            null
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglTerminate(display)
            }
        }
    }

    /**
     * Creates a minimal EGL context to read the GL_RENDERER string.
     * Falls back to null if any EGL step fails.
     */
    private fun queryGlRenderer(display: EGLDisplay): String? {
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        return try {
            val attribList = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0)) {
                return null
            }
            val config = configs[0] ?: return null

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) return null

            // We don't need a real surface; EGL_NO_SURFACE is sufficient for renderer query on
            // most drivers. The call may fail on strict drivers — that's acceptable.
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, context)

            android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER)
        } catch (e: Exception) {
            null
        } finally {
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context)
            }
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
        }
    }

    private fun hasNpuOrGpuHint(info: String): Boolean {
        val lower = info.lowercase()
        return NPU_GPU_VENDOR_HINTS.any { lower.contains(it) }
    }
}
