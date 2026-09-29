/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentContextAssembler.kt
 * Purpose    : Assembles a complete AgentContext from a MemorySnapshot
 *              before Agent.execute() is called.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Pure function / factory
 *
 * Key Concepts:
 *   - Agents are stateless — they receive a fully assembled AgentContext
 *   - Assembler combines ShortTermMemory + ConversationMemory + LongTermMemory
 *     into a single coherent AgentContext
 *   - Privacy: when AgentRequest.context.isPrivacyMode == true, long-term
 *     memories are excluded from the assembled context
 *   - Token budget: context memories are ranked and trimmed by SafetyLimits
 *   - Pure Kotlin — no I/O, no suspend functions, no dependencies beyond
 *     domain types. Memory *retrieval* happens upstream (in a future
 *     MemoryRepository implementation in :data); this assembler only combines.
 *
 * Usage:
 * ```kotlin
 * val snapshot = MemorySnapshot(
 *     shortTerm = ShortTermMemory(inputPrompt = request.input),
 *     conversation = conversationMemory,
 *     longTerm = if (isPrivacyMode) null else userLongTermMemory,
 *     maxContextTokens = safetyLimits.maxContextTokens,
 * )
 * val context = AgentContextAssembler.assemble(request, snapshot)
 * val enrichedRequest = request.copy(context = context)
 * ```
 *
 * Dependencies: domain agent models (AgentContext, MemorySnapshot, SafetyLimits)
 * ============================================================
 */

package com.aiassistant.domain.agent

/**
 * Assembles an [AgentContext] from a [MemorySnapshot] for a given [AgentRequest].
 *
 * This is the single place where the three memory scopes are combined into the
 * [AgentContext] injected into [Agent.execute]. Agents never call this directly.
 */
object AgentContextAssembler {

    /**
     * Produce an [AgentContext] from [request] and [snapshot].
     *
     * Assembly rules:
     * 1. userId from [request].
     * 2. conversationId from [request.conversationId].
     * 3. conversationHistory from [snapshot.toConversationHistory()].
     * 4. memories from [snapshot.toContextMemories()] — excludes long-term when
     *    privacy mode is active on the existing context or when [snapshot.longTerm]
     *    is null.
     * 5. personaSystemPrompt, ragDocumentIds, availableTools from any existing
     *    [request.context] (preserved to avoid losing caller-supplied values).
     * 6. isPrivacyMode and isOffline from existing context (or false if absent).
     * 7. extraContext from existing context (or empty).
     *
     * @param request  The originating request.
     * @param snapshot The assembled memory snapshot for this execution.
     * @return A fully assembled [AgentContext].
     */
    fun assemble(request: AgentRequest, snapshot: MemorySnapshot): AgentContext {
        val existing = request.context
        val privacyMode = existing?.isPrivacyMode ?: false

        // Build the privacy-aware snapshot view
        val effectiveSnapshot = if (privacyMode) {
            // Exclude long-term memories when privacy mode is active
            snapshot.copy(longTerm = null)
        } else {
            snapshot
        }

        return AgentContext(
            userId = request.userId,
            conversationId = request.conversationId,
            conversationHistory = effectiveSnapshot.toConversationHistory(),
            memories = effectiveSnapshot.toContextMemories(),
            personaSystemPrompt = existing?.personaSystemPrompt,
            ragDocumentIds = existing?.ragDocumentIds ?: emptyList(),
            availableTools = existing?.availableTools ?: emptyList(),
            isPrivacyMode = privacyMode,
            isOffline = existing?.isOffline ?: false,
            extraContext = existing?.extraContext ?: emptyMap(),
        )
    }

    /**
     * Assemble context with an explicit [safetyLimits] token budget.
     *
     * Convenience overload that applies [SafetyLimits.maxContextTokens]
     * to the snapshot before assembly.
     *
     * @param request       The originating request.
     * @param snapshot      The raw memory snapshot (maxContextTokens may be 0).
     * @param safetyLimits  Limits to apply; [SafetyLimits.maxContextTokens] overrides
     *                      any token budget already on the snapshot.
     */
    fun assemble(
        request: AgentRequest,
        snapshot: MemorySnapshot,
        safetyLimits: SafetyLimits,
    ): AgentContext {
        val boundedSnapshot = if (safetyLimits.maxContextTokens > 0) {
            snapshot.copy(maxContextTokens = safetyLimits.maxContextTokens)
        } else {
            snapshot
        }
        return assemble(request, boundedSnapshot)
    }

    /**
     * Minimal context assembly for agents that don't need full memory retrieval.
     *
     * Creates a [ShortTermMemory] from [request.input] and builds a bare
     * [AgentContext] preserving any values already set in [request.context].
     */
    fun assembleMinimal(request: AgentRequest): AgentContext {
        val existing = request.context
        val shortTerm = ShortTermMemory(inputPrompt = request.input)
        val snapshot = MemorySnapshot(shortTerm = shortTerm)
        return assemble(request, snapshot)
            .let { assembled ->
                // Re-apply any caller-supplied context values that assembleMinimal
                // would otherwise overwrite with defaults
                if (existing != null) {
                    assembled.copy(
                        memories = existing.memories + assembled.memories,
                        personaSystemPrompt = existing.personaSystemPrompt
                            ?: assembled.personaSystemPrompt,
                    )
                } else {
                    assembled
                }
            }
    }
}
