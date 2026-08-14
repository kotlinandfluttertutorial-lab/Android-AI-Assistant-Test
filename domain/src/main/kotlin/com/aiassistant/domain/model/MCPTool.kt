/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : MCPTool.kt
 * Purpose    : MCPTool — domain module component
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

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : MCPTool.kt
 * Purpose    : MCPTool — domain module component
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
 * MCPTool.kt
 *
 * Purpose: Domain entity representing an MCP (Model Context Protocol) external tool
 *          connector available through the AI Orchestrator.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: None
 *
 * Requirements: 19.2
 */

package com.aiassistant.domain.model

/**
 * Represents an MCP external tool that the AI Orchestrator can invoke on behalf of
 * the user (e.g. GitHub, Gmail, Google Drive, Slack, Jira).
 *
 * Tools are discovered via the backend's MCP Broker and surfaced to the UI so the user
 * can see which integrations are available and whether they require confirmation before
 * executing write operations.
 *
 * @param name                 The unique tool identifier used when invoking the tool
 *                             (e.g. "github", "gmail", "google_drive").
 * @param displayName          Human-readable name shown in the UI.
 * @param description          Short description of the tool's capabilities.
 * @param requiresConfirmation If `true`, the UI must show a confirmation dialog before
 *                             the AI Orchestrator executes a write operation with this tool.
 * @param isAvailable          Whether the tool connector is currently configured and
 *                             reachable from the backend.
 */
data class MCPTool(
    val name: String,
    val displayName: String,
    val description: String,
    val requiresConfirmation: Boolean = false,
    val isAvailable: Boolean = true
)
