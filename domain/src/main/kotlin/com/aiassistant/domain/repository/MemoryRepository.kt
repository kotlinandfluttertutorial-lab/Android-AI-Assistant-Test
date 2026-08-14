/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : MemoryRepository.kt
 * Purpose    : Domain contract defining data access operations for Memory entities
 *
 * Architecture Layer : Domain
 * Pattern Used       : Repository Interface
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
 * File       : MemoryRepository.kt
 * Purpose    : Domain contract defining data access operations for Memory entities
 *
 * Architecture Layer : Domain
 * Pattern Used       : Repository Interface
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
package com.aiassistant.domain.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.Memory
import kotlinx.coroutines.flow.Flow

interface MemoryRepository {
    fun getMemories(): Flow<ApiResult<List<Memory>>>
    suspend fun updateMemory(memoryId: String, newContent: String): ApiResult<Memory>
    suspend fun deleteMemory(memoryId: String): ApiResult<Unit>
}
