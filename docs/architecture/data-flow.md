# Data Flow — Android AI Assistant

> **Status:** Current implementation  
> **Updated:** 2026-10-03

This document covers every significant data path through the system — from the Android client through the backend to storage and back. All Mermaid diagrams render in GitHub, GitLab, and most Markdown viewers.

---

## Table of Contents

1. [Request routing overview](#1-request-routing-overview)
2. [Chat / LLM data flow](#2-chat--llm-data-flow)
3. [Agent execution data flow](#3-agent-execution-data-flow)
4. [RAG ingestion data flow](#4-rag-ingestion-data-flow)
5. [RAG query data flow](#5-rag-query-data-flow)
6. [MCP tool execution data flow](#6-mcp-tool-execution-data-flow)
7. [Authentication data flow](#7-authentication-data-flow)
8. [Data storage topology](#8-data-storage-topology)

---

## 1. Request routing overview

```mermaid
flowchart TD
    A[Android App] -->|HTTPS REST| B[FastAPI\nrouter layer]
    A -->|WSS| C[WebSocket\nrouter]

    B --> D{Route?}
    D -->|/auth/*| E[AuthService]
    D -->|/api/v1/chat| F[LLMService\nDirect chat]
    D -->|/api/v1/agent/*| G[AgentServiceFactory\nSingleAgentRunner]
    D -->|/api/v1/rag/*| H[RAGPipeline]
    D -->|/api/v1/documents/*| I[DocumentRepository\nCelery]
    D -->|/mcp/*| J[MCPServer]

    C -->|user_message| G

    G --> K[AgentExecutionLoop]
    K -->|decide| F
    K -->|retrieve| H
    K -->|call_tool| J
```

---

## 2. Chat / LLM data flow

Direct-LLM mode: the simplest path — no retrieval, no tool calls.

```mermaid
sequenceDiagram
    participant App as Android App
    participant API as POST /api/v1/chat
    participant InjDet as InjectionDetector
    participant Prompt as PromptBuilder
    participant LLM as LLMService
    participant Gemini as GeminiProvider

    App->>API: POST {message, conversation_id}
    API->>InjDet: check_input(message, user_id, db)
    alt injection detected
        InjDet-->>API: PromptInjectionError
        API-->>App: HTTP 400 PROMPT_INJECTION_DETECTED
    end
    InjDet-->>API: None (clean)
    API->>Prompt: build(message, conversation_id, user_id)
    Prompt-->>API: LLMRequest
    API->>LLM: generate(LLMRequest)
    LLM->>Gemini: generate(request)
    Gemini-->>LLM: LLMResponse
    LLM-->>API: LLMResponse
    API-->>App: {answer, provider, model, usage}
```

**Key data at each hop:**

| Hop | What travels | What is dropped |
|---|---|---|
| App → API | Raw user message, conversation_id | JWT (in Authorization header, stripped before logging) |
| API → InjDet | message text, user_id | — |
| API → Prompt | message, conversation_id, user_id | — |
| API → LLM | LLMRequest (prompt, system_prompt, user_id) | Full JWT |
| LLM → Gemini | prompt text, temperature, max_tokens | API key set in constructor, never in logs |
| API → App | answer text, token counts | Internal model name variants |

---

## 3. Agent execution data flow

```mermaid
flowchart LR
    A[POST /api/v1/agent/execute] --> B[InjectionDetector]
    B --> C[AgentServiceFactory\n.build_runner]
    C --> D[SingleAgentRunner\n.run]

    D --> E[OrchestrationPlanner]
    E -->|AgentPlan| F[AgentExecutionLoop]

    F -->|_decide loop| G[LLMAdapter\nGemini Flash]
    G -->|JSON decision| F

    F -->|retrieve| H[RAGPipeline\n.ask]
    H --> I[(ChromaDB\nvector store)]
    H --> J[LLMService\ngenerate answer]

    F -->|call_tool| K[MCPServer\n.execute]
    K --> L[AtlassianMCP\nJira / Confluence]

    F -->|respond| G

    F --> M[ObservabilityTracker\nstructured logs]
    F --> N[OrchestrationResult]
    N --> A
```

### Step-by-step trace

1. **Request arrives** — `POST /api/v1/agent/execute` with `{message, enable_rag, enable_mcp, max_steps}`.
2. **Injection check** — `InjectionDetector.check_input()` blocks prompt injection attempts; HTTP 400 on match.
3. **Factory builds runner** — `AgentServiceFactory.build_runner(db)` wires `LLMServiceAdapter`, `RAGPipeline`, `MCPServer`, and reads limits from `Settings`.
4. **Planning** — `OrchestrationPlanner` routes via `AgentRouter` (priority: explicit name → capability match → conversation context → first capable) then calls `AgentPlanner.build_plan()`.
5. **Execution loop** — `AgentExecutionLoop._run_inner()` runs under `asyncio.timeout(config.timeout_s)`. Each iteration:
   - Checks `step_count < max_steps` and `tool_call_count < max_tool_calls`.
   - Calls `_decide()` → LLM returns a JSON decision (`respond | retrieve | call_tool | wait | finish`).
   - Dispatches via `ActionDispatcher`.
6. **Result** — `OrchestrationResult` carries `output`, `citations`, `tool_calls`, `spans`, `total_tokens`, `elapsed_ms`.

### Data in `OrchestrationResult`

```
OrchestrationResult
├── run_id          — UUID for this execution
├── request_id      — echoed from AgentRequest
├── agent_name      — e.g. "ai-assistant"
├── status          — "completed" | "failed" | "timed_out"
├── output          — accumulated assistant answer text
├── citations[]     — [{document_id, document_name, excerpt, page_number, score}]
├── tool_calls[]    — [{tool_name, input, output, failed, error_message}]
├── spans[]         — [{step_index, action_type, duration_ms, tokens_used, success}]
├── total_tokens    — sum across all LLM calls
├── step_count
└── elapsed_ms
```

---

## 4. RAG ingestion data flow

```mermaid
sequenceDiagram
    participant App as Android App
    participant API as POST /api/v1/documents/upload
    participant Celery as Celery Worker\n(ingestion queue)
    participant Loader as DocumentLoader\n(PDF/DOCX/TXT)
    participant Chunker as DocumentChunker
    participant Embed as SentenceTransformer\nall-MiniLM-L6-v2
    participant Chroma as ChromaDB\ncollection per user
    participant PG as PostgreSQL\ndocument_chunks

    App->>API: multipart/form-data (file)
    API->>API: MIME + size validation
    API->>PG: INSERT documents (status=pending)
    API->>Celery: ingest_document_task.delay(doc_id, user_id)
    API-->>App: {document_id, status="pending"}

    Celery->>PG: UPDATE status=processing
    Celery->>Loader: extract_text(bytes, mime_type)
    Loader-->>Celery: raw text
    Celery->>Chunker: chunk(text, size=512, overlap=64)
    Chunker-->>Celery: [Chunk(text, index, page)]
    loop for each chunk
        Celery->>Embed: encode(chunk.text)
        Embed-->>Celery: float[384]
        Celery->>Chroma: add(collection="documents_{user_id}", embedding, metadata)
        Celery->>PG: INSERT document_chunks (embedding via pgvector)
    end
    Celery->>PG: UPDATE documents SET status=ready
    App->>API: GET /api/v1/documents/{id}/status
    API-->>App: {status="ready", progress=1.0}
```

**Storage written:**
- `documents` table: status, file metadata, chunk count
- `document_chunks` table: chunk text, page number, embedding (pgvector `vector(384)`)
- ChromaDB collection `documents_{user_id}`: embeddings + metadata for ANN search

---

## 5. RAG query data flow

```mermaid
flowchart TD
    A["POST /api/v1/rag/query\n{question, document_ids?, top_k}"] --> B[RAGPipeline.ask]

    B --> C[VectorRetriever]

    C --> D[SentenceTransformer\nembed question]
    D --> E[ChromaDB.query\ncollection=documents_{user_id}]
    E --> F[Top-K chunks\nby cosine similarity]

    F --> G{chunks found?}
    G -->|no| H["Answer: no relevant information found"]
    G -->|yes| I[ContextBuilder\nbuild_prompt + build_citations]

    I --> J[LLMService.generate\ngrounded answer]
    J --> K[RAGAnswer\nanswer + sources + latency_ms]

    K --> L["Response\n{answer, sources[], request_id}"]
```

**Privacy enforcement:**
- ChromaDB query is scoped to `collection="documents_{user_id}"` — no cross-user access.
- `user_id` in logs is redacted to first 8 characters (e.g. `aaaaaaaa…`).
- Raw question text is never logged — only `question_length`.

---

## 6. MCP tool execution data flow

```mermaid
sequenceDiagram
    participant Agent as AgentExecutionLoop
    participant Dispatcher as ActionDispatcher
    participant Server as MCPServer
    participant Executor as MCPExecutor
    participant Registry as MCPRegistry
    participant Broker as MCPBroker
    participant Atlassian as AtlassianMCPConnector
    participant DB as PostgreSQL\naudit_logs

    Agent->>Dispatcher: dispatch(CallToolDecision{tool_name, parameters})
    Dispatcher->>Server: execute(tool_name, params, user_id)
    Server->>Executor: execute(tool_name, params, user_id)

    Executor->>Registry: is_allowed(tool_name)
    alt not allowed
        Registry-->>Executor: False
        Executor-->>Server: MCPToolResult(success=False, "not on allowlist")
    end

    Executor->>Registry: get_model(tool_name)
    Registry-->>Executor: MCPToolModel(timeout_ms=20000, ...)

    Executor->>Executor: MCPValidator.validate(params, model)

    Executor->>Broker: invoke(tool_name, params, user_id) [within asyncio.timeout]
    Broker->>DB: INSERT audit_logs (tool_name, user_id, params_summary)
    Broker->>Atlassian: invoke(params, user_id)
    Atlassian->>Atlassian: fetch OAuth token (cached)
    Atlassian->>Atlassian: POST /tools/call (JSON-RPC 2.0)
    Atlassian-->>Broker: MCPToolResult
    Broker-->>Executor: MCPToolResult
    Executor->>Executor: MCPValidator.validate_result (best-effort)
    Executor-->>Server: MCPToolResult
    Server-->>Dispatcher: MCPToolResult
    Dispatcher-->>Agent: ActionOutcome{output, success, tool_record}
```

**Data never logged:**
- `params` containing passwords, tokens, secrets, API keys — the `AgentSafetyGuard` calls `redact_sensitive_args()` before any log write.
- Full `user_id` — only first 8 characters appear in logs.
- OAuth access tokens — the Atlassian connector uses `_redact()` on all exception messages.

---

## 7. Authentication data flow

```mermaid
sequenceDiagram
    participant App as Android App
    participant API as POST /auth/login
    participant Auth as AuthService
    participant PG as PostgreSQL
    participant Redis as Redis
    participant App2 as Android App

    App->>API: {email, password}
    API->>Auth: login(email, password)
    Auth->>PG: SELECT users WHERE email=...
    Auth->>Auth: bcrypt.verify(password, hash)
    alt invalid
        Auth-->>API: Unauthorized
        API->>Redis: INCR lockout:{user_id}
        API-->>App: HTTP 401
    end
    Auth->>Auth: create_access_token (JWT HS256, 15 min)
    Auth->>Auth: create_refresh_token (opaque, stored as SHA-256 hash)
    Auth->>PG: UPDATE users SET refresh_token_hash=...
    Auth-->>API: {access_token, refresh_token, user}
    API-->>App: 200 {access_token, refresh_token}

    Note over App,App2: On every API request
    App2->>API: Authorization: Bearer <access_token>
    API->>API: AuthInterceptor (OkHttp)\nattaches header automatically
    API->>API: JWT decode + signature verify
    API->>API: get_current_user dependency
```

**JWT claims carried:**
- `sub` — user UUID
- `exp` — expiry epoch (15 minutes from issuance)
- `roles` — list of RBAC roles

**What is NOT in the JWT:**
- Password hash
- Email address
- Any PII beyond the opaque user UUID

---

## 8. Data storage topology

```mermaid
erDiagram
    USERS {
        uuid id PK
        text email
        text password_hash
        text role
        bool privacy_mode
    }

    CONVERSATIONS {
        uuid id PK
        uuid user_id FK
        text title
        text provider
        bool is_pinned
    }

    MESSAGES {
        uuid id PK
        uuid conversation_id FK
        text role
        text content
        int input_tokens
        int output_tokens
    }

    DOCUMENTS {
        uuid id PK
        uuid user_id FK
        text file_name
        text ingestion_status
        int page_count
        text doc_title_extracted
    }

    DOCUMENT_CHUNKS {
        uuid id PK
        uuid document_id FK
        text chunk_text
        int chunk_index
        int page_number
        vector embedding
    }

    MEMORIES {
        uuid id PK
        uuid user_id FK
        text content
        text memory_type
    }

    AUDIT_LOGS {
        uuid id PK
        uuid user_id FK
        text event_type
        text resource_type
        timestamptz created_at
    }

    USERS ||--o{ CONVERSATIONS : "owns"
    USERS ||--o{ DOCUMENTS : "uploads"
    USERS ||--o{ MEMORIES : "has"
    USERS ||--o{ AUDIT_LOGS : "generates"
    CONVERSATIONS ||--o{ MESSAGES : "contains"
    DOCUMENTS ||--o{ DOCUMENT_CHUNKS : "split into"
```

**Storage systems summary:**

| Store | Technology | Data | Isolation |
|---|---|---|---|
| Primary DB | PostgreSQL 16 + pgvector | Users, conversations, messages, documents, chunks, memories, audit logs | Row-level: user_id FK on all tables |
| Vector store | ChromaDB 0.5.20 | Document embeddings, memory embeddings | Collection per user: `documents_{user_id}`, `user_{user_id}_memories` |
| Cache / broker | Redis 7 | Rate-limit counters, session state, Celery tasks, JWT denylist | Key namespace per user |
| Object storage | MinIO (local) / GCS (prod) | Raw document files | Object path includes user_id prefix |
| Android local | Room (SQLite) | Conversations, messages, documents (cache), on-device chunks | Single-user device |
