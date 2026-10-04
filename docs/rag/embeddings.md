# RAG Embeddings

> **Updated:** 2026-10-03

How text is converted to vectors for semantic search.

---

## Embedding model

| Property | Value |
|---|---|
| Model | `sentence-transformers/all-MiniLM-L6-v2` |
| Vector dimensions | 384 |
| Max input tokens | 256 (content is pre-chunked to 512 tokens to stay within this) |
| Similarity metric | Cosine similarity |
| Inference | CPU-only |
| Library | `sentence-transformers` 5.6.1 |
| Model size | ~90 MB |

The model produces a 384-dimensional dense vector for any input text. Semantic similarity is measured by cosine distance between query and chunk vectors.

---

## Why all-MiniLM-L6-v2?

- **Accuracy:** Strong performance on semantic textual similarity benchmarks for its size class.
- **Speed:** ~35 ms per chunk on CPU — fast enough for Celery background ingestion.
- **Size:** 90 MB fits in the production Docker image without significant build time increase.
- **No API cost:** Runs entirely in-process — no external embedding API calls, no quota.
- **Privacy:** Document text never leaves the server for embedding.

For higher accuracy at higher cost, the target architecture includes a path to swap the provider via the `IEmbeddingProvider` interface.

---

## `IEmbeddingProvider` interface

```python
class IEmbeddingProvider(Protocol):
    async def embed(self, text: str) -> list[float]: ...
    async def embed_batch(self, texts: list[str]) -> list[list[float]]: ...
```

`SentenceTransformerEmbeddingProvider` implements this interface. To swap the model:

1. Implement `IEmbeddingProvider`.
2. Replace the injection in `VectorRetriever` constructor.
3. Re-index all documents (delete and re-upload, or run a migration).

---

## Cold-start mitigation

The model takes ~35 seconds to load on first use. Three mechanisms address this:

1. **Docker image pre-download** — the production `Dockerfile` runs:
   ```
   RUN python -c "from sentence_transformers import SentenceTransformer; \
                  SentenceTransformer('sentence-transformers/all-MiniLM-L6-v2')"
   ```
   The model weights are baked into the image at build time.

2. **Lifespan warmup** — `app/main.py` lifespan handler runs a background warmup:
   ```python
   model.encode(["warmup"], show_progress_bar=False)
   ```
   This happens after uvicorn binds to the port but before the first request.

3. **Celery worker singleton** — the Celery worker loads the model once at startup and reuses it across tasks (Python module-level singleton pattern in `rag_service.py`).

---

## Vector storage

Embeddings are stored in two places:

### ChromaDB (primary — ANN search)

- Collection per user: `documents_{user_id}`
- Automatic HNSW index for approximate nearest-neighbour (ANN) search
- Sub-second search across millions of vectors
- HTTP API on `chromadb:8000` (Docker service) or embedded `PersistentClient` (production Cloud Run)

### PostgreSQL pgvector (secondary — exact cosine)

- Column: `document_chunks.embedding vector(384)` (pgvector extension)
- Enables exact cosine distance queries:
  ```sql
  ORDER BY embedding <=> query_vector LIMIT 5
  ```
- Used for hybrid search (pgvector cosine + BM25 + ChromaDB ANN) in the target architecture.
- Currently populated but not yet used for retrieval (migration 0017 required for BM25 + hybrid fusion).

---

## Differential privacy

When `DP_EPSILON` is set (default 1.0), Laplace noise is added to memory embeddings before ChromaDB writes (Requirement 37.1, 37.8):

```python
noisy_embedding = add_laplace_noise(embedding, epsilon=settings.DP_EPSILON)
```

Document chunk embeddings do not currently receive DP noise — this is a future extension point (see `docs/architecture/quality-review.md §5, E7`).
