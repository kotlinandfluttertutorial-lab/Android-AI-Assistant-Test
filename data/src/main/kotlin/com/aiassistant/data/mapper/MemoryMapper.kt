/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : MemoryMapper.kt
 * Purpose    : MemoryMapper — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Domain / Entity Mapper
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
 * Module     : data
 * File       : MemoryMapper.kt
 * Purpose    : MemoryMapper — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Domain / Entity Mapper
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
 * MemoryMapper.kt â€” data module
 *
 * Purpose: Mapping between [MemoryDto] (Retrofit) and [Memory] (domain model).
 *          Memories are not cached locally, so no entity mapping is needed.
 *
 * Architecture: data module â€” mapper layer. Pure functions, no side effects.
 * Dependencies: domain (Memory, MemoryType), data.remote.memory (MemoryDto)
 *
 * Requirements: 7.3, 7.4
 */
package com.aiassistant.data.mapper

import com.aiassistant.data.remote.memory.MemoryDto
import com.aiassistant.domain.model.Memory
import com.aiassistant.domain.model.MemoryType

/**
 * Maps a [MemoryDto] (Retrofit) to a [Memory] (domain model).
 */
fun MemoryDto.toDomain(): Memory = Memory(
    id = id,
    userId = userId,
    content = content,
    memoryType = MemoryType.fromValue(memoryType),
    createdAt = createdAt
)
