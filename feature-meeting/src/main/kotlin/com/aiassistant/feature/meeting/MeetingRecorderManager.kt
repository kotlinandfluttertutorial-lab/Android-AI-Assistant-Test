/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-meeting
 * File       : MeetingRecorderManager.kt
 * Purpose    : MeetingRecorderManager — feature-meeting module component
 *
 * Architecture Layer : Feature (feature-meeting)
 * Pattern Used       : Kotlin Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-meeting
 * File       : MeetingRecorderManager.kt
 * Purpose    : MeetingRecorderManager — feature-meeting module component
 *
 * Architecture Layer : Feature (feature-meeting)
 * Pattern Used       : Kotlin Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
/**
 * MeetingRecorderManager.kt
 *
 * Purpose: Android-aware helper class that wraps MediaRecorder for meeting audio capture.
 *          Isolates all Android framework dependencies so MeetingViewModel stays
 *          context-free and fully testable.
 * Architecture: feature-meeting â€” composable layer helper; created via
 *               `remember { MeetingRecorderManager(context) }` in MeetingRecorderScreen
 *               and released via DisposableEffect when the composable leaves composition.
 * Dependencies: android.media.MediaRecorder
 *
 * Requirements: 19.1
 *
 * Design decisions:
 * - This is NOT a ViewModel. It is a plain class the composable creates and disposes.
 * - Audio source: MIC; output format: MPEG_4; encoder: AAC â€” widely compatible defaults.
 * - Recording is written to context.cacheDir to avoid WRITE_EXTERNAL_STORAGE requirements.
 * - release() must be called when the composable is disposed to free native resources.
 * - Error callback is invoked on MediaRecorder.OnErrorListener to surface hardware errors.
 */
package com.aiassistant.feature.meeting

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Helper that owns and manages the [MediaRecorder] instance for meeting audio capture.
 *
 * All methods are safe to call from the main thread.
 *
 * Lifecycle:
 * 1. Create: `val manager = MeetingRecorderManager(context)`
 * 2. Use: `startRecording(onError)`, `stopRecording()`
 * 3. Destroy: `release()` â€” frees the MediaRecorder. Must be called to avoid leaks.
 */
class MeetingRecorderManager(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var outputFilePath: String = ""

    /**
     * Starts audio recording to a temporary file in the app's cache directory.
     *
     * @param onError Invoked with an error message if MediaRecorder encounters an error.
     * @return The absolute path of the output audio file.
     */
    fun startRecording(onError: (String) -> Unit): String {
        val outputFile = File(context.cacheDir, "meeting_${System.currentTimeMillis()}.m4a")
        outputFilePath = outputFile.absolutePath

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setOutputFile(outputFilePath)

            setOnErrorListener { _, what, extra ->
                onError("MediaRecorder error: what=$what extra=$extra")
            }

            try {
                prepare()
                start()
            } catch (e: Exception) {
                onError("Failed to start recording: ${e.message}")
                release()
                return outputFilePath
            }
        }

        mediaRecorder = recorder
        return outputFilePath
    }

    /**
     * Stops the active recording and finalises the output file.
     *
     * @return The absolute path of the completed audio file.
     */
    fun stopRecording(): String {
        try {
            mediaRecorder?.stop()
        } catch (e: IllegalStateException) {
            // Recorder was not recording â€” safe to ignore
        } finally {
            mediaRecorder?.reset()
        }
        return outputFilePath
    }

    /**
     * Releases all MediaRecorder resources.
     * Must be called from the composable's [DisposableEffect] onDispose block.
     */
    fun release() {
        try {
            mediaRecorder?.stop()
        } catch (_: IllegalStateException) {
            // Already stopped â€” safe to ignore
        }
        mediaRecorder?.release()
        mediaRecorder = null
    }
}
