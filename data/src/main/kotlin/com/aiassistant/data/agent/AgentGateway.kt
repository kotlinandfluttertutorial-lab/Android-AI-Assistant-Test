/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : AgentGateway.kt
 * Purpose    : Data-layer implementation of AgentGatewayRepository.
 *              Bridges ViewModels → AgentOrchestrator → Agents.
 *              Phase 3: ChatAgent. Phase 4: CodeAgent, RagAgent.
 *
 * Architecture Layer : Data — agent sub-package
 * Pattern Used       : Repository implementation (Gateway adapter)
 *
 * Key Concepts:
 *   - Implements AgentGatewayRepository (domain interface)
 *   - ViewModels depend only on the domain interface
 *   - All agents registered at singleton init time; no hardcoded routing
 *   - Phase 4 adds executeCode() and executeRag() entry points
 *
 * Dependencies: domain (AgentGatewayRepository, orchestrator types),
 *               data (ChatAgent, CodeAgent, RagAgent)
 * ============================================================
 */

package com.aiassistant.data.agent

import com.aiassistant.domain.agent.AgentCapability
import com.aiassistant.domain.agent.AgentContext
import com.aiassistant.domain.agent.AgentEvent
import com.aiassistant.domain.agent.AgentGatewayCodeExtension
import com.aiassistant.domain.agent.AgentGatewayDocumentExtension
import com.aiassistant.domain.agent.AgentGatewayRepository
import com.aiassistant.domain.agent.AgentRequest
import com.aiassistant.domain.agent.DefaultAgentOrchestrator
import com.aiassistant.domain.agent.DefaultAgentPlanner
import com.aiassistant.domain.agent.DefaultAgentRegistry
import com.aiassistant.domain.agent.DefaultAgentRouter
import com.aiassistant.domain.agent.METADATA_KEY_PLAN_STEPS
import com.aiassistant.domain.agent.ToolPermission
import com.aiassistant.domain.agent.ToolSchema
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Production implementation of [AgentGatewayRepository].
 *
 * All three agents are registered at construction time.  The orchestrator
 * uses [DefaultAgentRouter] to dispatch based on metadata hints or
 * capability requirements — no hardcoded routing logic here.
 *
 * @param chatAgent  Conversational agent (Phase 3)
 * @param codeAgent  Code analysis/generation agent (Phase 4)
 * @param ragAgent   RAG retrieval agent (Phase 4)
 */
@Singleton
class AgentGateway @Inject constructor(
    private val chatAgent: ChatAgent,
    private val codeAgent: CodeAgent,
    private val ragAgent: RagAgent,
    private val pdfAgent: PdfAgent,
    private val toolAgent: ToolAgent,
    private val toolRegistry: com.aiassistant.domain.agent.ToolRegistry,
) : AgentGatewayRepository, AgentGatewayCodeExtension, AgentGatewayDocumentExtension {

    // ── Registry and orchestrator ─────────────────────────────────────────────

    private val registry = DefaultAgentRegistry().also { reg ->
        reg.register(chatAgent)
        reg.register(codeAgent)
        reg.register(ragAgent)
        reg.register(pdfAgent)
        reg.register(toolAgent)
    }

    private val orchestrator = DefaultAgentOrchestrator(
        registry = registry,
        router = DefaultAgentRouter(),
        planner = DefaultAgentPlanner(),
    )

    // ── AgentGatewayRepository (Phase 3) ─────────────────────────────────────

    override fun executeChat(
        conversationId: String,
        content: String,
        provider: String,
        context: AgentContext?,
    ): Flow<AgentEvent> {
        val request = AgentRequest(
            userId = resolveUserId(context),
            input = content,
            conversationId = conversationId,
            provider = provider.takeIf { it.isNotBlank() },
            capabilities = emptySet(),
            context = context,
            metadata = mapOf(
                ChatAgent.METADATA_AGENT_NAME to ChatAgent.NAME,
            ),
        )
        return orchestrator.execute(request)
    }

    // ── Phase 4: Code ─────────────────────────────────────────────────────────

    /**
     * Execute a code analysis or generation request via [CodeAgent].
     *
     * @param code         The source code (or natural-language prompt for GENERATE).
     * @param action       [CodeAgent.METADATA_CODE_ACTION] value, e.g. `"explain"`.
     * @param language     Language identifier, e.g. `"kotlin"`, `"python"`.
     * @param context      Optional [AgentContext] (user ID, persona, etc.)
     */
    fun executeCode(
        code: String,
        action: String,
        language: String,
        context: AgentContext? = null,
    ): Flow<AgentEvent> {
        val request = AgentRequest(
            userId = resolveUserId(context),
            input = code,
            capabilities = setOf(AgentCapability.CODE_ANALYSIS),
            context = context,
            metadata = mapOf(
                CodeAgent.METADATA_AGENT_NAME to CodeAgent.NAME,
                CodeAgent.METADATA_CODE_ACTION to action,
                CodeAgent.METADATA_LANGUAGE to language,
            ),
        )
        return orchestrator.execute(request)
    }

    // ── Phase 4: RAG ─────────────────────────────────────────────────────────

    /**
     * Execute a RAG document query via [RagAgent].
     *
     * @param query       The user's natural-language question.
     * @param documentId  Specific document UUID to scope retrieval. If null
     *                    [RagAgent] picks the first READY document.
     * @param context     Optional [AgentContext].
     */
    fun executeRag(
        query: String,
        documentId: String?,
        context: AgentContext? = null,
    ): Flow<AgentEvent> {
        val metadataBuilder = mutableMapOf(
            RagAgent.METADATA_AGENT_NAME to RagAgent.NAME,
        )
        if (!documentId.isNullOrBlank()) {
            metadataBuilder[RagAgent.METADATA_DOCUMENT_ID] = documentId
        }
        val request = AgentRequest(
            userId = resolveUserId(context),
            input = query,
            capabilities = setOf(AgentCapability.DOCUMENT_RETRIEVAL),
            context = context,
            metadata = metadataBuilder,
        )
        return orchestrator.execute(request)
    }

    // ── Phase 4: Code→RAG→Code handoff ────────────────────────────────────────

    /**
     * Execute a three-step Code→RAG→Code handoff plan.
     *
     * Step 1 (code): Analyse/generate initial response.
     * Step 2 (rag):  Retrieve relevant document context.
     * Step 3 (code): Generate enriched response using RAG context.
     *
     * The [METADATA_KEY_PLAN_STEPS] instructs [DefaultAgentPlanner] to
     * build a 3-step plan targeting the named agents in sequence.
     *
     * @param code       The source code to analyse.
     * @param action     Code action (e.g. `"explain"`, `"review"`).
     * @param language   Language identifier.
     * @param documentId Optional document to scope RAG retrieval.
     * @param context    Optional [AgentContext].
     */
    fun executeCodeWithRagContext(
        code: String,
        action: String,
        language: String,
        documentId: String? = null,
        context: AgentContext? = null,
    ): Flow<AgentEvent> {
        val metadataBuilder = mutableMapOf(
            CodeAgent.METADATA_CODE_ACTION to action,
            CodeAgent.METADATA_LANGUAGE to language,
            METADATA_KEY_PLAN_STEPS to "${CodeAgent.NAME},${RagAgent.NAME},${CodeAgent.NAME}",
        )
        if (!documentId.isNullOrBlank()) {
            metadataBuilder[RagAgent.METADATA_DOCUMENT_ID] = documentId
        }
        val request = AgentRequest(
            userId = resolveUserId(context),
            input = code,
            capabilities = emptySet(),
            context = context,
            maxSteps = 15,  // 3 agents × up to 5 steps each
            metadata = metadataBuilder,
        )
        return orchestrator.execute(request)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun resolveUserId(context: AgentContext?): String =
        context?.userId?.takeIf { it.isNotBlank() } ?: ANONYMOUS_USER_ID

    // ── Phase 5: PDF ──────────────────────────────────────────────────────────

    override fun executePdf(
        action: String,
        input: String,
        documentId: String?,
        fileUri: String?,
        fileName: String?,
        mimeType: String,
        context: AgentContext?,
    ): Flow<AgentEvent> {
        val meta = mutableMapOf(
            PdfAgent.METADATA_AGENT_NAME to PdfAgent.NAME,
            PdfAgent.METADATA_PDF_ACTION to action,
        )
        documentId?.let { meta[PdfAgent.METADATA_DOCUMENT_ID] = it }
        fileUri?.let { meta[PdfAgent.METADATA_FILE_URI] = it }
        fileName?.let { meta[PdfAgent.METADATA_FILE_NAME] = it }
        meta[PdfAgent.METADATA_MIME_TYPE] = mimeType
        val request = AgentRequest(
            userId = resolveUserId(context),
            input = input.ifBlank { "Process document." },
            capabilities = emptySet(),
            context = context,
            metadata = meta,
        )
        return orchestrator.execute(request)
    }

    // ── Phase 5: Tool ─────────────────────────────────────────────────────────

    override fun executeTool(
        toolName: String,
        toolArgs: Map<String, String>,
        callerPermissions: Set<ToolPermission>,
        confirmed: Boolean,
        context: AgentContext?,
    ): Flow<AgentEvent> {
        val meta = mutableMapOf(
            ToolAgent.METADATA_AGENT_NAME to ToolAgent.NAME,
            ToolAgent.METADATA_TOOL_NAME to toolName,
        )
        if (confirmed) meta[ToolAgent.METADATA_CONFIRMED] = "true"
        if (callerPermissions.isNotEmpty()) {
            meta[ToolAgent.METADATA_PERMISSIONS] = callerPermissions.joinToString(",") { it.name }
        }
        toolArgs.forEach { (k, v) -> meta["${ToolAgent.ARGS_PREFIX}$k"] = v }
        val request = AgentRequest(
            userId = resolveUserId(context),
            input = "Execute tool: $toolName",
            capabilities = setOf(AgentCapability.TOOL_USE),
            context = context,
            metadata = meta,
        )
        return orchestrator.execute(request)
    }

    override fun listTools(): List<ToolSchema> =
        toolRegistry.list().map { it.schema }

    companion object {
        private const val ANONYMOUS_USER_ID = "anonymous"
    }
}
