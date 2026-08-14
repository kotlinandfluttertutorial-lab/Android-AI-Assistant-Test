/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-ai
 * File       : OnDeviceModelManager.kt
 * Purpose    : Manages the full lifecycle of on-device GGUF model files:
 *              - Reads the bundled model_manifest.json from assets
 *              - Downloads quantized INT4/INT8 models to internal storage
 *              - Verifies SHA-256 checksums before allowing inference
 *              - Detects absent / corrupt files and signals the caller to fall back
 *                to the cloud LLM provider or to show a download prompt
 *
 * Architecture Layer : Feature (feature-on-device-ai)
 * Pattern Used       : Manager / Repository-style service
 *
 * Key Concepts:
 *   - SHA-256 integrity gate before any model load (Requirement 31.7)
 *   - Falls back to cloud LLM if model is absent or corrupt (Requirement 31.6)
 *   - Coroutine-friendly; IO-bound work runs on Dispatchers.IO
 *   - File names are resolved against Context.filesDir (internal storage)
 *
 * Dependencies:
 *   - android.content.Context (assets, filesDir)
 *   - java.security.MessageDigest
 *   - kotlinx.coroutines
 *   - kotlinx.serialization
 *
 * Requirements: 31.2, 31.6, 31.7
 * ============================================================
 */

package com.aiassistant.feature.ondeviceai

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

// ─── Constants ───────────────────────────────────────────────────────────────

private const val MANIFEST_ASSET_PATH = "model_manifest.json"
private const val DOWNLOAD_BUFFER_SIZE = 8192
private const val TAG = "OnDeviceModelManager"

// ─── ModelStatus (used by OnDeviceCapabilityChecker) ─────────────────────────

/**
 * Simplified status enum used by [OnDeviceCapabilityChecker] to communicate model
 * readiness without exposing internal File references to the state layer.
 */
sealed class ModelStatus {
    /** Model file is present and verified. */
    data class Ready(val entry: ModelEntry) : ModelStatus()

    /** Model file is absent — download required. */
    data class Absent(val entry: ModelEntry) : ModelStatus()

    /** Model file exists but SHA-256 verification failed — treat as corrupt. */
    data class VerificationFailed(val entry: ModelEntry) : ModelStatus()

    /** A download is currently in progress. */
    data class Downloading(val entry: ModelEntry) : ModelStatus()
}

// ─── Model state ─────────────────────────────────────────────────────────────

/**
 * Represents the verification state of a GGUF model file.
 */
sealed class ModelFileState {
    /** File is present and its SHA-256 matches the manifest. Ready to use. */
    data class Ready(val file: File, val entry: ModelEntry) : ModelFileState()

    /** File is absent — needs download before inference. */
    data class Absent(val entry: ModelEntry) : ModelFileState()

    /** File is present but the checksum does not match — treat as corrupt. */
    data class Corrupt(val entry: ModelEntry) : ModelFileState()

    /** A download is currently in progress. */
    data class Downloading(val entry: ModelEntry, val progressFraction: Float) : ModelFileState()

    /** Download failed with a message. */
    data class DownloadFailed(val entry: ModelEntry, val reason: String) : ModelFileState()
}

/**
 * Download progress event emitted by [OnDeviceModelManager.downloadModel].
 */
sealed class DownloadEvent {
    data class Progress(val fraction: Float) : DownloadEvent()
    data class Success(val file: File) : DownloadEvent()
    data class Failure(val reason: String) : DownloadEvent()
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Manages on-device GGUF model file lifecycle.
 *
 * **Lifecycle contract:**
 * 1. Call [checkModelState] at startup to determine if a model is ready, absent, or corrupt.
 * 2. If absent or corrupt: display a download prompt and call [downloadModel] which emits
 *    [DownloadEvent] objects.
 * 3. If ready: pass the [File] path to [OnDeviceInferenceClient] for loading.
 *
 * All IO operations are dispatched to [Dispatchers.IO] internally.
 *
 * Requirements: 31.2, 31.6, 31.7
 */
@Singleton
class OnDeviceModelManager @Inject constructor(@ApplicationContext private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    // Expose a reactive state so the UI can observe lifecycle changes
    private val _modelState = MutableStateFlow<ModelFileState?>(null)
    val modelState: StateFlow<ModelFileState?> = _modelState.asStateFlow()

    // ─── Manifest ────────────────────────────────────────────────────────────

    /**
     * Reads and parses `assets/model_manifest.json`.
     *
     * @throws IllegalStateException if the asset cannot be opened or parsed.
     */
    suspend fun loadManifest(): ModelManifest = withContext(Dispatchers.IO) {
        context.assets.open(MANIFEST_ASSET_PATH).use { stream ->
            val raw = stream.bufferedReader().readText()
            json.decodeFromString(ModelManifest.serializer(), raw)
        }
    }

    // ─── State check ─────────────────────────────────────────────────────────

    /**
     * Checks the file system state for [entry] and updates [modelState].
     *
     * Returns one of:
     * - [ModelFileState.Ready] — file present and SHA-256 verified.
     * - [ModelFileState.Absent] — file does not exist.
     * - [ModelFileState.Corrupt] — file exists but checksum mismatch.
     *
     * Requirement: 31.6, 31.7
     */
    suspend fun checkModelState(entry: ModelEntry): ModelFileState = withContext(Dispatchers.IO) {
        val file = modelFile(entry)

        val state = when {
            !file.exists() -> {
                Log.i(TAG, "Model '${entry.id}' absent at ${file.absolutePath}")
                ModelFileState.Absent(entry)
            }
            verifyChecksum(file, entry.sha256) -> {
                Log.i(TAG, "Model '${entry.id}' ready — checksum OK")
                ModelFileState.Ready(file, entry)
            }
            else -> {
                Log.w(TAG, "Model '${entry.id}' CORRUPT — checksum mismatch; deleting")
                file.delete()
                ModelFileState.Corrupt(entry)
            }
        }
        _modelState.value = state
        state
    }

    // ─── Download ────────────────────────────────────────────────────────────

    /**
     * Downloads [entry] from its [ModelEntry.downloadUrl], verifies the SHA-256 checksum,
     * and emits [DownloadEvent] progress / result objects as a [Flow].
     *
     * On checksum failure after download the partial file is deleted and a
     * [DownloadEvent.Failure] is emitted so the caller can retry.
     *
     * Requirement: 31.2, 31.7
     */
    fun downloadModel(entry: ModelEntry): Flow<DownloadEvent> = flow {
        _modelState.value = ModelFileState.Downloading(entry, 0f)

        val destFile = modelFile(entry)
        val tempFile = File(destFile.parent, "${destFile.name}.tmp")

        try {
            val url = URL(entry.downloadUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.connect()

            val contentLength = connection.contentLength.toLong()
            var bytesRead = 0L

            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (contentLength > 0) {
                            val fraction = bytesRead.toFloat() / contentLength.toFloat()
                            _modelState.value = ModelFileState.Downloading(entry, fraction)
                            emit(DownloadEvent.Progress(fraction))
                        }
                    }
                }
            }
            connection.disconnect()

            // Verify before making the file visible to inference
            if (verifyChecksum(tempFile, entry.sha256)) {
                tempFile.renameTo(destFile)
                _modelState.value = ModelFileState.Ready(destFile, entry)
                Log.i(TAG, "Downloaded '${entry.id}' — checksum OK")
                emit(DownloadEvent.Success(destFile))
            } else {
                tempFile.delete()
                val msg = "Checksum mismatch after download of '${entry.id}'"
                Log.e(TAG, msg)
                _modelState.value = ModelFileState.Corrupt(entry)
                emit(DownloadEvent.Failure(msg))
            }
        } catch (e: Exception) {
            tempFile.delete()
            val msg = "Download failed for '${entry.id}': ${e.message}"
            Log.e(TAG, msg, e)
            _modelState.value = ModelFileState.DownloadFailed(entry, msg)
            emit(DownloadEvent.Failure(msg))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Simplified convenience method returning [ModelStatus] for use by
     * [OnDeviceCapabilityChecker] which needs a non-File status value.
     *
     * Picks the first model in the manifest; if the manifest is empty returns [ModelStatus.Absent].
     */
    suspend fun checkModelStatus(): ModelStatus = withContext(Dispatchers.IO) {
        val manifest = runCatching { loadManifest() }.getOrNull()
            ?: return@withContext ModelStatus.Absent(
                ModelEntry(
                    id = "unknown",
                    displayName = "Unknown",
                    fileName = "unknown.gguf",
                    downloadUrl = "",
                    sha256 = "",
                    sizeBytes = 0,
                    quantization = "INT4"
                )
            )
        if (manifest.models.isEmpty()) {
            return@withContext ModelStatus.Absent(
                ModelEntry(
                    id = "unknown",
                    displayName = "Unknown",
                    fileName = "unknown.gguf",
                    downloadUrl = "",
                    sha256 = "",
                    sizeBytes = 0,
                    quantization = "INT4"
                )
            )
        }
        val entry = manifest.models.first()
        return@withContext when (val state = checkModelState(entry)) {
            is ModelFileState.Ready -> ModelStatus.Ready(state.entry)
            is ModelFileState.Absent -> ModelStatus.Absent(state.entry)
            is ModelFileState.Corrupt -> ModelStatus.VerificationFailed(state.entry)
            is ModelFileState.Downloading -> ModelStatus.Downloading(state.entry)
            is ModelFileState.DownloadFailed -> ModelStatus.VerificationFailed(state.entry)
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Resolves the canonical local [File] path for a model entry.
     * Files are stored under [Context.filesDir]/models/.
     */
    fun modelFile(entry: ModelEntry): File {
        val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }
        return File(modelsDir, entry.fileName)
    }

    /**
     * Computes the SHA-256 digest of [file] and compares it (case-insensitively) to
     * [expectedHex].
     *
     * Returns true if the digests match; false otherwise.
     *
     * Requirement: 31.7
     */
    fun verifyChecksum(file: File, expectedHex: String): Boolean {
        if (!file.exists()) return false
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                var read: Int
                while (stream.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            actual.equals(expectedHex, ignoreCase = true)
        } catch (e: Exception) {
            Log.e(TAG, "Checksum computation failed: ${e.message}")
            false
        }
    }

    /**
     * Computes SHA-256 of the given [InputStream]. Useful in tests to compare
     * in-memory data against a known hash without writing to disk.
     */
    fun checksumOfStream(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
        var read: Int
        while (stream.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
