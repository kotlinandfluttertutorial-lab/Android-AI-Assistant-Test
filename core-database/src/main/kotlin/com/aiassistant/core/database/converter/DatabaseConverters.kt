/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : DatabaseConverters.kt
 * Purpose    : Room TypeConverters for all custom column types stored in
 *              the AppDatabase.
 *
 * Architecture Layer : Core-Database — data persistence layer.
 *                      Used exclusively by Room; never called directly
 *                      from domain or feature modules.
 *
 * Dependencies       : kotlinx.serialization (JSON), java.nio (ByteBuffer),
 *                      java.time.Instant
 *
 * Design Decision    : Three converter groups are registered together in one
 *                      class so Room only needs a single @TypeConverters
 *                      annotation on AppDatabase.  Each group is documented
 *                      inline so the trade-offs are clear to future readers.
 * ============================================================
 */
package com.aiassistant.core.database.converter

import androidx.room.TypeConverter
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room type converters for the AppDatabase.
 *
 * Handles:
 *  - List<String>  <-> JSON string          (kotlinx.serialization)
 *  - Long?         <-> Instant?             (java.time.Instant, minSdk 26+)
 *  - FloatArray    <-> ByteArray            (little-endian IEEE 754 float32 blob)
 */
class DatabaseConverters {

    companion object {
        private const val FLOAT_SIZE_BYTES = 4
    }

    // ── List<String> ↔ JSON ──────────────────────────────────────────────────

    @TypeConverter
    fun fromStringList(value: List<String>): String = Json.encodeToString(value)

    @TypeConverter
    fun toStringList(value: String): List<String> = Json.decodeFromString(value)

    // ── Long? ↔ Instant? ─────────────────────────────────────────────────────

    @TypeConverter
    fun fromInstant(instant: Instant?): Long? = instant?.toEpochMilli()

    @TypeConverter
    fun toInstant(epochMilli: Long?): Instant? = epochMilli?.let { Instant.ofEpochMilli(it) }

    // ── FloatArray ↔ ByteArray (little-endian IEEE 754 float32) ─────────────
    //
    // Design decision: on-device embeddings are stored as a raw binary blob
    // rather than a JSON array for two reasons:
    //   1. Space — a 384-dimensional float32 embedding is 384 × 4 = 1,536 bytes
    //      as a blob vs ~2,800+ bytes as a JSON decimal-string array.
    //   2. Speed — ByteBuffer.wrap() is O(1); no JSON tokenisation per row.
    //
    // Byte order is LITTLE_ENDIAN to match the native memory layout used by
    // TensorFlow Lite and MediaPipe on ARM/x86 Android hardware, so the bytes
    // can be passed directly to native inference APIs without a copy.

    @TypeConverter
    fun fromFloatArray(value: FloatArray): ByteArray {
        val buffer = java.nio.ByteBuffer
            .allocate(value.size * FLOAT_SIZE_BYTES)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(value)
        return buffer.array()
    }

    @TypeConverter
    fun toFloatArray(value: ByteArray): FloatArray {
        val floatBuffer = java.nio.ByteBuffer
            .wrap(value)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
        return FloatArray(floatBuffer.remaining()).also { floatBuffer.get(it) }
    }
}
