/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : GetOnDeviceDocumentsUseCase.kt
 * Purpose    : Returns a live Flow of on-device documents for the active user,
 *              consumed by OnDeviceDocumentViewModel to drive the document list UI.
 *
 * Architecture Layer : Domain — pure Kotlin use case.
 *
 * Requirements: 33.1, 33.2, 33.3
 * ============================================================
 */
package com.aiassistant.domain.usecase.ondevicerag

import com.aiassistant.domain.model.OnDeviceDocument
import com.aiassistant.domain.repository.OnDeviceDocumentRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Provides a reactive stream of [OnDeviceDocument] objects for [userId].
 *
 * Emits a new list whenever the Room database changes (document added,
 * status updated, or document deleted).
 *
 * @param documentRepository Local-only document persistence.
 */
class GetOnDeviceDocumentsUseCase @Inject constructor(
    private val documentRepository: OnDeviceDocumentRepository,
) {

    /**
     * @param userId Owner whose documents to observe.
     * @return Cold [Flow] emitting the current list on each Room change.
     */
    operator fun invoke(userId: String): Flow<List<OnDeviceDocument>> =
        documentRepository.getDocuments(userId)
}
