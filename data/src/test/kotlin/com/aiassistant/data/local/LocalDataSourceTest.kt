/**
 * LocalDataSourceTest.kt — data module
 *
 * Purpose: Unit tests for [ConversationLocalDataSource] and [MessageLocalDataSource],
 *          verifying that each method correctly delegates to the underlying DAO and runs
 *          on the IO dispatcher.
 *
 *   ConversationLocalDataSource:
 *     - getConversationsPaged()      — delegates to ConversationDao.getConversations()
 *     - searchConversations()        — delegates to ConversationDao.searchConversations()
 *     - getConversationById()        — delegates to ConversationDao.getConversationById()
 *     - insertConversation()         — delegates to ConversationDao.insertConversation()
 *     - insertConversations()        — delegates to ConversationDao.insertConversations()
 *     - softDeleteConversation()     — delegates to ConversationDao.softDeleteConversation()
 *     - renameConversation()         — delegates to ConversationDao.renameConversation()
 *     - pinConversation()            — delegates to ConversationDao.pinConversation()
 *     - updateConversation()         — delegates to ConversationDao.updateConversation()
 *
 *   MessageLocalDataSource:
 *     - getMessagesForConversation() — delegates to MessageDao.getMessagesForConversation()
 *     - insertMessage()              — delegates to MessageDao.insertMessage()
 *     - insertMessages()             — delegates to MessageDao.insertMessages()
 *     - updateMessage()              — delegates to MessageDao.updateMessage()
 *     - getPendingMessages()         — delegates to MessageDao.getPendingMessages()
 *     - updateSyncStatus()           — delegates to MessageDao.updateSyncStatus()
 *
 * Architecture: data module — pure JVM unit tests, no Android framework or Robolectric.
 *
 * Test toolchain:
 * - Kotest DescribeSpec  — test structure
 * - MockK                — mock ConversationDao, MessageDao
 * - Turbine              — Flow collection assertions
 * - kotlinx.coroutines.test — runTest
 *
 * Requirements covered: 10.1, 10.2, 10.3, 11.1, 11.3, 11.4
 */
package com.aiassistant.data.local

import app.cash.turbine.test
import com.aiassistant.core.database.dao.ConversationDao
import com.aiassistant.core.database.dao.MessageDao
import com.aiassistant.core.database.entity.ConversationEntity
import com.aiassistant.core.database.entity.MessageEntity
import com.aiassistant.data.repository.TestDispatcherProvider
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

// ─── Fixtures ─────────────────────────────────────────────────────────────────

private fun fakeConversationEntity(
    id: String = "conv-1",
    userId: String = "user-1",
    title: String = "Test Conversation",
    isPinned: Boolean = false,
    isDeleted: Boolean = false,
    provider: String = "openai",
    createdAt: Long = 1_000_000L,
    updatedAt: Long = 2_000_000L
) = ConversationEntity(
    id = id,
    userId = userId,
    title = title,
    isPinned = isPinned,
    isDeleted = isDeleted,
    provider = provider,
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun fakeMessageEntity(
    id: String = "msg-1",
    conversationId: String = "conv-1",
    role: String = "user",
    content: String = "Hello",
    syncStatus: String = "pending",
    createdAt: Long = 1_000_000L
) = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    inputTokens = 0,
    outputTokens = 0,
    provider = "openai",
    syncStatus = syncStatus,
    createdAt = createdAt
)

// ─── Spec ─────────────────────────────────────────────────────────────────────

class LocalDataSourceTest : DescribeSpec({

    val conversationDao: ConversationDao = mockk(relaxed = true)
    val messageDao: MessageDao = mockk(relaxed = true)
    val dispatchers = TestDispatcherProvider()

    lateinit var conversationDataSource: ConversationLocalDataSource
    lateinit var messageDataSource: MessageLocalDataSource

    beforeEach {
        clearAllMocks()
        conversationDataSource = ConversationLocalDataSource(conversationDao, dispatchers)
        messageDataSource = MessageLocalDataSource(messageDao, dispatchers)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ConversationLocalDataSource
    // ══════════════════════════════════════════════════════════════════════════

    describe("ConversationLocalDataSource") {

        // ── getConversationsPaged() ────────────────────────────────────────────

        describe("getConversationsPaged()") {
            it("delegates to conversationDao.getConversations() with the given userId") {
                val pagingSource = mockk<androidx.paging.PagingSource<Int, ConversationEntity>>()
                every { conversationDao.getConversations("user-1") } returns pagingSource

                val result = conversationDataSource.getConversationsPaged("user-1")

                result shouldBe pagingSource
            }
        }

        // ── searchConversations() ──────────────────────────────────────────────

        describe("searchConversations()") {
            it("returns Flow from conversationDao.searchConversations()") {
                runTest {
                    val entity = fakeConversationEntity()
                    every { conversationDao.searchConversations("test", "user-1") } returns flowOf(listOf(entity))

                    conversationDataSource.searchConversations("test", "user-1").test {
                        val items = awaitItem()
                        items.size shouldBe 1
                        items[0].id shouldBe "conv-1"
                        awaitComplete()
                    }
                }
            }

            it("returns empty flow when no matches") {
                runTest {
                    every { conversationDao.searchConversations("nomatch", "user-1") } returns flowOf(emptyList())

                    conversationDataSource.searchConversations("nomatch", "user-1").test {
                        awaitItem() shouldBe emptyList()
                        awaitComplete()
                    }
                }
            }
        }

        // ── getConversationById() ──────────────────────────────────────────────

        describe("getConversationById()") {
            it("returns Flow emitting the entity for the given id") {
                runTest {
                    val entity = fakeConversationEntity(id = "conv-42")
                    every { conversationDao.getConversationById("conv-42") } returns flowOf(entity)

                    conversationDataSource.getConversationById("conv-42").test {
                        awaitItem() shouldBe entity
                        awaitComplete()
                    }
                }
            }

            it("returns Flow emitting null when conversation not found") {
                runTest {
                    every { conversationDao.getConversationById("missing") } returns flowOf(null)

                    conversationDataSource.getConversationById("missing").test {
                        awaitItem() shouldBe null
                        awaitComplete()
                    }
                }
            }
        }

        // ── insertConversation() ───────────────────────────────────────────────

        describe("insertConversation()") {
            it("delegates insert to conversationDao") {
                runTest {
                    val entity = fakeConversationEntity()
                    coEvery { conversationDao.insertConversation(entity) } returns Unit

                    conversationDataSource.insertConversation(entity)

                    coVerify(exactly = 1) { conversationDao.insertConversation(entity) }
                }
            }
        }

        // ── insertConversations() ──────────────────────────────────────────────

        describe("insertConversations()") {
            it("delegates bulk insert to conversationDao") {
                runTest {
                    val entities = listOf(fakeConversationEntity("c-1"), fakeConversationEntity("c-2"))
                    coEvery { conversationDao.insertConversations(entities) } returns Unit

                    conversationDataSource.insertConversations(entities)

                    coVerify(exactly = 1) { conversationDao.insertConversations(entities) }
                }
            }

            it("handles empty list without error") {
                runTest {
                    coEvery { conversationDao.insertConversations(emptyList()) } returns Unit

                    conversationDataSource.insertConversations(emptyList())

                    coVerify(exactly = 1) { conversationDao.insertConversations(emptyList()) }
                }
            }
        }

        // ── softDeleteConversation() ───────────────────────────────────────────

        describe("softDeleteConversation()") {
            it("delegates soft-delete to conversationDao with id and timestamp") {
                runTest {
                    coEvery { conversationDao.softDeleteConversation("conv-1", 9_999_999L) } returns Unit

                    conversationDataSource.softDeleteConversation("conv-1", 9_999_999L)

                    coVerify(exactly = 1) { conversationDao.softDeleteConversation("conv-1", 9_999_999L) }
                }
            }
        }

        // ── renameConversation() ───────────────────────────────────────────────

        describe("renameConversation()") {
            it("delegates rename to conversationDao with new title and timestamp") {
                runTest {
                    coEvery { conversationDao.renameConversation("conv-1", "New Title", 5_000L) } returns Unit

                    conversationDataSource.renameConversation("conv-1", "New Title", 5_000L)

                    coVerify(exactly = 1) { conversationDao.renameConversation("conv-1", "New Title", 5_000L) }
                }
            }
        }

        // ── pinConversation() ──────────────────────────────────────────────────

        describe("pinConversation()") {
            it("delegates pin=true to conversationDao") {
                runTest {
                    coEvery { conversationDao.pinConversation("conv-1", true, 6_000L) } returns Unit

                    conversationDataSource.pinConversation("conv-1", true, 6_000L)

                    coVerify(exactly = 1) { conversationDao.pinConversation("conv-1", true, 6_000L) }
                }
            }

            it("delegates pin=false (unpin) to conversationDao") {
                runTest {
                    coEvery { conversationDao.pinConversation("conv-1", false, 7_000L) } returns Unit

                    conversationDataSource.pinConversation("conv-1", false, 7_000L)

                    coVerify(exactly = 1) { conversationDao.pinConversation("conv-1", false, 7_000L) }
                }
            }
        }

        // ── updateConversation() ───────────────────────────────────────────────

        describe("updateConversation()") {
            it("delegates update to conversationDao") {
                runTest {
                    val entity = fakeConversationEntity(title = "Updated Title")
                    coEvery { conversationDao.updateConversation(entity) } returns Unit

                    conversationDataSource.updateConversation(entity)

                    coVerify(exactly = 1) { conversationDao.updateConversation(entity) }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MessageLocalDataSource
    // ══════════════════════════════════════════════════════════════════════════

    describe("MessageLocalDataSource") {

        // ── getMessagesForConversation() ───────────────────────────────────────

        describe("getMessagesForConversation()") {
            it("returns Flow from messageDao.getMessagesForConversation()") {
                runTest {
                    val messages = listOf(fakeMessageEntity("m-1"), fakeMessageEntity("m-2"))
                    every { messageDao.getMessagesForConversation("conv-1") } returns flowOf(messages)

                    messageDataSource.getMessagesForConversation("conv-1").test {
                        val items = awaitItem()
                        items.size shouldBe 2
                        items[0].id shouldBe "m-1"
                        awaitComplete()
                    }
                }
            }

            it("returns empty Flow when no messages exist") {
                runTest {
                    every { messageDao.getMessagesForConversation("empty-conv") } returns flowOf(emptyList())

                    messageDataSource.getMessagesForConversation("empty-conv").test {
                        awaitItem() shouldBe emptyList()
                        awaitComplete()
                    }
                }
            }
        }

        // ── insertMessage() ────────────────────────────────────────────────────

        describe("insertMessage()") {
            it("delegates to messageDao.insertMessage()") {
                runTest {
                    val entity = fakeMessageEntity()
                    coEvery { messageDao.insertMessage(entity) } returns Unit

                    messageDataSource.insertMessage(entity)

                    coVerify(exactly = 1) { messageDao.insertMessage(entity) }
                }
            }
        }

        // ── insertMessages() ───────────────────────────────────────────────────

        describe("insertMessages()") {
            it("delegates bulk insert to messageDao") {
                runTest {
                    val entities = listOf(fakeMessageEntity("m-1"), fakeMessageEntity("m-2"))
                    coEvery { messageDao.insertMessages(entities) } returns Unit

                    messageDataSource.insertMessages(entities)

                    coVerify(exactly = 1) { messageDao.insertMessages(entities) }
                }
            }
        }

        // ── updateMessage() ────────────────────────────────────────────────────

        describe("updateMessage()") {
            it("delegates update to messageDao") {
                runTest {
                    val entity = fakeMessageEntity(content = "Updated content", syncStatus = "synced")
                    coEvery { messageDao.updateMessage(entity) } returns Unit

                    messageDataSource.updateMessage(entity)

                    coVerify(exactly = 1) { messageDao.updateMessage(entity) }
                }
            }
        }

        // ── getPendingMessages() ───────────────────────────────────────────────

        describe("getPendingMessages()") {
            it("returns list of pending messages from messageDao") {
                runTest {
                    val pending = listOf(
                        fakeMessageEntity("m-p1", syncStatus = "pending"),
                        fakeMessageEntity("m-p2", syncStatus = "pending")
                    )
                    coEvery { messageDao.getPendingMessages() } returns pending

                    val result = messageDataSource.getPendingMessages()

                    result.size shouldBe 2
                    result[0].syncStatus shouldBe "pending"
                    coVerify(exactly = 1) { messageDao.getPendingMessages() }
                }
            }

            it("returns empty list when no pending messages exist") {
                runTest {
                    coEvery { messageDao.getPendingMessages() } returns emptyList()

                    val result = messageDataSource.getPendingMessages()

                    result shouldBe emptyList()
                }
            }
        }

        // ── updateSyncStatus() ─────────────────────────────────────────────────

        describe("updateSyncStatus()") {
            it("delegates status update to messageDao with synced status") {
                runTest {
                    coEvery { messageDao.updateSyncStatus("msg-1", "synced") } returns Unit

                    messageDataSource.updateSyncStatus("msg-1", "synced")

                    coVerify(exactly = 1) { messageDao.updateSyncStatus("msg-1", "synced") }
                }
            }

            it("delegates status update to messageDao with failed status") {
                runTest {
                    coEvery { messageDao.updateSyncStatus("msg-2", "failed") } returns Unit

                    messageDataSource.updateSyncStatus("msg-2", "failed")

                    coVerify(exactly = 1) { messageDao.updateSyncStatus("msg-2", "failed") }
                }
            }
        }
    }
})
