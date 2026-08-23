/**
 * NoteRepositoryImplTest.kt â€” data module
 *
 * Purpose: Unit tests for [NoteRepositoryImpl], focusing on:
 *   - syncStatus state transitions: PENDING â†’ SYNCED / FAILED
 *   - delete clearing both local Room cache and remote entries
 *   - offline-first strategy: Room emits first; remote sync gated by connectivity
 *   - background sync of pending notes on getNotes()
 *
 * Architecture: data module â€” unit tests (pure JVM, no Android framework).
 *
 * Test toolchain:
 * - Kotest DescribeSpec  â€” test structure and assertions
 * - MockK                â€” mocking NoteDao, NoteRemoteDataSource,
 *                          ConnectivityObserver, SecureStorage
 * - kotlinx.coroutines.test â€” runTest + UnconfinedTestDispatcher
 * - Turbine               â€” Flow collection assertions
 *
 * Requirements covered: 13.4 (syncStatus transitions), 13.1 (delete clears local + remote),
 *                       21.1 (unit test coverage)
 */
package com.aiassistant.data.repository

import app.cash.turbine.test
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.database.dao.NoteDao
import com.aiassistant.core.database.entity.NoteEntity
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.core.security.SecureStorage
import com.aiassistant.data.remote.note.NoteDto
import com.aiassistant.data.remote.note.NoteRemoteDataSource
import com.aiassistant.domain.model.Note
import com.aiassistant.domain.model.SyncStatus
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

// â”€â”€â”€ Test doubles â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

// â”€â”€â”€ Fixtures â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

private fun fakeNoteEntity(
    id: String = "note-1",
    userId: String = "user-1",
    syncStatus: String = "pending",
    tags: String = "[]"
) = NoteEntity(
    id = id,
    userId = userId,
    title = "Test Note",
    content = "Test content",
    tags = tags,
    syncStatus = syncStatus,
    createdAt = 1_000_000L,
    updatedAt = 2_000_000L
)

private fun fakeNote(
    id: String = "note-1",
    userId: String = "user-1",
    syncStatus: SyncStatus = SyncStatus.PENDING,
    tags: List<String> = emptyList()
) = Note(
    id = id,
    userId = userId,
    title = "Test Note",
    content = "Test content",
    tags = tags,
    syncStatus = syncStatus,
    createdAt = 1_000_000L,
    updatedAt = 2_000_000L
)

private fun fakeNoteDto(id: String = "note-1", userId: String = "user-1", syncStatus: String = "synced") = NoteDto(
    id = id,
    userId = userId,
    title = "Test Note",
    content = "Test content",
    tags = emptyList(),
    syncStatus = syncStatus,
    createdAt = 1_000_000L,
    updatedAt = 2_000_000L
)

// â”€â”€â”€ Spec â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

class NoteRepositoryImplTest :
    DescribeSpec({

        // â”€â”€ Shared mocks â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        val noteDao: NoteDao = mockk(relaxed = true)
        val remoteSource: NoteRemoteDataSource = mockk()
        val connectivityObserver: ConnectivityObserver = mockk()
        val secureStorage: SecureStorage = mockk()
        val dispatchers = TestDispatcherProvider()

        lateinit var repository: NoteRepositoryImpl

        beforeEach {
            clearAllMocks()
            every { secureStorage.getJwt() } returns "header.payload.userId"
            every { connectivityObserver.isConnectedFlow } returns flowOf(true)
            // Default: no pending notes unless test overrides
            coEvery { noteDao.getPendingNotes() } returns emptyList()
            repository = NoteRepositoryImpl(
                noteDao = noteDao,
                remoteSource = remoteSource,
                connectivityObserver = connectivityObserver,
                secureStorage = secureStorage,
                dispatchers = dispatchers
            )
        }

        afterEach {
            repository.cancelSync()
            unmockkAll()
        }

        // â”€â”€â”€ getNotes() â€” Room emission â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        describe("getNotes()") {
            it("emits ApiResult.Success containing notes from Room immediately") {
                runTest {
                    val entities = listOf(
                        fakeNoteEntity(id = "note-1", syncStatus = "synced"),
                        fakeNoteEntity(id = "note-2", syncStatus = "pending")
                    )
                    every { noteDao.getNotesByUser(any()) } returns flowOf(entities)
                    every { connectivityObserver.isConnected() } returns false

                    repository.getNotes().test {
                        val result = awaitItem()
                        result.shouldBeInstanceOf<ApiResult.Success<*>>()
                        val notes = (result as ApiResult.Success).data
                        notes.size shouldBe 2
                        notes[0].id shouldBe "note-1"
                        notes[0].syncStatus shouldBe SyncStatus.SYNCED
                        notes[1].id shouldBe "note-2"
                        notes[1].syncStatus shouldBe SyncStatus.PENDING
                        awaitComplete()
                    }
                }
            }

            it("emits empty list when Room has no notes") {
                runTest {
                    every { noteDao.getNotesByUser(any()) } returns flowOf(emptyList())
                    every { connectivityObserver.isConnected() } returns false

                    repository.getNotes().test {
                        val result = awaitItem()
                        result.shouldBeInstanceOf<ApiResult.Success<*>>()
                        (result as ApiResult.Success).data shouldBe emptyList()
                        awaitComplete()
                    }
                }
            }
        }

        // â”€â”€â”€ saveNote() â€” syncStatus transitions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        describe("saveNote() â€” syncStatus transitions") {

            describe("online â€” PENDING â†’ SYNCED") {
                it("saves note to Room with PENDING status immediately") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { remoteSource.updateNote(any(), any(), any(), any()) } returns
                            ApiResult.Success(fakeNoteDto(syncStatus = "synced"))

                        val note = fakeNote(syncStatus = SyncStatus.SYNCED) // initial status is irrelevant
                        repository.saveNote(note)

                        coVerify(exactly = 1) {
                            noteDao.insertNote(match { it.syncStatus == "pending" })
                        }
                    }
                }

                it("returns ApiResult.Success with PENDING note") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { remoteSource.updateNote(any(), any(), any(), any()) } returns
                            ApiResult.Success(fakeNoteDto(syncStatus = "synced"))

                        val result = repository.saveNote(fakeNote())

                        result.shouldBeInstanceOf<ApiResult.Success<*>>()
                        (result as ApiResult.Success).data.syncStatus shouldBe SyncStatus.PENDING
                    }
                }

                it("updates Room to SYNCED after successful remote sync") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { remoteSource.updateNote(any(), any(), any(), any()) } returns
                            ApiResult.Success(fakeNoteDto(syncStatus = "synced"))

                        repository.saveNote(fakeNote(id = "note-1"))

                        // After successful remote call, Room should be updated to SYNCED
                        coVerify(atLeast = 1) {
                            noteDao.updateNote(match { it.syncStatus == "synced" })
                        }
                    }
                }

                it("calls remote updateNote with correct parameters") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { remoteSource.updateNote(any(), any(), any(), any()) } returns
                            ApiResult.Success(fakeNoteDto())

                        repository.saveNote(fakeNote(id = "note-1"))

                        coVerify(exactly = 1) {
                            remoteSource.updateNote("note-1", any(), any(), any())
                        }
                    }
                }
            }

            describe("online â€” PENDING â†’ FAILED (remote sync fails)") {
                it("updates Room to FAILED when remote sync returns an error") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { remoteSource.updateNote(any(), any(), any(), any()) } returns
                            ApiResult.Error(DomainError.ServerError("Server error", 500))

                        repository.saveNote(fakeNote(id = "note-1"))

                        // Room should be updated to FAILED on sync error
                        coVerify(atLeast = 1) {
                            noteDao.updateNote(match { it.syncStatus == "failed" })
                        }
                    }
                }

                it("updates Room to FAILED when remote is unavailable") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { remoteSource.updateNote(any(), any(), any(), any()) } returns
                            ApiResult.NetworkUnavailable

                        repository.saveNote(fakeNote(id = "note-1"))

                        coVerify(atLeast = 1) {
                            noteDao.updateNote(match { it.syncStatus == "failed" })
                        }
                    }
                }
            }

            describe("offline â€” note stays PENDING") {
                it("saves note to Room with PENDING status when offline") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        repository.saveNote(fakeNote())

                        coVerify(exactly = 1) {
                            noteDao.insertNote(match { it.syncStatus == "pending" })
                        }
                    }
                }

                it("does NOT call remote when offline") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        repository.saveNote(fakeNote())

                        coVerify(exactly = 0) { remoteSource.updateNote(any(), any(), any(), any()) }
                    }
                }

                it("returns ApiResult.Success with PENDING note when offline") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        val result = repository.saveNote(fakeNote())

                        result.shouldBeInstanceOf<ApiResult.Success<*>>()
                        (result as ApiResult.Success).data.syncStatus shouldBe SyncStatus.PENDING
                    }
                }
            }
        }

        // â”€â”€â”€ syncPendingNotes() â€” background sync on getNotes() â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        describe("background sync of pending notes on getNotes()") {
            it("syncs pending notes to SYNCED when connected on getNotes()") {
                runTest {
                    val pendingEntity = fakeNoteEntity(id = "note-1", syncStatus = "pending")
                    every { noteDao.getNotesByUser(any()) } returns flowOf(listOf(pendingEntity))
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { noteDao.getPendingNotes() } returns listOf(pendingEntity)
                    coEvery { remoteSource.updateNote(any(), any(), any(), any()) } returns
                        ApiResult.Success(fakeNoteDto(syncStatus = "synced"))

                    repository.getNotes().test {
                        awaitItem() // Room emission
                        awaitComplete()
                    }

                    // Background sync should have updated pending note to synced
                    coVerify(atLeast = 1) {
                        noteDao.updateNote(match { it.syncStatus == "synced" })
                    }
                }
            }

            it("marks pending note as FAILED when sync fails") {
                runTest {
                    val pendingEntity = fakeNoteEntity(id = "note-1", syncStatus = "pending")
                    every { noteDao.getNotesByUser(any()) } returns flowOf(listOf(pendingEntity))
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { noteDao.getPendingNotes() } returns listOf(pendingEntity)
                    coEvery { remoteSource.updateNote(any(), any(), any(), any()) } returns
                        ApiResult.Error(DomainError.NetworkError("Connection reset"))

                    repository.getNotes().test {
                        awaitItem()
                        awaitComplete()
                    }

                    coVerify(atLeast = 1) {
                        noteDao.updateNote(match { it.syncStatus == "failed" })
                    }
                }
            }

            it("skips background sync when offline") {
                runTest {
                    every { noteDao.getNotesByUser(any()) } returns flowOf(emptyList())
                    every { connectivityObserver.isConnected() } returns false

                    repository.getNotes().test {
                        awaitItem()
                        awaitComplete()
                    }

                    coVerify(exactly = 0) { remoteSource.updateNote(any(), any(), any(), any()) }
                }
            }
        }

        // â”€â”€â”€ deleteNote() â€” clears local + remote â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        describe("deleteNote()") {

            describe("online â€” both local and remote are cleared") {
                it("deletes from Room DAO immediately") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { remoteSource.deleteNote(any()) } returns ApiResult.Success(Unit)

                        repository.deleteNote("note-1")

                        coVerify(exactly = 1) { noteDao.deleteNote("note-1") }
                    }
                }

                it("calls remote deleteNote when connected") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { remoteSource.deleteNote("note-1") } returns ApiResult.Success(Unit)

                        repository.deleteNote("note-1")

                        coVerify(exactly = 1) { remoteSource.deleteNote("note-1") }
                    }
                }

                it("returns ApiResult.Success(Unit) when both local and remote succeed") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { remoteSource.deleteNote("note-1") } returns ApiResult.Success(Unit)

                        val result = repository.deleteNote("note-1")

                        result shouldBe ApiResult.Success(Unit)
                    }
                }

                it("returns ApiResult.Success(Unit) even when remote delete fails (best-effort)") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { remoteSource.deleteNote("note-1") } returns
                            ApiResult.Error(DomainError.ServerError("Server error", 500))

                        val result = repository.deleteNote("note-1")

                        // Local delete always wins; remote failure is swallowed
                        result shouldBe ApiResult.Success(Unit)
                        coVerify(exactly = 1) { noteDao.deleteNote("note-1") }
                    }
                }

                it("local delete still occurs when remote returns network unavailable") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { remoteSource.deleteNote("note-1") } returns ApiResult.NetworkUnavailable

                        repository.deleteNote("note-1")

                        coVerify(exactly = 1) { noteDao.deleteNote("note-1") }
                    }
                }
            }

            describe("offline â€” only local cache is cleared") {
                it("deletes from Room DAO even when offline") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        repository.deleteNote("note-1")

                        coVerify(exactly = 1) { noteDao.deleteNote("note-1") }
                    }
                }

                it("does NOT call remote when offline") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        repository.deleteNote("note-1")

                        coVerify(exactly = 0) { remoteSource.deleteNote(any()) }
                    }
                }

                it("returns ApiResult.Success(Unit) offline after local delete") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        val result = repository.deleteNote("note-1")

                        result shouldBe ApiResult.Success(Unit)
                    }
                }
            }
        }

        // â”€â”€â”€ getNotesByTag() â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        describe("getNotesByTag()") {
            it("filters notes to only those containing the requested tag") {
                runTest {
                    val entities = listOf(
                        fakeNoteEntity(id = "note-1", tags = "[\"kotlin\",\"android\"]"),
                        fakeNoteEntity(id = "note-2", tags = "[\"python\"]"),
                        fakeNoteEntity(id = "note-3", tags = "[\"kotlin\"]")
                    )
                    every { noteDao.getNotesByUser(any()) } returns flowOf(entities)

                    repository.getNotesByTag("kotlin").test {
                        val result = awaitItem()
                        result.shouldBeInstanceOf<ApiResult.Success<*>>()
                        val notes = (result as ApiResult.Success).data
                        notes.size shouldBe 2
                        notes.all { "kotlin" in it.tags } shouldBe true
                        awaitComplete()
                    }
                }
            }

            it("returns all notes when tag is blank") {
                runTest {
                    val entities = listOf(
                        fakeNoteEntity(id = "note-1", tags = "[\"kotlin\"]"),
                        fakeNoteEntity(id = "note-2", tags = "[]")
                    )
                    every { noteDao.getNotesByUser(any()) } returns flowOf(entities)

                    repository.getNotesByTag("").test {
                        val result = awaitItem()
                        result.shouldBeInstanceOf<ApiResult.Success<*>>()
                        (result as ApiResult.Success).data.size shouldBe 2
                        awaitComplete()
                    }
                }
            }
        }
    })
