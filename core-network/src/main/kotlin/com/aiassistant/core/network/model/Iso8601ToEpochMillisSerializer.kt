package com.aiassistant.core.network.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

/**
 * Deserializes an ISO-8601 timestamp string (e.g. "2026-09-25T20:47:42.919960Z")
 * into epoch milliseconds (Long). Serializes back to ISO-8601 format.
 *
 * The backend returns timestamps as ISO-8601 strings; the Android DTOs store them
 * as Long epoch-millis for easy comparison and formatting.
 */
object Iso8601ToEpochMillisSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Iso8601ToEpochMillis", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Long {
        val raw = decoder.decodeString()
        return try {
            Instant.parse(raw).toEpochMilli()
        } catch (_: Exception) {
            // Fall back to plain numeric string (legacy or test data)
            raw.toLongOrNull() ?: 0L
        }
    }

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeString(Instant.ofEpochMilli(value).toString())
    }
}
