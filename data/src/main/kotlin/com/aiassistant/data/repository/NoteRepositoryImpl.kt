/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : NoteRepositoryImpl.kt
 * Purpose    : Implements NoteRepository with Room (local) and Retrofit (remote) data sources
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
 * File       : NoteRepositoryImpl.kt
 * Purpose    : Implements NoteRepository with Room (local) and Retrofit (remote) data sources
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
 * NoteRepositoryImpl.kt â€” data module
 *
 * Purpose: Production implementation of [NoteRepository]. Orchestrates [NoteDao]
 *          (Room local cache) and [NoteRemoteDataSource] (Retrofit) following an
 *          offline-first strategy: Room is the single source of truth.
 *
 * Architecture: data module â€” repository layer. Bridges domain contracts
 *               ([NoteRepository]) with infrastructure concerns (Room, Retrofit,
 *               ConnectivityObserver). Wired at runtime via [NoteDataModule].
 *
 * Offline-first rules:
 *   - [getNotes] / [getNotesByTag] always emit from Room first.
 *   - [saveNote] writes to Room immediately with [SyncStatus.PENDING], then syncs
 *     to the backend when connected.
 *   - [deleteNote] deletes locally first, then calls remote if connected.
 *   - [summarizeNote] / [rewriteNote] require connectivity.
 *
 * syncStatus field (Requirement 13.4):
 *   PENDING â†’ note has local changes not yet sent to backend.
 *   SYNCED  â†’ note matches the backend state.
 *   FAILED  â†’ last sync attempt failed.
 *
 * Requirements: 13.1, 13.2, 13.3, 13.4, 13.5
 */
package com.aiassistant.data.repository

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.database.dao.NoteDao
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.core.security.SecureStorage
import com.aiassistant.data.mapper.toDomain
import com.aiassistant.data.mapper.toEntity
import com.aiassistant.data.remote.note.NoteRemoteDataSource
import com.aiassistant.domain.model.Note
import com.aiassistant.domain.model.SyncStatus
import com.aiassistant.domain.repository.NoteRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "NoteRepository"

/**
 * Offline-first implementation of [NoteRepository].
 *
 * Room is the single source of truth. Remote sync happens in the background and
 * updates the local database, which triggers new emissions from active [Flow]s.
 *
 * @param noteDao              Room DAO for note persistence.
 * @param remoteSource         Retrofit-backed remote data source.
 * @param connectivityObserver Synchronous connectivity check.
 * @param secureStorage        Credential store used to resolve the authenticated user ID.
 * @param dispatchers          Injectable dispatcher provider.
 */
@Singleton
class NoteRepositoryImpl @Inject constructor(
    private val noteDao: NoteDao,
    private val remoteSource: NoteRemoteDataSource,
    private val connectivityObserver: ConnectivityObserver,
    private val secureStorage: SecureStorage,
    private val dispatchers: DispatcherProvider
) : NoteRepository {

    /** Application-scoped scope for fire-and-forget background sync operations. */
    private val syncScope = CoroutineScope(dispatchers.io + SupervisorJob())

    /**
     * Cancels the internal sync scope.
     * Only used in unit tests to prevent CoroutineScope leaks.
     */
    @VisibleForTesting
    internal fun cancelSync() {
        syncScope.cancel()
    }

    // â”€â”€â”€ NoteRepository â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Returns a [Flow] of all notes owned by the authenticated user, sorted by [Note.updatedAt].
     *
     * Emits from Room immediately, then kicks off a background sync.
     */
    override fun getNotes(): Flow<ApiResult<List<Note>>> {
        val userId = resolveUserId()
        syncScope.launch { syncPendingNotes() }
        return noteDao.getNotesByUser(userId)
            .map { entities -> ApiResult.Success(entities.map { it.toDomain() }) }
    }

    /**
     * Returns a [Flow] of notes filtered by [tag] (Requirement 13.5).
     *
     * Filtering is performed in-memory from the Room emission. An empty [tag] returns all notes.
     */
    override fun getNotesByTag(tag: String): Flow<ApiResult<List<Note>>> {
        val userId = resolveUserId()
        return noteDao.getNotesByUser(userId)
            .map { entities ->
                val notes = entities.map { it.toDomain() }
                val filtered = if (tag.isBlank()) notes else notes.filter { tag in it.tags }
                ApiResult.Success(filtered)
            }
    }

    /**
     * Saves (create or update) a note locally with [SyncStatus.PENDING], then syncs
     * to the backend when connected (Requirement 13.4).
     *
     * For a new note pass a [Note] with a new unique [Note.id]. For an update pass the
     * modified [Note]. The returned [Note] reflects the persisted state.
     *
     * @param note The note to save.
     */
    override suspend fun saveNote(note: Note): ApiResult<Note> = withContext(dispatchers.io) {
        val now = Instant.now().toEpochMilli()
        val pendingNote = note.copy(syncStatus = SyncStatus.PENDING, updatedAt = now)
        noteDao.insertNote(pendingNote.toEntity())

        if (connectivityObserver.isConnected()) {
            syncNote(pendingNote)
        }

        ApiResult.Success(pendingNote)
    }

    /**
     * Permanently deletes a note from Room and the backend.
     *
     * Local delete happens immediately. Remote delete is attempted when connected.
     *
     * @param noteId The unique identifier of the note to delete.
     */
    override suspend fun deleteNote(noteId: String): ApiResult<Unit> = withContext(dispatchers.io) {
        noteDao.deleteNote(noteId)

        if (!connectivityObserver.isConnected()) {
            Log.d(TAG, "deleteNote: offline â€” local delete applied, remote call skipped.")
            return@withContext ApiResult.Success(Unit)
        }

        when (val result = remoteSource.deleteNote(noteId)) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Error -> {
                Log.w(TAG, "deleteNote: remote call failed: ${result.error.message}")
                ApiResult.Success(Unit)
            }
            is ApiResult.NetworkUnavailable -> ApiResult.Success(Unit)
            is ApiResult.Loading -> ApiResult.Success(Unit)
        }
    }

    /**
     * Requests an AI-generated summary (â‰¤150 words) of a note (Requirement 13.2).
     *
     * Requires connectivity.
     *
     * @param noteId The unique identifier of the note to summarise.
     */
    override suspend fun summarizeNote(noteId: String): ApiResult<String> = withContext(dispatchers.io) {
        if (!connectivityObserver.isConnected()) return@withContext ApiResult.NetworkUnavailable
        remoteSource.summarizeNote(noteId)
    }

    /**
     * Requests an AI rewrite of a note in the user's learned writing style.
     *
     * Requires connectivity.
     *
     * @param noteId The unique identifier of the note to rewrite.
     */
    override suspend fun rewriteNote(noteId: String): ApiResult<String> = withContext(dispatchers.io) {
        if (!connectivityObserver.isConnected()) return@withContext ApiResult.NetworkUnavailable
        remoteSource.rewriteNote(noteId)
    }

    // â”€â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Syncs all locally [SyncStatus.PENDING] notes to the backend.
     *
     * Called on launch and after saveNote. Silently skips when offline.
     */
    private suspend fun syncPendingNotes() {
        if (!connectivityObserver.isConnected()) return
        val pendingEntities = noteDao.getPendingNotes()
        pendingEntities.forEach { entity ->
            syncNote(entity.toDomain())
        }
    }

    /**
     * Attempts to push a single [note] to the backend.
     *
     * On success, updates Room to [SyncStatus.SYNCED]. On failure, marks as [SyncStatus.FAILED].
     */
    private suspend fun syncNote(note: Note) {
        val result = remoteSource.updateNote(note.id, note.title, note.content, note.tags)
        val newStatus = when (result) {
            is ApiResult.Success -> SyncStatus.SYNCED
            else -> SyncStatus.FAILED
        }
        if (result is ApiResult.Success) {
            noteDao.updateNote(note.copy(syncStatus = newStatus).toEntity())
        } else {
            noteDao.updateNote(note.copy(syncStatus = SyncStatus.FAILED).toEntity())
        }
    }

    /** Resolves the authenticated user's ID from [SecureStorage]. */
    private fun resolveUserId(): String = secureStorage.getJwt()?.substringAfterLast('.') ?: ""
}
