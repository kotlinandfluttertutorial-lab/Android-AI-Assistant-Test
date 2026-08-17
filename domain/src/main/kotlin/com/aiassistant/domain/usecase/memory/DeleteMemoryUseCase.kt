/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : DeleteMemoryUseCase.kt
 * Purpose    : Encapsulates the 'DeleteMemory' business operation
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
 * DeleteMemoryUseCase.kt
 *
 * Purpose: Deletes an individual memory entry and its vector embedding from the
 *          Memory Service.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), MemoryRepository
 *
 * Requirements: 7.3, 7.4
 */

package com.aiassistant.domain.usecase.memory

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.repository.MemoryRepository
import javax.inject.Inject

/**
 * Use case for deleting a single memory entry.
 *
 * THE AI_Assistant SHALL allow the User to delete individual stored memories
 * (Requirement 7.3). WHEN a User deletes a memory, THE Memory_Service SHALL remove
 * the corresponding Embedding from the Vector_Store within 10 seconds (Requirement 7.4).
 *
 * The 10-second SLA is enforced at the backend level; this use case delegates directly
 * to the repository.
 *
 * @param memoryRepository Repository providing the memory delete operation.
 */
class DeleteMemoryUseCase @Inject constructor(private val memoryRepository: MemoryRepository) {

    /**
     * Executes the memory deletion.
     *
     * @param memoryId The unique identifier of the memory to delete.
     * @return [ApiResult.Success] with [Unit] on success.
     */
    suspend operator fun invoke(memoryId: String): ApiResult<Unit> = memoryRepository.deleteMemory(memoryId)
}
