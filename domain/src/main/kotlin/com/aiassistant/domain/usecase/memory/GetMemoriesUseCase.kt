/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : GetMemoriesUseCase.kt
 * Purpose    : Encapsulates the 'GetMemories' business operation
 *
 * Architecture Layer : Domain
 * Pattern Used       : Clean Architecture Use Case
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
 * File       : GetMemoriesUseCase.kt
 * Purpose    : Encapsulates the 'GetMemories' business operation
 *
 * Architecture Layer : Domain
 * Pattern Used       : Clean Architecture Use Case
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
 * GetMemoriesUseCase.kt
 *
 * Purpose: Retrieves all memory entries for the authenticated user from the Memory Service.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), MemoryRepository, Memory
 *
 * Requirements: 7.3
 */

package com.aiassistant.domain.usecase.memory

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.Memory
import com.aiassistant.domain.repository.MemoryRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Use case for fetching all stored memories for the authenticated user.
 *
 * THE AI_Assistant SHALL display a Memory screen allowing the User to view, edit, and
 * delete individual stored memories (Requirement 7.3).
 *
 * Memories are not cached locally (sensitive data) â€” they are always fetched directly
 * from the remote Memory Service.
 *
 * @param memoryRepository Repository providing the memory retrieval operation.
 */
class GetMemoriesUseCase @Inject constructor(private val memoryRepository: MemoryRepository) {

    /**
     * Returns a [Flow] of all memories for the authenticated user.
     *
     * @return Cold [Flow] emitting [ApiResult.Success] with the full memory list,
     *         or [ApiResult.Error] / [ApiResult.NetworkUnavailable] on failure.
     */
    operator fun invoke(): Flow<ApiResult<List<Memory>>> = memoryRepository.getMemories()
}
