/*
 * Unit tests for LocalVectorStoreAdapter.
 *
 * Both [LocalVectorIndex] and [OnDeviceChunkDao] are faked —
 * no Room DB, no real vector search, no production credentials.
 */
package com.aiassistant.data.vector

import com.aiassistant.core.common.ChunkSearchResult
import com.aiassistant.core.common.LocalVectorIndex
import com.aiassistant.core.common.TextChunk
import com.aiassistant.core.database.dao.OnDeviceChunkDao
import com.aiassistant.core.database.entity.OnDeviceChunkEntity
import com.aiassistant.domain.agent.EmbeddingVector
import com.aiassistant.domain.agent.VectorChunk
import com.aiassistant.domain.agent.VectorSearchResult
import com.aiassistant.domain.agent.VectorStore
import com.aiassistant.domain.agent.VectorStoreException
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

// ── Fake LocalVectorIndex ─────────────────────────────────────────────────────

private open class FakeLocalVectorIndex : LocalVectorIndex {
    val added = mutableListOf<Pair<String, TextChunk>>()
    val deleted = mutableListOf<Pair<String, String>>()

    private val searchResults = mutableListOf<ChunkSearchResult>()
    var throwOnAdd: Boolean = false
    var throwOnSearch: Boolean = false

    fun stubSearchResult(result: ChunkSearchResult) = searchResults.add(result)

    override suspend fun addChunk(userId: String, chunk: TextChunk, embedding: FloatArray) {
        if (throwOnAdd) throw RuntimeException("add failed")
        added += userId to chunk
    }

    override suspend fun search(
        userId: String,
        queryEmbedding: FloatArray,
        k: Int,
        minSimilarity: Float,
    ): List<ChunkSearchResult> {
        if (throwOnSearch) throw RuntimeException("search failed")
        return searchResults.filter { it.cosineSimilarity >= minSimilarity }.take(k)
    }

    override suspend fun deleteByDocument(userId: String, documentId: String) {
        deleted += userId to documentId
    }
}
// ── Fake OnDeviceChunkDao ─────────────────────────────────────────────────────

private open class FakeOnDeviceChunkDao : OnDeviceChunkDao {
    private val chunks = mutableListOf<OnDeviceChunkEntity>()

    fun addEntity(e: OnDeviceChunkEntity) = chunks.add(e)

    override suspend fun insert(chunk: OnDeviceChunkEntity) { chunks.add(chunk) }
    override suspend fun insertAll(chunks: List<OnDeviceChunkEntity>) { this.chunks.addAll(chunks) }
    override suspend fun getAllChunks(userId: String) = chunks.filter { it.userId == userId }
    override suspend fun getChunksForDocument(userId: String, documentId: String) =
        chunks.filter { it.userId == userId && it.documentId == documentId }
    override suspend fun deleteByDocument(userId: String, documentId: String) {
        chunks.removeAll { it.userId == userId && it.documentId == documentId }
    }
    override suspend fun countChunks(userId: String) = chunks.count { it.userId == userId }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private const val U1 = "user-1"

private fun chunk(
    chunkId: String = "c1",
    documentId: String = "doc-1",
    userId: String = U1,
    text: String = "hello world",
    pageNumber: Int? = 1,
    chunkIndex: Int = 0,
) = VectorChunk(
    chunkId = chunkId,
    documentId = documentId,
    documentName = "TestDoc",
    userId = userId,
    chunkIndex = chunkIndex,
    text = text,
    pageNumber = pageNumber,
    charStart = 0,
    charEnd = text.length,
)

private fun embedding(dim: Int = 4) = EmbeddingVector(values = List(dim) { 0.5f })

private fun entity(
    id: String = "c1",
    userId: String = U1,
    documentId: String = "doc-1",
) = OnDeviceChunkEntity(
    id = id,
    userId = userId,
    documentId = documentId,
    documentName = "Doc",
    chunkIndex = 0,
    pageNumber = null,
    startCharOffset = 0,
    endCharOffset = 5,
    content = "text",
    embeddingBlob = FloatArray(4) { 0.5f },
    createdAt = 0L,
)

// ── Tests ─────────────────────────────────────────────────────────────────────

class LocalVectorStoreAdapterTest {

    private fun adapter(
        index: FakeLocalVectorIndex = FakeLocalVectorIndex(),
        dao: FakeOnDeviceChunkDao = FakeOnDeviceChunkDao(),
    ) = LocalVectorStoreAdapter(index = index, dao = dao)

    // ── Interface contract ────────────────────────────────────────────────────

    @Test fun `implements VectorStore interface`() {
        val vs: VectorStore = adapter()
        vs shouldNotBe null
    }

    // ── upsert ────────────────────────────────────────────────────────────────

    @Test fun `upsert delegates to LocalVectorIndex addChunk`() = runTest {
        val index = FakeLocalVectorIndex()
        val a = adapter(index = index)
        a.upsert(chunk(), embedding())
        index.added shouldHaveSize 1
    }

    @Test fun `upsert passes correct userId`() = runTest {
        val index = FakeLocalVectorIndex()
        val a = adapter(index = index)
        a.upsert(chunk(userId = "alice"), embedding())
        index.added.first().first shouldBe "alice"
    }

    @Test fun `upsert passes correct chunkId`() = runTest {
        val index = FakeLocalVectorIndex()
        val a = adapter(index = index)
        a.upsert(chunk(chunkId = "my-chunk"), embedding())
        index.added.first().second.id shouldBe "my-chunk"
    }

    @Test fun `upsert passes correct text content`() = runTest {
        val index = FakeLocalVectorIndex()
        val a = adapter(index = index)
        a.upsert(chunk(text = "the quick brown fox"), embedding())
        index.added.first().second.content shouldBe "the quick brown fox"
    }

    @Test fun `upsert passes pageNumber`() = runTest {
        val index = FakeLocalVectorIndex()
        val a = adapter(index = index)
        a.upsert(chunk(pageNumber = 5), embedding())
        index.added.first().second.pageNumber shouldBe 5
    }

    @Test fun `upsert null pageNumber preserved`() = runTest {
        val index = FakeLocalVectorIndex()
        val a = adapter(index = index)
        a.upsert(chunk(pageNumber = null), embedding())
        (index.added.first().second.pageNumber == null).shouldBeTrue()
    }

    @Test fun `upsert wraps exception in VectorStoreException`() = runTest {
        val index = FakeLocalVectorIndex().also { it.throwOnAdd = true }
        val a = adapter(index = index)
        assertThrows<VectorStoreException> {
            a.upsert(chunk(), embedding())
        }
    }

    @Test fun `upsert converts embedding to FloatArray`() = runTest {
        val index = FakeLocalVectorIndex()
        val a = adapter(index = index)
        val ev = EmbeddingVector(values = listOf(0.1f, 0.2f, 0.3f))
        a.upsert(chunk(), ev)
        // If no exception was thrown the FloatArray conversion worked
        index.added shouldHaveSize 1
    }

    // ── search ────────────────────────────────────────────────────────────────

    @Test fun `search returns empty list when index has no results`() = runTest {
        adapter().search(U1, embedding()).shouldBeEmpty()
    }

    @Test fun `search returns VectorSearchResult list`() = runTest {
        val index = FakeLocalVectorIndex()
        index.stubSearchResult(ChunkSearchResult("c1", "doc-1", "hello", 0.9f))
        val results = adapter(index = index).search(U1, embedding())
        results shouldHaveSize 1
        (results[0] is VectorSearchResult).shouldBeTrue()
    }

    @Test fun `search maps cosineSimilarity to VectorSearchResult similarity`() = runTest {
        val index = FakeLocalVectorIndex()
        index.stubSearchResult(ChunkSearchResult("c1", "doc-1", "text", 0.75f))
        val results = adapter(index = index).search(U1, embedding())
        results[0].similarity shouldBe 0.75f
    }

    @Test fun `search maps content to VectorChunk text`() = runTest {
        val index = FakeLocalVectorIndex()
        index.stubSearchResult(ChunkSearchResult("c1", "doc-1", "chunk text", 0.8f))
        val results = adapter(index = index).search(U1, embedding())
        results[0].chunk.text shouldBe "chunk text"
    }

    @Test fun `search maps id to VectorChunk chunkId`() = runTest {
        val index = FakeLocalVectorIndex()
        index.stubSearchResult(ChunkSearchResult("my-chunk-id", "doc-1", "t", 0.9f))
        val results = adapter(index = index).search(U1, embedding())
        results[0].chunk.chunkId shouldBe "my-chunk-id"
    }

    @Test fun `search retrieval path is local_ann`() = runTest {
        val index = FakeLocalVectorIndex()
        index.stubSearchResult(ChunkSearchResult("c1", "doc-1", "t", 0.9f))
        val results = adapter(index = index).search(U1, embedding())
        results[0].retrievalPath shouldBe "local_ann"
    }

    @Test fun `search passes topK to index`() = runTest {
        val index = FakeLocalVectorIndex()
        repeat(5) { i -> index.stubSearchResult(ChunkSearchResult("c$i", "d", "t", 0.9f)) }
        val results = adapter(index = index).search(U1, embedding(), topK = 3)
        results shouldHaveSize 3
    }

    @Test fun `search passes minSimilarity to index`() = runTest {
        val index = FakeLocalVectorIndex()
        index.stubSearchResult(ChunkSearchResult("c1", "d", "t", 0.3f)) // below 0.5
        index.stubSearchResult(ChunkSearchResult("c2", "d", "t", 0.8f)) // above 0.5
        val results = adapter(index = index).search(U1, embedding(), minSimilarity = 0.5f)
        results shouldHaveSize 1
        results[0].chunk.chunkId shouldBe "c2"
    }

    @Test fun `search clamps similarity to 0-1`() = runTest {
        val index = FakeLocalVectorIndex()
        // Fake can return > 1.0 from dot product; adapter must clamp
        index.stubSearchResult(ChunkSearchResult("c1", "d", "t", 1.2f))
        val results = adapter(index = index).search(U1, embedding(), minSimilarity = 0f)
        results[0].similarity shouldBe 1.0f
    }

    @Test fun `search wraps exception in VectorStoreException`() = runTest {
        val index = FakeLocalVectorIndex().also { it.throwOnSearch = true }
        assertThrows<VectorStoreException> {
            adapter(index = index).search(U1, embedding())
        }
    }

    // ── deleteByDocument ──────────────────────────────────────────────────────

    @Test fun `deleteByDocument delegates to LocalVectorIndex`() = runTest {
        val index = FakeLocalVectorIndex()
        adapter(index = index).deleteByDocument(U1, "doc-99")
        index.deleted shouldHaveSize 1
        index.deleted[0] shouldBe (U1 to "doc-99")
    }

    @Test fun `deleteByDocument wraps exception in VectorStoreException`() = runTest {
        val index = object : FakeLocalVectorIndex() {
            override suspend fun deleteByDocument(userId: String, documentId: String) =
                throw RuntimeException("io error")
        }
        assertThrows<VectorStoreException> {
            adapter(index = index).deleteByDocument(U1, "doc-1")
        }
    }

    // ── deleteAll ─────────────────────────────────────────────────────────────

    @Test fun `deleteAll calls deleteByDocument for each distinct document`() = runTest {
        val index = FakeLocalVectorIndex()
        val dao = FakeOnDeviceChunkDao().also {
            it.addEntity(entity(id = "c1", userId = U1, documentId = "doc-A"))
            it.addEntity(entity(id = "c2", userId = U1, documentId = "doc-A"))
            it.addEntity(entity(id = "c3", userId = U1, documentId = "doc-B"))
        }
        adapter(index = index, dao = dao).deleteAll(U1)
        // Two distinct documents → two deleteByDocument calls
        index.deleted shouldHaveSize 2
        index.deleted.map { it.second }.toSet() shouldBe setOf("doc-A", "doc-B")
    }

    @Test fun `deleteAll no-ops when user has no chunks`() = runTest {
        val index = FakeLocalVectorIndex()
        adapter(index = index).deleteAll("nobody")
        index.deleted.shouldBeEmpty()
    }

    @Test fun `deleteAll does not delete other users chunks`() = runTest {
        val index = FakeLocalVectorIndex()
        val dao = FakeOnDeviceChunkDao().also {
            it.addEntity(entity(id = "c1", userId = U1, documentId = "doc-1"))
            it.addEntity(entity(id = "c2", userId = "other", documentId = "doc-2"))
        }
        adapter(index = index, dao = dao).deleteAll(U1)
        // Only doc-1 deleted; doc-2 belongs to "other"
        index.deleted.map { it.second } shouldBe listOf("doc-1")
    }

    // ── count ─────────────────────────────────────────────────────────────────

    @Test fun `count returns zero for empty store`() = runTest {
        adapter().count(U1) shouldBe 0
    }

    @Test fun `count returns correct number`() = runTest {
        val dao = FakeOnDeviceChunkDao().also {
            it.addEntity(entity(id = "c1", userId = U1))
            it.addEntity(entity(id = "c2", userId = U1))
            it.addEntity(entity(id = "c3", userId = "other"))
        }
        adapter(dao = dao).count(U1) shouldBe 2
    }

    @Test fun `count wraps exception in VectorStoreException`() = runTest {
        val dao = object : FakeOnDeviceChunkDao() {
            override suspend fun countChunks(userId: String) = throw RuntimeException("db error")
        }
        assertThrows<VectorStoreException> {
            adapter(dao = dao).count(U1)
        }
    }
}
