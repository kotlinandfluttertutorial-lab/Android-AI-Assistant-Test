# API Endpoints Reference

**Base URL (production):** `https://ai-assistant-backend-106071012091.asia-south1.run.app`
**Base URL (local):** `http://localhost:8000`
**Authentication:** `Authorization: Bearer <JWT>` on all protected endpoints
**Full docs:** `{BASE_URL}/docs` (Swagger UI)

---

## Health and readiness

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/health` | None | Liveness probe — returns `{"status":"ok"}` |
| GET | `/ready` | None | Readiness probe — checks DB, Redis, env vars |

---

## Authentication (`/api/v1/auth/`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/auth/register` | None | Register with email + password |
| POST | `/auth/login` | None | Login, receive JWT + refresh token |
| POST | `/auth/google` | None | Exchange Google ID token for JWT |
| POST | `/auth/refresh` | Refresh token | Issue a new JWT |
| POST | `/auth/logout` | JWT | Invalidate refresh token |
| DELETE | `/auth/account` | JWT | Delete account and all data |

---

## Chat (`/api/v1/chat/`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/chat` | JWT | Send message, get AI response (non-streaming) |
| WebSocket | `/chat/ws` | JWT (query param) | Streaming chat with token-by-token response |
| GET | `/conversations` | JWT | List user's conversations |
| GET | `/conversations/{id}` | JWT | Get conversation with messages |
| DELETE | `/conversations/{id}` | JWT | Delete conversation |

**POST /chat request:**
```json
{
  "message": "Explain the RAG pipeline",
  "conversation_id": "optional-uuid",
  "provider": "gemini | openai | claude | auto"
}
```

---

## RAG Documents (`/api/v1/documents/`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/documents` | JWT | Upload document (PDF/DOCX/TXT/MD, max 50MB) |
| POST | `/documents/upload` | JWT | Alias for POST /documents |
| GET | `/documents` | JWT | List user's documents |
| POST | `/documents/query` | JWT | Semantic search across all user documents |
| POST | `/documents/{id}/query` | JWT | Semantic search within one document |
| DELETE | `/documents/{id}` | JWT | Delete document, chunks, GCS file, vectors |

**POST /documents/query request:**
```json
{
  "query": "What caused the latency spike?",
  "document_ids": ["optional-uuid-1", "optional-uuid-2"],
  "top_k": 5
}
```

**POST /documents/query response:**
```json
{
  "answer": "The latency spike was caused by... [Source: INC-001.md, Page 1]",
  "citations": [
    {"document_name": "INC-001.md", "page_number": 1, "chunk_index": 0}
  ],
  "context_used": "Retrieved Context:\n--- Chunk 1 [Source: ...]..."
}
```

---

## Jobs (`/api/v1/jobs/`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/jobs/{job_id}` | JWT | Poll ingestion job status |

**Response status values:** `queued` → `running` → `completed` | `failed`

---

## Admin (`/api/v1/admin/`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/admin/users` | JWT + Admin role | List all users |
| POST | `/admin/rag/reindex` | JWT + Admin role | Re-index all knowledge base documents |
| PUT | `/admin/privacy/epsilon` | JWT + Admin role | Update differential privacy epsilon |

---

## AI / Analysis (`/api/v1/`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/code/analyze` | JWT | Analyze code for bugs, style, security |
| POST | `/translation/translate` | JWT | Translate text |
| POST | `/transcription/transcribe` | JWT | Transcribe audio |
| POST | `/suggestions/context` | JWT | Context-aware suggestions |
| POST | `/productivity/calendar/suggest-times` | JWT | Suggest meeting times |

---

## Rate limits

All authenticated endpoints are rate-limited per user via Redis:
- Default: 60 requests/minute
- Chat: 20 requests/minute
- Upload: 10 requests/minute

Rate limit headers returned on every response:
- `X-RateLimit-Limit: 60`
- `X-RateLimit-Remaining: 58`
- `X-RateLimit-Reset: 1735000000`

HTTP 429 is returned when the limit is exceeded.

---

## Error response format

All errors follow this structure:
```json
{
  "detail": "Human-readable error message",
  "code": "machine_readable_error_code"
}
```

Common error codes:
- `invalid_credentials` — wrong email/password
- `token_expired` — JWT is past its expiry
- `rate_limit_exceeded` — too many requests
- `file_too_large` — upload exceeds 50 MB
- `unsupported_format` — file type not allowed
- `document_not_found` — document ID not found or not owned by user
