/**
 * DeviceCapabilityDetector.kt
 *
 * Purpose: Detects whether the current device has an NPU or dedicated GPU with ≥4 GB
 *          available memory, which is the threshold required to offer on-device AI
 *          inference as a selectable LLM_Provider option.
 *
 * Architecture: feature-on-device-ai — hardware capability query; no domain/data deps.
 * Dependencies: Android framework (ActivityManager, EGL), core-common (DispatcherProvider)
 *
 * Design decisions:
 * - EGL is used to query the GPU vendor and renderer strings because Android has no
 *   public NPU API before Android 14. Presence of "NPU", "Hexagon", or "CoreML" in the
 *   renderer string is treated as an NPU signal.
 * - Memory threshold is evaluated against ActivityManager.MemoryInfo.availMem because
 *   availMem reflects memory actually available to a new process at query time. totalMem
 *   is intentionally NOT used: a device may advertise 8 GB total but have < 4 GB free
 *   due to system and other app usage.
 * - The detector is deliberately conservative: if EGL initialisation fails or the
 *   renderer string cannot be retrieved, the method returns false rather than throwing.
 * - Detection runs on the IO dispatcher so it does not block the main thread.
 *
 * Requirements: 31.1
 */
package com.aiassistant.feature.ondeviceai

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import com.aiassistant.core.common.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Minimum available memory in bytes to qualify for on-device inference (4 GB). */
private const val MIN_AVAILABLE_MEMORY_BYTES = 4L * 1024L * 1024L * 1024L

/** Vendor/renderer string fragments that indicate an NPU or AI-optimised GPU. */
private val NPU_VENDOR_KEYWORDS = listOf(
    "NPU", "npu",
    "Hexagon",       // Qualcomm NPU / Hexagon DSP
    "CoreML",        // Apple silicon (not expected on Android but defensive)
    "NeuroPilot",    // MediaTek APU
    "MLPE",          // Samsung Exynos NPU
    "Turing",        // NVIDIA (Tegra-based Android devices)
    "Adreno",        // Qualcomm Adreno GPU
    "Mali",          // ARM Mali GPU
    "PowerVR",       // PowerVR GPU
)

/**
 * Queries the device's hardware capability to determine whether on-device inference is
 * supported.
 *
 * The check succeeds when both conditions hold:
 * 1. The EGL renderer string contains a keyword associated with an NPU or dedicated GPU.
 * 2. At least 4 GB of RAM is available according to [ActivityManager.MemoryInfo].
 */
@Singleton
class DeviceCapabilityDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) {

    /**
     * Returns `true` when the device has an NPU/GPU with ≥4 GB available memory.
     *
     * Runs on [DispatcherProvider.io] to avoid blocking the main thread.
     *
     * @return `true` if the device meets the on-device inference threshold.
     */
    suspend fun isOnDeviceInferenceSupported(): Boolean = withContext(dispatchers.io) {
        hasNpuOrDedicatedGpu() && hasEnoughAvailableMemory()
    }

    /**
     * Returns `true` when EGL reports a vendor/renderer string containing at least one
     * NPU/GPU keyword. Falls back to `false` if EGL cannot be initialised.
     */
    internal fun hasNpuOrDedicatedGpu(): Boolean {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return false

            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false

            // Choose a simple config — we only need the renderer string
            val attribList = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0)) {
                return false
            }
            if (numConfigs[0] == 0) return false

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) return false

            val pbAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], pbAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) return false

            EGL14.eglMakeCurrent(display, surface, surface, context)

            val renderer = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER).orEmpty()
            val vendor = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VENDOR).orEmpty()

            NPU_VENDOR_KEYWORDS.any { keyword ->
                renderer.contains(keyword, ignoreCase = true) ||
                    vendor.contains(keyword, ignoreCase = true)
            }
        } catch (_: Exception) {
            false
        } finally {
            // Always clean up EGL resources
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }
    }

    /**
     * Returns `true` when [ActivityManager.MemoryInfo.availMem] reports ≥4 GB.
     */
    internal fun hasEnoughAvailableMemory(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.availMem >= MIN_AVAILABLE_MEMORY_BYTES
    }
}
