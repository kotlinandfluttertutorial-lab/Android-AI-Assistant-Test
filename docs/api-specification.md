# API Specification
## Android AI Assistant — Enterprise Edition

**Base URL:** `https://api.handsonandroid.com/v1`  
**Auth:** All protected endpoints require `Authorization: Bearer <JWT>` header.  
**Content-Type:** `application/json` unless otherwise noted.

---

## Authentication Endpoints

### `POST /auth/register`
Register a new user account.

**Auth:** None

**Request:**
```json
{
  "email": "user@example.com",
  "password": "MySecurePass123!",
  "display_name": "Jane Smith"
}
```

**Response 201:**
```json
{
  "user": { "id": "uuid", "email": "user@example.com", "role": "user" },
  "access_token": "eyJ...",
  "refresh_token": "eyJ...",
  "token_type": "bearer"
}
```

**Errors:** `400` (validation), `409` (email already registered)

---

### `POST /auth/login`
Authenticate with email and password.

**Auth:** None

**Request:**
```json
{ "email": "user@example.com", "password": "MySecurePass123!" }
```

**Response 200:**
```json
{
  "access_token": "eyJ...",
  "refresh_token": "eyJ...",
  "token_type": "bearer",
  "expires_in": 900
}
```

**Errors:** `401` (invalid credentials), `423` (account locked — 5 failed attempts within 10 min)

---

### `POST /auth/refresh`
Exchange a valid refresh token for a new access + refresh token pair (rotation).

**Auth:** None

**Request:**
```json
{ "refresh_token": "eyJ..." }
```

**Response 200:**
```json
{
  "access_token": "eyJ...",
  "refresh_token": "eyJ...",
  "expires_in": 900
}
```

**Errors:** `401` (expired, revoked, or malformed token)

---

### `POST /auth/logout`
Revoke all active refresh tokens for the current session.

**Auth:** Required

**Response 204:** No content

---

### `POST /auth/google`
Exchange a Google OAuth2 authorization code for platform tokens.

**Auth:** None

**Request:** `{ "code": "4/0A..." }`

**Response 200:** Same as `/auth/login`

---

## User Endpoints

### `GET /users/me`
Return the authenticated user's profile.

**Auth:** Required

**Response 200:**
```json
{
  "id": "uuid",
  "email": "user@example.com",
  "display_name": "Jane Smith",
  "role": "user",
  "active_provider": "openai",
  "created_at": "2025-01-01T00:00:00Z"
}
```

---

### `PATCH /users/me`
Update user profile or preferences.

**Auth:** Required

**Request:**
```json
{ "display_name": "Jane D.", "active_provider": "gemini" }
```

**Response 200:** Updated user object

---

## Conversation Endpoints

### `GET /conversations`
List all conversations paginated.

**Auth:** Required  
**Query:** `?page=1&page_size=20&search=<query>`

**Response 200:**
```json
{
  "items": [
    {
      "id": "uuid",
      "title": "Q4 Planning",
      "is_pinned": false,
      "provider": "openai",
      "updated_at": "2025-01-15T10:30:00Z"
    }
  ],
  "total": 142,
  "page": 1,
  "page_size": 20
}
```

---

### `POST /conversations`
Create a new conversation.

**Auth:** Required

**Request:** `{ "title": "New Chat", "provider": "openai" }`

**Response 201:** Conversation object

---

### `GET /conversations/{id}`
Get a single conversation with its messages.

**Auth:** Required (must own conversation)

**Response 200:** Conversation object with `messages` array

**Errors:** `403` (not owner), `404` (not found)

---

### `PATCH /conversations/{id}`
Rename or pin/unpin a conversation.

**Auth:** Required (must own)

**Request:** `{ "title": "Renamed", "is_pinned": true }`

**Response 200:** Updated conversation

---

### `DELETE /conversations/{id}`
Soft-delete a conversation.

**Auth:** Required (must own)

**Response 204:** No content

---

## Chat Endpoints

### `POST /chat/complete`
Non-streaming chat completion (for short prompts or fallback).

**Auth:** Required

**Request:**
```json
{
  "conversation_id": "uuid",
  "message": "Summarise this document",
  "provider": "openai"
}
```

**Response 200:**
```json
{
  "message_id": "uuid",
  "content": "The document covers...",
  "usage": { "input_tokens": 120, "output_tokens": 85 }
}
```

---

## Document (RAG) Endpoints

### `POST /documents/upload`
Upload a document for RAG ingestion.

**Auth:** Required  
**Content-Type:** `multipart/form-data`

**Request:** `file` (binary, max 50 MB), `conversation_id` (optional)

**Response 202:**
```json
{
  "document_id": "uuid",
  "job_id": "uuid",
  "status": "pending"
}
```

**Errors:** `400` (unsupported format), `413` (file too large)

---

### `GET /documents`
List all documents for the authenticated user.

**Auth:** Required

**Response 200:** `{ "items": [...], "total": N }`

---

### `DELETE /documents/{id}`
Delete a document and all its chunks and embeddings.

**Auth:** Required (must own)

**Response 204:** No content (cleanup within 60 seconds)

---

### `POST /documents/{id}/query`
Query a specific document using RAG.

**Auth:** Required (must own document)

**Request:** `{ "question": "What is the revenue in Q4?" }`

**Response 200:**
```json
{
  "answer": "Revenue was $12.4M [1]",
  "citations": [
    { "chunk_index": 3, "page": 12, "document_name": "Q4_Report.pdf", "text": "..." }
  ],
  "usage": { "input_tokens": 890, "output_tokens": 64 }
}
```

---

## Memory Endpoints

### `GET /memory`
List all stored memories for the authenticated user.

**Auth:** Required

**Response 200:**
```json
{
  "items": [
    { "id": "uuid", "content": "Prefers concise answers", "memory_type": "preference", "created_at": "..." }
  ]
}
```

---

### `DELETE /memory/{id}`
Delete a specific memory (removed from ChromaDB within 10 seconds).

**Auth:** Required (must own)

**Response 204:** No content

---

## MCP Tool Endpoints

### `GET /tools`
Discover all registered MCP tools and their schemas.

**Auth:** Required

**Response 200:**
```json
{
  "tools": [
    {
      "name": "github_create_issue",
      "description": "Create a GitHub issue",
      "requires_confirmation": true,
      "parameters": { ... }
    }
  ]
}
```

---

### `POST /tools/{name}/invoke`
Invoke an MCP tool.

**Auth:** Required  
**Note:** Write operations require explicit `confirmed: true` in the request body.

**Request:**
```json
{
  "params": { "repo": "owner/repo", "title": "Bug: login fails", "body": "..." },
  "confirmed": true
}
```

**Response 200:**
```json
{
  "result": { "issue_url": "https://github.com/..." },
  "tool_name": "github_create_issue",
  "status": "success"
}
```

**Errors:** `400` (validation), `403` (write not confirmed), `502` (tool invocation failed)

---

## Productivity Endpoints

### `GET /todos` · `POST /todos` · `PATCH /todos/{id}` · `DELETE /todos/{id}`
CRUD operations on TodoItems. Supports `?completed=true&due_before=<date>` filters.

### `GET /calendar` · `POST /calendar` · `DELETE /calendar/{id}`
CRUD operations on CalendarEvents. Supports `?start=<date>&end=<date>` range filter.

### `GET /reminders` · `POST /reminders` · `PATCH /reminders/{id}` · `DELETE /reminders/{id}`
CRUD operations on Reminders.

### `GET /habits` · `POST /habits` · `DELETE /habits/{id}`
CRUD for HabitDefinitions.

### `POST /habits/{id}/entries`
Log a habit completion entry.

### `GET /habits/{id}/insights`
Get AI-generated insights for a habit (streaming SSE).

---

## Code Analysis Endpoints

### `POST /code/analyze`
Submit source code for AI-powered analysis.

**Auth:** Required

**Request:**
```json
{
  "code": "def factorial(n):\n    return 1 if n <= 1 else n * factorial(n - 1)",
  "language_id": "python",
  "action": "explain"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `code` | string | ✓ | Source code to analyse. Min 1 char, max 100 000 chars. |
| `language_id` | string | ✓ | One of: `kotlin` `java` `python` `javascript` `cpp` `sql` |
| `action` | string | ✓ | One of: `explain` `fix_bug` `generate_tests` |

**Actions:**

| Action | Returns |
|---|---|
| `explain` | Markdown with **What it does**, **How it works**, and **Improvements** sections |
| `fix_bug` | Corrected code with inline `# FIX:` comments on every changed line |
| `generate_tests` | Complete test suite using the standard framework for the language (pytest / JUnit 5 / Jest / etc.), Arrange/Act/Assert pattern |

**Response 200:**
```json
{
  "language_id": "python",
  "original_code": "def factorial(n):\n    return 1 if n <= 1 else n * factorial(n - 1)",
  "action": "explain",
  "content": "## What it does\nComputes factorial recursively...\n\n## How it works\n..."
}
```

| Field | Description |
|---|---|
| `language_id` | Echoed back — used by the Android client for syntax highlighting (Req 12.6) |
| `original_code` | Verbatim code submitted by the client |
| `action` | Echoed back |
| `content` | AI-generated result |

**Errors:**
| Code | Reason |
|---|---|
| `400` | Prompt injection pattern detected in submitted code — `{"error":{"code":"PROMPT_INJECTION_DETECTED"}}` |
| `422` | Invalid `language_id` or `action` value; `code` empty or exceeds 100 000 chars |
| `503` | LLM provider unavailable |
| `504` | LLM call exceeded per-action timeout (30 s for `explain`/`fix_bug`, 45 s for `generate_tests`) |

**Android client binding:** `CodeApiService.kt` → `POST code/analyze` via Retrofit. Wired through `CodeRemoteDataSource` → `CodeRepositoryImpl` → `AnalyzeCodeUseCase` → `CodeViewModel`.

---

## Notification Endpoints

### `POST /notifications/token`
Register or update a Firebase push notification token.

**Auth:** Required

**Request:** `{ "token": "fcm_token_string", "device_id": "uuid" }`

**Response 200:** `{ "registered": true }`

---

## Admin Endpoints (role: `admin` required)

### `GET /admin/users`
List all users with search and pagination.

### `PATCH /admin/users/{id}`
Update user role or active status.

### `DELETE /admin/users/{id}/sessions`
Invalidate all JWTs and refresh tokens for a user.

### `GET /admin/metrics`
Aggregated platform metrics.

### `GET /admin/audit-logs`
Paginated audit log with filters for user, event type, and date range.

### `GET /admin/errors`
Top-10 error types in the last 24 hours with stack trace summaries.

---

## WebSocket — `/ws/chat/{conversation_id}`

**Connection:** `wss://host/ws/chat/{conv_id}?token=<JWT>`

JWT is validated on connection upgrade. Invalid JWT → connection refused (HTTP 401).

### Client → Server Message

```json
{
  "message": "Explain quantum computing",
  "provider": "openai"
}
```

### Server → Client Events

**Token event** (one per LLM token):
```json
{ "type": "token", "data": "Quantum" }
```

**Done event** (stream complete):
```json
{
  "type": "done",
  "usage": { "input_tokens": 45, "output_tokens": 312 }
}
```

**Error event**:
```json
{ "type": "error", "message": "Provider unavailable. Retrying with fallback." }
```

**Tool call event** (MCP tool being invoked):
```json
{
  "type": "tool_call",
  "toolName": "github_create_issue",
  "toolInput": { "repo": "owner/repo", "title": "..." }
}
```

### Reconnection

If the WebSocket connection drops mid-stream, the backend buffers up to 1,000 tokens for 60 seconds. The client should reconnect using exponential backoff (1s → 2s → 4s → 8s → 16s, capped at 30s, max 5 attempts) and will receive buffered tokens on reconnect.

---

## Standard Error Response

All error responses follow this schema:

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "email field is required",
    "details": { ... }
  }
}
```

| HTTP Code | Meaning |
|-----------|---------|
| 400 | Bad request / validation error / prompt injection detected |
| 401 | Missing or invalid JWT |
| 403 | Insufficient role |
| 404 | Resource not found |
| 409 | Conflict (e.g., email already exists) |
| 413 | Request entity too large |
| 423 | Account locked |
| 429 | Rate limit exceeded (60 req/min per user) |
| 500 | Internal server error |
| 502 | External service (LLM / MCP tool) failure |
| 503 | LLM or upstream service temporarily unavailable |
| 504 | LLM call timed out (per-endpoint timeout exceeded) |
