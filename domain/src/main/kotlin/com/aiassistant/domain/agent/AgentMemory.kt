/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentMemory.kt
 * Purpose    : Typed memory store hierarchy for Phase 8.
 *              Defines ShortTermMemory, ConversationMemory, LongTermMemory,
 *              MemorySnapshot (aggregate), and MemoryEntry (shared value type).
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Immutable value objects (data classes)
 *
 * Key Concepts:
 *   - Three distinct memory scopes:
 *       SHORT_TERM   — current request scope, discarded after execution
 *       CONVERSATION — current conversation session, cleared on end
 *       LONG_TERM    — user-approved persistent knowledge, never auto-stored
 *   - Privacy guarantee: isPrivacyMode on AgentContext prevents any new
 *     LongTermMemory entries from being persisted (enforced at write time)
 *   - MemorySnapshot is the assembled aggregate passed into AgentContextAssembler
 *     before Agent.execute() is called
 *   - All types are serialisable for persistence and transport
 *   - Pure Kotlin — zero Android/framework dependencies
 *
 * Security requirement: sensitive information MUST NOT be automatically stored.
 * LongTermMemory entries require explicit user approval (approvedByUser = true).
 *
 * Dependencies: kotlinx-serialization-json
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.serialization.Serializable

// ── Shared value type ─────────────────────────────────────────────────────────

/**
 * A single unit of memory content with optional relevance score and source tag.
 *
 * @param content        The text content of this memory entry.
 * @param relevanceScore Cosine similarity to the current query (0.0–1.0).
 *                       0.0 means no relevance score computed.
 * @param source         Optional tag identifying the origin of this memory
 *                       (e.g. "user_statement", "task_result", "project_doc").
 */
@Serializable
data class MemoryEntry(
    val content: String,
    val relevanceScore: Float = 0f,
    val source: String = "",
) {
    init {
        require(content.isNotBlank()) { "MemoryEntry.content must not be blank." }
        require(relevanceScore in 0f..1f) {
            "MemoryEntry.relevanceScore must be in 0.0..1.0, was $relevanceScore."
        }
    }

    /** Convert to the existing [ContextMemory] type used in [AgentContext]. */
    fun toContextMemory(): ContextMemory = ContextMemory(
        content = content,
        relevanceScore = relevanceScore,
    )

    companion object {
        fun fromContextMemory(cm: ContextMemory, source: String = ""): MemoryEntry =
            MemoryEntry(content = cm.content, relevanceScore = cm.relevanceScore, source = source)
    }
}

// ── Three memory scopes ───────────────────────────────────────────────────────

/**
 * Short-term memory — scoped to the current agent request.
 *
 * Holds the input prompt, task description, and intermediate agent results
 * accumulated during a single execution cycle. Discarded immediately after
 * the execution completes.
 *
 * ## Contents
 * - [inputPrompt]    The user's original input.
 * - [currentTask]    A human-readable description of the task being executed.
 * - [agentResults]   Intermediate results from each step (for multi-agent plans).
 * - [tokenCount]     Running count of tokens used in this request so far.
 * - [maxTokens]      Token budget for this request (0 = unlimited).
 *
 * ## Privacy
 * Short-term memory is never persisted — it is entirely ephemeral.
 */
@Serializable
data class ShortTermMemory(
    val inputPrompt: String,
    val currentTask: String = "",
    val agentResults: List<MemoryEntry> = emptyList(),
    val tokenCount: Int = 0,
    val maxTokens: Int = 0,
) {
    init {
        require(inputPrompt.isNotBlank()) { "ShortTermMemory.inputPrompt must not be blank." }
        require(tokenCount >= 0) { "ShortTermMemory.tokenCount must be non-negative." }
        require(maxTokens >= 0) { "ShortTermMemory.maxTokens must be non-negative." }
    }

    /** True when a token budget is set and the count has reached it. */
    val isTokenBudgetExceeded: Boolean
        get() = maxTokens > 0 && tokenCount >= maxTokens

    /** Returns a copy with [additionalTokens] added to [tokenCount]. */
    fun consumeTokens(additionalTokens: Int): ShortTermMemory =
        copy(tokenCount = tokenCount + additionalTokens)

    /** Returns a copy with [result] appended to [agentResults]. */
    fun withAgentResult(result: MemoryEntry): ShortTermMemory =
        copy(agentResults = agentResults + result)

    /** Returns a copy with [task] set as [currentTask]. */
    fun withTask(task: String): ShortTermMemory = copy(currentTask = task)
}

/**
 * Conversation memory — scoped to the current conversation session.
 *
 * Holds the recent message history for the active conversation, plus any
 * retrieved semantic memories that are relevant to this conversation's context.
 * Cleared when the conversation ends.
 *
 * ## Contents
 * - [conversationId]  The unique ID of the active conversation.
 * - [recentMessages]  Most recent conversation turns (newest last), capped by [maxMessages].
 * - [retrievedMemories] Semantically retrieved memories for the current query.
 * - [maxMessages]     Maximum number of messages to keep in context.
 *
 * ## Privacy
 * Conversation memory is session-scoped and not persisted beyond the conversation
 * unless explicitly promoted to [LongTermMemory] with user approval.
 */
@Serializable
data class ConversationMemory(
    val conversationId: String,
    val recentMessages: List<ContextMessage> = emptyList(),
    val retrievedMemories: List<MemoryEntry> = emptyList(),
    val maxMessages: Int = DEFAULT_MAX_MESSAGES,
) {
    init {
        require(conversationId.isNotBlank()) {
            "ConversationMemory.conversationId must not be blank."
        }
        require(maxMessages > 0) {
            "ConversationMemory.maxMessages must be positive, was $maxMessages."
        }
    }

    /**
     * Returns a copy with [message] appended to [recentMessages], trimming the
     * oldest message if the list would exceed [maxMessages].
     */
    fun withMessage(message: ContextMessage): ConversationMemory {
        val updated = recentMessages + message
        return copy(
            recentMessages = if (updated.size > maxMessages) {
                updated.drop(updated.size - maxMessages)
            } else {
                updated
            }
        )
    }

    /** Returns a copy with [memories] replacing [retrievedMemories]. */
    fun withRetrievedMemories(memories: List<MemoryEntry>): ConversationMemory =
        copy(retrievedMemories = memories)

    /** Convert [recentMessages] into [ContextMessage] list for [AgentContext]. */
    fun toContextMessages(): List<ContextMessage> = recentMessages

    companion object {
        const val DEFAULT_MAX_MESSAGES: Int = 20
    }
}

/**
 * Long-term memory — user-approved persistent knowledge.
 *
 * Contains facts, preferences, and project knowledge that the user has explicitly
 * approved for retention. This memory persists across sessions and is retrieved
 * semantically per query.
 *
 * ## Safety requirements
 * - [approvedByUser] MUST be `true` — entries created without approval are rejected.
 * - Sensitive information (passwords, PII, financial data) MUST NOT be stored.
 *   Callers are responsible for screening content before creating entries.
 * - When [AgentContext.isPrivacyMode] is `true`, new entries MUST NOT be persisted.
 *
 * ## Contents
 * - [entries]       The stored memory entries.
 * - [projectKnowledge] Project-specific facts (tech stack, conventions, etc.).
 * - [userId]        Owner of this long-term memory store.
 */
@Serializable
data class LongTermMemory(
    val userId: String,
    val entries: List<LongTermMemoryEntry> = emptyList(),
    val projectKnowledge: List<MemoryEntry> = emptyList(),
) {
    init {
        require(userId.isNotBlank()) { "LongTermMemory.userId must not be blank." }
    }

    /** Returns a copy with [entry] added, if [entry.approvedByUser] is true. */
    fun withEntry(entry: LongTermMemoryEntry): LongTermMemory {
        require(entry.approvedByUser) {
            "LongTermMemory.withEntry: entry must be user-approved before persisting."
        }
        return copy(entries = entries + entry)
    }

    /** Returns a copy with [fact] added to [projectKnowledge]. */
    fun withProjectFact(fact: MemoryEntry): LongTermMemory =
        copy(projectKnowledge = projectKnowledge + fact)

    /** Retrieve entries by source tag (delegating to the inner [MemoryEntry.source]). */
    fun findBySource(source: String): List<LongTermMemoryEntry> =
        entries.filter { it.entry.source == source }

    /** Convert all entries to [ContextMemory] for injection into [AgentContext]. */
    fun toContextMemories(): List<ContextMemory> =
        entries.map { it.entry.toContextMemory() } +
            projectKnowledge.map { it.toContextMemory() }
}

/**
 * A single user-approved long-term memory entry.
 *
 * @param entry          The memory content.
 * @param approvedByUser MUST be true — the user explicitly approved storage.
 * @param createdAtMs    When this entry was created (epoch ms).
 * @param tags           Optional tags for filtering (e.g. "preference", "project", "fact").
 */
@Serializable
data class LongTermMemoryEntry(
    val entry: MemoryEntry,
    val approvedByUser: Boolean,
    val createdAtMs: Long = System.currentTimeMillis(),
    val tags: List<String> = emptyList(),
) {
    init {
        require(approvedByUser) {
            "LongTermMemoryEntry must have approvedByUser=true. " +
                "Sensitive information must not be automatically stored."
        }
    }
}

// ── Aggregate snapshot ────────────────────────────────────────────────────────

/**
 * Assembled aggregate of all three memory scopes for a single agent execution.
 *
 * [AgentContextAssembler] reads this snapshot and produces an [AgentContext]
 * that is injected into [Agent.execute]. Agents receive fully assembled context
 * — they never query memory stores directly.
 *
 * @param shortTerm         Ephemeral request-scoped memory (always present).
 * @param conversation      Session-scoped conversation memory (null if not in a conversation).
 * @param longTerm          Persistent user-approved memory (null if not available or privacy mode).
 * @param maxContextTokens  Token budget for the combined context window.
 *                          0 = unlimited. See [SafetyLimits.MAX_CONTEXT_TOKENS].
 */
@Serializable
data class MemorySnapshot(
    val shortTerm: ShortTermMemory,
    val conversation: ConversationMemory? = null,
    val longTerm: LongTermMemory? = null,
    val maxContextTokens: Int = 0,
) {
    /**
     * Assembles all memory entries into a flat [List<ContextMemory>] suitable
     * for [AgentContext.memories], ranked by [MemoryEntry.relevanceScore] descending.
     *
     * When [maxContextTokens] > 0, entries are trimmed to fit within the budget
     * using a rough 4-characters-per-token estimate.
     */
    fun toContextMemories(): List<ContextMemory> {
        val all = mutableListOf<ContextMemory>()

        // Short-term agent results (highest priority for multi-step plans)
        all += shortTerm.agentResults.map { it.toContextMemory() }

        // Conversation retrieved memories
        conversation?.retrievedMemories?.map { it.toContextMemory() }?.let { all += it }

        // Long-term memories
        longTerm?.toContextMemories()?.let { all += it }

        // Rank by relevance, most relevant first
        val ranked = all.sortedByDescending { it.relevanceScore }

        if (maxContextTokens <= 0) return ranked

        // Trim to token budget (rough estimate: 4 chars ≈ 1 token)
        var tokenBudget = maxContextTokens
        return ranked.takeWhile { entry ->
            val tokens = (entry.content.length / 4).coerceAtLeast(1)
            if (tokens > tokenBudget) return@takeWhile false
            tokenBudget -= tokens
            true
        }
    }

    /**
     * Returns the most recent conversation messages for injection into
     * [AgentContext.conversationHistory]. Empty if no conversation memory.
     */
    fun toConversationHistory(): List<ContextMessage> =
        conversation?.toContextMessages() ?: emptyList()
}
