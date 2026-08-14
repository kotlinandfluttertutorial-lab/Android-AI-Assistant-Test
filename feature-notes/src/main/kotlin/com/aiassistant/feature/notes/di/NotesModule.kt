/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-notes
 * File       : NotesModule.kt
 * Purpose    : Hilt module providing Notes dependencies to the DI graph
 *
 * Architecture Layer : Feature (feature-notes)
 * Pattern Used       : Hilt DI Module
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
 * Module     : feature-notes
 * File       : NotesModule.kt
 * Purpose    : Hilt module providing Notes dependencies to the DI graph
 *
 * Architecture Layer : Feature (feature-notes)
 * Pattern Used       : Hilt DI Module
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
 * NotesModule.kt â€” feature-notes module
 *
 * Purpose: Hilt [dagger.Module] providing domain use case instances needed by
 *          [NotesViewModel]. The use cases do not carry @Inject constructor
 *          annotations in the domain module (pure-Kotlin, no DI framework dependency
 *          by design), so they are provided here via @Provides factory methods.
 *
 * Architecture: feature-notes â€” installs into [dagger.hilt.android.components.ViewModelComponent].
 * Dependencies: domain (SaveNoteUseCase, SummarizeNoteUseCase, RewriteNoteUseCase, NoteRepository)
 *
 * Design decisions:
 * - @Provides rather than @Binds because use cases are instantiated by factory.
 * - Scoped to ViewModelComponent so each ViewModel instance gets its own use-case
 *   instances, while the NoteRepository (bound at SingletonComponent) is shared.
 *
 * Requirements: 13.1, 13.2, 13.3
 */
package com.aiassistant.feature.notes.di

import com.aiassistant.domain.repository.NoteRepository
import com.aiassistant.domain.usecase.note.RewriteNoteUseCase
import com.aiassistant.domain.usecase.note.SaveNoteUseCase
import com.aiassistant.domain.usecase.note.SummarizeNoteUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/**
 * Hilt module providing use-case instances for the notes ViewModel.
 */
@Module
@InstallIn(ViewModelComponent::class)
object NotesModule {

    /**
     * Provides [SaveNoteUseCase] backed by the injected [NoteRepository].
     */
    @Provides
    fun provideSaveNoteUseCase(repo: NoteRepository): SaveNoteUseCase = SaveNoteUseCase(repo)

    /**
     * Provides [SummarizeNoteUseCase] backed by the injected [NoteRepository].
     */
    @Provides
    fun provideSummarizeNoteUseCase(repo: NoteRepository): SummarizeNoteUseCase = SummarizeNoteUseCase(repo)

    /**
     * Provides [RewriteNoteUseCase] backed by the injected [NoteRepository].
     */
    @Provides
    fun provideRewriteNoteUseCase(repo: NoteRepository): RewriteNoteUseCase = RewriteNoteUseCase(repo)
}
