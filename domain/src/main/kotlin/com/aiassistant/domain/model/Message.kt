/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : Message.kt
 * Purpose    : Message — domain module component
 *
 * Architecture Layer : Domain
 * Pattern Used       : Kotlin Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
/**
 * Message.kt
 *
 * Purpose: Domain entity representing a single turn within a Conversation, attributed
 *          to either the User or the AI_Orchestrator.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: None
 *
 * Requirements: 2.6, 2.7, 10.2
 */

package com.aiassistant.domain.model

import java.time.Instant

/**
 * Represents an individual message within a [Conversation].
 *
 * WHEN a User selects regenerate on a Message, THE AI_Orchestrator SHALL produce a new
 * response and append it as an alternative to the existing Message (Requirement 2.6).
 *
 * WHILE the device is offline, THE AI_Assistant SHALL queue outgoing Messages using
 * WorkManager (Requirement 10.2). The [syncStatus] field tracks this lifecycle.
 *
 * @param id               Unique identifier for the message.
 * @param conversationId   Identifier of the parent [Conversation].
 * @param role             Who authored the message: "user", "assistant", or "system".
 * @param content          The text content of the message.
 * @param inputTokens      Number of input tokens consumed (populated after server response).
 * @param outputTokens     Number of output tokens generated (populated after server response).
 * @param provider         The LLM provider that generated this message (empty for user messages).
 * @param syncStatus       Lifecycle state of the message with respect to backend sync:
 *                         "synced" | "pending" | "failed".
 * @param createdAt        Timestamp when the message was created locally.
 */
data class Message(
    val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val provider: String = "",
    val syncStatus: String = "pending",
    val createdAt: Instant
)
