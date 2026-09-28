/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentGatewayCodeExtension.kt
 * Purpose    : Domain interface for routing code analysis requests through
 *              the Agent Orchestrator layer from feature-code.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Interface (Gateway)
 *
 * Design Decision:
 *   feature-code MUST NOT depend on :data.  The AgentGateway implementation
 *   lives in :data, but CodeViewModel (in :feature-code) must only depend on
 *   this :domain interface, which is the same pattern as AgentGatewayRepository
 *   for chat.
 *
 *   A separate interface (rather than extending AgentGatewayRepository) keeps
 *   the domain boundary clean — chat and code have different method signatures.
 *
 * Dependencies: domain agent models
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.coroutines.flow.Flow

/**
 * Domain contract for routing code analysis and generation requests through
 * the Agent Orchestrator stack from [com.aiassistant.feature.code.CodeViewModel].
 *
 * The implementation is [com.aiassistant.data.agent.AgentGateway] in :data.
 * The binding is declared in `AgentDataModule`.
 */
interface AgentGatewayCodeExtension {

    /**
     * Execute a code analysis or generation request and stream [AgentEvent] values.
     *
     * @param code     The source code (or natural-language prompt for GENERATE action).
     * @param action   [CodeAgentAction.apiValue] string, e.g. `"explain"`, `"fix_bug"`.
     * @param language Language name, e.g. `"KOTLIN"`, `"PYTHON"`.
     * @param context  Optional [AgentContext] (user id, persona, etc.).
     * @return Cold [Flow] ending with [AgentEvent.Completed] or [AgentEvent.Failed].
     */
    fun executeCode(
        code: String,
        action: String,
        language: String,
        context: AgentContext? = null,
    ): Flow<AgentEvent>

    /**
     * Execute a three-step Code→RAG→Code handoff plan.
     *
     * @param code       Source code to analyse.
     * @param action     Code action string.
     * @param language   Language name.
     * @param documentId Optional document UUID to scope RAG retrieval.
     * @param context    Optional [AgentContext].
     */
    fun executeCodeWithRagContext(
        code: String,
        action: String,
        language: String,
        documentId: String? = null,
        context: AgentContext? = null,
    ): Flow<AgentEvent>
}
