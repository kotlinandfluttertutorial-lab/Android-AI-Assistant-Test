"""Vector-storage layer for the AI Assistant RAG module.

Public surface
--------------
ChromaVectorStore   – IVectorStore backed by ChromaDB.
ChromaConfig        – dataclass controlling ChromaDB connection mode.

Two connection modes are supported:

* **Persistent** (default for local dev / tests):
  ``chromadb.PersistentClient(path=persist_dir)`` — embedded in-process,
  backed by a local SQLite file.  No server required.

* **HTTP** (production):
  ``chromadb.HttpClient(host=..., port=...)`` — connects to a separately
  running ChromaDB server (Docker Compose / Cloud Run internal service).

Business logic always depends on :class:`~app.interfaces.core.IVectorStore`,
never on ``ChromaVectorStore`` directly, so ChromaDB can be swapped or
mocked in tests without changing callers.

Usage::

    from app.vector import ChromaVectorStore, ChromaConfig
    from app.embedding import SentenceTransformerEmbeddingProvider

    store = ChromaVectorStore(ChromaConfig())
    # await store.upsert(stored_chunk)
    # await store.search(user_id, query_embedding, top_k=5)
"""

from app.vector.chroma_store import ChromaConfig, ChromaVectorStore

__all__ = ["ChromaConfig", "ChromaVectorStore"]
