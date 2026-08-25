# Skill: RAG Pipeline

## Purpose
Extend or debug the Retrieval-Augmented Generation (RAG) pipeline that powers the
`feature-rag` module (Android) and `rag_service.py` (backend). Covers document
ingestion, chunking, embedding, vector storage (ChromaDB), semantic retrieval, and
prompt augmentation inside `AIOrchestrator`.

## When to Use
- Adding support for a new document type (e.g. `.pptx`, `.csv`)
- Tuning chunk size or retrieval top-K
- Changing the embedding model
- Debugging empty or irrelevant retrieval results
- Adding citation support to RAG responses
- Wiring a new frontend upload flow to the existing backend pipeline

---

## End-to-End Pipeline

```
Android (feature-rag)
    User picks file  →  POST /api/rag/documents  (multipart)
                                ↓
Backend
    RagRouter  →  RagService.ingest_document()
        ↓
    [Celery task: queue=ingestion]
        ↓
    1. Read file bytes (MinIO upload for storage)
        ↓
    2. Extract text
       ├─ PDF      → pypdf
       ├─ DOCX     → python-docx
       ├─ Image    → Pillow + pytesseract OCR
       └─ TXT/MD   → direct read
        ↓
    3. Chunk text  (RecursiveCharacterTextSplitter, chunk_size=1000, overlap=200)
        ↓
    4. Embed chunks  (SentenceTransformer: all-MiniLM-L6-v2, 384-dim)
        ↓
    5. Upsert to ChromaDB  (collection per user_id)
        ↓
    6. Persist DocumentEntity in PostgreSQL  (status: ready)
        ↓
    Android polls GET /api/rag/documents/{id}  until status == "ready"

── At query time ──────────────────────────────────────────────────────────────

Android (feature-chat, feature-rag)
    User sends message  →  WebSocket /ws/chat/{conversationId}
                                ↓
Backend  AIOrchestrator.stream_chat()
    1. Embed user query  (same SentenceTransformer model)
        ↓
    2. ChromaDB.query(user_collection, n_results=3)
        ↓
    3. Inject retrieved chunks into system prompt as:
       "Relevant context:\n{chunk1}\n{chunk2}\n{chunk3}"
        ↓
    4. Continue normal LLM call with augmented prompt
```

---

## Backend Files

| File | Responsibility |
|---|---|
| `backend/app/services/rag_service.py` | Ingestion + query logic |
| `backend/app/api/rag/router.py` | REST endpoints |
| `backend/app/workers/tasks/rag_tasks.py` | Celery async ingestion task |
| `backend/app/models/document.py` | `Document` ORM model |
| `backend/app/repositories/document_repository.py` | DB access for documents |

---

## Android Files

| File | Responsibility |
|---|---|
| `feature-rag/` | Upload UI, document list, Q&A screen |
| `domain/usecase/document/` | `UploadDocumentUseCase`, `GetDocumentsUseCase`, `DeleteDocumentUseCase` |
| `data/remote/rag/RagApiService.kt` | Retrofit multipart upload + document queries |
| `data/repository/DocumentRepositoryImpl.kt` | Remote + local sync |
| `core-database/entity/DocumentEntity.kt` | Local cache of document metadata |

---

## Adding a New Document Type

### Backend

1. Add a new extraction branch in `rag_service.py`:

```python
async def _extract_text(self, file_bytes: bytes, mime_type: str) -> str:
    if mime_type == "application/pdf":
        return self._extract_pdf(file_bytes)
    elif mime_type in ("application/vnd.ms-powerpoint",
                       "application/vnd.openxmlformats-officedocument.presentationml.presentation"):
        return self._extract_pptx(file_bytes)   # NEW
    # ... existing branches ...
    raise ValueError(f"Unsupported MIME type: {mime_type}")

def _extract_pptx(self, file_bytes: bytes) -> str:
    from pptx import Presentation
    from io import BytesIO
    prs = Presentation(BytesIO(file_bytes))
    text_parts = []
    for slide in prs.slides:
        for shape in slide.shapes:
            if shape.has_text_frame:
                text_parts.append(shape.text_frame.text)
    return "\n".join(text_parts)
```

2. Add `python-pptx` to `backend/requirements.txt` (pinned version).
3. Add the MIME type to the router's validation allowlist.

### Android

4. Update `RagApiService.kt` if the MIME type needs to be passed in the request.
5. Update the file picker in `feature-rag` to include the new extension.

---

## Chunking Configuration

Current defaults in `rag_service.py`:
```python
CHUNK_SIZE    = 1_000   # characters
CHUNK_OVERLAP = 200     # characters overlap between consecutive chunks
```

Tuning guidance:
- Smaller chunks (500) → more precise retrieval, more ChromaDB entries
- Larger chunks (2000) → more context per chunk, fewer results needed
- Overlap prevents information loss at chunk boundaries

---

## Embedding Model

Current: `sentence-transformers/all-MiniLM-L6-v2` (384-dimensional, CPU-only)

Model is warmed up at FastAPI startup in `main.py`:
```python
def _warmup() -> None:
    model = _rag_service._get_embedding_model()
    model.encode(["warmup"], show_progress_bar=False)
```

To change the model:
1. Update `RAG_EMBEDDING_MODEL` in `app/config/settings.py`.
2. Ensure the new model's output dimension matches the ChromaDB collection dimension.
3. **Re-ingest all existing documents** — embeddings are not compatible across models.
4. Consider a rolling migration that keeps both models active during the transition.

---

## ChromaDB Collection Convention

Collections are scoped per user to prevent cross-user data leakage:

```python
collection_name = f"user_{user_id}"
```

ChromaDB 1.5.9 is bound to `127.0.0.1:8001` in `docker-compose.yml` (does not
expose port to network) to mitigate CVE-2026-45829.

---

## Retrieval in `AIOrchestrator`

```python
# In build_prompt_context():
try:
    chunks = await self._memory_service.search_documents(
        user_id=user_id,
        query=user_message,
        n_results=3,
    )
    if chunks:
        rag_context = "Relevant context from your documents:\n" + \
                      "\n---\n".join(chunk.text for chunk in chunks)
        prompt_messages.insert(1, PromptMessage(role="system", content=rag_context))
except Exception:
    # Graceful degradation — RAG failure does not block the chat (Req 7.2)
    logger.warning("RAG retrieval failed; continuing without context")
```

Always wrap retrieval in a try/except and continue without RAG on failure.

---

## Android Upload Flow

```kotlin
// domain/usecase/document/UploadDocumentUseCase.kt
class UploadDocumentUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
) {
    suspend operator fun invoke(
        uri: Uri,
        fileName: String,
        mimeType: String,
    ): ApiResult<Document> = documentRepository.uploadDocument(uri, fileName, mimeType)
}
```

The ViewModel polls document status using `observeDocumentById(id).collect { }` via
Room's `Flow` — the repository syncs status from the API via WorkManager.

---

## Monitoring Ingestion Jobs

Celery worker processes the `ingestion` queue. Monitor via:
- Docker: `docker logs <celery_worker_container> -f`
- Flower (if deployed): `http://localhost:5555`
- Grafana: `rag_ingestion_duration_seconds` histogram

Failed ingestion tasks update `Document.status = "error"` in PostgreSQL so the
Android client can display an error state.

---

## Checklist

- [ ] New document type extraction added in `_extract_text()`
- [ ] New library pinned in `requirements.txt`
- [ ] MIME type added to the router allowlist
- [ ] ChromaDB collection scoped to `user_{user_id}`
- [ ] RAG retrieval wrapped in try/except (graceful degradation)
- [ ] `Document.status` updated to `"ready"` or `"error"` after Celery task completes
- [ ] Android polls document status via `Flow` from Room (not busy-wait)
- [ ] Re-ingestion plan documented if embedding model changes
- [ ] Integration test covers upload → ingest → query path
- [ ] ChromaDB only bound to `127.0.0.1` (not `0.0.0.0`)
