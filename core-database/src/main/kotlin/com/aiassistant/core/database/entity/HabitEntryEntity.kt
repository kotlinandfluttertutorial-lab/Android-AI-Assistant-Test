/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : HabitEntryEntity.kt
 * Purpose    : Room entity class representing the HabitEntry database table
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
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "habit_entries",
    foreignKeys = [
        ForeignKey(
            entity = HabitDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["habitId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("habitId")]
)
data class HabitEntryEntity(
    @PrimaryKey val id: String,
    val habitId: String,
    val userId: String,
    /** epoch ms */
    val completedAt: Long,
    /** optional user note for this entry */
    val note: String?
)
