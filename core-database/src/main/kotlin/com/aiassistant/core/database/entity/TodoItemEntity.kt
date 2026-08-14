/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : TodoItemEntity.kt
 * Purpose    : Room entity class representing the TodoItem database table
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
 * File       : TodoItemEntity.kt
 * Purpose    : Room entity class representing the TodoItem database table
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

@Entity(tableName = "todo_items")
data class TodoItemEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val title: String,
    val description: String,
    val isCompleted: Boolean,
    val dueDate: Long?, // epoch ms, nullable
    val priority: String, // "low" | "medium" | "high"
    val tags: String, // JSON array stored as string
    val syncStatus: String, // "synced" | "pending" | "failed"
    val createdAt: Long,
    val updatedAt: Long
)
