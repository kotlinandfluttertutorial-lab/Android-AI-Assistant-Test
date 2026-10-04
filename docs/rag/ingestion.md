# RAG Document Ingestion

> **Updated:** 2026-10-03

How uploaded documents become searchable via the RAG pipeline.

---

## Supported formats

| Format | MIME type | Extraction method |
|---|---|---|
| PDF | `application/pdf` | pdfplumber (primary) → pytesseract OCR (fallback for scanned PDFs) |
| DOCX | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` | python-docx |
| TXT | `text/plain` | Direct read (UTF-8 with Latin-1 fallback) |
| Markdown | `text/markdown`, `text/x-markdown` | Direct read (same as TXT; formatting stripped) |

Maximum file size: `MAX_FILE_SIZE_MB` (default 50 MB, configurable).

---

## Ingestion pipeline

```mermaid
flowchart TD
    A[POST /api/v1/documents/upload\nmultipart/form-data] --> B[MIME + extension\nvalidation]
    B --> C[INSERT documents\nstatus=pending]
    C --> D[ingest_document_task.delay\nCelery ingestion queue]
    D --> E[Celery worker]

    E --> F[UPDATE status=processing]
    E --> G[DocumentLoader\nextract_text]
    G --> H[DocumentChunker\nsliding window 512/64]
    H --> I[SentenceTransformerEmbeddingProvider\nencode chunks]
    I --> J[ChromaDB.add_documents\ncollection=documents_{user_id}]
    I --> K[PostgreSQL INSERT document_chunks\nchunk_text + pgvector embedding]
    J --> L[UPDATE status=ready]
    K --> L

    style L fill:#6f6,color:#000
```

### Stage 1: Upload validation

- MIME type checked against allowlist (PDF, DOCX, TXT, MD).
- File extension verified against MIME type.
- Size checked against `MAX_FILE_SIZE_MB`.
- `Document` row inserted with `ingestion_status="pending"`.
- Celery task queued: `ingest_document_task.delay(document_id, user_id)`.
- Response: `{document_id, status="pending"}` — immediate.

### Stage 2: Text extraction (Celery worker)

```python
text = DocumentLoader.load(bytes, mime_type)
```

- **PDF:** pdfplumber extracts text page by page. If a page returns < 10 characters, pytesseract OCR is attempted as fallback.
- **DOCX:** python-docx iterates paragraphs and table cells.
- **TXT/MD:** Raw decode, UTF-8 with Latin-1 fallback.

### Stage 3: Chunking

```python
chunks = DocumentChunker.chunk(text, chunk_size=512, overlap=64)
```

- Tokenised by tiktoken (cl100k_base encoding for accurate token counts).
- Fixed-size sliding window: each chunk is at most 512 tokens.
- 64-token overlap between consecutive chunks preserves context across boundaries.
- Metadata carried per chunk: `chunk_index`, `page_number` (PDF only).

### Stage 4: Embedding

```python
embedding = SentenceTransformerEmbeddingProvider.embed(chunk.text)
# Returns float[384] — 384-dim vector from all-MiniLM-L6-v2
```

- Model loaded once per worker process — ~35 s cold start, ~35 ms per chunk thereafter.
- CPU-only inference (no GPU dependency).
- The production Dockerfile pre-downloads the model to avoid cold-start latency.

### Stage 5: Storage

```python
ChromaVectorStore.add(
    user_id=user_id,
    chunks=[{text, embedding, metadata}],
    collection_name="documents_{user_id}",
)
# Also writes to PostgreSQL document_chunks table via pgvector
```

- ChromaDB collection per user: `documents_{user_id}`.
- PostgreSQL `document_chunks`: `chunk_text`, `page_number`, `embedding` (pgvector `vector(384)`).

### Stage 6: Status update

- `documents.ingestion_status` updated to `"ready"` on success, `"failed"` on error.
- `documents.page_count` updated from PDF page count.
- Error message stored in `documents.error_message` on failure.

---

## Status polling

```
GET /api/v1/documents/{id}/status
Authorization: Bearer <token>
```

**Response:**
```json
{
  "document_id": "abc-123",
  "status": "ready",
  "progress": 1.0,
  "error_message": null
}
```

| Status | Meaning |
|---|---|
| `pending` | Queued, not yet started |
| `processing` | Celery worker is running |
| `ready` | Ingestion complete — document is searchable |
| `failed` | Ingestion failed — see `error_message` |

Recommended polling interval: 3–5 seconds. The `local_smoke_test.py` script polls for up to 30 seconds.

---

## Error handling

| Failure mode | Result | User impact |
|---|---|---|
| MIME type not supported | HTTP 422 before upload | User sees validation error immediately |
| File too large | HTTP 413 before upload | User sees size error immediately |
| Text extraction fails | `status="failed"`, `error_message` set | User can delete and re-upload |
| Embedding model unavailable | `status="failed"` | Model warmup should prevent this; retry possible |
| ChromaDB unreachable | `status="failed"` | Check ChromaDB health |
| Celery worker down | Task stays `pending` | Restart worker; task re-queued automatically |
