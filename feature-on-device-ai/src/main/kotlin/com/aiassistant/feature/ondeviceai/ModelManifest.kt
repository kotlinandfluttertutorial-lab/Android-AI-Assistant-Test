/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-ai
 * File       : ModelManifest.kt
 * Purpose    : Kotlin data classes representing the bundled model_manifest.json asset.
 *              The manifest lists every quantized GGUF model available for download,
 *              its expected SHA-256 checksum, file name, and download URL.
 *
 * Architecture Layer : Feature (feature-on-device-ai)
 * Pattern Used       : Data / Value Object
 *
 * Key Concepts:
 *   - @Serializable — deserialized from assets/model_manifest.json via
 *     kotlinx.serialization
 *   - Immutable data classes; no Android framework dependencies
 *
 * Requirements: 31.2, 31.7
 * ============================================================
 */

package com.aiassistant.feature.ondeviceai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Root wrapper deserialized from `assets/model_manifest.json`.
 */
@Serializable
data class ModelManifest(
    @SerialName("models")
    val models: List<ModelEntry>
)

/**
 * A single quantized model entry in the manifest.
 *
 * @param id          Stable identifier (e.g. "llama3-8b-int4").
 * @param displayName Human-readable label shown in settings.
 * @param fileName    Name of the GGUF file on local storage.
 * @param downloadUrl HTTPS URL to fetch the file from.
 * @param sha256      Expected lowercase hex SHA-256 hash of the downloaded file.
 * @param sizeBytes   Approximate file size in bytes (used for progress indication).
 * @param quantization Quantization format tag (e.g. "INT4", "INT8").
 */
@Serializable
data class ModelEntry(
    @SerialName("id")
    val id: String,
    @SerialName("displayName")
    val displayName: String,
    @SerialName("fileName")
    val fileName: String,
    @SerialName("downloadUrl")
    val downloadUrl: String,
    @SerialName("sha256")
    val sha256: String,
    @SerialName("sizeBytes")
    val sizeBytes: Long,
    @SerialName("quantization")
    val quantization: String
)
