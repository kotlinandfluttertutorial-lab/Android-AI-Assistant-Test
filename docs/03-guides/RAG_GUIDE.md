# RAG (Retrieval-Augmented Generation) — Phase 9 Learning Guide

A thorough walkthrough of the RAG pipeline: what it is, why it matters,
how every stage of the pipeline works, and how this project implements it.

---

## Table of Contents

1. [What is RAG and why does it matter?](#1-what-is-rag-and-why-does-it-matter)
2. [The RAG Pipeline — Overview](#2-the-rag-pipeline--overview)
3. [Stage 1 — Document Ingestion](#3-stage-1--document-ingestion)
4. [Stage 2 — Chunking](#4-stage-2--chunking)
5. [Stage 3 — Embedding](#5-stage-3--embedding)
6. [Stage 4 — Vector Storage (ChromaDB + pgvector)](#6-stage-4--vector-storage-chromadb--pgvector)
7. [Stage 5 — Retrieval](#7-stage-5--retrieval)
8. [Stage 6 — Generation (LLM)](#8-stage-6--generation-llm)
9. [The DevOps Knowledge Base](#9-the-devops-knowledge-base)
10. [RAG Evaluation](#10-rag-evaluation)
11. [Key Design Decisions & Trade-offs](#11-key-design-decisions--trade-offs)
12. [Interview Questions](#12-interview-questions)
13. [Exercises](#13-exercises)

---

## 1. What is RAG and why does it matter?

A Large Language Model (LLM) like Gemini or GPT-4o is trained on data that has a
cutoff date. It knows nothing about:

- Your specific runbooks ("how do we restart the API service?")
- Your historical incidents ("what caused the DB connection pool exhaustion in July?")
- Your deployment configuration ("what Cloud Run region does this service deploy to?")
- Your architecture decisions ("why did we choose Neon over Cloud SQL?")

**RAG** (Retrieval-Augmented Generation) solves this by fetching relevant context
from your private knowledge base at query time and injecting it into the LLM prompt:

```
Without RAG:
  User: "How do we restart the API service?"
  LLM: "I don't have information about your specific service. Generally you would..."
       (generic, unhelpful)

With RAG:
  User: "How do we restart the API service?"
  Retriever fetches: knowledge/runbooks/service-restart.md
  LLM: "According to the service-restart runbook:
       1. Open Cloud Console → Cloud Run → ai-assistant-backend
       2. Click 'Edit & Deploy New Revision'
       3. Toggle 'Serve this revision immediately'..."
       (specific, actionable, grounded in your docs)
```

### Why not just fine-tune the LLM?

| Approach | How it works | When to use |
|---|---|---|
| **RAG** | Retrieve relevant chunks at query time, inject into prompt | Knowledge that changes frequently, large corpuses, needs citations |
| **Fine-tuning** | Update model weights on your data | Teaching the model a new *skill* (style, format), not new facts |
| **Prompt engineering** | Put everything in the system prompt | < 2,000 tokens of stable context |

RAG is almost always the right choice for knowledge retrieval because:
- Documents can be updated without retraining
- You can see exactly what context the LLM received (citations)
- It scales to millions of documents (fine-tuning can't handle a whole knowledge base)
- The LLM can say "I don't have information about X" when no chunk matches

---

## 2. The RAG Pipeline — Overview

This project implements two distinct RAG pipelines:

### Pipeline A — User Document RAG

Users upload their own documents (PDFs, DOCX, TXT, Markdown) for question-answering:

```
User uploads PDF
      │
      ▼
POST /documents  →  validate  →  store in GCS/MinIO
      │
      ▼
Celery ingest_document_task (async, background)
      │
      ├── 1. Download from GCS/MinIO
      ├── 2. Extract text (pypdf / python-docx / UTF-8)
      ├── 3. Chunk with tiktoken (512 tokens, 64 overlap)
      ├── 4. Embed with SentenceTransformer all-MiniLM-L6-v2
      ├── 5. Store vectors in pgvector (PostgreSQL)
      └── 6. Mark document ready, notify via FCM
                │
                ▼
User asks: POST /documents/query {"query": "What are the key findings?"}
                │
                ▼
      embed query  →  pgvector similarity search  →  top-K chunks
                │
                ▼
      inject context + citations into LLM prompt  →  answer + citations
```

### Pipeline B — DevOps Knowledge Base RAG (Phase 13)

System documents (runbooks, incidents, architecture) seeded at startup:

```
knowledge/ directory
    ├── runbooks/*.md         — operational procedures
    ├── incidents/*.md        — historical incident reports
    ├── architecture/*.md     — system design docs
    └── deployment/*.md       — deployment guides
          │
          ▼
scripts/seed_knowledge.py   (idempotent, runs at every startup)
          │
          ▼
ChromaDB collection "devops_knowledge"
          │
          ▼
AI DevOps Assistant (Phase 13): "Why did the API fail at 14:32?"
  search_runbooks("connection pool")  →  retrieve knowledge
  LLM reasons over context  →  grounded answer with runbook citations
```

---

## 3. Stage 1 — Document Ingestion

### Upload flow

```python
# app/api/rag/router.py
@router.post("/documents")
async def upload_document(
    file: UploadFile,
    db: AsyncSession = Depends(get_db),
    current_user: TokenPayload = Depends(get_current_user),
):
    # 1. Validate BEFORE any I/O (Property 26)
    rag_service.validate_mime_and_upload(
        filename=file.filename,
        size_bytes=file.size,
        content_type=file.content_type,
    )

    # 2. Store in GCS/MinIO
    file_bytes = await file.read()
    minio_key = await rag_service.store_file_minio(
        file_bytes, file.filename, user_id
    )

    # 3. Create Document row in PostgreSQL
    document = await doc_repo.create(...)

    # 4. Create Job row (tracks ingestion progress)
    job = await job_repo.create(...)

    # 5. Dispatch Celery task asynchronously
    ingest_document_task.delay(str(document.id), str(current_user.sub))

    # Returns immediately — ingestion happens in the background
    return DocumentUploadResponse(document_id=..., job_id=..., status="pending")
```

### Why validate before I/O?

If validation runs after the file is stored, you've already paid the storage
cost for an invalid file. Early validation (Property 26) rejects unsupported
formats and oversized files in < 1ms.

Supported formats:
- `application/pdf` / `.pdf` — pypdf with OCR fallback
- `application/vnd.openxmlformats-officedocument.wordprocessingml.document` / `.docx` — python-docx
- `text/plain` / `.txt` — UTF-8 decode
- `text/markdown` / `.md` — UTF-8 decode

### Why Celery for ingestion?

Ingestion involves: downloading a file, extracting text, encoding 50-500 embeddings.
This takes 2-30 seconds. Running it synchronously in the HTTP handler would:
- Block the handler for 30 seconds
- Consume a thread/coroutine for the full duration
- Time out Cloud Run's 60-second request limit for large files

Celery runs ingestion in a background worker (separate process/container). The HTTP
handler returns immediately with `{"status": "pending", "job_id": "..."}`.
The client polls `GET /jobs/{job_id}` to track progress.

### Retry policy

```python
@celery_app.task(bind=True, max_retries=3)
def ingest_document_task(self, document_id, user_id):
    try:
        file_bytes = await rag_service.download_file_minio(key)
    except Exception as exc:
        countdown = 2 ** self.request.retries   # 1s, 2s, 4s
        raise self.retry(exc=exc, countdown=countdown, max_retries=3)
```

Exponential backoff: 1s → 2s → 4s. After 3 failures, the document is marked
permanently failed and the user receives a push notification.

---

## 4. Stage 2 — Chunking

### Why chunk at all?

An LLM's context window has a limit (e.g. 8,192 tokens for Gemini Flash). A 100-page
PDF is ~50,000 tokens — it can't all fit. Chunking splits the document into pieces
that fit in the context window, and retrieval selects only the relevant pieces.

### Token-based chunking with tiktoken

```python
# app/services/rag_service.py
def chunk_text(self, text, chunk_size=512, overlap=64):
    enc = tiktoken.encoding_for_model("gpt-3.5-turbo")
    tokens = enc.encode(text)

    stride = chunk_size - overlap   # = 448 tokens of new content per chunk
    chunks = []
    start = 0

    while start < len(tokens):
        end = min(start + chunk_size, len(tokens))
        chunk_text = enc.decode(tokens[start:end])
        chunks.append(ChunkResult(text=chunk_text))
        if end == len(tokens):
            break
        start += stride   # slide forward by stride, not chunk_size
```

### The overlap parameter — why it matters

```
Chunk 1:  [tokens 0..511]
Chunk 2:  [tokens 448..959]    ← 64 overlapping tokens with chunk 1
Chunk 3:  [tokens 896..1407]   ← 64 overlapping tokens with chunk 2
```

Without overlap, a sentence that spans the boundary between chunk 1 and chunk 2
gets split in half. Neither chunk contains the complete sentence. The LLM can't
reason about incomplete sentences.

With 64-token overlap, both neighbouring chunks contain the boundary content.
The retriever will find at least one of them.

**Property 7 (chunk coverage):** Every token of the source text appears in at
least one chunk. This is mathematically guaranteed because:
- `stride = chunk_size - overlap < chunk_size`
- So every window advances by less than a full chunk width
- Every token is covered by `ceil(chunk_size / stride)` chunks

### Chunk size trade-offs

| Setting | Result |
|---|---|
| Large chunks (1024 tokens) | More context per chunk; but retrieval matches large sections — less precise |
| Small chunks (128 tokens) | Very precise retrieval; but may miss context from surrounding paragraphs |
| **512 tokens (default)** | Balance: covers a paragraph or two, retrievable at sentence granularity |

---

## 5. Stage 3 — Embedding

### What is an embedding?

An embedding is a fixed-length vector of floating-point numbers that represents
the **semantic meaning** of a piece of text. Texts with similar meanings have
vectors that are geometrically close to each other.

```
"The database connection pool is exhausted."
→ [0.23, -0.41, 0.89, ..., 0.12]  (384 dimensions)

"Too many concurrent connections to PostgreSQL."
→ [0.21, -0.39, 0.87, ..., 0.11]  (similar vector — semantically close)

"The weather is sunny today."
→ [-0.55, 0.73, -0.22, ..., 0.94] (very different vector)
```

The embedding model learns these relationships from a massive corpus. You never
see the individual numbers — you only use the distances between them.

### The model: `all-MiniLM-L6-v2`

```python
# app/services/rag_service.py
def _get_embedding_model(self):
    if self._embedding_model is None:
        from sentence_transformers import SentenceTransformer
        self._embedding_model = SentenceTransformer("all-MiniLM-L6-v2")
    return self._embedding_model
```

Properties of `all-MiniLM-L6-v2`:
- **384 dimensions** — small and fast; larger models (1536 dims) are slower
- **512 token max input** — this is why we chunk to 512 tokens
- **CPU-only** — no GPU needed; the Dockerfile pre-bakes it into the image
- **Multilingual** — works reasonably well in non-English languages
- **~90 MB** — baked into the Docker image to avoid runtime downloads

### Embedding during ingestion vs query

Both ingestion and query use **the same embedding model** at the same settings.
This is critical — if you embed chunks with model A and queries with model B,
the cosine similarity scores become meaningless.

```python
# Ingestion (rag_service.embed_and_store):
embeddings = model.encode(chunks, show_progress_bar=False)
pgvector_store(embeddings)

# Query (rag_service.query_documents):
query_embedding = model.encode([query])[0]
similar_chunks = pgvector.similarity_search(query_embedding, top_k=5)
```

### Why run embedding off the event loop?

```python
def _encode():
    model = self._get_embedding_model()
    embeddings = model.encode(texts, show_progress_bar=False)
    return [emb.tolist() for emb in embeddings]

embeddings = await asyncio.to_thread(_encode)   # CPU-bound → thread pool
```

Embedding is CPU-bound (matrix multiplication). Running it on the async event loop
would block all other requests for the duration. `asyncio.to_thread()` offloads it
to a thread pool, keeping the event loop free for other requests.

---

## 6. Stage 4 — Vector Storage (ChromaDB + pgvector)

### Why two vector stores?

This project uses both ChromaDB and pgvector, each for different purposes:

| Store | Used for | Why |
|---|---|---|
| **pgvector** | User document chunks | Co-located with metadata in PostgreSQL; consistent transactions; citations with page numbers |
| **ChromaDB** | DevOps knowledge base | Simple in-process store; no schema migrations; idempotent re-seeding |

### pgvector — vector similarity in PostgreSQL

```sql
-- Migration 0014 adds a vector column to document_chunks
ALTER TABLE document_chunks ADD COLUMN embedding vector(384);

-- Similarity search query (cosine distance)
SELECT content, document_name, page_number
FROM document_chunks
WHERE user_id = 'uuid'
ORDER BY embedding <=> '[0.23, -0.41, ...]'   -- <=> = cosine distance
LIMIT 5;
```

`<=>` is pgvector's cosine distance operator. Lower distance = more similar.
pgvector creates an IVFFlat or HNSW index to make this fast at scale.

### User isolation (Property 8)

```python
# User document chunks are scoped by user_id in PostgreSQL
await repo.create_chunk(
    document_id=doc_uuid,
    user_id=user_uuid,   # ← scopes search to this user's documents
    ...
)

# Query filters by user_id
WHERE user_id = $1 ORDER BY embedding <=> $2 LIMIT 5
```

User A cannot retrieve User B's document chunks — the filter is enforced at
the database level, not the application level.

### ChromaDB embedded (in-process)

```python
# rag_service.py
def _make_chroma_client(self):
    import chromadb
    client = chromadb.PersistentClient(path=persist_dir)
    return client
```

The application runs ChromaDB **in-process** using `PersistentClient` — no
separate ChromaDB HTTP server needed. Data is stored in a local SQLite database
at `persist_dir` (`/tmp/chroma` on Cloud Run).

The limitation: Cloud Run's filesystem is ephemeral — ChromaDB data is wiped
on every new revision. The `lifespan()` in `main.py` re-seeds from the
`knowledge/` directory on every startup (handled by `seed_knowledge.py`).

---

## 7. Stage 5 — Retrieval

### Semantic retrieval with citations

```python
async def query_documents(self, user_id, query, document_ids=None, top_k=5, db=None):
    # 1. Embed the query
    query_embedding = await asyncio.to_thread(
        lambda: model.encode([query])[0].tolist()
    )

    # 2. pgvector similarity search
    # SELECT content, document_name, page_number
    # FROM document_chunks
    # WHERE user_id = $1
    # ORDER BY embedding <=> $2 LIMIT 5
    chunks = await repo.similarity_search(
        user_id=user_id,
        embedding=query_embedding,
        document_ids=document_ids,
        top_k=top_k,
    )

    # 3. Build context with citations
    context_parts = []
    for chunk in chunks:
        context_parts.append(
            f"[Source: {chunk.document_name}, Page {chunk.page_number}]\n"
            f"{chunk.content}"
        )
    context = "\n\n---\n\n".join(context_parts)

    return QueryResult(
        query=query,
        retrieved_chunks=chunks,
        context=context,
    )
```

### Citation types

```python
@dataclass
class RetrievedChunk:
    content:           str
    document_name:     str
    page_number:       int          # for PDF/DOCX
    citation_type:     str          # "page" or "char_offset"
    char_offset_start: int | None   # for TXT/Markdown
    char_offset_end:   int | None   # for TXT/Markdown
```

For PDFs and DOCX, citations reference page numbers. For TXT/Markdown, they
reference character offsets — the exact byte range in the original file.

Property 9 (citation completeness): every retrieved chunk includes a citation.
This is enforced at the data model level — `document_name` and `page_number` are
required fields in `document_chunks`.

### The `top_k` parameter

```python
top_k = 5   # retrieve 5 chunks by default
```

Setting `top_k` too low (1-2) means relevant context may not be retrieved.
Setting it too high (20+) fills the LLM context window with noise and increases
cost. 5 is the standard starting point for most RAG systems.

---

## 8. Stage 6 — Generation (LLM)

After retrieval, the context is injected into the LLM prompt:

```python
# Conceptual — actual implementation in app/services/chat_service.py
prompt = f"""You are a helpful assistant. Answer the user's question based
ONLY on the provided context. If the context does not contain the answer,
say "I don't have information about that in the provided documents."

Context:
{context}

Question: {query}

Answer (cite sources as [document_name, page_number]):"""
```

### Key LLM prompt principles for RAG

1. **Ground the LLM in the context** — "Answer ONLY based on the provided context"
   prevents hallucination. The LLM cannot make up information if you explicitly
   restrict it to the retrieved chunks.

2. **Require citation format** — "cite sources as [document_name, page_number]"
   makes citations appear in the answer text, not just in the metadata.

3. **Graceful degradation** — "If the context does not contain the answer, say..."
   prevents the LLM from hallucinating when no relevant chunks are found.

4. **Temperature = 0 for factual queries** — RAG is for factual retrieval, not creative
   generation. `LLM_TEMPERATURE=0.3` (settings default) balances factuality and fluency.

---

## 9. The DevOps Knowledge Base

The `knowledge/` directory is the input to the DevOps RAG pipeline (Phase 13):

```
knowledge/
├── runbooks/
│   ├── database-recovery.md      — Postgres recovery procedures
│   ├── rollback.md               — Cloud Run rollback steps
│   ├── scaling.md                — Scaling the backend
│   └── service-restart.md        — Restarting services
│
├── incidents/
│   ├── INC-001-db-connection-pool.md  — DB pool exhaustion post-mortem
│   ├── INC-002-llm-timeout.md         — OpenAI timeout incident
│   └── INC-003-chroma-cold-start.md   — ChromaDB cold-start failure
│
├── architecture/                  — System design docs
└── deployment/                    — Deployment guides
```

### Adding new knowledge

```bash
# 1. Add a new runbook
echo "# Runbook: Redis Flush" > knowledge/runbooks/redis-flush.md

# 2. Re-seed the knowledge base
python backend/scripts/seed_knowledge.py

# Or from the Cloud Run admin endpoint (no restart needed):
curl -X POST https://your-backend.run.app/admin/rag/reindex \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

### Seeding is idempotent

The seed script uses deterministic chunk IDs: `{relative_path}::{chunk_index}`.
Every run deletes existing chunks for each file before re-inserting:

```python
# For runbooks/service-restart.md chunk 0:
chunk_id = "runbooks/service-restart.md::0"

# On every run:
collection.delete(ids=chunk_ids)  # remove old version
collection.add(ids=chunk_ids, ...)  # insert new version
```

This means re-running the script after editing a document updates the vectors
without duplicating them.

### The DevOps Assistant uses this knowledge

When the DevOps Assistant (Phase 13) receives "Why did the API fail at 14:32?":

```python
# Phase 13 ReAct tool loop
tools = [
    search_logs(query="error", start_time="14:30", end_time="14:35"),
    search_runbooks("connection pool"),          # ← queries ChromaDB
    search_incidents("db connection"),           # ← queries ChromaDB
    get_metrics("error_rate", start="14:30"),
]

# Retrieval result:
# From INC-001-db-connection-pool.md:
#   "Root cause: 30-second LLM calls holding asyncpg connections open..."
#   "Fix: increase pool_size from 5 to 15, reduce LLM timeout to 30s"
#
# LLM reasoning:
# "Based on the logs and incident history, the 14:32 failure matches
#  INC-001 — connection pool exhaustion. The fix applied in July was..."
```

---

## 10. RAG Evaluation

How do you know your RAG pipeline is working well? Three metrics matter:

### Retrieval Precision@K

"Of the K chunks retrieved, how many are actually relevant?"

```python
# Example evaluation
query = "How do we restart the backend?"
relevant_chunks = {"service-restart.md::0", "service-restart.md::1"}
retrieved_chunks = ["service-restart.md::0", "scaling.md::3", "rollback.md::1"]

# Precision@3 = relevant_retrieved / K = 1 / 3 = 0.33
```

### Retrieval Recall@K

"Of all relevant chunks in the corpus, what fraction did we retrieve?"

```python
# Recall@3 = relevant_retrieved / total_relevant = 1 / 2 = 0.50
```

### Answer Faithfulness

"Does the LLM's answer contain only claims supported by the retrieved context?"

```python
# Faithfulness check (manual or automated with another LLM):
context = "Service restart takes 30 seconds on Cloud Run."
answer = "According to the runbook, restart takes 30 seconds."
# Faithful: yes — the answer matches the context.

answer_bad = "Service restart is instant."
# Faithful: no — this contradicts the context. Hallucination.
```

### Running RAG evaluation locally

```python
# Pseudo-code for a simple retrieval precision test
async def test_retrieval(query: str, expected_sources: list[str]):
    result = await rag_service.query_documents(
        user_id=test_user_id,
        query=query,
        top_k=5,
        db=db,
    )
    retrieved_sources = [c.document_name for c in result.retrieved_chunks]
    matches = [s for s in expected_sources if s in retrieved_sources]
    precision = len(matches) / len(retrieved_sources)
    recall = len(matches) / len(expected_sources)
    print(f"Precision@5: {precision:.2f}, Recall: {recall:.2f}")
```

---

## 11. Key Design Decisions & Trade-offs

### 11.1 Two vector stores: pgvector for users, ChromaDB for system docs

pgvector lives in the same PostgreSQL database as all other application data.
Queries join user context (permissions, document metadata) with vector similarity
in one SQL statement. Transactions are consistent.

ChromaDB is simpler for the system knowledge base because it doesn't need joins
with user data. The seeding script can run against ChromaDB without knowing the
PostgreSQL schema. The two stores serve different query patterns.

### 11.2 tiktoken instead of character-based chunking

Character-based chunking (`text[:2000]`) is naive — a 2,000-character chunk might
be 400 tokens or 800 tokens depending on the content. tiktoken counts actual tokens
and the chunk is exactly the size the LLM and embedding model expect.

### 11.3 all-MiniLM-L6-v2 (384 dims) vs larger models

OpenAI's text-embedding-ada-002 has 1,536 dimensions and slightly better retrieval
quality. `all-MiniLM-L6-v2` has 384 dimensions and runs on CPU in ~50ms per batch.

For this project: free, CPU-only, pre-baked into the image. For a production system
with strict retrieval quality requirements, upgrading to a larger model is a
one-line change (`SentenceTransformer("all-mpnet-base-v2")`).

### 11.4 Async ingestion via Celery

Synchronous ingestion would block HTTP handlers for 10-60 seconds per document
(larger PDFs + slower embedding). Celery decouples ingestion from the HTTP request,
allows retries with exponential backoff, and lets multiple documents be processed
concurrently by scaling the worker.

### 11.5 Idempotent seeding with deterministic chunk IDs

Without deterministic IDs, re-running the seed script after editing a runbook
would create duplicate chunks. The deterministic ID scheme (`{path}::{index}`)
ensures re-seeding is safe at any time without manual cleanup.

### 11.6 ChromaDB ephemeral storage on Cloud Run

ChromaDB's in-process PersistentClient stores data in `/tmp/chroma` which is wiped
on every new Cloud Run revision. The startup seeding in `lifespan()` re-builds the
index on every cold start (< 120 seconds cap).

For production with large knowledge bases: mount a Cloud Storage FUSE volume at
`/tmp/chroma` to persist data across revisions, or migrate to AlloyDB pgvector
(which is already installed via Alembic migration 0014).

---

## 12. Interview Questions

**1. What is RAG and when would you use it instead of fine-tuning?**

RAG (Retrieval-Augmented Generation) fetches relevant documents from a knowledge
base at query time and injects them into the LLM prompt as context. Use RAG when:
your knowledge changes frequently (runbooks, documentation), you need citations
for auditability, or your knowledge base is too large to fine-tune on.
Use fine-tuning when: you want the model to adopt a specific writing style or
format, or you're teaching it a new capability rather than new facts.

**2. What is an embedding and how does cosine similarity work?**

An embedding is a dense vector representation of text where semantically similar
texts have geometrically close vectors. Cosine similarity measures the angle
between two vectors — 1.0 means identical direction (semantically identical),
0.0 means perpendicular (unrelated). In practice:
- Cosine similarity > 0.8 → highly related
- Cosine similarity 0.5–0.8 → somewhat related  
- Cosine similarity < 0.3 → unrelated

pgvector uses `<=>` (cosine distance, which is 1 - cosine_similarity) to find
the K nearest vectors to a query embedding.

**3. Why is the overlap parameter important in chunking?**

Overlap ensures that content near chunk boundaries appears in both adjacent chunks.
Without overlap, a sentence spanning the boundary between chunk N and chunk N+1
would be split — neither chunk contains the complete sentence, making retrieval
unreliable for queries about that content. With 64-token overlap (about 50 words),
boundary content is always retrievable even if the query exactly matches the
boundary sentence.

**4. What is the difference between Precision@K and Recall@K in RAG evaluation?**

Precision@K = "of the K chunks retrieved, how many are relevant?" It measures
whether the retriever is returning useful results. Recall@K = "of all relevant
chunks in the corpus, how many did we retrieve?" It measures whether the retriever
is missing important context. For RAG, you typically optimise for recall (missing
relevant context causes the LLM to give incomplete answers) while monitoring
precision (irrelevant chunks waste context window tokens and confuse the LLM).

**5. What is hallucination in LLMs and how does RAG mitigate it?**

Hallucination is when an LLM confidently generates plausible but factually
incorrect information. It happens because LLMs are trained to generate fluent text
regardless of factual accuracy. RAG mitigates hallucination by: (1) restricting
the LLM to answer only from the retrieved context ("answer ONLY based on the
provided documents"), (2) providing explicit citations so claims can be verified,
and (3) instructing the LLM to say "I don't have information about that" when
no relevant context is found. The LLM becomes a reasoning engine over verified
facts rather than a free-form generator.

---

## 13. Exercises

**Exercise 1 — Add a new runbook and test retrieval**

1. Create `knowledge/runbooks/redis-cache-clear.md`:
   ```markdown
   # Runbook: Redis Cache Clear

   ## When to use
   After a configuration change that affects cached data.

   ## Steps
   1. Connect to Redis: `redis-cli -u $REDIS_URL`
   2. List all keys: `KEYS *`
   3. Clear rate limit keys: `DEL rate:*` (note: resets all rate limits)
   4. Clear JWT revocation list: `DEL revoked_jti:*`
   5. Verify: `DBSIZE` should decrease
   ```

2. Re-seed: `python backend/scripts/seed_knowledge.py`

3. Test retrieval via the API:
   ```bash
   curl -X POST http://localhost:8000/devops/chat \
     -H "Authorization: Bearer $TOKEN" \
     -d '{"question": "How do I clear the Redis cache?"}'
   ```

4. Verify the answer references your new runbook.

**Exercise 2 — Measure the impact of chunk size**

1. Create a test PDF (~10 pages)
2. Upload it via `POST /documents`
3. Query it: `POST /documents/query {"query": "your question"}`
4. Check how many chunks were retrieved and their page numbers
5. Change `RAG_CHUNK_SIZE=256` (smaller chunks) in `.env.local`
6. Re-upload the same document and query again
7. Compare: are the retrieved chunks more or less relevant? More or fewer chunks needed?

**Exercise 3 — Add a new incident report**

Document a real (or hypothetical) incident in the standard format:

```markdown
# INC-004 — Redis Rate Limit Exhaustion

**Date:** 2026-10-01
**Severity:** MEDIUM

## Symptoms
- Rate limiting disabled (fail-open) from 09:15 to 09:47
- All requests succeeded (HTTP 200) — no user impact
- Logs: "Rate limit Redis check failed (fail-open)"

## Root Cause
Upstash Redis daily request limit exceeded at 09:15 UTC.

## Fix
1. Upgrade Upstash plan to 100k commands/day
2. Add alert: RATE_LIMIT_FAILURES_TOTAL > 0 for 5 minutes → warning

## Prevention
Monitor `rate_limited_requests_total` Prometheus metric.
```

Seed it and verify the DevOps Assistant can answer: "What happened with the
Redis rate limiting incident?"

**Exercise 4 — Implement basic retrieval evaluation**

Add a test in `backend/tests/unit/` that evaluates retrieval precision
for 5 known queries against the knowledge base:

```python
EVAL_CASES = [
    {
        "query": "How do we restart the backend service?",
        "expected_sources": ["service-restart.md"],
    },
    {
        "query": "What caused the database connection pool exhaustion?",
        "expected_sources": ["INC-001-db-connection-pool.md"],
    },
    # ... 3 more cases
]

async def test_retrieval_precision():
    for case in EVAL_CASES:
        result = await rag_service.query_documents(
            user_id=TEST_USER,
            query=case["query"],
            top_k=5,
        )
        sources = [c.document_name for c in result.retrieved_chunks]
        matches = [s for s in case["expected_sources"] if s in sources]
        precision = len(matches) / len(sources)
        assert precision >= 0.2, f"Precision too low for: {case['query']}"
```

**Exercise 5 — Understand the embedding space**

Run this script to visualise how similar your knowledge base documents are:

```python
from sentence_transformers import SentenceTransformer
import numpy as np

model = SentenceTransformer("all-MiniLM-L6-v2")

documents = [
    ("runbook", "How to restart the backend service on Cloud Run"),
    ("incident", "Database connection pool was exhausted due to slow LLM calls"),
    ("architecture", "FastAPI + PostgreSQL + ChromaDB backend architecture"),
    ("unrelated", "The best recipes for chocolate chip cookies"),
]

texts = [d[1] for d in documents]
embeddings = model.encode(texts)

# Compute cosine similarities
from numpy.linalg import norm

for i, (cat_i, _) in enumerate(documents):
    for j, (cat_j, _) in enumerate(documents):
        if i >= j:
            continue
        cos_sim = np.dot(embeddings[i], embeddings[j]) / (
            norm(embeddings[i]) * norm(embeddings[j])
        )
        print(f"{cat_i} vs {cat_j}: similarity = {cos_sim:.3f}")
```

Which pairs have the highest similarity? Does the embedding model correctly
identify that the runbook and incident report are more related to each other
than either is to the cookie recipe?
