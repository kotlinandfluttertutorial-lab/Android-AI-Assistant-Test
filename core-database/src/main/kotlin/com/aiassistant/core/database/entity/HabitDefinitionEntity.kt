/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : HabitDefinitionEntity.kt
 * Purpose    : Room entity class representing the HabitDefinition database table
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
 * File       : HabitDefinitionEntity.kt
 * Purpose    : Room entity class representing the HabitDefinition database table
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

@Entity(tableName = "habit_definitions")
data class HabitDefinitionEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val name: String,
    val description: String,
    val recurrence: String, // "daily" | "weekly"
    val targetFrequency: Int, // times per recurrence period
    val createdAt: Long,
    val updatedAt: Long
)
