# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services
# File    : search_service.py
# Purpose : Business logic for AI-powered semantic search
#
# Architecture Layer : Service
# Pattern Used       : Service Layer (Business Logic)
#
# Key Concepts:
#   - FastAPI async request handling
#   - ChromaDB cosine similarity search across 4 per-user collections
#   - SentenceTransformer embedding generation
#
# Dependencies:
#   - chromadb
#   - sentence-transformers
# ============================================================

"""Search Service — performs semantic search across conversations, notes, documents, memories.

The SearchService queries all four ChromaDB per-user collections using cosine similarity:
  - conversations_{user_id}
  - notes_{user_id}
  - documents_{user_id}
  - memories_{user_id}

Results are filtered to cosine similarity ≥ 0.5 (ChromaDB distance ≤ 0.5),
sorted by relevance descending, and returned with deep-link URIs.

Collections that do not exist or have 0 embeddings are skipped entirely
(empty groups are omitted from the response).

Requirements: 36.2, 36.3, 36.5, 36.7
"""

from __future__ import annotations

import asyncio
import logging
import uuid
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.config.settings import get_settings
from app.schemas.search import SemanticSearchResultItem

logger = logging.getLogger(__name__)

# ChromaDB cosine distance threshold: distance ≤ 0.5 corresponds to similarity ≥ 0.5
_DISTANCE_THRESHOLD = 0.5

# Number of results to fetch per collection (before threshold filtering)
_N_RESULTS_PER_COLLECTION = 50

# Source-type collection name templates
_COLLECTION_TEMPLATES = {
    "conversation": "conversations_{user_id}",
    "note": "notes_{user_id}",
    "document": "documents_{user_id}",
    "memory": "memories_{user_id}",
}


class SearchService:
    """Business logic for AI-powered semantic search.

    Queries all four ChromaDB per-user collections and returns results with
    cosine similarity ≥ 0.5 sorted by relevance score descending.

    Args:
        db: SQLAlchemy async session (reserved for future metadata lookups).

    Requirements: 36.2, 36.3, 36.5, 36.7
    """

    def __init__(self, db: AsyncSession) -> None:
        self._db = db
        self._settings = get_settings()
        self._embedding_model = None  # lazy-loaded on first call

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    async def semantic_search(
        self,
        user_id: uuid.UUID,
        query: str,
    ) -> list[SemanticSearchResultItem]:
        """Perform semantic search across all four content-type collections.

        Workflow:
        1. Generate query embedding via SentenceTransformer.
        2. Query each of the 4 user-scoped ChromaDB collections.
        3. Filter results to cosine distance ≤ 0.5 (similarity ≥ 0.5).
        4. Skip collections that don't exist or are empty.
        5. Sort all passing results by relevance score descending.
        6. Return results as SemanticSearchResultItem objects.

        Args:
            user_id: UUID of the authenticated user (scopes all collection queries).
            query: Natural language search string.

        Returns:
            List of :class:`SemanticSearchResultItem` objects sorted by
            relevance_score descending. Only results with score ≥ 0.5 are included.
            Empty list if no results meet the threshold.

        Requirements: 36.2, 36.3, 36.5, 36.7
        """

        # Step 1: generate query embedding
        def _encode_query() -> list[float]:
            model = self._get_embedding_model()
            embeddings = model.encode([query], show_progress_bar=False)
            emb = embeddings[0]
            # Support both numpy ndarray (.tolist()) and plain list
            result: list[float] = emb.tolist() if hasattr(emb, "tolist") else list(emb)
            return result

        try:
            query_embedding = await asyncio.to_thread(_encode_query)
        except Exception as exc:
            logger.warning("Embedding generation failed for semantic search: %s", exc)
            return []

        # Step 2: query each collection concurrently
        all_results: list[SemanticSearchResultItem] = []

        for source_type, collection_template in _COLLECTION_TEMPLATES.items():
            collection_name = collection_template.format(user_id=user_id)
            results = await self._query_collection(
                collection_name=collection_name,
                source_type=source_type,
                user_id=user_id,
                query_embedding=query_embedding,
            )
            all_results.extend(results)

        # Step 3: sort all results by relevance score descending
        all_results.sort(key=lambda r: r.relevance_score, reverse=True)

        return all_results

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _get_embedding_model(self) -> Any:
        """Lazy-load and cache the SentenceTransformer embedding model."""
        if self._embedding_model is None:
            from sentence_transformers import SentenceTransformer

            self._embedding_model = SentenceTransformer("all-MiniLM-L6-v2")
        return self._embedding_model

    def _get_chroma_client(self) -> Any:
        """Create a ChromaDB HTTP client."""
        import chromadb

        return chromadb.HttpClient(
            host=self._settings.CHROMA_HOST,
            port=self._settings.CHROMA_PORT,
        )

    async def _query_collection(
        self,
        collection_name: str,
        source_type: str,
        user_id: uuid.UUID,
        query_embedding: list[float],
    ) -> list[SemanticSearchResultItem]:
        """Query a single ChromaDB collection and return filtered results."""

        def _do_query() -> list[dict[str, Any]]:
            """Execute the ChromaDB query synchronously."""
            try:
                client = self._get_chroma_client()
                try:
                    collection = client.get_collection(collection_name)
                except Exception:
                    # Collection does not exist — skip
                    return []

                count = collection.count()
                if count == 0:
                    # Empty collection — skip (empty group omission)
                    return []

                n = min(_N_RESULTS_PER_COLLECTION, count)
                results = collection.query(
                    query_embeddings=[query_embedding],
                    n_results=n,
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
                        "id": ids_list[i],
                        "document": docs_list[i] if docs_list else "",
                        "metadata": metas_list[i] if metas_list else {},
                        "distance": dists_list[i] if dists_list else 1.0,
                    }
                    for i in range(len(ids_list))
                ]
            except Exception as exc:
                logger.warning(
                    "ChromaDB query failed for collection %s: %s",
                    collection_name,
                    exc,
                )
                return []

        raw_results = await asyncio.to_thread(_do_query)

        items: list[SemanticSearchResultItem] = []
        for r in raw_results:
            distance = float(r["distance"])

            # Filter: only include results with cosine distance ≤ 0.5
            if distance > _DISTANCE_THRESHOLD:
                continue

            relevance_score = round(1.0 - distance, 2)
            metadata = r.get("metadata", {})
            doc_text = r.get("document", "")

            # Truncate excerpt to 300 chars
            excerpt = doc_text[:300]

            # Extract source name from metadata (fall back to collection name)
            source_name = (
                metadata.get("source_name")
                or metadata.get("title")
                or metadata.get("name")
                or f"{source_type.capitalize()} item"
            )

            # Extract source ID from metadata for deep-link construction
            source_id = metadata.get("source_id") or metadata.get("id") or r["id"]

            deep_link = f"aiassistant://{source_type}s/{source_id}"

            items.append(
                SemanticSearchResultItem(
                    source_type=source_type,
                    source_name=source_name,
                    excerpt=excerpt,
                    relevance_score=relevance_score,
                    deep_link=deep_link,
                )
            )

        return items
