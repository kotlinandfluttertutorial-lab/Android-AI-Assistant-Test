# RAG Pipeline Architecture

**Last updated:** 2026-08-26

---

## Overview

RAG (Retrieval-Augmented Generation) lets the AI answer questions about documents
it has never been trained on by retrieving relevant text chunks at query time and
injecting them into the LLM prompt as context.

```
Documents (PDF, DOCX, TXT, MD)
         │
         ▼ INGESTION PIPELINE
    Validate format + size
         │
         ▼
    Store raw file → GCS (android-ai-assistant-89cec-files)
         │
         ▼
    Extract text (pypdf / python-docx / UTF-8 decode / OCR fallback)
         │
         ▼
    Chunk text (tiktoken, 512 tokens, 64 overlap)
         │
         ▼
    Embed chunks (SentenceTransformer: all-MiniLM-L6-v2, 384-dim)
         │
         ▼
    Store vectors → ChromaDB collection: documents_{user_id}
    Store metadata → PostgreSQL: document_chunks table
         │
         ▼ RETRIEVAL PIPELINE (at query time)
    Embed query (same all-MiniLM-L6-v2 model)
         │
         ▼
    Cosine similarity search → ChromaDB (top-K=5 chunks)
         │
         ▼
    Fetch metadata → PostgreSQL (document name, page number)
         │
         ▼
    Assemble context string with citations
         │
         ▼
    LLM prompt: "Use the following context... [CONTEXT] Question: [QUERY]"
         │
         ▼
    LLM response with inline citations
         │
         ▼
    Return: answer + citations list + context_used
```

---

## Ingestion details

### File validation (before any storage)

- Allowed extensions: `.pdf`, `.docx`, `.txt`, `.md`
- Max file size: 50 MB (configurable via `MAX_FILE_SIZE_MB`)
- MIME type checked from `Content-Type` header AND file extension
- HTTP 422 returned immediately if validation fails — no I/O performed

### Text extraction

| Format | Library | Notes |
|--------|---------|-------|
| PDF | pypdf | Native text extraction; OCR fallback via pytesseract for scanned pages |
| DOCX | python-docx | Paragraph-level extraction |
| TXT / MD | UTF-8 decode | Falls back to latin-1 if UTF-8 fails |

### Chunking

- Tokenizer: tiktoken `gpt-3.5-turbo` encoding (same vocabulary as GPT models)
- Chunk size: 512 tokens (configurable: `RAG_CHUNK_SIZE`)
- Overlap: 64 tokens (configurable: `RAG_CHUNK_OVERLAP`)
- Min chunk: 64 tokens; max: 2048 tokens
- Algorithm: sliding window — every token appears in at least one chunk
- TXT/MD chunks store character offsets for citations (no page numbers)
- PDF/DOCX chunks store page numbers

### Embedding model

- Model: `all-MiniLM-L6-v2` (sentence-transformers)
- Dimension: 384 floats per vector
- Speed: ~1000 sentences/second on CPU
- Loaded once at startup; warmed up via dummy encode to avoid cold start
- CPU-only — no GPU required

### Storage

**ChromaDB:**
- Collection per user: `documents_{user_id}` (user isolation)
- Shared collection: `devops_knowledge` (operational knowledge base)
- Metadata per chunk: `document_id`, `chunk_index`, `page_number`, `citation_type`
- Storage: ephemeral on Cloud Run (wiped per revision)

**PostgreSQL (`document_chunks` table):**
- `document_id` → foreign key to `documents` table
- `chunk_index` → position within document
- `chroma_id` → reference to ChromaDB vector
- `content` → raw chunk text
- `page_number`, `citation_type`, `char_offset_start`, `char_offset_end`

---

## Retrieval details

### Query embedding

The same `all-MiniLM-L6-v2` model embeds the user's query. Using the same model
for both ingestion and retrieval is critical — different models produce
incompatible vector spaces.

### Similarity search

- ChromaDB cosine similarity search
- `n_results=top_k` (default 5, configurable per request up to 20)
- Optional `where` filter for specific `document_ids`
- Results ranked by similarity score (closest first)

### Context assembly

```
Query: Why is the API slow?

Retrieved Context:

--- Chunk 1 [Source: runbook.md, Page 1] ---
The API latency spike was caused by connection pool exhaustion. When the
pool is exhausted, new requests queue and eventually timeout...

--- Chunk 2 [Source: INC-001.md, Page 1] ---
Root cause: LLM calls holding DB connections open for 15-45 seconds...
```

### LLM prompt structure

```
System: "You are a helpful DevOps assistant. Use only the provided context
         to answer questions. Always cite the source document and page number."

Context: [assembled context string with citation markers]

User: [original query]

Instructions: "Provide a concise answer. Include [Source: doc, Page N] citations."
```

---

## ChromaDB ephemeral storage and re-indexing

Cloud Run's filesystem is wiped on each new revision. This means the ChromaDB
vector index is empty after every deployment.

**Re-indexing strategy:**
1. On startup, `seed_knowledge.py` re-indexes all documents in `knowledge/`
2. User documents are re-ingested via `POST /api/v1/rag/reindex` (admin endpoint)
3. For large document sets, consider Cloud Storage FUSE volume mount for persistence

**Why ephemeral is acceptable at portfolio scale:**
- `knowledge/` has ~15 documents → seeds in < 30 seconds
- User documents are still in PostgreSQL and GCS → can be re-ingested on demand
- Production upgrade path: Weaviate Cloud or Pinecone for persistent managed vectors

---

## DevOps knowledge collection

A separate ChromaDB collection `devops_knowledge` is populated from `knowledge/`.
The AI DevOps Assistant queries this collection to answer questions like:
- "How do I restart the backend service?"
- "What caused INC-001?"
- "What is the RAG pipeline architecture?"

This collection is populated by `backend/scripts/seed_knowledge.py` and is
re-indexed on every deployment.
