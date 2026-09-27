/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : tools/DateTimeTool.kt
 * Purpose    : Returns current date/time in requested format.
 *              No user data accessed; requires only READ_DATETIME permission.
 *
 * Security: no I/O, no external calls, no user data.
 * ============================================================
 */

package com.aiassistant.data.agent.tools

import com.aiassistant.domain.agent.Tool
import com.aiassistant.domain.agent.ToolPermission
import com.aiassistant.domain.agent.ToolResult
import com.aiassistant.domain.agent.ToolSchema
import com.aiassistant.domain.agent.ToolValidationError
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DateTimeTool @Inject constructor() : Tool {

    override val schema = ToolSchema(
        name = NAME,
        displayName = "Date & Time",
        description = "Returns the current date and time in a requested format.",
        parametersSchema = mapOf(
            "format" to "Output format: 'iso' (ISO-8601, default), 'date', 'time', 'timestamp', or a custom Java DateTimeFormatter pattern.",
            "timezone" to "IANA timezone name (e.g. 'UTC', 'America/New_York'). Defaults to UTC.",
        ),
        requiredPermissions = setOf(ToolPermission.READ_DATETIME),
        timeoutMs = 1_000L,
    )

    override fun validate(args: Map<String, String>) {
        val tz = args["timezone"]
        if (!tz.isNullOrBlank()) {
            try {
                ZoneId.of(tz)
            } catch (_: Exception) {
                throw ToolValidationError(NAME, "timezone", "unrecognised timezone '$tz'")
            }
        }
    }

    override suspend fun execute(args: Map<String, String>, userId: String): ToolResult {
        val start = System.currentTimeMillis()
        return try {
            val tz = args["timezone"]?.takeIf { it.isNotBlank() }?.let { ZoneId.of(it) }
                ?: ZoneId.of("UTC")
            val now = ZonedDateTime.now(tz)
            val format = args["format"]?.trim() ?: "iso"
            val output = when (format.lowercase()) {
                "iso", "" -> now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                "date"    -> now.format(DateTimeFormatter.ISO_LOCAL_DATE)
                "time"    -> now.format(DateTimeFormatter.ISO_LOCAL_TIME)
                "timestamp" -> now.toInstant().toEpochMilli().toString()
                else      -> now.format(DateTimeFormatter.ofPattern(format))
            }
            ToolResult(
                toolName = NAME,
                success = true,
                output = output,
                durationMs = System.currentTimeMillis() - start,
                metadata = mapOf("timezone" to tz.id, "format" to format),
            )
        } catch (e: Exception) {
            ToolResult(
                toolName = NAME,
                success = false,
                error = "DateTime error: ${e.message ?: "unknown error"}",
                durationMs = System.currentTimeMillis() - start,
            )
        }
    }

    companion object { const val NAME = "datetime" }
}
