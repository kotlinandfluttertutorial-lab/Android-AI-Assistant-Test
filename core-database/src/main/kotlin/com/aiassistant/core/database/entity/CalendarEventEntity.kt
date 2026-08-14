/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : CalendarEventEntity.kt
 * Purpose    : Room entity class representing the CalendarEvent database table
 *
 * Architecture Layer : Core-Database
 * Pattern Used       : Room Entity
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
 * Module     : core-database
 * File       : CalendarEventEntity.kt
 * Purpose    : Room entity class representing the CalendarEvent database table
 *
 * Architecture Layer : Core-Database
 * Pattern Used       : Room Entity
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
package com.aiassistant.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "calendar_events")
data class CalendarEventEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val title: String,
    val description: String,
    val startTime: Long, // epoch ms
    val endTime: Long, // epoch ms
    val location: String?,
    val isAllDay: Boolean,
    val source: String, // "local" | "google_calendar"
    val syncStatus: String, // "synced" | "pending" | "failed"
    val createdAt: Long,
    val updatedAt: Long
)
