"""Embedding layer for the AI Assistant RAG module.

Public surface
--------------
SentenceTransformerEmbeddingProvider  – IEmbeddingProvider backed by
    sentence-transformers (all-MiniLM-L6-v2 by default).
EmbeddingConfig                        – dataclass for model configuration.

The provider wraps the same lazy-initialisation + thread-safe logic used
inside RAGService._get_embedding_model(), extracted into a standalone,
injectable class so:
  • agents and the document pipeline can embed without importing RAGService
  • tests can inject a fake provider without loading sentence-transformers
  • the model name is now configurable (previously hardcoded)

Usage::

    from app.embedding import SentenceTransformerEmbeddingProvider, EmbeddingConfig

    provider = SentenceTransformerEmbeddingProvider(EmbeddingConfig())
    vec = await provider.embed("Hello world")
    # vec.values  →  list[float], len 384
    # vec.model_name  →  "all-MiniLM-L6-v2"
"""

from app.embedding.provider import EmbeddingConfig, SentenceTransformerEmbeddingProvider

__all__ = ["EmbeddingConfig", "SentenceTransformerEmbeddingProvider"]
