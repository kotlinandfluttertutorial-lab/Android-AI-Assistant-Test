# API Specification
## Android AI Assistant — Enterprise Edition

**Base URL:** `https://api.handsonandroid.com/api/v1`  
**Auth:** All protected endpoints require `Authorization: Bearer <JWT>` header.  
**Content-Type:** `application/json` unless otherwise noted.  
**Rate Limit:** 60 requests/minute per authenticated user; 20 requests/minute per IP on public
endpoints. HTTP 429 with `Retry-After` header on breach.

---

## Standard Error Response

All error responses follow this schema:

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "email field is required",
    "details": {}
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
| 422 | Unprocessable entity (semantic validation failure) |
| 423 | Account locked |
| 429 | Rate limit exceeded |
| 500 | Internal server error |
| 502 | External service (LLM / MCP tool) failure |

---

## Auth Endpoints

### `POST /auth/register`

Register a new user account.

**Auth:** None

**Request:**
```json
{
  "email": "jane@example.com",
  "password": "MySecurePass123!",
  "display_name": "Jane Smith"
}
```

**Response 201:**
```json
{
  "user": { "id": "550e8400-e29b-41d4-a716-446655440000", "email": "jane@example.com", "role": "user" },
  "access_token": "eyJhbGciOiJIUzI1NiJ9...",
  "refresh_token": "eyJhbGciOiJIUzI1NiJ9...",
  "token_type": "bearer",
  "expires_in": 900
}
```

**Errors:** `400` (validation), `409` (email already registered)

---

### `POST /auth/login`

Authenticate with email and password.

**Auth:** None

**Request:**
```json
{ "email": "jane@example.com", "password": "MySecurePass123!" }
```

**Response 200:**
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiJ9...",
  "refresh_token": "eyJhbGciOiJIUzI1NiJ9...",
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
{ "refresh_token": "eyJhbGciOiJIUzI1NiJ9..." }
```

**Response 200:**
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiJ9...",
  "refresh_token": "eyJhbGciOiJIUzI1NiJ9...",
  "expires_in": 900
}
```

**Errors:** `401` (expired, revoked, or replayed token — replays revoke entire token family)

---

### `POST /auth/logout`

Revoke all active refresh tokens for the current session.

**Auth:** Required

**Response 204:** No content

---

### `POST /auth/google`

Exchange a Google OAuth2 authorization code for platform tokens. Maps Google account to
local user record on first sign-in.

**Auth:** None

**Request:** `{ "code": "4/0AX4XfWg..." }`

**Response 200:** Same schema as `POST /auth/login`

---

## User Endpoints

### `GET /users/me`

Return the authenticated user's profile.

**Auth:** Required

**Response 200:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "jane@example.com",
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

**Request:** `{ "display_name": "Jane D.", "active_provider": "gemini" }`

**Response 200:** Updated user object (same schema as `GET /users/me`)

---

## Conversation Endpoints

### `GET /conversations`

List conversations paginated, sorted by `updated_at` descending.

**Auth:** Required  
**Query:** `?page=1&page_size=20&search=<text>`

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

**Response 201:** Full conversation object

---

### `GET /conversations/{id}`

Get a single conversation with its messages.

**Auth:** Required (must own conversation)

**Response 200:** Conversation object with `messages` array ordered by `created_at` ASC

**Errors:** `403` (not owner), `404` (not found)

---

### `PATCH /conversations/{id}`

Rename or pin/unpin a conversation.

**Auth:** Required (must own)

**Request:** `{ "title": "Renamed", "is_pinned": true }`

**Response 200:** Updated conversation object

---

### `DELETE /conversations/{id}`

Soft-delete a conversation (local cache cleared within 5 seconds).

**Auth:** Required (must own)

**Response 204:** No content

---

## Chat / WebSocket Endpoints

### `POST /chat/complete`

Non-streaming chat completion (fallback; use WebSocket for production).

**Auth:** Required

**Request:**
```json
{
  "conversation_id": "uuid",
  "message": "Summarise this document",
  "provider": "openai"
}
```

**Constraints:** `message` max 32,000 characters (HTTP 422 if exceeded)

**Response 200:**
```json
{
  "message_id": "uuid",
  "content": "The document covers...",
  "provider": "openai",
  "usage": { "input_tokens": 120, "output_tokens": 85 }
}
```

---

### WebSocket `/ws/chat/{conversation_id}`

**Connection:** `wss://host/ws/chat/{conv_id}?token=<JWT>`

JWT is validated on connection upgrade. Invalid JWT → HTTP 401 (connection refused).

#### Client → Server Message

```json
{
  "message": "Explain quantum computing",
  "provider": "openai"
}
```

#### Server → Client Events

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

**Error event:**
```json
{ "type": "error", "message": "Provider unavailable. Retrying with fallback." }
```

**Tool call event:**
```json
{
  "type": "tool_call",
  "toolName": "github_create_issue",
  "toolInput": { "repo": "owner/repo", "title": "Bug: login fails" }
}
```

#### WebSocket Close Codes

| Code | Meaning |
|------|---------|
| `4001` | Authentication failure (JWT absent, expired, malformed, or revoked) |
| `1001` | Going Away / Heartbeat timeout (no pong within 10 s of ping) |

#### Reconnection & Buffering

Backend buffers up to 1,000 tokens for 60 seconds on disconnect. Client reconnects with
exponential backoff: 1 s → 2 s → 4 s → 8 s → 16 s (capped 30 s, max 5 attempts). Buffered
tokens are delivered on successful reconnect.

---

## RAG / Document Endpoints

### `POST /documents`

Upload a document for RAG ingestion.

**Auth:** Required  
**Content-Type:** `multipart/form-data`

**Request Fields:**
- `file` (binary, max 50 MB)
- `conversation_id` (UUID, optional)

**Response 202:**
```json
{
  "document_id": "uuid",
  "job_id": "uuid",
  "status": "pending"
}
```

**Errors:** `400` (unsupported format), `413` (file > 50 MB)

---

### `GET /documents`

List all documents for the authenticated user.

**Auth:** Required  
**Query:** `?page=1&page_size=20&status=ready`

**Response 200:**
```json
{
  "items": [
    {
      "id": "uuid",
      "file_name": "Q4_Report.pdf",
      "ingestion_status": "ready",
      "page_count": 42,
      "size_bytes": 2048000,
      "created_at": "2025-01-15T10:00:00Z"
    }
  ],
  "total": 5
}
```

---

### `DELETE /documents/{id}`

Delete a document and all its chunks and embeddings (cleanup within 60 seconds).

**Auth:** Required (must own)

**Response 204:** No content

---

### `POST /documents/{id}/query`

Query a specific document using RAG.

**Auth:** Required (must own)

**Request:** `{ "question": "What is the Q4 revenue?" }`

**Response 200:**
```json
{
  "answer": "Q4 revenue was $12.4M [1]",
  "citations": [
    {
      "index": 1,
      "chunk_index": 3,
      "citation_type": "page",
      "page": 12,
      "document_name": "Q4_Report.pdf",
      "text": "Q4 revenue reached $12.4M representing..."
    }
  ],
  "usage": { "input_tokens": 890, "output_tokens": 64 }
}
```

---

### `GET /jobs/{job_id}`

Poll the status of an ingestion or background job.

**Auth:** Required

**Response 200:**
```json
{
  "id": "uuid",
  "status": "completed",
  "job_type": "rag_ingest",
  "error_message": null,
  "started_at": "2025-01-15T10:00:05Z",
  "completed_at": "2025-01-15T10:00:28Z"
}
```

`status` values: `pending` / `running` / `completed` / `failed`  
On `failed`, `error_message` contains stage name and file name.

---

## Memory Endpoints

### `GET /memory`

List all stored memories for the authenticated user.

**Auth:** Required

**Response 200:**
```json
{
  "items": [
    {
      "id": "uuid",
      "content": "Prefers concise bullet-point answers",
      "memory_type": "preference",
      "created_at": "2025-01-10T09:00:00Z"
    }
  ],
  "total": 14
}
```

---

### `PATCH /memory/{id}`

Edit the text content of a memory.

**Auth:** Required (must own)

**Request:** `{ "content": "Updated memory content" }`

**Response 200:** Updated memory object

---

### `DELETE /memory/{id}`

Delete a memory (removed from ChromaDB within 10 seconds).

**Auth:** Required (must own)

**Response 204:** No content  
**Error:** `408` if ChromaDB removal not confirmed within 10 seconds (memory left intact)

---

## MCP Tool Endpoints

### `GET /tools`

Discover all registered MCP tools and their input schemas.

**Auth:** Required

**Response 200:**
```json
{
  "tools": [
    {
      "name": "github_create_issue",
      "description": "Create a GitHub issue in a repository",
      "requires_confirmation": true,
      "parameters": {
        "type": "object",
        "properties": {
          "repo": { "type": "string", "description": "owner/repo" },
          "title": { "type": "string" },
          "body": { "type": "string" }
        },
        "required": ["repo", "title"]
      }
    }
  ]
}
```

---

### `POST /tools/{name}/invoke`

Invoke an MCP tool. Write operations require `confirmed: true`.

**Auth:** Required

**Request:**
```json
{
  "params": { "repo": "acme/backend", "title": "Bug: login fails", "body": "Steps to reproduce..." },
  "confirmed": true
}
```

**Response 200:**
```json
{
  "result": { "issue_url": "https://github.com/acme/backend/issues/42" },
  "tool_name": "github_create_issue",
  "status": "success"
}
```

**Errors:** `400` (validation), `403` (write not confirmed), `408` (tool timeout > 30 s), `502` (tool invocation failed)

---

## Image Endpoints

### `POST /images/analyze`

Analyze an image with OCR and/or vision LLM.

**Auth:** Required  
**Content-Type:** `multipart/form-data`

**Request Fields:**
- `image` (binary, JPEG / PNG / WebP, max 4096×4096, max 10 MB)
- `prompt` (string, optional) — user-supplied analysis prompt
- `conversation_id` (UUID, optional)

**Response 200:**
```json
{
  "ocr_text": "Invoice #1042\nAmount Due: $540.00",
  "bounding_boxes": [
    { "text": "Invoice #1042", "x": 10, "y": 20, "width": 200, "height": 30 }
  ],
  "no_text_found": false,
  "vision_analysis": "This is an invoice from Acme Corp...",
  "provider_used": "openai"
}
```

**Errors:** `400` (unsupported format), `413` (exceeds limits), `422` (no vision-capable provider active)

---

### `POST /images/scan-barcode`

Decode a barcode or QR code from an image.

**Auth:** Required  
**Content-Type:** `multipart/form-data`

**Request Fields:** `image` (binary)

**Response 200:**
```json
{
  "decoded_payload": "https://example.com/product/123",
  "barcode_type": "QR_CODE"
}
```

**Errors:** `422` (barcode could not be decoded)

---

## Productivity Endpoints

### To-Do

#### `GET /todos`

**Auth:** Required  
**Query:** `?completed=false&due_before=2025-12-31&page=1&page_size=20`

**Response 200:** `{ "items": [<TodoItem>], "total": N }`

#### `POST /todos`

**Auth:** Required  
**Request:** `{ "title": "Write tests", "description": "...", "due_date": "2025-06-01T00:00:00Z", "priority": "high", "tags": ["dev"] }`  
**Response 201:** Created `TodoItem`

#### `PATCH /todos/{id}`

**Auth:** Required  
**Request:** `{ "is_completed": true }`  
**Response 200:** Updated `TodoItem`

#### `DELETE /todos/{id}`

**Auth:** Required  
**Response 204:** No content

#### `POST /todos/generate`

Generate up to 20 `TodoItem` candidates from a natural language prompt. Does NOT persist.

**Auth:** Required  
**Request:** `{ "prompt": "Plan a product launch event" }`  
**Response 200:** `{ "candidates": [<TodoItem>, ...] }` — confirm with `POST /todos` per item

---

### Calendar

#### `GET /calendar`

**Auth:** Required  
**Query:** `?start=2025-06-01T00:00:00Z&end=2025-06-30T23:59:59Z`

**Response 200:** `{ "items": [<CalendarEvent>], "total": N }`

#### `POST /calendar`

**Auth:** Required  
**Request:** `{ "title": "Team Standup", "start_time": "2025-06-02T09:00:00Z", "end_time": "2025-06-02T09:30:00Z" }`  
**Response 201:** Created `CalendarEvent`

#### `DELETE /calendar/{id}`

**Auth:** Required  
**Response 204:** No content

#### `POST /calendar/suggest-times`

Get AI-suggested optimal meeting times (minimum 3, maximum 10 slots).

**Auth:** Required  
**Request:** `{ "duration_minutes": 60, "preferred_hours": [9, 10, 11] }`  
**Response 200:** `{ "slots": [{ "start": "...", "end": "..." }] }`

---

### Reminders

#### `GET /reminders`

**Auth:** Required  
**Response 200:** `{ "items": [<Reminder>], "total": N }` sorted by `trigger_time` ASC

#### `POST /reminders`

**Auth:** Required  
**Request:** `{ "title": "Review PR", "trigger_time": "2025-06-02T08:45:00Z", "recurrence_rule": "RRULE:FREQ=WEEKLY", "linked_todo_id": null }`  
**Response 201:** Created `Reminder`

#### `PATCH /reminders/{id}`

**Auth:** Required  
**Response 200:** Updated `Reminder`

#### `DELETE /reminders/{id}`

**Auth:** Required  
**Response 204:** No content

#### `POST /reminders/suggest`

Generate a suggested reminder from natural language. Does NOT persist.

**Auth:** Required  
**Request:** `{ "prompt": "Remind me to review the PR before tomorrow's standup" }`  
**Response 200:** `{ "suggestion": { "title": "Review PR", "trigger_time": "2025-06-02T08:45:00Z" } }`

---

### Habits

#### `GET /habits`

**Auth:** Required  
**Response 200:** `{ "items": [<HabitDefinition>], "total": N }`

#### `POST /habits`

**Auth:** Required  
**Request:** `{ "name": "Morning run", "description": "30-minute run", "recurrence": "daily", "target_frequency": 1 }`  
**Response 201:** Created `HabitDefinition`

#### `DELETE /habits/{id}`

**Auth:** Required  
**Response 204:** No content

#### `POST /habits/{id}/entries`

Log a habit completion entry.

**Auth:** Required  
**Request:** `{ "completed_at": "2025-06-02T07:30:00Z", "note": "Felt great" }`  
**Response 201:** Created `HabitEntry`

#### `GET /habits/{id}/insights`

Get AI-generated insights (requires ≥ 7 days of logged entries).

**Auth:** Required

**Response 200 (SSE stream):**
```json
{ "completion_rate": 0.85, "best_day": "Monday", "streak": 12, "prediction": "On track to hit 90% this month" }
```

**Error:** `422` if fewer than 7 days of entries exist

---

## Admin Endpoints (role: `admin` required)

### `GET /admin/users`

**Auth:** Required (`admin` role)  
**Query:** `?search=jane&role=user&page=1&page_size=20`

**Response 200:** `{ "items": [<UserProfile>], "total": N }`

---

### `PATCH /admin/users/{id}`

Update user role or active status. Deactivation immediately invalidates all tokens.

**Auth:** Required (`admin` role)

**Request:** `{ "role": "premium", "is_active": true }`

**Response 200:** Updated user object

---

### `DELETE /admin/users/{id}/sessions`

Invalidate all JWTs and refresh tokens for a user (force re-login on all devices).

**Auth:** Required (`admin` role)

**Response 204:** No content

---

### `GET /admin/metrics`

Aggregated platform metrics (refreshed within 60 seconds).

**Auth:** Required (`admin` role)

**Response 200:**
```json
{
  "active_users_24h": 142,
  "messages_per_hour": 380,
  "token_usage_today": { "openai": 1420000, "gemini": 320000 },
  "cost_today_usd": { "openai": 4.26, "gemini": 0.64 },
  "error_rate": 0.002
}
```

---

### `GET /admin/audit-logs`

Paginated audit log.

**Auth:** Required (`admin` role)  
**Query:** `?user_id=<uuid>&event_type=login_failed&from=2025-01-01&page=1&page_size=50`

**Response 200:** `{ "items": [<AuditLogEntry>], "total": N }`

---

### `GET /admin/errors`

Top-10 error types in the last 24 hours with stack trace summaries.

**Auth:** Required (`admin` role)

**Response 200:** `{ "errors": [{ "error_type": "ValueError", "count": 12, "last_seen": "...", "sample_trace": "..." }] }`

---

## Notification Endpoints

### `POST /notifications/token`

Register or rotate a Firebase Cloud Messaging push notification token.

**Auth:** Required

**Request:** `{ "token": "fcm_token_string", "device_id": "uuid" }`

**Response 200:** `{ "registered": true }`

---

## Generation Endpoints

### `POST /resume/generate`

Generate an ATS-optimised resume in Markdown within 30 seconds.

**Auth:** Required

**Request:**
```json
{
  "work_experiences": [{ "company": "Acme", "title": "Engineer", "years": 3 }],
  "contact_info": { "name": "Jane Smith", "email": "jane@example.com" },
  "target_job_description": "We are looking for a senior Python engineer..."
}
```

**Response 200:** `{ "resume_markdown": "# Jane Smith\n...", "word_count": 380 }`

---

### `POST /resume/cover-letter`

Generate a cover letter (≤ 400 words). Both `resume_data` and `job_description` are required.

**Auth:** Required

**Request:** `{ "resume_data": { ... }, "job_description": "..." }`

**Response 200:** `{ "cover_letter": "Dear Hiring Manager,\n...", "word_count": 387 }`

**Errors:** `422` if either field is missing (structured error with missing field identification)

---

### `POST /email/generate`

Generate a professional email.

**Auth:** Required

**Request:** `{ "intent": "Request a meeting to discuss Q3 results", "recipient": "manager" }`

**Response 200:**
```json
{
  "subject": "Request to Discuss Q3 Results",
  "greeting": "Dear Michael,",
  "body": "I hope this message finds you well...",
  "closing": "Best regards,\nJane Smith"
}
```

---

### `POST /email/grammar-correct`

Grammar correction with diff highlighting.

**Auth:** Required

**Request:** `{ "text": "I wanted to discus the project statuses with you." }`

**Response 200:**
```json
{
  "corrected_text": "I wanted to discuss the project status with you.",
  "changes": [
    { "original": "discus", "corrected": "discuss", "position": 16 },
    { "original": "statuses", "corrected": "status", "position": 40 }
  ],
  "no_changes_needed": false
}
```

---

## Health / Readiness Endpoints

### `GET /health`

Lightweight health check (no downstream checks).

**Auth:** None

**Response 200:**
```json
{ "status": "ok", "version": "1.0.0" }
```

---

### `GET /ready`

Readiness check — verifies all downstream service connections.

**Auth:** None

**Response 200:**
```json
{
  "status": "ready",
  "checks": {
    "postgres": "ok",
    "redis": "ok",
    "chromadb": "ok",
    "minio": "ok"
  }
}
```

**Response 503** (any downstream unavailable):
```json
{
  "status": "not_ready",
  "checks": { "postgres": "ok", "redis": "error", "chromadb": "ok", "minio": "ok" }
}
```
