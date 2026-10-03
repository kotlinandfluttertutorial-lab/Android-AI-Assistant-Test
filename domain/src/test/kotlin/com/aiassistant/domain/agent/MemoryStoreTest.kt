/*
 * Domain unit tests for MemoryStore interface and MemoryStoreException.
 *
 * Tests:
 *   - Minimal stub implements the contract correctly
 *   - MemoryStoreException carries retryable flag and cause
 *   - Privacy-mode semantics (no-op store returns empty id)
 */
package com.aiassistant.domain.agent

import com.aiassistant.domain.model.Memory
import com.aiassistant.domain.model.MemoryType
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

// ── Minimal stub ──────────────────────────────────────────────────────────────

private open class InMemoryMemoryStore : MemoryStore {
    private val store = mutableMapOf<String, Memory>()
    private var nextId = 1

    override suspend fun store(memory: Memory): String {
        val id = memory.id.ifBlank { "mem-${nextId++}" }
        val persisted = memory.copy(id = id)
        store[id] = persisted
        return id
    }

    override suspend fun retrieve(
        userId: String,
        query: String,
        topK: Int,
        memoryType: MemoryType?,
    ): List<Memory> = store.values
        .filter { it.userId == userId && (memoryType == null || it.memoryType == memoryType) }
        .take(topK)

    override fun observe(userId: String) =
        flowOf(store.values.filter { it.userId == userId })

    override suspend fun delete(userId: String, memoryId: String) {
        store.remove(memoryId)
    }

    override suspend fun deleteAll(userId: String) {
        store.keys.removeAll(
            store.values.filter { it.userId == userId }.map { it.id }.toSet()
        )
    }
}

private fun memory(
    userId: String = "u1",
    content: String = "User prefers dark mode.",
    type: MemoryType = MemoryType.FACT,
    id: String = "",
) = Memory(id = id, userId = userId, content = content, memoryType = type, createdAt = 0L)

// ── Tests ─────────────────────────────────────────────────────────────────────

class MemoryStoreTest {

    private fun store() = InMemoryMemoryStore()

    @Test fun `store returns non-blank id for new memory`() = runTest {
        val id = store().store(memory())
        id.isNotBlank().shouldBeTrue()
    }

    @Test fun `store is idempotent when id is pre-set`() = runTest {
        val s = store()
        val id1 = s.store(memory(id = "fixed-id"))
        val id2 = s.store(memory(id = "fixed-id", content = "Updated content"))
        id1 shouldBe "fixed-id"
        id2 shouldBe "fixed-id"
        s.retrieve("u1", "anything", topK = 10) shouldHaveSize 1
    }

    @Test fun `retrieve returns memories for correct user only`() = runTest {
        val s = store()
        s.store(memory(userId = "alice", content = "Alice fact"))
        s.store(memory(userId = "bob", content = "Bob fact"))
        val results = s.retrieve("alice", "query")
        results shouldHaveSize 1
        results[0].userId shouldBe "alice"
    }

    @Test fun `retrieve respects topK limit`() = runTest {
        val s = store()
        repeat(10) { s.store(memory(content = "fact $it")) }
        s.retrieve("u1", "query", topK = 3) shouldHaveSize 3
    }

    @Test fun `retrieve with memoryType filter returns only that type`() = runTest {
        val s = store()
        s.store(memory(type = MemoryType.FACT))
        s.store(memory(type = MemoryType.PREFERENCE))
        s.store(memory(type = MemoryType.STYLE))
        val facts = s.retrieve("u1", "q", memoryType = MemoryType.FACT)
        facts.all { it.memoryType == MemoryType.FACT }.shouldBeTrue()
    }

    @Test fun `retrieve returns empty list when store is empty`() = runTest {
        store().retrieve("u1", "anything").shouldBeEmpty()
    }

    @Test fun `observe emits current state`() = runTest {
        val s = store()
        s.store(memory(content = "observable fact"))
        val snapshot = s.observe("u1").first()
        snapshot shouldHaveSize 1
    }

    @Test fun `delete removes specific memory`() = runTest {
        val s = store()
        val id = s.store(memory())
        s.delete("u1", id)
        s.retrieve("u1", "query").shouldBeEmpty()
    }

    @Test fun `delete of unknown id is no-op`() = runTest {
        val s = store()
        s.store(memory())
        s.delete("u1", "nonexistent-id")
        s.retrieve("u1", "q") shouldHaveSize 1
    }

    @Test fun `deleteAll removes all memories for user`() = runTest {
        val s = store()
        repeat(3) { s.store(memory(content = "fact $it")) }
        s.store(memory(userId = "other", content = "other user"))
        s.deleteAll("u1")
        s.retrieve("u1", "q").shouldBeEmpty()
        s.retrieve("other", "q") shouldHaveSize 1
    }

    @Test fun `privacy mode no-op store returns empty string`() = runTest {
        val privacyStore = object : InMemoryMemoryStore() {
            override suspend fun store(memory: Memory): String = ""
        }
        val id = privacyStore.store(memory())
        id.isBlank().shouldBeTrue()
    }

    @Test fun `MemoryStoreException carries retryable flag`() {
        val e = MemoryStoreException("network timeout", retryable = true)
        e.retryable.shouldBeTrue()
        e.message shouldBe "network timeout"
    }

    @Test fun `MemoryStoreException non-retryable by default`() {
        val e = MemoryStoreException("bad state")
        e.retryable.shouldBeFalse()
    }

    @Test fun `MemoryStoreException preserves cause`() {
        val cause = RuntimeException("root cause")
        val e = MemoryStoreException("wrapper", cause = cause)
        e.cause shouldBe cause
    }
}
