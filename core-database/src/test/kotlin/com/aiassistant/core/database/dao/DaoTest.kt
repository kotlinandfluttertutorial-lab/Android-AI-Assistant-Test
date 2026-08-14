package com.aiassistant.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiassistant.core.database.AppDatabase
import com.aiassistant.core.database.entity.ConversationEntity
import com.aiassistant.core.database.entity.MessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DAO unit tests using an in-memory Room database backed by Robolectric.
 *
 * Validates:
 *  - Requirements 11.2 (FTS search returns structurally correct results, respects user scoping
 *    and soft-delete exclusions)
 *  - Requirements 21.1 (CASCADE delete on conversation removes all child messages)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DaoTest {

    // ── Database & DAOs ───────────────────────────────────────────────────────

    private lateinit var db: AppDatabase
    private lateinit var conversationDao: ConversationDao
    private lateinit var messageDao: MessageDao

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the SQLite rowid for the given conversation id.
     * FTS4 content tables rely on rowid for joining — we need the rowid to
     * insert a matching FTS row.
     */
    private fun rowIdForConversation(conversationId: String): Long {
        val cursor = db.openHelper.writableDatabase.query(
            "SELECT rowid FROM conversations WHERE id = ?",
            arrayOf(conversationId)
        )
        return cursor.use {
            check(it.moveToFirst()) { "No conversation row found for id=$conversationId" }
            it.getLong(0)
        }
    }

    /**
     * Returns the SQLite rowid for the given message id.
     */
    private fun rowIdForMessage(messageId: String): Long {
        val cursor = db.openHelper.writableDatabase.query(
            "SELECT rowid FROM messages WHERE id = ?",
            arrayOf(messageId)
        )
        return cursor.use {
            check(it.moveToFirst()) { "No message row found for id=$messageId" }
            it.getLong(0)
        }
    }

    /**
     * Inserts a conversation and a matching FTS row in one step.
     */
    private suspend fun insertConversationWithFts(conversation: ConversationEntity) {
        conversationDao.insertConversation(conversation)
        val rowId = rowIdForConversation(conversation.id)
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO conversations_fts(rowid, title) VALUES (?, ?)",
            arrayOf(rowId, conversation.title)
        )
    }

    /**
     * Inserts a message and a matching FTS row in one step.
     */
    private suspend fun insertMessageWithFts(message: MessageEntity) {
        messageDao.insertMessage(message)
        val rowId = rowIdForMessage(message.id)
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO messages_fts(rowid, content) VALUES (?, ?)",
            arrayOf(rowId, message.content)
        )
    }

    // ── Fixture builders ──────────────────────────────────────────────────────

    private fun conversation(
        id: String,
        userId: String = "user-1",
        title: String = "Test Conversation",
        isDeleted: Boolean = false,
        updatedAt: Long = System.currentTimeMillis()
    ) = ConversationEntity(
        id = id,
        userId = userId,
        title = title,
        isPinned = false,
        isDeleted = isDeleted,
        provider = "openai",
        createdAt = 1_000L,
        updatedAt = updatedAt
    )

    private fun message(
        id: String,
        conversationId: String,
        content: String = "Hello world",
        syncStatus: String = "synced",
        createdAt: Long = System.currentTimeMillis()
    ) = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = "user",
        content = content,
        inputTokens = 5,
        outputTokens = 10,
        provider = "openai",
        syncStatus = syncStatus,
        createdAt = createdAt
    )

    // ── Setup / teardown ──────────────────────────────────────────────────────

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

        conversationDao = db.conversationDao()
        messageDao = db.messageDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ConversationDao + FTS tests
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * FTS search returns only the conversation whose title matches the query.
     *
     * Validates: Requirements 11.2
     */
    @Test
    fun searchConversations_returnsMatchingConversations() = runTest {
        val conv1 = conversation(id = "conv-1", title = "Android development tips")
        val conv2 = conversation(id = "conv-2", title = "Kotlin coroutines guide")
        insertConversationWithFts(conv1)
        insertConversationWithFts(conv2)

        val results = conversationDao.searchConversations("Android", "user-1").first()

        assertEquals(1, results.size)
        assertEquals("conv-1", results.first().id)
        assertEquals("Android development tips", results.first().title)
    }

    /**
     * FTS search excludes conversations that have been soft-deleted.
     *
     * Validates: Requirements 11.2
     */
    @Test
    fun searchConversations_excludesDeletedConversations() = runTest {
        val conv = conversation(id = "conv-del", title = "Machine learning basics")
        insertConversationWithFts(conv)
        conversationDao.softDeleteConversation("conv-del", System.currentTimeMillis())

        val results = conversationDao.searchConversations("Machine", "user-1").first()

        assertTrue("Expected no results for soft-deleted conversation", results.isEmpty())
    }

    /**
     * FTS search scoped to a userId only returns conversations belonging to that user.
     *
     * Validates: Requirements 11.2
     */
    @Test
    fun searchConversations_excludesOtherUsers() = runTest {
        val convA = conversation(id = "conv-a", userId = "user-A", title = "Deep learning notes")
        val convB = conversation(id = "conv-b", userId = "user-B", title = "Deep learning notes")
        insertConversationWithFts(convA)
        insertConversationWithFts(convB)

        val results = conversationDao.searchConversations("Deep", "user-A").first()

        assertEquals(1, results.size)
        assertEquals("conv-a", results.first().id)
        assertTrue(results.all { it.userId == "user-A" })
    }

    /**
     * getConversationById emits null after a conversation is soft-deleted.
     *
     * Validates: Requirements 11.2
     */
    @Test
    fun getConversationById_returnsNullAfterSoftDelete() = runTest {
        val conv = conversation(id = "conv-soft")
        conversationDao.insertConversation(conv)

        // Verify it exists first
        assertNotNull(conversationDao.getConversationById("conv-soft").first())

        conversationDao.softDeleteConversation("conv-soft", System.currentTimeMillis())

        val result = conversationDao.getConversationById("conv-soft").first()
        assertNull("Expected null for soft-deleted conversation", result)
    }

    /**
     * insertConversations (bulk) persists all entities and they are individually retrievable.
     *
     * Validates: Requirements 11.2
     */
    @Test
    fun insertConversations_andRetrieve() = runTest {
        val conversations = listOf(
            conversation(id = "bulk-1", title = "First convo"),
            conversation(id = "bulk-2", title = "Second convo"),
            conversation(id = "bulk-3", title = "Third convo")
        )
        conversationDao.insertConversations(conversations)

        for (conv in conversations) {
            val retrieved = conversationDao.getConversationById(conv.id).first()
            assertNotNull("Expected conversation ${conv.id} to be retrievable", retrieved)
            assertEquals(conv.id, retrieved!!.id)
            assertEquals(conv.title, retrieved.title)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MessageDao + FTS tests
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * FTS search on messages returns only the message whose content matches the query.
     *
     * Validates: Requirements 11.2
     */
    @Test
    fun searchMessages_returnsMatchingMessages() = runTest {
        conversationDao.insertConversation(conversation(id = "conv-msg-fts"))

        val msg1 = message(id = "msg-1", conversationId = "conv-msg-fts", content = "Tell me about Kotlin flows")
        val msg2 = message(id = "msg-2", conversationId = "conv-msg-fts", content = "What is the weather today")
        insertMessageWithFts(msg1)
        insertMessageWithFts(msg2)

        val results = messageDao.searchMessages("Kotlin", "user-1").first()

        assertEquals(1, results.size)
        assertEquals("msg-1", results.first().id)
    }

    /**
     * FTS search on messages excludes messages that belong to a soft-deleted conversation.
     *
     * Validates: Requirements 11.2
     */
    @Test
    fun searchMessages_excludesMessagesFromDeletedConversations() = runTest {
        conversationDao.insertConversation(conversation(id = "conv-del-msg"))
        val msg = message(id = "msg-del", conversationId = "conv-del-msg", content = "Neural networks explained")
        insertMessageWithFts(msg)

        conversationDao.softDeleteConversation("conv-del-msg", System.currentTimeMillis())

        val results = messageDao.searchMessages("Neural", "user-1").first()

        assertTrue("Expected no results for message in soft-deleted conversation", results.isEmpty())
    }

    /**
     * getPendingMessages returns only messages with syncStatus == "pending".
     *
     * Validates: Requirements 11.2
     */
    @Test
    fun getPendingMessages_returnsOnlyPendingStatus() = runTest {
        conversationDao.insertConversation(conversation(id = "conv-sync"))

        val pendingMsg =
            message(id = "msg-pending", conversationId = "conv-sync", syncStatus = "pending", createdAt = 1000L)
        val syncedMsg =
            message(id = "msg-synced", conversationId = "conv-sync", syncStatus = "synced", createdAt = 2000L)
        val failedMsg =
            message(id = "msg-failed", conversationId = "conv-sync", syncStatus = "failed", createdAt = 3000L)
        messageDao.insertMessage(pendingMsg)
        messageDao.insertMessage(syncedMsg)
        messageDao.insertMessage(failedMsg)

        val pending = messageDao.getPendingMessages()

        assertEquals(1, pending.size)
        assertEquals("msg-pending", pending.first().id)
        assertEquals("pending", pending.first().syncStatus)
    }

    /**
     * updateSyncStatus correctly changes the syncStatus field on the target message.
     *
     * Validates: Requirements 11.2
     */
    @Test
    fun updateSyncStatus_changesStatusCorrectly() = runTest {
        conversationDao.insertConversation(conversation(id = "conv-upd-sync"))
        val msg = message(id = "msg-upd", conversationId = "conv-upd-sync", syncStatus = "pending")
        messageDao.insertMessage(msg)

        messageDao.updateSyncStatus("msg-upd", "synced")

        val messages = messageDao.getMessagesForConversation("conv-upd-sync").first()
        val updatedMessage = messages.find { it.id == "msg-upd" }
        assertNotNull(updatedMessage)
        assertEquals("synced", updatedMessage!!.syncStatus)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Cascade delete test
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Hard-deleting a conversation via CASCADE FK removes all its child messages.
     *
     * Room's ForeignKey(onDelete = CASCADE) fires on a physical DELETE from the table.
     * Soft-delete (setting isDeleted = 1) does NOT trigger the FK cascade, so we use
     * a direct SQL DELETE to exercise the CASCADE path.
     *
     * Validates: Requirements 21.1
     */
    @Test
    fun cascadeDelete_removesMessagesWhenConversationDeleted() = runTest {
        val conversationId = "conv-cascade"
        conversationDao.insertConversation(conversation(id = conversationId))

        messageDao.insertMessage(message(id = "msg-c1", conversationId = conversationId, createdAt = 1000L))
        messageDao.insertMessage(message(id = "msg-c2", conversationId = conversationId, createdAt = 2000L))
        messageDao.insertMessage(message(id = "msg-c3", conversationId = conversationId, createdAt = 3000L))

        // Verify messages were inserted
        val before = messageDao.getMessagesForConversation(conversationId).first()
        assertEquals(3, before.size)

        // Hard-delete the conversation — triggers the FK CASCADE
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM conversations WHERE id = '$conversationId'"
        )

        val after = messageDao.getMessagesForConversation(conversationId).first()
        assertEquals(
            "Expected 0 messages after CASCADE delete of parent conversation",
            0,
            after.size
        )
    }
}
