/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : ReminderEntity.kt
 * Purpose    : Room entity class representing the Reminder database table
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
 * File       : ReminderEntity.kt
 * Purpose    : Room entity class representing the Reminder database table
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

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val title: String,
    val triggerTime: Long, // epoch ms
    val recurrenceRule: String?, // iCal RRULE string, nullable for one-time
    val linkedTodoId: String?, // FK to TodoItemEntity, nullable
    val isCompleted: Boolean,
    val syncStatus: String, // "synced" | "pending" | "failed"
    val createdAt: Long,
    val updatedAt: Long
)
