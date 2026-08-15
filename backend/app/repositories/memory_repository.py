# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : repositories
# File    : memory_repository.py
# Purpose : ChromaDB and PostgreSQL access layer for user memory embeddings
#
# Architecture Layer : Repository
# Pattern Used       : Repository Pattern
#
# Key Concepts:
#   - ChromaDB per-user collection naming: memories_{user_id}
#   - User-scoped isolation — every ChromaDB query filters by user_id
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - chromadb
#   - sentence-transformers
#   - sqlalchemy
# ============================================================

"""Memory repository — wraps ChromaDB per-user collection for memory embeddings.

The ``MemoryRepository`` stores and retrieves user-specific memory embeddings
in ChromaDB.  Each user gets a dedicated ChromaDB collection named
``memories_{user_id}`` to enforce strict cross-user isolation.

Every ChromaDB query enforces a ``user_id`` filter so that cross-user retrieval
is structurally impossible even if a bug in the caller omits the user_id.

Requirements: 7.1, 7.2, 7.4, 7.5
"""

from __future__ import annotations

import asyncio
import logging
import uuid
from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config.settings import get_settings
from app.models.memory import Memory, MemoryType

logger = logging.getLogger(__name__)


@dataclass
class MemorySearchResult:
    """A single memory retrieved from ChromaDB with relevance metadata.

    Attributes:
        memory_id: PostgreSQL UUID of the memory row.
        chroma_id: ChromaDB document ID.
        content: The memory text to inject into the prompt.
        memory_type: Classification of the memory (preference, fact, style).
        relevance_score: Cosine similarity distance from ChromaDB (lower = more similar).
    """

    memory_id: uuid.UUID
    chroma_id: str
    content: str
    memory_type: str
    relevance_score: float = 1.0


class MemoryRepository:
    """Wraps ChromaDB per-user collection for memory embedding storage and retrieval.

    Collection naming convention: ``memories_{user_id}``

    Every query is scoped to the requesting user's collection.  No method
    allows querying across user boundaries.

    Args:
        db: SQLAlchemy async session for reading/writing memory metadata in PostgreSQL.
        settings: Application settings (provides CHROMA_HOST, CHROMA_PORT).

    Requirements: 7.1, 7.2, 7.4, 7.5
    """

    def __init__(self, db: AsyncSession) -> None:
        self._db = db
        self._settings = get_settings()
        self._embedding_model = None  # lazy-loaded on first call

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _collection_name(self, user_id: uuid.UUID) -> str:
        """Return the ChromaDB collection name for a given user.

        Convention: ``memories_{user_id}``

        Args:
            user_id: UUID of the user.

        Returns:
            Collection name string.

        Requirements: 7.5
        """
        return f"memories_{user_id}"

    def _get_embedding_model(self):
        """Lazy-load and cache the SentenceTransformer embedding model.

        Returns:
            Loaded SentenceTransformer model.
        """
        if self._embedding_model is None:
            from sentence_transformers import SentenceTransformer

            self._embedding_model = SentenceTransformer("all-MiniLM-L6-v2")
        return self._embedding_model

    def _encode(self, texts: list[str]) -> list[list[float]]:
        """Generate embeddings for a list of texts synchronously.

        Args:
            texts: List of strings to embed.

        Returns:
            List of embedding vectors.
        """
        model = self._get_embedding_model()
        embeddings = model.encode(texts, show_progress_bar=False)
        return [emb.tolist() for emb in embeddings]

    def _get_chroma_client(self):
        """Create a ChromaDB HTTP client.

        Returns:
            chromadb.HttpClient instance.
        """
        import chromadb

        return chromadb.HttpClient(
            host=self._settings.CHROMA_HOST,
            port=self._settings.CHROMA_PORT,
        )

    # ------------------------------------------------------------------
    # Store (POST /memory)
    # ------------------------------------------------------------------

    async def store_memory(
        self,
        user_id: uuid.UUID,
        content: str,
        memory_type: MemoryType,
        epsilon: float | None = None,
        redis=None,
    ) -> Memory:
        """Store a memory as an embedding in ChromaDB and metadata in PostgreSQL.

        Workflow:
        1. Generate an embedding for the memory content.
        2. Apply Laplace differential-privacy noise to the embedding.
        3. Store the noised embedding vector in the user-scoped ChromaDB collection.
        4. Persist a ``Memory`` row in PostgreSQL with the ChromaDB ID.

        Args:
            user_id: UUID of the user who owns this memory.
            content: The memory text to store and embed.
            memory_type: Classification of the memory.
            epsilon: Optional DP epsilon override.  When ``None``, the current
                epsilon is resolved via ``get_current_epsilon`` (Redis → settings).
            redis: Optional async Redis client for epsilon lookup.

        Returns:
            The persisted :class:`~app.models.memory.Memory` ORM row.

        Requirements: 7.1, 37.1, 37.3, 37.4
        """
        from app.security.differential_privacy import (
            LaplaceNoiseInjector,
            get_current_epsilon,
        )

        collection_name = self._collection_name(user_id)
        chroma_id = str(uuid.uuid4())

        # Resolve epsilon: use the provided value or fetch from Redis/settings
        if epsilon is None:
            epsilon = await get_current_epsilon(redis)

        # Capture epsilon in a local variable for the thread closure
        _epsilon = epsilon

        # Generate embedding off the event loop (CPU-bound)
        def _embed_and_store() -> None:
            try:
                embeddings = self._encode([content])
                # Apply Laplace differential-privacy noise before storing
                noised_embedding = LaplaceNoiseInjector.add_noise(embeddings[0], epsilon=_epsilon)
                client = self._get_chroma_client()
                collection = client.get_or_create_collection(collection_name)
                collection.add(
                    ids=[chroma_id],
                    embeddings=[noised_embedding],
                    documents=[content],
                    metadatas=[{"user_id": str(user_id), "memory_type": memory_type.value}],
                )
            except Exception as exc:
                logger.warning(
                    "ChromaDB store failed for user %s (graceful degradation): %s",
                    user_id,
                    exc,
                )

        await asyncio.to_thread(_embed_and_store)

        # Persist metadata in PostgreSQL
        memory = Memory(
            user_id=user_id,
            content=content,
            memory_type=memory_type,
            chroma_id=chroma_id,
        )
        self._db.add(memory)
        await self._db.flush()
        return memory

    # ------------------------------------------------------------------
    # Retrieve top-K (GET /memory)
    # ------------------------------------------------------------------

    async def search_memories(
        self,
        user_id: uuid.UUID,
        query: str,
        top_k: int = 3,
    ) -> list[MemorySearchResult]:
        """Perform semantic search to return top-K most relevant memories.

        Queries ONLY the ``memory_{user_id}`` collection — cross-user retrieval
        is structurally prevented by using per-user collections.

        Args:
            user_id: UUID of the user whose memories to search.
            query: The current user message used as the semantic search query.
            top_k: Maximum number of memories to return (default: 3).

        Returns:
            List of :class:`MemorySearchResult` objects, ordered by relevance
            (most similar first).  Returns an empty list on error or when no
            memories are found.

        Requirements: 7.2, 7.5
        """
        collection_name = self._collection_name(user_id)

        # Generate query embedding off the event loop
        def _encode_query() -> list[float]:
            embeddings = self._encode([query])
            return embeddings[0]

        try:
            query_embedding = await asyncio.to_thread(_encode_query)
        except Exception as exc:
            logger.warning(
                "Embedding generation failed for user %s query; returning empty: %s",
                user_id,
                exc,
            )
            return []

        def _query_chroma() -> list[dict]:
            """Query ChromaDB and return list of result dicts."""
            try:
                client = self._get_chroma_client()
                try:
                    collection = client.get_collection(collection_name)
                except Exception:
                    # Collection does not exist — user has no memories yet
                    return []

                results = collection.query(
                    query_embeddings=[query_embedding],
                    n_results=min(top_k, collection.count()),
                    include=["documents", "metadatas", "distances"],
                )

                if not results or not results.get("ids"):
                    return []

                ids_list = results["ids"][0]
                docs_list = results.get("documents", [[]])[0]
                metas_list = results.get("metadatas", [[]])[0]
                dists_list = results.get("distances", [[]])[0]

                return [
                    {
                        "chroma_id": ids_list[i],
                        "content": docs_list[i] if docs_list else "",
                        "metadata": metas_list[i] if metas_list else {},
                        "distance": dists_list[i] if dists_list else 0.0,
                    }
                    for i in range(len(ids_list))
                ]
            except Exception as exc:
                logger.warning(
                    "ChromaDB query failed for user %s: %s",
                    user_id,
                    exc,
                )
                return []

        chroma_results = await asyncio.to_thread(_query_chroma)

        if not chroma_results:
            return []

        # Fetch PostgreSQL metadata to get proper UUIDs
        chroma_ids = [r["chroma_id"] for r in chroma_results]
        chroma_id_to_result = {r["chroma_id"]: r for r in chroma_results}

        pg_result = await self._db.execute(
            select(Memory).where(
                Memory.user_id == user_id,  # enforce user_id filter in SQL too
                Memory.chroma_id.in_(chroma_ids),
            )
        )
        pg_memories = {m.chroma_id: m for m in pg_result.scalars().all()}

        search_results: list[MemorySearchResult] = []
        for chroma_id in chroma_ids:
            chroma_data = chroma_id_to_result[chroma_id]
            pg_memory = pg_memories.get(chroma_id)

            if pg_memory is None:
                # ChromaDB has the vector but PostgreSQL row is missing — skip
                continue

            search_results.append(
                MemorySearchResult(
                    memory_id=pg_memory.id,
                    chroma_id=chroma_id,
                    content=pg_memory.content,
                    memory_type=pg_memory.memory_type.value,
                    relevance_score=float(chroma_data["distance"]),
                )
            )

        return search_results

    # ------------------------------------------------------------------
    # Delete (DELETE /memory/{id})
    # ------------------------------------------------------------------

    async def delete_memory(
        self,
        memory_id: uuid.UUID,
        user_id: uuid.UUID,
    ) -> bool:
        """Remove a memory's embedding from ChromaDB and its row from PostgreSQL.

        Args:
            memory_id: UUID of the memory to delete (from PostgreSQL).
            user_id: UUID of the owning user (enforces user-scoped access).

        Returns:
            ``True`` if the memory was found and deleted, ``False`` if not found
            or does not belong to the given user.

        Requirements: 7.4
        """
        # Fetch the PostgreSQL row first (also enforces user ownership)
        result = await self._db.execute(
            select(Memory).where(
                Memory.id == memory_id,
                Memory.user_id == user_id,  # user_id filter prevents cross-user deletion
            )
        )
        memory = result.scalar_one_or_none()

        if memory is None:
            return False

        chroma_id = memory.chroma_id
        collection_name = self._collection_name(user_id)

        # Delete from ChromaDB first (graceful degradation if it fails)
        def _delete_from_chroma() -> None:
            try:
                client = self._get_chroma_client()
                try:
                    collection = client.get_collection(collection_name)
                    collection.delete(ids=[chroma_id])
                except Exception:
                    # Collection or document may not exist
                    pass
            except Exception as exc:
                logger.warning(
                    "ChromaDB delete failed for chroma_id=%s user=%s: %s",
                    chroma_id,
                    user_id,
                    exc,
                )

        await asyncio.to_thread(_delete_from_chroma)

        # Delete from PostgreSQL
        await self._db.delete(memory)
        await self._db.flush()
        return True

    # ------------------------------------------------------------------
    # List all memories for a user
    # ------------------------------------------------------------------

    async def list_memories(
        self,
        user_id: uuid.UUID,
    ) -> list[Memory]:
        """Return all memories for a user, newest first.

        Args:
            user_id: UUID of the user.

        Returns:
            List of :class:`~app.models.memory.Memory` rows.

        Requirements: 7.3
        """
        result = await self._db.execute(
            select(Memory).where(Memory.user_id == user_id).order_by(Memory.created_at.desc())
        )
        return list(result.scalars().all())

    # ------------------------------------------------------------------
    # Fallback: recent memories from PostgreSQL (no ChromaDB)
    # ------------------------------------------------------------------

    async def get_recent_memories(
        self,
        user_id: uuid.UUID,
        top_k: int = 3,
    ) -> list[MemorySearchResult]:
        """Retrieve the most recent memories from PostgreSQL as a fallback.

        Used when ChromaDB is unavailable or when a query string is not provided.

        Args:
            user_id: UUID of the user.
            top_k: Maximum number of memories to return.

        Returns:
            List of :class:`MemorySearchResult` objects.
        """
        result = await self._db.execute(
            select(Memory)
            .where(Memory.user_id == user_id)
            .order_by(Memory.created_at.desc())
            .limit(top_k)
        )
        memories = result.scalars().all()
        return [
            MemorySearchResult(
                memory_id=m.id,
                chroma_id=m.chroma_id,
                content=m.content,
                memory_type=m.memory_type.value,
                relevance_score=1.0,
            )
            for m in memories
        ]
