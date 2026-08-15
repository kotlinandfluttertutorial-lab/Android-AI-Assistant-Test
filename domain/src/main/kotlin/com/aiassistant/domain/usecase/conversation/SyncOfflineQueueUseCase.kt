/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : SyncOfflineQueueUseCase.kt
 * Purpose    : Encapsulates the 'SyncOfflineQueue' business operation
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
 * SyncOfflineQueueUseCase.kt
 *
 * Purpose: Submits all queued (pending) messages to the backend when connectivity is
 *          restored, returning the count of successfully synced messages.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), domain repository (MessageRepository)
 *
 * Requirements: 10.2
 *
 * Design decisions:
 * - This use case is invoked by WorkManager's SyncMessagesWorker in the data layer when
 *   connectivity is restored. The domain layer exposes the intent; WorkManager wiring lives
 *   in the data module.
 * - No input parameters are required; the repository knows which messages are pending.
 */

package com.aiassistant.domain.usecase.conversation

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.repository.MessageRepository
import javax.inject.Inject

/**
 * Use case for synchronising the offline message queue with the backend.
 *
 * WHILE the device is offline, THE AI_Assistant SHALL queue outgoing Messages and submit
 * them when connectivity is restored using WorkManager (Requirement 10.2).
 *
 * This use case is the domain-layer entry point for that sync operation. The data module's
 * WorkManager worker calls this use case when network connectivity is available.
 *
 * @param messageRepository Repository providing the offline queue sync operation.
 */
class SyncOfflineQueueUseCase @Inject constructor(private val messageRepository: MessageRepository) {

    /**
     * Executes the offline queue synchronisation.
     *
     * Delegates to [MessageRepository.syncOfflineQueue], which retrieves all messages with
     * [Message.syncStatus] = "pending" (in original creation order), submits each to the
     * backend, and updates their status to "synced" or "failed" accordingly.
     *
     * @return [ApiResult.Success] with the number of messages successfully synced,
     *         [ApiResult.Error] when the sync operation encounters an unrecoverable failure,
     *         [ApiResult.NetworkUnavailable] when the device loses connectivity mid-sync.
     */
    suspend operator fun invoke(): ApiResult<Int> = messageRepository.syncOfflineQueue()
}
