/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : MemoryStore.kt
 * Purpose    : Domain-level abstraction for persisting and retrieving
 *              long-term user memories.  Agent implementations depend
 *              only on this interface — never on ChromaDB, Room, or
 *              any persistence library directly.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Interface (Port — Hexagonal Architecture)
 *
 * Design Rules:
 *   - Pure Kotlin, zero Android/framework/infrastructure dependencies.
 *   - Uses domain model types only (Memory, MemoryType from domain/model/).
 *   - MemoryRepository (domain/repository/) is the REST-level contract;
 *     MemoryStore is the agent-level contract used during execution.
 *     They are deliberately separate: MemoryRepository handles network
 *     I/O, MemoryStore handles the logical "read/write memory" operation
 *     that agents perform without knowing the transport.
 *
 * Privacy contract:
 *   When the user has privacy mode active, implementations MUST treat
 *   [store] as a no-op and return an empty string.  The interface does
 *   not enforce this — callers check [AgentContext.isPrivacyMode] before
 *   calling [store].
 *
 * Dependencies: domain/model/Memory.kt, kotlinx.coroutines.flow
 * ============================================================
 */

package com.aiassistant.domain.agent

import com.aiassistant.domain.model.Memory
import com.aiassistant.domain.model.MemoryType
import kotlinx.coroutines.flow.Flow

/**
 * Domain port for storing and retrieving long-term user memories.
 *
 * Agents call this interface during execution to:
 * - Inject relevant past context before generating a response.
 * - Persist new facts, preferences, or style observations after a response.
 *
 * Concrete implementations live in `:data` and bridge to the backend
 * via Retrofit (`MemoryRemoteDataSource`) or, for on-device scenarios,
 * to a local Room store.  Neither implementation leaks into this interface.
 *
 * ## Usage example
 * ```kotlin
 * // In an Agent implementation:
 * val memories = memoryStore.retrieve(userId = context.userId, query = request.input, topK = 5)
 * val enrichedContext = context.withMemories(
 *     memories.map { ContextMemory(content = it.content, relevanceScore = 0f) }
 * )
 * ```
 */
interface MemoryStore {

    /**
     * Persist [memory] and return its stable ID.
     *
     * Implementations MUST be idempotent: storing a [Memory] whose [Memory.id]
     * is non-blank and already exists updates the record rather than creating
     * a duplicate.
     *
     * When privacy mode is active for [memory.userId], this MUST be a no-op
     * and return an empty string.
     *
     * @param memory The memory to persist.  [Memory.id] may be blank for new memories.
     * @return The persisted memory ID, or empty string when no-op (privacy mode).
     * @throws MemoryStoreException On unrecoverable persistence failure.
     */
    suspend fun store(memory: Memory): String

    /**
     * Return the [topK] memories most semantically relevant to [query] for [userId].
     *
     * Results are ordered by descending relevance.  Returns an empty list when
     * no memories match or the store is empty for this user.
     *
     * @param userId      Owner of the memories.
     * @param query       Natural-language query string used for similarity search.
     * @param topK        Maximum number of memories to return.  Must be ≥ 1.
     * @param memoryType  When non-null, restricts results to this type.
     * @return List of [Memory] ordered by relevance, descending.  May be empty.
     */
    suspend fun retrieve(
        userId: String,
        query: String,
        topK: Int = 5,
        memoryType: MemoryType? = null,
    ): List<Memory>

    /**
     * Observe all memories for [userId] as a [Flow].
     *
     * Emits a fresh list whenever the underlying store changes.
     * Implementations backed by Room emit on every DB write;
     * implementations backed by a remote API may emit only once.
     *
     * @param userId Owner of the memories to observe.
     * @return Cold [Flow] of memory snapshots.
     */
    fun observe(userId: String): Flow<List<Memory>>

    /**
     * Delete the memory identified by [memoryId] owned by [userId].
     *
     * No-op when the memory does not exist.
     *
     * @param userId   Owner — used for isolation enforcement.
     * @param memoryId Stable ID returned by [store].
     */
    suspend fun delete(userId: String, memoryId: String)

    /**
     * Delete all memories belonging to [userId].
     *
     * Used for GDPR account deletion and "clear my memory" user action.
     *
     * @param userId Owner whose memories should be purged.
     */
    suspend fun deleteAll(userId: String)
}

/**
 * Thrown by [MemoryStore] implementations on unrecoverable failure.
 *
 * @param message   Human-readable description.  Must not contain stack traces.
 * @param retryable True when a retry may succeed (transient network error).
 * @param cause     Underlying throwable, if any.
 */
class MemoryStoreException(
    message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)
