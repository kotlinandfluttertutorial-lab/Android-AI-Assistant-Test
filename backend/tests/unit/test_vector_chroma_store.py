"""Unit tests for app.vector.chroma_store — ChromaConfig and ChromaVectorStore.

ChromaDB is ALWAYS mocked — no real ChromaDB server or SQLite file is
created.  The tests verify that:
  - the correct ChromaDB API calls are made with the right arguments
  - per-user collection isolation is enforced
  - metadata round-trips correctly through upsert → search
  - delete_by_document uses the right WHERE filter
  - delete_all drops the collection
  - count delegates to collection.count()
  - distance → similarity conversion is correct
  - retry logic fires on transient failures

No production credentials required.
"""

from __future__ import annotations

from unittest.mock import MagicMock, call, patch

import pytest

from app.interfaces.core import DocumentChunk, EmbeddingVector, RetrievedChunk, StoredChunk
from app.vector.chroma_store import (
    ChromaConfig,
    ChromaVectorStore,
    _collection_name,
    _distance_to_similarity,
    _chunk_to_metadata,
    _metadata_to_chunk,
    _NO_PAGE,
)


# ── Fixtures ──────────────────────────────────────────────────────────────────

def _chunk(
    chunk_id: str = "c1",
    document_id: str = "doc-1",
    user_id: str = "u1",
    chunk_index: int = 0,
    text: str = "Hello world",
    page_number: int | None = 1,
    char_start: int = 0,
    char_end: int = 11,
) -> DocumentChunk:
    return DocumentChunk(
        chunk_id=chunk_id,
        document_id=document_id,
        document_name="TestDoc",
        user_id=user_id,
        chunk_index=chunk_index,
        text=text,
        page_number=page_number,
        char_start=char_start,
        char_end=char_end,
    )


def _embedding(dim: int = 4, val: float = 0.5) -> EmbeddingVector:
    return EmbeddingVector(values=[val] * dim, model_name="test-model")


def _stored(chunk: DocumentChunk | None = None, dim: int = 4) -> StoredChunk:
    return StoredChunk(chunk=chunk or _chunk(), embedding=_embedding(dim))


def _make_store_with_mock_client() -> tuple[ChromaVectorStore, MagicMock, MagicMock]:
    """Return (store, mock_client, mock_collection)."""
    store = ChromaVectorStore(ChromaConfig(mode="persistent"))
    mock_collection = MagicMock()
    mock_collection.count.return_value = 0
    mock_client = MagicMock()
    mock_client.get_or_create_collection.return_value = mock_collection
    store._client = mock_client
    return store, mock_client, mock_collection


# ── ChromaConfig ──────────────────────────────────────────────────────────────

class TestChromaConfig:
    def test_default_mode_is_persistent(self):
        assert ChromaConfig().mode == "persistent"

    def test_invalid_mode_raises(self):
        with pytest.raises(ValueError, match="mode"):
            ChromaConfig(mode="invalid")

    def test_http_mode_accepted(self):
        cfg = ChromaConfig(mode="http")
        assert cfg.mode == "http"

    def test_default_host(self):
        assert ChromaConfig().host == "chromadb"

    def test_default_port(self):
        assert ChromaConfig().port == 8000

    def test_custom_persist_dir(self):
        cfg = ChromaConfig(persist_dir="/tmp/test_chroma")
        assert cfg.persist_dir == "/tmp/test_chroma"

    def test_max_retries_default(self):
        assert ChromaConfig().max_retries == 3


# ── Module helpers ────────────────────────────────────────────────────────────

class TestModuleHelpers:
    def test_collection_name_format(self):
        assert _collection_name("user-42") == "docs_user-42"

    def test_collection_name_unique_per_user(self):
        assert _collection_name("alice") != _collection_name("bob")

    def test_distance_to_similarity_zero_distance(self):
        assert _distance_to_similarity(0.0) == pytest.approx(1.0)

    def test_distance_to_similarity_max_distance(self):
        # cosine distance = 1 → similarity = 0
        assert _distance_to_similarity(1.0) == pytest.approx(0.0)

    def test_distance_to_similarity_mid(self):
        assert _distance_to_similarity(0.5) == pytest.approx(0.5)

    def test_distance_to_similarity_clamped_below_zero(self):
        assert _distance_to_similarity(1.5) == pytest.approx(0.0)

    def test_distance_to_similarity_clamped_above_one(self):
        assert _distance_to_similarity(-0.1) == pytest.approx(1.0)

    def test_chunk_to_metadata_all_fields(self):
        c = _chunk(page_number=3, char_start=10, char_end=50)
        meta = _chunk_to_metadata(c)
        assert meta["document_id"] == "doc-1"
        assert meta["user_id"] == "u1"
        assert meta["chunk_index"] == 0
        assert meta["page_number"] == 3
        assert meta["char_start"] == 10
        assert meta["char_end"] == 50

    def test_chunk_to_metadata_none_page_number(self):
        c = _chunk(page_number=None)
        meta = _chunk_to_metadata(c)
        assert meta["page_number"] == _NO_PAGE

    def test_metadata_to_chunk_round_trip(self):
        c = _chunk(page_number=2, char_start=5, char_end=20)
        meta = _chunk_to_metadata(c)
        rebuilt = _metadata_to_chunk("c1", meta, c.text)
        assert rebuilt.chunk_id == "c1"
        assert rebuilt.document_id == c.document_id
        assert rebuilt.page_number == 2
        assert rebuilt.char_start == 5
        assert rebuilt.char_end == 20
        assert rebuilt.text == c.text

    def test_metadata_to_chunk_null_page_number_restored(self):
        c = _chunk(page_number=None)
        meta = _chunk_to_metadata(c)
        rebuilt = _metadata_to_chunk("c1", meta, c.text)
        assert rebuilt.page_number is None


# ── ChromaVectorStore — client initialisation ─────────────────────────────────

class TestClientInit:
    def test_client_not_created_at_construction(self):
        store = ChromaVectorStore(ChromaConfig())
        assert store._client is None

    def test_persistent_client_created_lazily(self):
        store = ChromaVectorStore(ChromaConfig(persist_dir="/tmp/test_cp"))
        mock_client = MagicMock()
        with patch("chromadb.PersistentClient", return_value=mock_client) as mock_cls:
            client = store._get_client()
            mock_cls.assert_called_once_with(path="/tmp/test_cp")
        assert client is mock_client

    def test_http_client_created_when_mode_is_http(self):
        store = ChromaVectorStore(ChromaConfig(mode="http", host="myhost", port=9000))
        mock_client = MagicMock()
        with patch("chromadb.HttpClient", return_value=mock_client) as mock_cls:
            client = store._get_client()
            mock_cls.assert_called_once_with(host="myhost", port=9000, ssl=False)
        assert client is mock_client

    def test_client_reused_on_second_call(self):
        store, mock_client, _ = _make_store_with_mock_client()
        c1 = store._get_client()
        c2 = store._get_client()
        assert c1 is c2


# ── upsert ─────────────────────────────────────────────────────────────────────

class TestUpsert:
    @pytest.mark.asyncio
    async def test_upsert_calls_collection_upsert(self):
        store, mock_client, mock_collection = _make_store_with_mock_client()
        sc = _stored()
        await store.upsert(sc)
        mock_collection.upsert.assert_called_once()

    @pytest.mark.asyncio
    async def test_upsert_uses_correct_collection(self):
        store, mock_client, mock_collection = _make_store_with_mock_client()
        sc = _stored(_chunk(user_id="alice"))
        await store.upsert(sc)
        mock_client.get_or_create_collection.assert_called_once_with(
            name="docs_alice",
            metadata={"hnsw:space": "cosine"},
        )

    @pytest.mark.asyncio
    async def test_upsert_passes_chunk_id_as_id(self):
        store, _, mock_collection = _make_store_with_mock_client()
        sc = _stored(_chunk(chunk_id="my-chunk-id"))
        await store.upsert(sc)
        call_kwargs = mock_collection.upsert.call_args.kwargs
        assert call_kwargs["ids"] == ["my-chunk-id"]

    @pytest.mark.asyncio
    async def test_upsert_passes_embedding_values(self):
        store, _, mock_collection = _make_store_with_mock_client()
        ev = EmbeddingVector(values=[0.1, 0.2, 0.3, 0.4])
        sc = StoredChunk(chunk=_chunk(), embedding=ev)
        await store.upsert(sc)
        call_kwargs = mock_collection.upsert.call_args.kwargs
        assert call_kwargs["embeddings"] == [[0.1, 0.2, 0.3, 0.4]]

    @pytest.mark.asyncio
    async def test_upsert_passes_text_as_document(self):
        store, _, mock_collection = _make_store_with_mock_client()
        sc = _stored(_chunk(text="test content"))
        await store.upsert(sc)
        call_kwargs = mock_collection.upsert.call_args.kwargs
        assert call_kwargs["documents"] == ["test content"]

    @pytest.mark.asyncio
    async def test_upsert_passes_metadata(self):
        store, _, mock_collection = _make_store_with_mock_client()
        sc = _stored(_chunk(document_id="doc-42", page_number=7))
        await store.upsert(sc)
        call_kwargs = mock_collection.upsert.call_args.kwargs
        meta = call_kwargs["metadatas"][0]
        assert meta["document_id"] == "doc-42"
        assert meta["page_number"] == 7


# ── search ────────────────────────────────────────────────────────────────────

def _make_query_result(
    chunk_id: str = "c1",
    distance: float = 0.2,
    document_id: str = "doc-1",
    user_id: str = "u1",
    text: str = "content",
    page_number: int = 1,
) -> dict:
    meta = {
        "document_id": document_id,
        "document_name": "Doc",
        "user_id": user_id,
        "chunk_index": 0,
        "page_number": page_number,
        "char_start": 0,
        "char_end": len(text),
    }
    return {
        "ids": [[chunk_id]],
        "documents": [[text]],
        "metadatas": [[meta]],
        "distances": [[distance]],
    }


class TestSearch:
    @pytest.mark.asyncio
    async def test_search_returns_empty_when_collection_empty(self):
        store, _, mock_collection = _make_store_with_mock_client()
        mock_collection.count.return_value = 0
        results = await store.search("u1", _embedding())
        assert results == []

    @pytest.mark.asyncio
    async def test_search_returns_retrieved_chunks(self):
        store, _, mock_collection = _make_store_with_mock_client()
        mock_collection.count.return_value = 1
        mock_collection.query.return_value = _make_query_result()
        results = await store.search("u1", _embedding(), top_k=5)
        assert len(results) == 1
        assert isinstance(results[0], RetrievedChunk)

    @pytest.mark.asyncio
    async def test_search_similarity_computed_from_distance(self):
        store, _, mock_collection = _make_store_with_mock_client()
        mock_collection.count.return_value = 1
        mock_collection.query.return_value = _make_query_result(distance=0.3)
        results = await store.search("u1", _embedding())
        assert results[0].similarity == pytest.approx(0.7, abs=1e-5)

    @pytest.mark.asyncio
    async def test_search_filters_below_min_similarity(self):
        store, _, mock_collection = _make_store_with_mock_client()
        mock_collection.count.return_value = 1
        # distance=0.9 → similarity=0.1 which is below min_similarity=0.5
        mock_collection.query.return_value = _make_query_result(distance=0.9)
        results = await store.search("u1", _embedding(), min_similarity=0.5)
        assert results == []

    @pytest.mark.asyncio
    async def test_search_passes_query_embedding(self):
        store, _, mock_collection = _make_store_with_mock_client()
        mock_collection.count.return_value = 1
        mock_collection.query.return_value = _make_query_result()
        ev = EmbeddingVector(values=[0.1, 0.2, 0.3])
        await store.search("u1", ev, top_k=3)
        call_kwargs = mock_collection.query.call_args.kwargs
        assert call_kwargs["query_embeddings"] == [[0.1, 0.2, 0.3]]
        assert call_kwargs["n_results"] == 1  # min(top_k=3, count=1)

    @pytest.mark.asyncio
    async def test_search_result_metadata_round_trips(self):
        store, _, mock_collection = _make_store_with_mock_client()
        mock_collection.count.return_value = 1
        mock_collection.query.return_value = _make_query_result(
            chunk_id="cX", document_id="dX", user_id="u1", page_number=5
        )
        results = await store.search("u1", _embedding())
        dc = results[0].chunk
        assert dc.chunk_id == "cX"
        assert dc.document_id == "dX"
        assert dc.page_number == 5

    @pytest.mark.asyncio
    async def test_search_retrieval_path_is_chroma_ann(self):
        store, _, mock_collection = _make_store_with_mock_client()
        mock_collection.count.return_value = 1
        mock_collection.query.return_value = _make_query_result()
        results = await store.search("u1", _embedding())
        assert results[0].retrieval_path == "chroma_ann"

    @pytest.mark.asyncio
    async def test_search_isolates_by_user_collection(self):
        store, mock_client, mock_collection = _make_store_with_mock_client()
        mock_collection.count.return_value = 0
        await store.search("bob", _embedding())
        mock_client.get_or_create_collection.assert_called_with(
            name="docs_bob",
            metadata={"hnsw:space": "cosine"},
        )


# ── delete_by_document ────────────────────────────────────────────────────────

class TestDeleteByDocument:
    @pytest.mark.asyncio
    async def test_delete_by_document_calls_collection_delete(self):
        store, _, mock_collection = _make_store_with_mock_client()
        await store.delete_by_document("u1", "doc-99")
        mock_collection.delete.assert_called_once()

    @pytest.mark.asyncio
    async def test_delete_by_document_uses_where_filter(self):
        store, _, mock_collection = _make_store_with_mock_client()
        await store.delete_by_document("u1", "target-doc")
        call_kwargs = mock_collection.delete.call_args.kwargs
        assert call_kwargs["where"] == {"document_id": {"$eq": "target-doc"}}

    @pytest.mark.asyncio
    async def test_delete_by_document_correct_collection(self):
        store, mock_client, mock_collection = _make_store_with_mock_client()
        await store.delete_by_document("charlie", "doc-1")
        mock_client.get_or_create_collection.assert_called_with(
            name="docs_charlie",
            metadata={"hnsw:space": "cosine"},
        )


# ── delete_all ────────────────────────────────────────────────────────────────

class TestDeleteAll:
    @pytest.mark.asyncio
    async def test_delete_all_drops_collection(self):
        store, mock_client, _ = _make_store_with_mock_client()
        await store.delete_all("u1")
        mock_client.delete_collection.assert_called_once_with(name="docs_u1")

    @pytest.mark.asyncio
    async def test_delete_all_silent_when_collection_absent(self):
        store, mock_client, _ = _make_store_with_mock_client()
        mock_client.delete_collection.side_effect = Exception("not found")
        # Must not raise
        await store.delete_all("u1")


# ── count ─────────────────────────────────────────────────────────────────────

class TestCount:
    @pytest.mark.asyncio
    async def test_count_delegates_to_collection_count(self):
        store, _, mock_collection = _make_store_with_mock_client()
        mock_collection.count.return_value = 42
        result = await store.count("u1")
        assert result == 42

    @pytest.mark.asyncio
    async def test_count_zero_when_empty(self):
        store, _, mock_collection = _make_store_with_mock_client()
        mock_collection.count.return_value = 0
        assert await store.count("u1") == 0


# ── retry logic ───────────────────────────────────────────────────────────────

class TestRetryLogic:
    @pytest.mark.asyncio
    async def test_upsert_retries_on_transient_failure(self):
        store, _, mock_collection = _make_store_with_mock_client()
        call_count = 0

        def _flaky_upsert(**kwargs):
            nonlocal call_count
            call_count += 1
            if call_count < 3:
                raise RuntimeError("transient")

        mock_collection.upsert.side_effect = _flaky_upsert
        store._config.retry_base_delay_s = 0.0  # no sleep in tests

        await store.upsert(_stored())
        assert call_count == 3

    @pytest.mark.asyncio
    async def test_upsert_raises_after_max_retries(self):
        store, _, mock_collection = _make_store_with_mock_client()
        mock_collection.upsert.side_effect = RuntimeError("permanent failure")
        store._config.retry_base_delay_s = 0.0
        store._config.max_retries = 2

        with pytest.raises(RuntimeError, match="permanent failure"):
            await store.upsert(_stored())

        assert mock_collection.upsert.call_count == 2


# ── IVectorStore contract ─────────────────────────────────────────────────────

class TestIVectorStoreContract:
    def test_implements_interface(self):
        from app.interfaces.core import IVectorStore
        store = ChromaVectorStore()
        assert isinstance(store, IVectorStore)
