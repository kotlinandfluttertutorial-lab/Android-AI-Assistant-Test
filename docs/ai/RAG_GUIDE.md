# Phase 9 — RAG (Retrieval-Augmented Generation) Guide

> **Learning goal:** Understand why RAG exists, how the complete pipeline works
> in this project, and how to reason about retrieval quality, hallucination, and
> evaluation.
>
> **Career connection:** RAG is the core pattern behind every production AI system
> that needs to answer questions about private or up-to-date data. Every GenAI
> engineer role asks about it.

---

## 1. Concept — What Is RAG and Why Does It Exist?

Large Language Models (LLMs) have two fundamental limitations:

1. **Knowledge cutoff** — trained on data up to a date; know nothing after it
2. **Context window** — can only process ~128k tokens at once; cannot "know" an
   entire codebase, all your runbooks, or years of incident history

**RAG solves both** by fetching relevant information at query time and injecting
it into the LLM prompt as context:

```
Without RAG:
  User: "What caused INC-001?"
  LLM:  "I don't know what INC-001 refers to." (or hallucinates an answer)

With RAG:
  User:      "What caused INC-001?"
  Retriever: [finds INC-001.md in ChromaDB, returns the root cause section]
  LLM:       "According to your incident report, INC-001 was caused by
              connection pool exhaustion. [Source: INC-001.md, Page 1]"
```

The key insight: **the LLM's job is language understanding and generation, not
memory storage.** RAG separates what the model says from what the model knows.

---

## 2. Why RAG Instead of Fine-Tuning?

Both RAG and fine-tuning can teach an LLM about your private data. They serve
different purposes:

| Dimension | RAG | Fine-tuning |
|-----------|-----|------------|
| Update data | Change a file, re-index in seconds | Retrain the model (hours/days) |
| Citeable | Yes — every answer has a source | No — model "absorbs" knowledge |
| Cost | Cheap (vector search + one LLM call) | Expensive (GPU training time) |
| Hallucination risk | Lower (grounded in retrieved text) | Higher (model may confuse facts) |
| Best for | Changing docs, runbooks, incidents | Tone, style, domain vocabulary |

For operational knowledge that changes frequently (runbooks, incidents, architecture
docs), **RAG is almost always the right choice.**

---

## 3. Architecture — How This Project's RAG Works

```
INGESTION (happens once, offline)
────────────────────────────────────────────────────────────────────────
knowledge/*.md                          User uploads (PDF, DOCX, TXT, MD)
      │                                              │
      ▼                                              ▼
seed_knowledge.py                       POST /api/v1/documents
      │                                              │
      └──────────────────┬──────────────────────────┘
                         ▼
              Text Extraction
              (pypdf / python-docx / UTF-8)
                         │
                         ▼
              Tiktoken Chunking
              (512 tokens, 64 overlap)
                         │
                         ▼
              SentenceTransformer Embedding
              (all-MiniLM-L6-v2 → 384-dim vector)
                         │
                 ┌───────┴───────┐
                 ▼               ▼
           ChromaDB          PostgreSQL
      (vectors + metadata)  (content + citations)

RETRIEVAL (happens on every query)
────────────────────────────────────────────────────────────────────────
User query
      │
      ▼
Embed query (same model → 384-dim vector)
      │
      ▼
ChromaDB cosine similarity search
(top-K = 5 most similar chunks)
      │
      ▼
PostgreSQL lookup
(get document name, page number, content)
      │
      ▼
Assemble context string with citations
      │
      ▼
LLM prompt:
  "Use the following context... [CONTEXT] Question: [QUERY]"
      │
      ▼
LLM response with inline citations
      │
      ▼
Return: answer + citations list + context_used
```

---

## 4. Key Concepts

### Embedding

An embedding is a fixed-length numeric vector that represents the semantic
meaning of text. Semantically similar texts have vectors that are close together
in vector space.

```python
# "The dog ran fast" and "The canine moved quickly"
# are semantically similar — their embeddings are close

from sentence_transformers import SentenceTransformer
model = SentenceTransformer("all-MiniLM-L6-v2")

vec1 = model.encode("The dog ran fast")         # [0.12, -0.34, 0.71, ...]  384 numbers
vec2 = model.encode("The canine moved quickly") # [0.11, -0.33, 0.70, ...]  very similar
vec3 = model.encode("Stock market closed up")   # [-0.45, 0.23, -0.12, ...] very different
```

**Critical rule:** The same model must be used for ingestion AND retrieval.
Using different models produces incompatible vector spaces — queries will return
nonsensical results.

### Chunking

Documents are split into overlapping chunks before embedding. Why?

1. **Context window limit** — embedding models have a token limit (512 for all-MiniLM-L6-v2)
2. **Precision** — embedding 512 tokens captures the meaning of one idea; embedding
   50,000 tokens averages out into noise
3. **Granularity** — you want to retrieve the paragraph about "connection pool" not
   the entire 40-page incident report

**Overlap** (64 tokens in this project) means adjacent chunks share content at
their boundaries. This prevents a sentence from being split across chunks with
neither chunk capturing its full meaning:

```
Text:    [... A ... B ... C ... D ... E ...]

No overlap:   [A B C D]  [E F G H]       ← "D E" boundary is lost
With overlap: [A B C D]  [C D E F]       ← "D" appears in both chunks
```

### Cosine Similarity

ChromaDB ranks chunks by cosine similarity — how "close" the query vector is to
each chunk vector in 384-dimensional space.

```
similarity = cos(θ) = (query · chunk) / (|query| × |chunk|)

Range: -1 (opposite) to 1 (identical)
Typical useful range: 0.5 – 0.95 for RAG retrieval
```

### Top-K Retrieval

`top_k=5` means "return the 5 most similar chunks." This is the primary knob
for controlling retrieval quality:

- **Too low (k=1):** single point of failure — if the best chunk doesn't have the
  answer, the LLM has nothing to work with
- **Too high (k=20):** noisy context — weakly related chunks confuse the LLM
- **5 is a good default** for documents in the hundreds-of-pages range

### Context window

The assembled context (k chunks × ~512 tokens each) + the query + the LLM
instructions must fit within the LLM's context window. For GPT-4o (128k tokens),
5 × 512 = 2560 tokens of context is trivial. For smaller models, keep k low.

### Citations

Every retrieved chunk knows which document and page it came from. Good RAG
systems always include citations so users can verify answers:

```
"The connection pool exhaustion occurred because LLM calls held DB connections
open for 15–45 seconds. [Source: INC-001-db-connection-pool.md, Page 1]"
```

Citations serve three purposes:
1. Allow users to verify the answer against the source
2. Make hallucination obvious — a false citation is immediately checkable
3. Give the LLM grounding — "say where you got this" reduces confabulation

### Hallucination

Hallucination is when an LLM generates plausible but false information. RAG
reduces (but does not eliminate) hallucination by providing ground truth in the
prompt. The LLM can still:
- Mis-summarise a retrieved chunk
- Blend two chunks in a contradictory way
- Invent a detail not in the context

This is why every response includes `context_used` — the caller can verify the
answer against the raw retrieved text.

---

## 5. Implementation — Key Files

| File | What it does |
|------|-------------|
| `backend/app/services/rag_service.py` | Complete ingestion + retrieval pipeline |
| `backend/app/workers/rag_worker.py` | Celery async ingestion with retries |
| `backend/app/api/rag/router.py` | REST endpoints (upload, query, delete) |
| `backend/scripts/seed_knowledge.py` | Seed DevOps knowledge base into ChromaDB |
| `knowledge/` | Runbooks, incidents, architecture, deployment docs |
| `feature-rag/` | Android document list, upload UI, per-document chat |

### How chunking is configured

Settings in `backend/app/config/settings.py`:
```python
RAG_CHUNK_SIZE    = 512   # tokens per chunk
RAG_CHUNK_OVERLAP = 64    # overlapping tokens between consecutive chunks
RAG_TOP_K         = 5     # default number of chunks to retrieve
```

Override at query time via `top_k` in the request body (range 1–20).

### How ChromaDB collections are named

```python
# User documents — one collection per user
collection_name = f"documents_{user_id}"

# DevOps knowledge base — one shared collection
collection_name = "devops_knowledge"
```

User isolation is enforced at the collection level: no user can accidentally
query another user's documents.

---

## 6. Evaluation — How Do You Know RAG Is Working?

RAG evaluation has two distinct dimensions:

### Retrieval quality (Is the right content being retrieved?)

**Precision@K:** Of the K chunks retrieved, what fraction are actually relevant?

```python
# Example: query = "What caused INC-001?"
# Retrieved: [INC-001.md chunk, runbook.md chunk, architecture.md chunk, ...]
# Relevant:  [INC-001.md chunk, runbook.md chunk]
# Precision@3 = 2/3 = 0.67
```

**Recall@K:** Of all relevant chunks in the corpus, what fraction did we retrieve?

**Practical check:** Run known queries and manually verify the top-5 chunks are
relevant. For this project's knowledge base:

```bash
# Start ChromaDB locally
docker-compose up chromadb -d

# Seed the knowledge base
python backend/scripts/seed_knowledge.py

# Test retrieval
python - <<'EOF'
import chromadb
from sentence_transformers import SentenceTransformer

model = SentenceTransformer("all-MiniLM-L6-v2")
client = chromadb.HttpClient(host="localhost", port=8001)
collection = client.get_collection("devops_knowledge")

query = "What caused the database connection pool to be exhausted?"
embedding = model.encode([query])[0].tolist()

results = collection.query(query_embeddings=[embedding], n_results=3)
for i, (doc, meta) in enumerate(zip(results["documents"][0], results["metadatas"][0])):
    print(f"--- Chunk {i+1} [{meta['source']}] ---")
    print(doc[:200])
    print()
EOF
```

Expected: Chunk 1 should be from `incidents/INC-001-db-connection-pool.md`.

### Generation quality (Is the LLM answering correctly from the context?)

**Faithfulness:** Does the answer only use information from the retrieved context?

```python
# Check: Is every claim in the answer supported by context_used?
# If the answer says "X" but context_used doesn't mention "X",
# that's a hallucination.
```

**Answer relevance:** Does the answer address the original question?

**Practical check:** Compare `answer` with `context_used` in the API response.
The answer should be a summary/synthesis of content present in `context_used`.

---

## 7. Debug — Common Issues

### ChromaDB returns empty results after deploy

**Cause:** Cloud Run filesystem is ephemeral — wiped on each new revision.

**Fix:**
```bash
# Option A — re-run seed script
python backend/scripts/seed_knowledge.py

# Option B — call admin endpoint
curl -X POST https://ai-assistant-backend-106071012091.asia-south1.run.app/api/v1/admin/rag/reindex \
  -H "Authorization: Bearer YOUR_ADMIN_JWT"
```

**Prevention:** `seed_async()` is called in `main.py` lifespan — it runs
automatically on every startup. If ChromaDB is still cold-starting at that
moment, it logs a warning and continues. The admin endpoint exists as a manual
fallback.

### Retrieval returns wrong documents

**Cause:** The query embedding and the chunk embeddings use different models.

**Verify:** Check `seed_knowledge.py` and `rag_service.py` both use
`all-MiniLM-L6-v2`. Never change the embedding model for a collection — if
you switch models, delete the collection and re-index from scratch.

### Answers are factually wrong

**Cause:** Hallucination — the LLM is not staying grounded in the context.

**Debug:** Check `context_used` in the response. If the context contains the
correct information but the answer is wrong, the LLM prompt needs stronger
grounding instructions.

**Current prompt:**
```python
"Use the following retrieved context to answer the user's question.
Always cite the source document and page number in your answer."
```

Consider adding: "Only use information from the retrieved context. If the context
does not contain the answer, say so explicitly."

### Chunking produces too many tiny chunks

**Cause:** Document has very short paragraphs (e.g. a table-heavy file).

**Fix:** Increase `RAG_CHUNK_SIZE` in settings or pass `chunk_size=1024` per query.
Chunks below `min_chunk_size=64` tokens are automatically dropped.

---

## 8. Interview Questions

**Q1: What is RAG? When would you use it over fine-tuning?**

RAG (Retrieval-Augmented Generation) combines a retrieval step with an LLM.
At query time, relevant documents are fetched from a vector store and injected
into the LLM's context window. The LLM generates an answer grounded in that
retrieved content.

Use RAG when the knowledge changes frequently (runbooks, incidents, API docs),
when you need citeable answers, or when training cost is a constraint.
Use fine-tuning when you need to change the model's tone, style, or vocabulary,
or when the knowledge is stable and the dataset is large enough to justify
training compute.

In practice, production systems often use both: fine-tune for domain vocabulary,
RAG for current facts.

---

**Q2: What is the difference between semantic search and keyword search?**

Keyword search matches exact words. If you search for "DB timeout" and the
document says "database connection refused," keyword search misses it.

Semantic search embeds both the query and the documents into a shared vector
space where similar meanings cluster together. "DB timeout" and "database
connection refused" embed close to each other, so semantic search finds the
relevant document even without keyword overlap.

This is why RAG uses embedding-based retrieval rather than full-text search for
knowledge-base Q&A.

---

**Q3: Why does chunk overlap matter?**

Without overlap, a sentence at the boundary of two chunks appears in neither
chunk completely — its meaning is split across the boundary. Overlap ensures
that context at chunk boundaries is captured by at least one chunk.

The trade-off: larger overlap increases the number of chunks (more storage, more
retrieval time) and increases redundancy. A 12% overlap (64/512 tokens) is a
good default — enough to preserve boundary context without excessive duplication.

---

**Q4: What is a vector database? How does it differ from a relational database?**

A vector database stores high-dimensional numeric vectors and supports
approximate nearest-neighbour (ANN) search — finding the K vectors most similar
to a query vector by cosine similarity or Euclidean distance.

A relational database stores structured rows and columns and supports exact
matching, range queries, and joins via SQL.

They complement each other in a RAG system: ChromaDB stores the embedding
vectors and returns the most similar chunk IDs; PostgreSQL stores the content,
document metadata, and citation information for those chunk IDs.

---

**Q5: How do you prevent prompt injection in a RAG system?**

Prompt injection occurs when user-controlled input in the prompt causes the LLM
to ignore its instructions. In RAG, the attack surface includes both the query
and the document content.

This project's mitigations:
1. `sanitize_user_string()` applied to all query inputs before they reach the LLM
2. Retrieved context is injected as a labelled block ("Retrieved Context:") not
   inline with the instructions
3. LLM instructions are in the system prompt, not the user turn
4. Output validation: answer must not contain certain patterns (credentials, etc.)

---

**Q6: How do you evaluate retrieval quality without labeled data?**

When you don't have a labeled set of (query, relevant chunks) pairs:

1. **Spot check** — manually run 20 representative queries and judge whether the
   top-3 retrieved chunks are relevant
2. **Synthetic queries** — use an LLM to generate questions from each chunk, then
   verify each question retrieves its source chunk in top-3
3. **RAGAS framework** — automatic faithfulness and relevance scoring using an
   LLM-as-judge approach
4. **Downstream task accuracy** — measure end-user task success rate before/after
   RAG changes

For this project, the minimal viable check is: run the 10 known queries from the
`knowledge/` documents and verify each returns chunks from the correct source
file.

---

## 9. Exercise

After running `docker-compose up chromadb -d` and seeding the knowledge base:

1. **Test exact retrieval** — query for "How do I restart the backend service?"
   and verify Chunk 1 is from `runbooks/service-restart.md`.

2. **Test cross-document retrieval** — query "Why did the error rate spike?"
   and verify chunks from both `incidents/INC-001-db-connection-pool.md` and
   `runbooks/service-restart.md` appear in the top 5.

3. **Add a new document** — create `knowledge/runbooks/celery-worker.md` with
   instructions for restarting the Celery worker. Run `seed_knowledge.py` and
   verify the document appears in results for "restart background tasks".

4. **Verify idempotency** — run `seed_knowledge.py` twice in a row. The second
   run should complete without errors and `collection.count()` should be the
   same as after the first run.

5. **Call the admin endpoint** — get an admin JWT, then call:
   ```bash
   curl -X POST http://localhost:8000/api/v1/admin/rag/reindex \
     -H "Authorization: Bearer YOUR_ADMIN_JWT" | jq .
   # Expected: {"status":"ok","files_indexed":14,"chunks_indexed":...,"errors":[]}
   ```

---

## Phase 9 Summary

**What was built this phase:**

```
knowledge/
├── runbooks/     4 docs  — service-restart, database-recovery, scaling, rollback
├── incidents/    3 docs  — INC-001 (DB pool), INC-002 (LLM timeout), INC-003 (Chroma cold start)
├── architecture/ 3 docs  — system-overview, rag-pipeline, api-endpoints
└── deployment/   3 docs  — cloud-run-deploy, secrets-management, migrations

backend/scripts/seed_knowledge.py
  — tiktoken chunking, all-MiniLM-L6-v2 embeddings
  — deterministic IDs, idempotent, devops_knowledge collection

POST /api/v1/admin/rag/reindex
  — HTTP trigger for reseeding from a running service

main.py lifespan
  — auto-seeds on every startup (non-fatal if ChromaDB is unavailable)
```

**What was already complete (existing RAG system):**
- `RAGService` — validate, extract, chunk, embed, store, query
- Per-user ChromaDB collections (`documents_{user_id}`)
- All REST endpoints (upload, list, query, delete)
- Celery ingestion worker with retry/backoff
- Android feature-rag UI (document list, upload, per-document chat)

**Next phase:** Phase 10 — AI Error Analysis.
Say `NEXT` to continue.
