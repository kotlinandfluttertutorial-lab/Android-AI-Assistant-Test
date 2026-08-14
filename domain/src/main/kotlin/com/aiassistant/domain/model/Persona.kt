/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : Persona.kt
 * Purpose    : Persona — domain module component
 *
 * Architecture Layer : Domain
 * Pattern Used       : Kotlin Data Class
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
 * Persona.kt
 *
 * Purpose: Domain entity representing an AI persona with customizable system prompt and tone.
 * Architecture: domain module — pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: None
 *
 * Requirements: 32.1, 32.3, 32.4, 32.5, 32.6
 */

package com.aiassistant.domain.model

/**
 * The tone/style that the AI should adopt when using this persona.
 *
 * Maps to the "tone" string field in the backend and Room entity.
 */
enum class PersonaTone(val value: String) {
    /** Professional, formal, and business-appropriate language. */
    PROFESSIONAL("professional"),

    /** Relaxed, conversational, and friendly language. */
    CASUAL("casual"),

    /** Brief, to-the-point responses with minimal elaboration. */
    CONCISE("concise"),

    /** Thorough, comprehensive responses with extensive explanation. */
    DETAILED("detailed"),

    /** Imaginative, exploratory, and creative language. */
    CREATIVE("creative");

    companion object {
        fun fromValue(value: String): PersonaTone = entries.firstOrNull { it.value == value } ?: PROFESSIONAL
    }
}

/**
 * Represents an AI persona with customizable system prompt and tone.
 *
 * THE AI_Orchestrator SHALL inject the selected Persona's system prompt + tone + scope
 * into the LLM system message (Requirement 32.8).
 *
 * @param id                Unique identifier for the persona.
 * @param userId            Identifier of the persona's owner.
 * @param name              Display name of the persona (1–80 characters).
 * @param systemPrompt      Custom system prompt text (1–4,000 characters).
 * @param tone              The tone/style for AI responses using this persona.
 * @param scopeDescription  Optional description of when to use this persona (0–500 characters).
 * @param adminLocked       When true, only admin users can edit or delete this persona.
 * @param allowedRoles      List of role identifiers permitted to use this persona (empty = all roles).
 * @param createdAt         Epoch milliseconds when the persona was created.
 * @param updatedAt         Epoch milliseconds of the last edit.
 */
data class Persona(
    val id: String,
    val userId: String,
    val name: String,
    val systemPrompt: String,
    val tone: PersonaTone = PersonaTone.PROFESSIONAL,
    val scopeDescription: String? = null,
    val adminLocked: Boolean = false,
    val allowedRoles: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long
)
