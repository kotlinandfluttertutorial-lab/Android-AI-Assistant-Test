package com.aiassistant.feature.notes

import app.cash.turbine.test
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.model.Note
import com.aiassistant.domain.model.SyncStatus
import com.aiassistant.domain.repository.NoteRepository
import com.aiassistant.domain.usecase.note.RewriteNoteUseCase
import com.aiassistant.domain.usecase.note.SaveNoteUseCase
import com.aiassistant.domain.usecase.note.SummarizeNoteUseCase
import com.aiassistant.domain.usecase.suggestions.DismissSuggestionUseCase
import com.aiassistant.domain.usecase.suggestions.GetContextSuggestionsUseCase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest :
    DescribeSpec({

        val saveNoteUseCase = mockk<SaveNoteUseCase>()
        val summarizeNoteUseCase = mockk<SummarizeNoteUseCase>()
        val rewriteNoteUseCase = mockk<RewriteNoteUseCase>()
        val noteRepository = mockk<NoteRepository>()
        val getContextSuggestionsUseCase = mockk<GetContextSuggestionsUseCase>()
        val dismissSuggestionUseCase = mockk<DismissSuggestionUseCase>()

        val testDispatcher = StandardTestDispatcher()
        val dispatchers = object : DispatcherProvider {
            override val main = testDispatcher
            override val mainImmediate = testDispatcher
            override val io = testDispatcher
            override val default = testDispatcher
            override val unconfined = testDispatcher
        }

        val notesFlow = MutableStateFlow<ApiResult<List<Note>>>(ApiResult.Loading)

        beforeSpec {
            Dispatchers.setMain(testDispatcher)
        }

        afterSpec {
            Dispatchers.resetMain()
            unmockkAll()
        }

        beforeEach {
            clearMocks(
                saveNoteUseCase,
                summarizeNoteUseCase,
                rewriteNoteUseCase,
                noteRepository,
                getContextSuggestionsUseCase,
                dismissSuggestionUseCase
            )
            notesFlow.value = ApiResult.Loading
            every { noteRepository.getNotes() } returns notesFlow
        }

        describe("NotesViewModel") {

            it("initially emits Loading and then NotesList") {
                runTest(testDispatcher) {
                    val viewModel = NotesViewModel(
                        saveNoteUseCase,
                        summarizeNoteUseCase,
                        rewriteNoteUseCase,
                        noteRepository,
                        dispatchers,
                        getContextSuggestionsUseCase,
                        dismissSuggestionUseCase
                    )

                    viewModel.uiState.test {
                        awaitItem() shouldBe NotesUiState.Loading

                        val notes = listOf(
                            Note("1", "u1", "Title", "Content", emptyList(), SyncStatus.SYNCED, 0L, 0L)
                        )
                        notesFlow.value = ApiResult.Success(notes)

                        awaitItem() shouldBe NotesUiState.NotesList(notes = notes, allTags = emptyList())
                    }
                }
            }
        }
    })
