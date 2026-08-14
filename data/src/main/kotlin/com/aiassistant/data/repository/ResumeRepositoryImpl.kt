/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ResumeRepositoryImpl.kt
 * Purpose    : Implements ResumeRepository with Room (local) and Retrofit (remote) data sources
 *
 * Architecture Layer : Data
 * Pattern Used       : Repository Implementation (offline-first)
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
 * File       : ResumeRepositoryImpl.kt
 * Purpose    : Implements ResumeRepository with Room (local) and Retrofit (remote) data sources
 *
 * Architecture Layer : Data
 * Pattern Used       : Repository Implementation (offline-first)
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
 * ResumeRepositoryImpl.kt â€” data module
 *
 * Purpose: Production implementation of [ResumeRepository]. Delegates all AI generation
 *          operations to [ResumeRemoteDataSource] (Retrofit).
 *
 * Architecture: data module â€” repository layer. Bridges domain contracts
 *               ([ResumeRepository]) with infrastructure concerns (Retrofit).
 *               Wired at runtime via [ResumeDataModule].
 *
 * Design decisions:
 * - Resume, cover letter, and email generation are stateless remote-only operations â€”
 *   no local caching is needed since results are ephemeral and user-initiated.
 * - All four methods delegate directly to [ResumeRemoteDataSource] which wraps
 *   every call in try/catch and maps to [ApiResult].
 *
 * Requirements: 14.1, 14.2, 14.4, 14.5
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.data.remote.resume.ResumeRemoteDataSource
import com.aiassistant.domain.repository.ResumeRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remote-only implementation of [ResumeRepository].
 *
 * All generation operations require connectivity. There is no local cache because
 * generated content is ephemeral and stored in the feature layer (ViewModel state).
 *
 * @param remoteSource Retrofit-backed data source for all resume and email endpoints.
 */
@Singleton
class ResumeRepositoryImpl @Inject constructor(private val remoteSource: ResumeRemoteDataSource) : ResumeRepository {

    /**
     * Generates an ATS-optimised resume in Markdown format (Requirement 14.1).
     */
    override suspend fun generateResume(professionalHistory: String, jobDescription: String): ApiResult<String> =
        remoteSource.generateResume(professionalHistory, jobDescription)

    /**
     * Generates a tailored cover letter (â‰¤ 400 words) (Requirement 14.2).
     */
    override suspend fun generateCoverLetter(professionalHistory: String, jobDescription: String): ApiResult<String> =
        remoteSource.generateCoverLetter(professionalHistory, jobDescription)

    /**
     * Generates a professional email with subject, greeting, body, and closing
     * (Requirement 14.4).
     */
    override suspend fun generateEmail(context: String, intent: String): ApiResult<String> =
        remoteSource.generateEmail(context, intent)

    /**
     * Corrects grammar in a draft email and returns the full corrected text
     * (Requirement 14.5).
     */
    override suspend fun correctGrammar(draftEmail: String): ApiResult<String> = remoteSource.correctGrammar(draftEmail)
}
