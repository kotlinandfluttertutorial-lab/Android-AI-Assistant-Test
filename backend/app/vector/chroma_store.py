# ============================================================
# Android AI Assistant — Backend
# Module  : vector
# File    : chroma_store.py
# Purpose : ChromaVectorStore — concrete IVectorStore backed by ChromaDB.
#
# Implements app.interfaces.core.IVectorStore.
#
# Architecture:
#   Business logic (agents, RAG pipeline, document ingestion) depends
#   ONLY on IVectorStore.  ChromaVectorStore is an infrastructure
#   adapter — it may be replaced or mocked without touching any caller.
#
# ChromaDB interaction:
#   - Per-user collections: "docs_{user_id}" — matches the isolation
#     contract in IVectorStore (cross-user data leakage impossible).
#   - PersistentClient (embedded) for dev/test; HttpClient for prod.
#   - Synchronous ChromaDB SDK calls are wrapped in asyncio.to_thread().
#   - Retry logic re-uses the exponential-backoff pattern from RAGService.
#
# Design rules:
#   - chromadb is lazy-imported; module importable without it.
#   - No SQLAlchemy, no Redis, no HTTP calls outside ChromaDB SDK.
#   - All metadata stored per-chunk for accurate citation reconstruction.
# ============================================================
"""ChromaVectorStore — ChromaDB-backed IVectorStore implementation."""

from __future__ import annotations

import asyncio
import logging
import os
import tempfile
import time
from dataclasses import dataclass, field
from typing import Any

from app.interfaces.core import (
    DocumentChunk,
    EmbeddingVector,
    IVectorStore,
    RetrievedChunk,
    StoredChunk,
)

logger = logging.getLogger(__name__)

# Collection name prefix — each user gets their own isolated collection.
_COLLECTION_PREFIX = "docs_"

# Metadata key names stored alongside each ChromaDB vector.
_META_DOCUMENT_ID = "document_id"
_META_DOCUMENT_NAME = "document_name"
_META_USER_ID = "user_id"
_META_CHUNK_INDEX = "chunk_index"
_META_PAGE_NUMBER = "page_number"  # stored as int; -1 means None
_META_CHAR_START = "char_start"
_META_CHAR_END = "char_end"
_META_TEXT = "text"  # stored in ChromaDB documents field

_NO_PAGE = -1  # sentinel for NULL page_number


@dataclass
class ChromaConfig:
    """Connection and behaviour configuration for :class:`ChromaVectorStore`.

    Attributes:
        mode:         ``"persistent"`` (embedded SQLite, good for dev/tests)
                      or ``"http"`` (remote ChromaDB server).
        persist_dir:  Directory for the embedded client.  Defaults to
                      ``$TMPDIR/chroma``.  Ignored when ``mode="http"``.
        host:         Hostname for HTTP mode.  Defaults to ``"chromadb"``.
        port:         Port for HTTP mode.  Defaults to ``8000``.
        ssl:          Whether to use HTTPS for HTTP mode.
        max_retries:  Number of ChromaDB call attempts before giving up.
        retry_base_delay_s: Base sleep interval for exponential backoff.
    """

    mode: str = "persistent"  # "persistent" | "http"
    persist_dir: str = field(default_factory=lambda: os.path.join(tempfile.gettempdir(), "chroma"))
    host: str = "chromadb"
    port: int = 8000
    ssl: bool = False
    max_retries: int = 3
    retry_base_delay_s: float = 1.0

    def __post_init__(self) -> None:
        if self.mode not in ("persistent", "http"):
            raise ValueError(
                f"ChromaConfig.mode must be 'persistent' or 'http', got {self.mode!r}."
            )

    @classmethod
    def from_settings(cls) -> ChromaConfig:
        """Build from the application Settings singleton.

        Falls back gracefully when Settings cannot be imported (e.g. in
        test environments without all env-vars set).
        """
        try:
            from app.config.settings import get_settings

            s = get_settings()
            persist_dir = getattr(
                s,
                "CHROMA_PERSIST_DIR",
                os.path.join(tempfile.gettempdir(), "chroma"),
            )
            mode = "http" if getattr(s, "CHROMA_USE_HTTP", False) else "persistent"
            return cls(
                mode=mode,
                persist_dir=persist_dir,
                host=getattr(s, "CHROMA_HOST", "chromadb"),
                port=getattr(s, "CHROMA_PORT", 8000),
                ssl=getattr(s, "CHROMA_SSL", False),
            )
        except Exception:
            return cls()


class ChromaVectorStore(IVectorStore):
    """ChromaDB-backed :class:`~app.interfaces.core.IVectorStore`.

    Each user's document chunks are stored in an isolated ChromaDB
    collection named ``docs_{user_id}``.  Operations are atomic within
    a single collection call; cross-user isolation is enforced by the
    collection naming scheme.

    Thread safety:
        The ChromaDB SDK is not async-native. All calls are executed in
        ``asyncio.to_thread()`` so the FastAPI event loop is never blocked.
        A shared ``_client`` object is lazily created and reused across
        calls; the ChromaDB Python client is documented as thread-safe for
        concurrent reads and serialised writes.

    Mocking in tests::

        from unittest.mock import MagicMock, AsyncMock, patch
        mock_collection = MagicMock()
        mock_collection.add = MagicMock()
        mock_collection.query = MagicMock(return_value={"ids": [[]], ...})
        store = ChromaVectorStore.__new__(ChromaVectorStore)
        store._client = MagicMock()
        store._client.get_or_create_collection.return_value = mock_collection
    """

    def __init__(self, config: ChromaConfig | None = None) -> None:
        self._config = config or ChromaConfig()
        self._client = None  # lazy-initialised

    # ── IVectorStore ──────────────────────────────────────────────────────────

    async def upsert(self, chunk: StoredChunk) -> None:
        """Store or update a chunk + embedding in ChromaDB.

        Upsert semantics: if a document with the same ``chunk_id`` already
        exists in the user's collection it is replaced.

        Args:
            chunk: The chunk and its dense embedding.
        """

        def _do() -> None:
            collection = self._get_collection(chunk.chunk.user_id)
            metadata = _chunk_to_metadata(chunk.chunk)
            self._with_retry(
                "upsert",
                lambda: collection.upsert(
                    ids=[chunk.chunk.chunk_id],
                    embeddings=[chunk.embedding.values],
                    documents=[chunk.chunk.text],
                    metadatas=[metadata],
                ),
            )

        await asyncio.to_thread(_do)

    async def search(
        self,
        user_id: str,
        query_embedding: EmbeddingVector,
        top_k: int = 5,
        min_similarity: float = 0.0,
    ) -> list[RetrievedChunk]:
        """Return the *top_k* chunks most similar to *query_embedding*.

        ChromaDB returns distances (L2 by default); we convert to cosine
        similarity using the collection's configured distance metric.
        When the collection uses cosine distance, ChromaDB returns values
        in [0, 2]: similarity = 1 - distance/2.
        When using L2, we approximate: similarity = 1 / (1 + distance).

        Args:
            user_id:         Restrict search to this user's collection.
            query_embedding: Dense query vector.
            top_k:           Maximum results to return.
            min_similarity:  Minimum cosine-similarity threshold.

        Returns:
            List of :class:`~app.interfaces.core.RetrievedChunk` ordered
            by similarity descending.
        """

        def _do() -> list[dict[str, Any]]:
            collection = self._get_collection(user_id)
            n = collection.count()
            if n == 0:
                return []
            effective_k = min(top_k, n)
            return self._with_retry(
                "query",
                lambda: collection.query(
                    query_embeddings=[query_embedding.values],
                    n_results=effective_k,
                    include=["documents", "metadatas", "distances"],
                ),
            )

        raw = await asyncio.to_thread(_do)
        if not raw:
            return []

        results: list[RetrievedChunk] = []
        ids = raw.get("ids", [[]])[0]
        metadatas = raw.get("metadatas", [[]])[0]
        distances = raw.get("distances", [[]])[0]
        documents = raw.get("documents", [[]])[0]

        for chunk_id, meta, dist, doc_text in zip(
            ids, metadatas, distances, documents, strict=True
        ):
            similarity = _distance_to_similarity(dist)
            if similarity < min_similarity:
                continue
            dc = _metadata_to_chunk(chunk_id, meta, doc_text)
            results.append(
                RetrievedChunk(chunk=dc, similarity=similarity, retrieval_path="chroma_ann")
            )

        return results

    async def delete_by_document(self, user_id: str, document_id: str) -> None:
        """Delete all chunks belonging to *document_id* for *user_id*.

        Uses ChromaDB's ``where`` filter so only this document's chunks
        are removed — other documents in the same user collection are
        untouched.

        Args:
            user_id:     Collection owner.
            document_id: Document whose chunks should be removed.
        """

        def _do() -> None:
            collection = self._get_collection(user_id)
            self._with_retry(
                "delete_by_document",
                lambda: collection.delete(where={_META_DOCUMENT_ID: {"$eq": document_id}}),
            )

        await asyncio.to_thread(_do)

    async def delete_all(self, user_id: str) -> None:
        """Delete all chunks for *user_id* by dropping their collection.

        Dropping and recreating the collection is more efficient than
        fetching all IDs and deleting them one by one.

        Args:
            user_id: Owner whose collection should be purged.
        """

        def _do() -> None:
            client = self._get_client()
            collection_name = _collection_name(user_id)
            try:
                self._with_retry(
                    "delete_collection",
                    lambda: client.delete_collection(name=collection_name),
                )
                logger.info("Deleted ChromaDB collection %r", collection_name)
            except Exception as exc:
                # Collection may not exist; silently ignore that case.
                logger.debug("delete_all: delete_collection failed (may not exist): %s", exc)

        await asyncio.to_thread(_do)

    async def count(self, user_id: str) -> int:
        """Return the number of stored chunks for *user_id*.

        Args:
            user_id: Owner to count chunks for.

        Returns:
            Non-negative chunk count.
        """

        def _do() -> int:
            collection = self._get_collection(user_id)
            return collection.count()

        return await asyncio.to_thread(_do)

    # ── Private helpers ───────────────────────────────────────────────────────

    def _get_client(self):
        """Return the lazily-created ChromaDB client."""
        if self._client is not None:
            return self._client

        import chromadb  # lazy import

        cfg = self._config
        if cfg.mode == "http":
            logger.info(
                "Creating ChromaDB HttpClient host=%r port=%d ssl=%s",
                cfg.host,
                cfg.port,
                cfg.ssl,
            )
            self._client = chromadb.HttpClient(
                host=cfg.host,
                port=cfg.port,
                ssl=cfg.ssl,
            )
        else:
            os.makedirs(cfg.persist_dir, exist_ok=True)
            logger.info(
                "Creating ChromaDB PersistentClient persist_dir=%r",
                cfg.persist_dir,
            )
            self._client = chromadb.PersistentClient(path=cfg.persist_dir)

        return self._client

    def _get_collection(self, user_id: str):
        """Return (or create) the ChromaDB collection for *user_id*."""
        client = self._get_client()
        name = _collection_name(user_id)
        # cosine distance matches the L2-normalised embeddings from
        # SentenceTransformer(normalize_embeddings=True)
        return client.get_or_create_collection(
            name=name,
            metadata={"hnsw:space": "cosine"},
        )

    def _with_retry(self, op_name: str, fn):
        """Call *fn()* with exponential backoff on failure.

        Mirrors RAGService._chroma_op_with_retry() but lives here so it
        can be used independently of RAGService.
        """
        delay = self._config.retry_base_delay_s
        last_exc: Exception | None = None
        for attempt in range(1, self._config.max_retries + 1):
            try:
                return fn()
            except Exception as exc:
                logger.warning(
                    "ChromaDB %s attempt %d/%d failed: %s",
                    op_name,
                    attempt,
                    self._config.max_retries,
                    exc,
                )
                if attempt < self._config.max_retries:
                    time.sleep(delay)
                    delay = min(delay * 2, 5.0)
                    last_exc = exc
                    continue
                raise
        raise last_exc  # type: ignore[misc]


# ---------------------------------------------------------------------------
# Module-level helpers
# ---------------------------------------------------------------------------


def _collection_name(user_id: str) -> str:
    """Return the ChromaDB collection name for *user_id*.

    Format: ``docs_{user_id}`` — mirrors ``memories_{user_id}`` in
    ``memory_repository.py``.
    """
    return f"{_COLLECTION_PREFIX}{user_id}"


def _chunk_to_metadata(chunk: DocumentChunk) -> dict[str, Any]:
    """Serialise a :class:`~app.interfaces.core.DocumentChunk` to a
    ChromaDB metadata dict (all values must be str, int, float, or bool).
    """
    return {
        _META_DOCUMENT_ID: chunk.document_id,
        _META_DOCUMENT_NAME: chunk.document_name,
        _META_USER_ID: chunk.user_id,
        _META_CHUNK_INDEX: chunk.chunk_index,
        _META_PAGE_NUMBER: chunk.page_number if chunk.page_number is not None else _NO_PAGE,
        _META_CHAR_START: chunk.char_start,
        _META_CHAR_END: chunk.char_end,
    }


def _metadata_to_chunk(
    chunk_id: str,
    meta: dict[str, Any],
    text: str,
) -> DocumentChunk:
    """Reconstruct a :class:`~app.interfaces.core.DocumentChunk` from
    ChromaDB metadata and the stored document text.
    """
    raw_page = meta.get(_META_PAGE_NUMBER, _NO_PAGE)
    page_number = None if raw_page == _NO_PAGE else int(raw_page)
    return DocumentChunk(
        chunk_id=chunk_id,
        document_id=str(meta.get(_META_DOCUMENT_ID, "")),
        document_name=str(meta.get(_META_DOCUMENT_NAME, "")),
        user_id=str(meta.get(_META_USER_ID, "")),
        chunk_index=int(meta.get(_META_CHUNK_INDEX, 0)),
        text=text,
        page_number=page_number,
        char_start=int(meta.get(_META_CHAR_START, 0)),
        char_end=int(meta.get(_META_CHAR_END, 0)),
    )


def _distance_to_similarity(distance: float) -> float:
    """Convert a ChromaDB cosine distance to a similarity score in [0, 1].

    ChromaDB cosine distance is in [0, 2]:
      distance = 1 - cosine_similarity
    So:
      similarity = 1 - distance      (clamped to [0, 1])
    """
    similarity = 1.0 - float(distance)
    return max(0.0, min(1.0, similarity))
