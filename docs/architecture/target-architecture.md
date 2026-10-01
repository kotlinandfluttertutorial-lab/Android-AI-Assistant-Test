# Target Architecture — Android AI Assistant (Enterprise Edition)

> **Document Type:** Target State Design  
> **Date:** 2026-10-01  
> **Status:** Approved design baseline — no production code changed  
> **Scope:** Introduces MCP, Agent, and Document/RAG as first-class integrated modules  
> **Prerequisite reading:** `docs/architecture/current-state.md`

---

## Table of Contents

1. [Design Principles](#1-design-principles)
2. [High-Level Target Architecture](#2-high-level-target-architecture)
3. [Backend Target Architecture](#3-backend-target-architecture)
   - 3.1 [Unified LLM Provider Layer](#31-unified-llm-provider-layer)
   - 3.2 [Agent System — Promoted to Primary Code Path](#32-agent-system--promoted-to-primary-code-path)
   - 3.3 [MCP Module — First-Class Integration](#33-mcp-module--first-class-integration)
   - 3.4 [Document / RAG Module — Enhanced Pipeline](#34-document--rag-module--enhanced-pipeline)
   - 3.5 [WebSocket Router — Agent-Aware](#35-websocket-router--agent-aware)
   - 3.6 [Memory Service — Extended Types](#36-memory-service--extended-types)
   - 3.7 [Celery Workers — Agent Queue](#37-celery-workers--agent-queue)
   - 3.8 [API Surface Changes](#38-api-surface-changes)
   - 3.9 [Database Changes](#39-database-changes)
4. [Android Target Architecture](#4-android-target-architecture)
   - 4.1 [JWT Auth for WebSocket — Fix](#41-jwt-auth-for-websocket--fix)
   - 4.2 [Agent Event Protocol Extension](#42-agent-event-protocol-extension)
   - 4.3 [MCP Tool UI Module](#43-mcp-tool-ui-module)
   - 4.4 [Document/RAG UI Enhancements](#44-documentrag-ui-enhancements)
   - 4.5 [On-Device RAG — Deduplication](#45-on-device-rag--deduplication)
5. [WebSocket Protocol — Extended Contract](#5-websocket-protocol--extended-contract)
6. [Data Flow Diagrams](#6-data-flow-diagrams)
   - 6.1 [Target Agent Request Flow](#61-target-agent-request-flow)
   - 6.2 [Target MCP Tool Flow](#62-target-mcp-tool-flow)
   - 6.3 [Target RAG Ingestion Flow](#63-target-rag-ingestion-flow)
   - 6.4 [Target RAG Query Flow](#64-target-rag-query-flow)
7. [Reuse Strategy — What Is NOT Rebuilt](#7-reuse-strategy--what-is-not-rebuilt)
8. [Duplication Resolution Plan](#8-duplication-resolution-plan)
9. [Non-Functional Requirements](#9-non-functional-requirements)
10. [Dependency Map — New vs. Existing](#10-dependency-map--new-vs-existing)

---

## 1. Design Principles

These principles govern all decisions in this target architecture:

1. **Extend, don't replace.** Every existing working component is treated as a dependency, not a rewrite target. New code plugs into existing interfaces (`Agent` ABC, `LLMProvider`, `MCPToolConnector`, `RAGService`, etc.).

2. **One code path for agent execution.** The `AgentOrchestrator` (already built in `agents/orchestrator.py`) becomes the single path for all AI requests that involve planning, tool use, or multi-step reasoning. The parallel `AIOrchestrator` is retained for simple single-turn chat only, then progressively delegated.

3. **No new abstractions without justification.** Before introducing a new base class, interface, or service, the existing ones in `agents/`, `llm/`, `services/` are checked. The goal is consolidation, not layering.

4. **Backward compatibility at the WebSocket contract level.** Existing `token` / `done` / `error` / `tool_call` messages continue to work unchanged. New agent event types are additive.

5. **Zero Android production breakage.** The JWT placeholder fix and agent protocol extension are the only Android changes that affect existing production flows. All other Android additions are purely additive new screens or modules.

6. **Test coverage accompanies every new component.** Each new agent, connector, or service ships with unit tests following the pattern in `tests/unit/agents/` and `tests/unit/test_mcp_broker.py`.

7. **Infrastructure changes are minimal.** One new Celery queue (`agents`), one new Redis key namespace (`agent:{user_id}:{session_id}`), and optional Alembic migration for agent state persistence. No new cloud services required.

---

## 2. High-Level Target Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        ANDROID CLIENT                               │
│                                                                     │
│  feature-chat ──► ChatDetailViewModel                               │
│       │              ├─ sends message                               │
│       │              ├─ receives: token / done / error              │
│       │              │           agent_step / agent_thinking        │  ← NEW
│       │              │           tool_started / tool_completed      │  ← NEW
│       │              └─ renders: AgentProgressCard (new Compose)    │  ← NEW
│       │                                                             │
│  feature-rag ──► RAGViewModel / DocumentChatViewModel               │
│       │              └─ existing upload + citation flow (unchanged) │
│       │                                                             │
│  feature-mcp (NEW) ──► MCPToolsViewModel                           │  ← NEW
│       └─ GET /mcp/tools → list + invoke tools                       │
│                                                                     │
│  core-ai  ──► AIStreamClient (extended event types)                 │
│  core-network ──► same OkHttp stack (unchanged)                     │
└─────────────────────────────┬───────────────────────────────────────┘
                              │ WSS /ws/chat/{id}?token=<jwt>
                              │ HTTPS /api/*
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     FASTAPI BACKEND                                 │
│                                                                     │
│  WebSocket Router ──► AgentOrchestrator  ◄──── NEW primary path     │
│       │                    │                                        │
│       │              ┌─────┴──────┐                                 │
│       │              │            │                                  │
│       │           AgentRouter  AgentPlanner                         │
│       │              │                                              │
│       │         AgentRegistry                                       │
│       │         ┌────┴────┬────┬────┬────┬────┐                    │
│       │     ChatAgent  CodeAgent RagAgent ToolAgent McpAgent  ...   │
│       │                    │         │        │                     │
│       │              LLMService  RAGService  MCPBroker             │
│       │              (unified)  (existing)  (existing+enhanced)    │
│       │                    │                    │                   │
│       │              LLMProvider           MCPConnectors (9+)       │
│       │              (single layer)                                 │
│       │                    │                                        │
│       │            GeminiProvider / LocalGemmaProvider              │
│       │                                                             │
│  REST Routes ──► /mcp/*, /documents/*, /agents/* (NEW)              │
│                                                                     │
│  Celery Workers                                                     │
│  ├── ingestion queue (existing rag_worker.py)                       │
│  ├── notifications queue (existing)                                 │
│  ├── gdpr queue (existing)                                          │
│  └── agents queue (NEW agent_worker.py)                             │
│                                                                     │
│  Storage                                                            │
│  ├── PostgreSQL (pgvector) ← +agent_executions table (migration 17) │
│  ├── ChromaDB (per-user collections, unchanged)                     │
│  ├── Redis (+ agent state namespace)                                │
│  └── MinIO / GCS (unchanged)                                        │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. Backend Target Architecture

### 3.1 Unified LLM Provider Layer

**Current problem (D1):** Two parallel LLM abstraction layers — `services/llm_clients.py` (older `BaseLLMClient`) and `llm/providers/` (newer `LLMProvider`) serve the same purpose.

**Target:** `llm/providers/` + `llm/service.py` becomes the **single** LLM interface. `services/llm_clients.py` adapters are wrapped and delegated rather than called directly.

```
Target LLM call path:

Any caller (AgentOrchestrator, RAGService, code router, etc.)
    │
    ▼
LLMService (llm/service.py)
    │   resolves by: complexity hint ("simple" / "complex" / "local")
    │                provider name from request
    │                fallback on quota error
    ▼
LLMProvider (llm/base.py ABC)
    ├── GeminiProvider     — google-genai SDK, retry + fallback
    ├── LocalGemmaProvider — Gemma via Ollama, zero external calls
    ├── OpenAIProvider     — wraps existing OpenAIClient (NEW thin wrapper)
    ├── ClaudeProvider     — wraps existing ClaudeClient (NEW thin wrapper)
    └── OllamaProvider     — wraps existing OllamaClient (NEW thin wrapper)
```

**Migration rule:**
- `AIOrchestrator._resolve_provider()` is updated to call `LLMService` instead of resolving `BaseLLMClient` instances directly.
- `services/llm_clients.py` is **not deleted** — the 4 remaining clients (`OpenAIClient`, `ClaudeClient`, `LlamaClient`, `MistralClient`) are thin-wrapped behind new `LLMProvider` subclasses.
- `BaseLLMClient` is kept as-is; the wrappers delegate to it. No existing tests break.

**New files:**
```
backend/app/llm/providers/
  ├── gemini_provider.py       (existing — unchanged)
  ├── local_gemma_provider.py  (existing — unchanged)
  ├── openai_provider.py       (NEW — wraps OpenAIClient)
  ├── claude_provider.py       (NEW — wraps ClaudeClient)
  └── ollama_provider.py       (NEW — wraps OllamaClient + LlamaClient + MistralClient)
```

### 3.2 Agent System — Promoted to Primary Code Path

**Current problem (D2):** `AgentOrchestrator` in `agents/` is built but not wired to the WebSocket router. The router still uses `AIOrchestrator` (service layer) directly.

**Target:** `AgentOrchestrator` becomes the primary execution path for all multi-step or tool-using requests. `AIOrchestrator` handles simple single-turn chat only.

#### Routing Decision

```
WebSocket router receives message
    │
    ▼
RequestClassifier.classify(user_message) → RequestType
    ├── SIMPLE_CHAT     → AIOrchestrator.stream_chat()  (existing, unchanged)
    └── AGENT_TASK      → AgentOrchestrator.execute()   (new primary path)
        (tool use, multi-step, RAG query, code, MCP)
```

`RequestClassifier` is a lightweight heuristic + keyword detection (no LLM call needed for routing):
- Contains `@tool`, `/rag`, `/code`, `/search`, or multi-sentence question → `AGENT_TASK`
- Default → `SIMPLE_CHAT`
- Can be overridden by `provider` field in client message: `"provider": "agent"`

#### Agent Registration

All existing concrete agents in `agents/` are registered at startup:

```python
# backend/app/agents/__init__.py (NEW wiring)
from app.agents.registry import AgentRegistry
from app.agents.chat_agent import ChatAgent
from app.agents.rag_agent import RagAgent
from app.agents.code_agent import CodeAgent
from app.agents.tool_agent import ToolAgent
from app.agents.image_agent import ImageAgent
from app.agents.voice_agent import VoiceAgent
from app.agents.web_agent import WebAgent
from app.agents.pdf_agent import PdfAgent

def build_registry(llm_service, rag_service, mcp_broker, memory_service) -> AgentRegistry:
    registry = AgentRegistry()
    registry.register(ChatAgent(llm_service, memory_service))
    registry.register(RagAgent(rag_service, llm_service))
    registry.register(CodeAgent(llm_service))
    registry.register(ToolAgent(mcp_broker, llm_service))
    registry.register(ImageAgent(llm_service))
    registry.register(VoiceAgent(llm_service))
    registry.register(WebAgent(llm_service))
    registry.register(PdfAgent(rag_service, llm_service))
    return registry
```

`AgentOrchestrator`, `AgentRouter`, and `AgentPlanner` are injected as FastAPI dependencies via a new `get_agent_orchestrator()` dependency function.

#### Event Streaming from AgentOrchestrator

The WebSocket router iterates the `AgentOrchestrator.execute()` async generator and maps each `AgentEvent` to a WebSocket frame:

| `AgentEvent` type | WS frame type | New? |
|---|---|---|
| `AgentStartedEvent` | `agent_started` | NEW |
| `AgentStatusChangedEvent` | `agent_status` | NEW |
| `AgentTokenEvent` | `token` | existing (backward-compatible) |
| `AgentThinkingEvent` | `agent_thinking` | NEW |
| `AgentToolStartedEvent` | `tool_started` | NEW (replaces silent `tool_call`) |
| `AgentToolCompletedEvent` | `tool_completed` | NEW |
| `AgentToolFailedEvent` | `tool_failed` | NEW |
| `AgentRetrievalCompletedEvent` | `retrieval_completed` | NEW |
| `AgentCompletedEvent` | `done` | existing (backward-compatible) |
| `AgentFailedEvent` | `error` | existing (backward-compatible) |
| `AgentCancelledEvent` | `error` (reason: cancelled) | existing (backward-compatible) |

Existing `token` and `done` / `error` frames are **identical** to current — Android clients that don't understand new event types ignore them safely.

### 3.3 MCP Module — First-Class Integration

**Current state:** `MCPBroker` and 9 connectors exist and work. The agent system can call `MCPBroker.invoke()` but only if the `ToolAgent` is wired to it. `AIOrchestrator` handles `tool_call` responses from LLMs ad hoc.

**Target:** `ToolAgent` is the authorised gateway for all MCP tool execution. Direct `tool_call` dispatch in `AIOrchestrator` is removed in favour of the `ToolAgent` path.

#### MCP Enhancements

```
MCPBroker (existing — no interface change)
    │
    ├── register(connector)    ← existing
    ├── discover()             ← existing — returns List[MCPToolSchema]
    └── invoke(tool_name, params, user_id)
            │
            ├── AuditService.log_mcp_invoke()  ← existing (mandatory)
            ├── requires_confirmation() check  ← existing
            └── MCPToolConnector.invoke()      ← existing
```

**New additions (no interface changes to existing code):**

1. `MCPBroker.invoke_with_streaming(tool_name, params, user_id, ws)` — NEW method that emits `tool_started` / `tool_completed` WebSocket events during execution. The existing `invoke()` is unchanged.

2. `AgentToolConfirmationRequiredEvent` — already defined in `agents/models.py`. The WebSocket router emits it when `MCPBroker` returns `confirmation_required`. The Android client shows a confirmation dialog; the user's response comes back as a new WebSocket message `{"type": "tool_confirm", "tool_name": "...", "confirmed": true}`.

3. **MCP tool streaming schema** — `MCPToolSchema` gains an optional `streaming: bool` field to indicate whether the tool supports incremental output.

#### New MCP Connectors (target)
The 9 existing connectors are untouched. Two new connectors are targeted:

| Connector | Class | Purpose |
|---|---|---|
| `BrowserConnector` | `browser_connector.py` | Web scraping / browsing (headless) |
| `DatabaseConnector` | `db_connector.py` | Read-only SQL query against configured DBs |

### 3.4 Document / RAG Module — Enhanced Pipeline

**Current state:** `RAGService` is complete and functional. ChromaDB is used for vector search. PostgreSQL `document_chunks` has a pgvector column but it is not yet used for hybrid search.

**Target:** Hybrid search (ChromaDB ANN + pgvector cosine) with result fusion. BM25 keyword search added as a third retrieval path for short, exact-match queries.

#### Retrieval Enhancement

```
RAGService.query_documents(user_id, query, top_k)
    │
    ├── Path A: ChromaDB ANN search (existing)
    │     → embed(query) → ChromaDB.query() → top_k candidates
    │
    ├── Path B: pgvector cosine search (NEW)
    │     → SELECT ... FROM document_chunks
    │       ORDER BY embedding <=> query_vector LIMIT top_k
    │
    └── Path C: BM25 full-text search (NEW — PostgreSQL tsvector)
          → SELECT ... FROM document_chunks
            WHERE to_tsvector('english', chunk_text) @@ plainto_tsquery(query)
            ORDER BY ts_rank LIMIT top_k

Results fused using Reciprocal Rank Fusion (RRF):
    → deduplicate by chunk_id
    → score = sum(1 / (k + rank_i)) across retrieval paths
    → return top_k by fused score
```

**Database change:** `document_chunks` gets a `tsvector` column (GIN index) for full-text search. This is added in **Alembic migration 17**.

#### Ingestion Enhancement

```
ingest_document_task (existing worker — extended, not replaced)
    │
    ├── extract_text()      ← unchanged
    ├── chunk_text()        ← unchanged
    ├── embed_and_store()   ← unchanged (ChromaDB + pgvector)
    └── NEW: update_fts_index()
              → UPDATE document_chunks
                SET fts_vector = to_tsvector('english', chunk_text)
                WHERE id = chunk_id
```

#### Metadata Extraction (new)
For PDF and DOCX, the ingestion pipeline extracts document-level metadata (title, author, creation date) and stores it in the `documents` table via two new columns: `doc_title_extracted` and `doc_metadata` (JSONB).

#### Citation Enhancement
`RAGService._format_citations()` is extended to include the retrieval path (`chroma_ann` / `pgvector_cosine` / `bm25_fts`) so the UI can display source quality indicators.

### 3.5 WebSocket Router — Agent-Aware

**Current:** `api/websocket/router.py` calls `AIOrchestrator.stream_chat()` directly.

**Target:** The router is updated to dispatch through `RequestClassifier`:

```python
# api/websocket/router.py (updated _handle_messages)
async def _handle_messages(ws, user, conversation_id, redis):
    async for raw_msg in ws.iter_json():
        msg_type = raw_msg.get("type", "user_message")

        if msg_type == "pong":
            heartbeat_monitor.pong_received(); continue

        if msg_type == "tool_confirm":
            # Forward tool confirmation to pending ToolAgent execution
            await agent_session_store.confirm_tool(
                user.id, conversation_id, raw_msg
            ); continue

        # Classify request
        request_type = RequestClassifier.classify(raw_msg["user_message"])

        if request_type == RequestType.SIMPLE_CHAT:
            await _handle_simple_chat(ws, user, conversation_id, raw_msg, redis)
        else:
            await _handle_agent_task(ws, user, conversation_id, raw_msg, redis)
```

`_handle_simple_chat()` — existing `AIOrchestrator` path (zero change).  
`_handle_agent_task()` — new function that:
1. Builds `AgentRequest` from the WS message
2. Calls `AgentOrchestrator.execute(request)` async generator
3. Maps each `AgentEvent` → WS frame (see §3.2 table)
4. Handles `AgentToolConfirmationRequiredEvent` → waits for `tool_confirm` message

### 3.6 Memory Service — Extended Types

**Current:** `MemoryType` has `FACT`, `PREFERENCE`, `WRITING_STYLE`.

**Target:** Add `AGENT_STATE` type for cross-session agent execution context:

```python
class MemoryType(str, Enum):
    FACT          = "fact"           # existing
    PREFERENCE    = "preference"     # existing
    WRITING_STYLE = "writing_style"  # existing
    AGENT_STATE   = "agent_state"    # NEW — stores agent plan fragments, tool results
```

`MemoryService.store_agent_state(user_id, session_id, state_dict)` — new method that serializes agent execution state into a memory entry for long-running or resumed tasks.

`MemoryService.retrieve_agent_state(user_id, session_id)` — retrieves and deserializes.

No schema change required — the `memories` table already has `memory_type` as a string column and `content` as text.

### 3.7 Celery Workers — Agent Queue

One new worker is added. All existing workers are unchanged.

```python
# backend/app/workers/agent_worker.py (NEW)
@celery_app.task(bind=True, queue="agents", max_retries=3)
def run_agent_task(self, request_dict: dict, user_id: str) -> dict:
    """Execute a long-running AgentRequest asynchronously.

    Used for tasks that exceed the WebSocket timeout (> 60 s):
    - Large document analysis
    - Multi-step research tasks
    - Background MCP workflows

    Results are pushed to the user via FCM notification or
    retrieved via GET /agents/executions/{execution_id}
    """
```

**Celery configuration update** (in `celery_app.py`):
```python
task_routes = {
    ...existing routes...
    "app.workers.agent_worker.*": {"queue": "agents"},
}
include = [
    ...existing includes...
    "app.workers.agent_worker",
]
```

**Docker Compose update** — Celery worker command gets the `agents` queue:
```yaml
command: >
  python -m celery -A app.workers.celery_app worker
  --loglevel=info --pool=solo
  -Q celery,ingestion,notifications,gdpr,alerts,agents
```

### 3.8 API Surface Changes

**New endpoints (additive — no existing endpoints changed):**

```
GET  /agents/capabilities          → List registered agent names + capabilities
GET  /agents/executions/{id}       → Poll async agent execution result
POST /agents/execute               → Trigger async agent execution (returns execution_id)

GET  /documents/{id}/metadata      → Extracted document-level metadata (NEW)
POST /documents/search             → Hybrid search (ANN + pgvector + BM25) (NEW)

GET  /mcp/tools                    → (existing — unchanged)
POST /mcp/invoke                   → (existing — unchanged)
POST /mcp/invoke/stream            → NEW: streaming MCP tool execution
```

**Modified endpoints (backward-compatible):**

```
WS /ws/chat/{id}   → accepts new "tool_confirm" message type
                   → emits new agent_* event types
                   → existing token/done/error unchanged
```

### 3.9 Database Changes

**Alembic migration 0017** (new):

```sql
-- Add tsvector column and GIN index for BM25 full-text search
ALTER TABLE document_chunks
  ADD COLUMN fts_vector tsvector
    GENERATED ALWAYS AS (to_tsvector('english', chunk_text)) STORED;

CREATE INDEX idx_document_chunks_fts ON document_chunks USING GIN (fts_vector);

-- Add document-level metadata columns
ALTER TABLE documents
  ADD COLUMN doc_title_extracted TEXT,
  ADD COLUMN doc_metadata JSONB;

-- Add agent execution persistence table
CREATE TABLE agent_executions (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  execution_id  TEXT NOT NULL UNIQUE,
  status        TEXT NOT NULL DEFAULT 'STARTED',
  request_json  JSONB NOT NULL,
  result_json   JSONB,
  error_json    JSONB,
  started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at  TIMESTAMPTZ,
  agent_name    TEXT,
  steps_taken   INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_agent_executions_user ON agent_executions(user_id, started_at DESC);
```

This is the **only** schema change. Existing migrations 0001–0016 are untouched.

---

## 4. Android Target Architecture

All Android changes follow the existing MVVM + Clean Architecture + Hilt patterns. No existing module is deleted or restructured.

### 4.1 JWT Auth for WebSocket — Fix

**Current problem (C1):** `ChatDetailViewModel` uses `val jwt = "placeholder_jwt"`.

**Target:** Real token injected from `AuthRepository`:

```kotlin
// In ChatDetailViewModel (update existing code)
@HiltViewModel
class ChatDetailViewModel @Inject constructor(
    private val sendMessageUseCase: SendMessageUseCase,
    private val authRepository: AuthRepository,  // ADD this injection
    ...
) : ViewModel() {

    private fun startStreaming(conversationId: String) {
        viewModelScope.launch {
            val jwt = authRepository.getAccessToken()  // reads from SecureStorage
                ?: return@launch // not authenticated
            aiStreamClient.connect(conversationId, jwt)
                .collect { event -> handleStreamEvent(event) }
        }
    }
}
```

`AuthRepository.getAccessToken()` already exists in `domain/repository/AuthRepository.kt` and `data/repository/AuthRepositoryImpl.kt` — no new interface needed.

### 4.2 Agent Event Protocol Extension

`AIStreamClient` interface and `AIStreamClientImpl` are extended to parse new event types:

```kotlin
// core-ai: StreamEvent.kt (additive — existing cases unchanged)
sealed class StreamEvent {
    data class Token(val text: String) : StreamEvent()          // existing
    data class Done(val usage: TokenUsage) : StreamEvent()       // existing
    data class Error(val message: String) : StreamEvent()        // existing
    data class ToolCall(val toolName: String, val toolInput: Map<String, Any>) : StreamEvent() // existing

    // NEW — additive, parsed from new WS frame types
    data class AgentStarted(val agentName: String) : StreamEvent()
    data class AgentThinking(val thought: String) : StreamEvent()
    data class ToolStarted(val toolName: String, val input: Map<String, Any>) : StreamEvent()
    data class ToolCompleted(val toolName: String, val result: String) : StreamEvent()
    data class ToolFailed(val toolName: String, val error: String) : StreamEvent()
    data class RetrievalCompleted(val chunkCount: Int, val sources: List<String>) : StreamEvent()
    data class ToolConfirmationRequired(val toolName: String, val description: String) : StreamEvent()
}
```

`AIStreamClientImpl.parseEvent()` is extended with `else -> null` fallthrough for unknown types — existing clients silently ignore new event types (backward-compatible).

`ChatDetailViewModel` handles new events:
```kotlin
is StreamEvent.AgentThinking    → update uiState.agentThought
is StreamEvent.ToolStarted      → update uiState.activeToolCall
is StreamEvent.ToolCompleted    → clear uiState.activeToolCall, add to toolHistory
is StreamEvent.ToolConfirmationRequired → uiState.pendingConfirmation = event
```

**New Compose components** in `core-ui`:
- `AgentProgressCard` — shows thinking indicator, active tool name, completed steps
- `ToolConfirmationDialog` — confirmation dialog for MCP tools that require consent
- `RetrievalSourcesCard` — shows RAG retrieval sources with BM25/ANN badge

### 4.3 MCP Tool UI Module

New feature module **`feature-mcp`** (follows existing feature module structure):

```
feature-mcp/
├── build.gradle.kts
└── src/main/kotlin/com/aiassistant/feature/mcp/
    ├── MCPToolsScreen.kt           ← Lists available tools from GET /mcp/tools
    ├── MCPToolsViewModel.kt        ← @HiltViewModel
    ├── MCPToolsUiState.kt
    └── di/
        └── MCPDataModule.kt
```

**Domain additions** (`:domain`):
- `MCPTool` model already exists at `domain/model/MCPTool.kt` — no change
- `MCPRepository` interface (NEW): `getTools(): Flow<List<MCPTool>>`, `invokeTool(name, params): MCPToolResult`

**Data additions** (`:data`):
- `MCPApiService.kt` (NEW Retrofit service for `/mcp/tools` and `/mcp/invoke`)
- `MCPRepositoryImpl.kt` (NEW implements `MCPRepository`)
- `MCPDataModule.kt` (NEW Hilt module)

This is a pure addition — no existing modules are changed.

### 4.4 Document/RAG UI Enhancements

`feature-rag` gains two enhancements to the existing screens (additive Compose code):

1. **Citation quality indicator** — `DocumentChatViewModel` parses the `retrieval_path` field from citations. `RagComponents.CitationCard` (in `core-ui`) shows a badge: `ANN` / `Keyword` / `Hybrid`.

2. **Document metadata card** — `RAGViewModel` calls the new `GET /documents/{id}/metadata` endpoint on document selection. `DocumentDetailScreen` shows extracted title, author, and creation date.

Both use the existing `DocumentRepository` interface — a single new method is added:
```kotlin
// domain/repository/DocumentRepository.kt (additive)
suspend fun getDocumentMetadata(documentId: String): DocumentMetadata
```

### 4.5 On-Device RAG — Deduplication

**Current problem (D3):** `core-on-device-ai` and `feature-on-device-ai` contain identical files.

**Target resolution:**
- `core-ai` (or `core-on-device-ai`) is the **owner** of all capability detection and model management logic
- `feature-on-device-ai` is refactored to depend on `core-on-device-ai` as a library — it provides UI only
- Files that are currently duplicated are deleted from the feature module

This is a **refactoring task** (no new functionality) tracked separately in the implementation plan. Production behavior is unchanged.

---

## 5. WebSocket Protocol — Extended Contract

The protocol is fully backward-compatible. Clients that ignore unknown `type` values continue to work.

### Client → Server (additions)

```jsonc
// Confirm a tool that requires user consent (NEW)
{ "type": "tool_confirm", "tool_name": "github", "confirmed": true }

// Request agent-mode explicitly (NEW — optional, classifier handles auto-detection)
{ "user_message": "...", "provider": "agent" }
```

### Server → Client (additions)

```jsonc
// Agent started (NEW)
{ "type": "agent_started", "agent_name": "rag_agent" }

// Agent is reasoning (NEW)
{ "type": "agent_thinking", "thought": "I need to search the user's documents..." }

// Tool execution started (NEW — replaces silent tool_call)
{ "type": "tool_started", "tool_name": "github", "tool_input": { "action": "list_issues" } }

// Tool execution completed (NEW)
{ "type": "tool_completed", "tool_name": "github", "result_summary": "Found 3 open issues" }

// Tool execution failed (NEW)
{ "type": "tool_failed", "tool_name": "github", "error": "Authentication failed" }

// RAG retrieval completed (NEW)
{ "type": "retrieval_completed", "chunk_count": 5, "sources": ["doc1.pdf p.3", "doc2.pdf p.7"] }

// Tool confirmation required (NEW)
{ "type": "tool_confirmation_required", "tool_name": "gmail", "description": "Send email to john@example.com?" }

// Existing — UNCHANGED
{ "type": "token",     "data": "<text chunk>" }
{ "type": "done",      "usage": { "inputTokens": 123, "outputTokens": 456 } }
{ "type": "error",     "message": "<description>" }
{ "type": "tool_call", "toolName": "github", "toolInput": { ... } }  // legacy, kept for compatibility
{ "type": "ping" }
```

---

## 6. Data Flow Diagrams

### 6.1 Target Agent Request Flow

```
User types: "Search my documents for the budget report and create a GitHub issue"
    │
    ▼
ChatDetailViewModel.sendMessage()
    │  JWT from SecureStorage (FIXED from C1)
    ▼
AIStreamClientImpl.connect() → WSS /ws/chat/{id}?token=<jwt>
    │
    ▼  [backend — websocket/router.py]
authenticate_websocket(token)
    │
    ▼
RequestClassifier.classify("Search my documents...") → AGENT_TASK
    │
    ▼
AgentRequest {
    user_id, input: "Search my documents...",
    capabilities: [RAG_RETRIEVAL, TOOL_USE],
    context: { memories: [...], conversation_history: [...] }
}
    │
    ▼
AgentOrchestrator.execute(request)
    │
    ├── 1. AgentRouter.route(request, registry)
    │        → ToolAgent (has RAG_RETRIEVAL + TOOL_USE capabilities)
    │
    ├── 2. AgentPlanner.build_plan(request, tool_agent, registry)
    │        → [Step 1: RagAgent, Step 2: ToolAgent(github)]
    │
    ├── 3. yield AgentStartedEvent → WS: {"type":"agent_started","agent_name":"tool_agent"}
    │
    ├── 4. Step 1: RagAgent.execute()
    │        → yield AgentThinkingEvent → WS: {"type":"agent_thinking","thought":"..."}
    │        → RAGService.query_documents() [hybrid search]
    │        → yield AgentRetrievalCompletedEvent → WS: {"type":"retrieval_completed",...}
    │        → yield AgentTokenEvent → WS: {"type":"token","data":"Found budget report..."}
    │
    ├── 5. Step 2: ToolAgent.execute() (handoff input = RAG result)
    │        → LLMService.generate() → decides: call github tool
    │        → yield AgentToolStartedEvent → WS: {"type":"tool_started","tool_name":"github"}
    │        → MCPBroker.invoke("github", {action:"create_issue",...}, user_id)
    │             → AuditService.log_mcp_invoke() [mandatory]
    │             → GitHubConnector.invoke()
    │        → yield AgentToolCompletedEvent → WS: {"type":"tool_completed",...}
    │        → yield AgentTokenEvent × N → WS: {"type":"token","data":"..."}
    │
    └── 6. yield AgentCompletedEvent → WS: {"type":"done","usage":{...}}

    [Android side]
    StreamEvent.AgentStarted  → show AgentProgressCard
    StreamEvent.AgentThinking → show thought bubble
    StreamEvent.RetrievalCompleted → show source list
    StreamEvent.Token         → accumulate in streamingText
    StreamEvent.ToolStarted   → show tool spinner
    StreamEvent.ToolCompleted → dismiss tool spinner, log in toolHistory
    StreamEvent.Done          → commit message, hide progress card
```

### 6.2 Target MCP Tool Flow

```
ToolAgent.execute() decides to invoke a tool
    │
    ▼
MCPBroker.invoke_with_streaming(tool_name, params, user_id, ws_proxy)
    │
    ├── AuditService.log_mcp_invoke()          [ALWAYS — existing behavior]
    │
    ├── connector.requires_confirmation()?
    │     YES → yield AgentToolConfirmationRequiredEvent
    │            → WS: {"type":"tool_confirmation_required",...}
    │            → wait for {"type":"tool_confirm","confirmed":true} from client
    │            → if confirmed=false → yield AgentToolFailedEvent (user declined)
    │     NO  → proceed
    │
    ├── MCPToolConnector.invoke(params)
    │     → actual external API call (GitHub, Gmail, Slack, Jira, etc.)
    │
    ├── yield AgentToolCompletedEvent (success)
    │       OR AgentToolFailedEvent (error)
    │
    └── AuditService.update_mcp_result(audit_id, status)
```

### 6.3 Target RAG Ingestion Flow

```
POST /documents (unchanged entry point)
    │
    ▼
validate MIME + size + extension          [existing]
    │
    ▼
StorageService.upload() → MinIO/GCS       [existing]
    │
    ▼
ingest_document_task.delay()              [existing Celery task, extended]
    │
    ▼ [rag_worker.py — extended, not replaced]
RAGService.extract_text()                 [existing]
    │
    ▼
RAGService.chunk_text()                   [existing]
    │
    ▼
RAGService.embed_and_store()              [existing: ChromaDB + pgvector]
    │
    ▼
RAGService.update_fts_index()             [NEW: populate tsvector column]
    │
    ▼
RAGService.extract_document_metadata()    [NEW: title, author, date from PDF/DOCX]
    │
    ▼
DocumentRepository.update_metadata()      [NEW: save to documents.doc_metadata]
    │
    ▼
Job status → COMPLETED
RAGService.send_ingestion_notification()  [existing]
```

### 6.4 Target RAG Query Flow

```
POST /documents/query {query, top_k}      [existing endpoint]
OR
POST /documents/search {query, top_k, search_mode}  [NEW endpoint]
    │
    ▼ [RAGService.query_documents() — extended]
embed(query) via SentenceTransformer       [existing]
    │
    ├── Path A: ChromaDB.query()           [existing ANN search]
    │
    ├── Path B: pgvector cosine search     [NEW — uses document_chunks.embedding]
    │
    └── Path C: BM25 tsvector search       [NEW — uses document_chunks.fts_vector]
                                           (only when query has ≥3 words)
    │
    ▼
Reciprocal Rank Fusion (RRF)              [NEW — fuses A+B+C results]
    │
    ▼
Top k chunks → assemble context           [existing]
    │
    ▼
LLMService.generate(context + query)      [calls unified LLM layer]
    │
    ▼
Response + enhanced citations:
  { answer, citations: [{ doc, page, text, retrieval_path, score }] }
```

---

## 7. Reuse Strategy — What Is NOT Rebuilt

This section lists every component that must be reused directly to avoid duplicate implementations.

| Component | Reuse Rule |
|---|---|
| `agents/base.py` Agent ABC | All new agents subclass this — no new base class |
| `agents/orchestrator.py` AgentOrchestrator | Wired into router — not replaced |
| `agents/registry.py` AgentRegistry | Populated at startup — not replaced |
| `agents/models.py` AgentEvent hierarchy | All new events use these classes — no parallel event model |
| `services/rag_service.py` RAGService | Extended in-place — not replaced |
| `services/mcp_broker.py` MCPBroker | Extended in-place — not replaced |
| All 9 MCP connectors | Unchanged — registered via existing `register()` |
| `services/memory_service.py` MemoryService | Add `AGENT_STATE` type only — not replaced |
| `llm/base.py` LLMProvider ABC | All new providers implement this — not replaced |
| `llm/providers/gemini_provider.py` GeminiProvider | Unchanged |
| `llm/providers/local_gemma_provider.py` LocalGemmaProvider | Unchanged |
| `workers/rag_worker.py` | Extended with FTS step — not replaced |
| `workers/celery_app.py` | Add agents queue only — not replaced |
| `security/` (all files) | Unchanged — agent routes use existing `get_current_user` + `require_roles` |
| `database/redis.py` | Unchanged |
| `core-ai` AIStreamClient interface | Extended with new StreamEvent variants — interface contract preserved |
| `core-ai` MiniLmEmbeddingModel, LocalVectorIndexImpl | Unchanged |
| `domain/agent/` (all Kotlin interfaces) | Android implementations wired in — not replaced |
| `data/agent/` (all concrete Android agents) | Registered via AgentRegistry — not replaced |
| Room database + all DAOs | Unchanged — no new Android entities needed |
| All Compose components in `core-ui` | Extended with 3 new components — existing components unchanged |

---

## 8. Duplication Resolution Plan

Each duplication issue from the current state is resolved without breaking existing functionality:

| Issue | Resolution | When |
|---|---|---|
| **D1** — Two LLM provider layers | Wrap `BaseLLMClient` implementations behind new `LLMProvider` subclasses. Update `AIOrchestrator` to call `LLMService`. Retire direct `BaseLLMClient` usage. | Phase 2 (LLM unification) |
| **D2** — AgentOrchestrator not wired | Wire `AgentOrchestrator` as primary agent path via `RequestClassifier` in WebSocket router. | Phase 1 (agent wiring) |
| **D3** — core-on-device-ai duplicates feature-on-device-ai | `feature-on-device-ai` deletes duplicate files, depends on `core-on-device-ai` as a library module. | Phase 3 (Android dedup) |
| **D4** — Flutter / Android WebSocket duplication | Document the WS contract in `docs/architecture/`. Protocol changes go through a versioning process. Full unification out of scope. | Ongoing |
| **D5** — `app/HomeDashboard` vs `feature-dashboard` | Remove `app/HomeDashboard.kt` and `app/HomeDashboardViewModel.kt`. App module uses `feature-dashboard` only. | Phase 3 (Android dedup) |
| **D6** — Three modules for on-device model management | Consolidate into `core-on-device-ai`. `feature-on-device-rag` and `feature-on-device-ai` delegate to it. | Phase 3 (Android dedup) |
| **C1** — JWT placeholder | Wire `AuthRepository.getAccessToken()` in `ChatDetailViewModel`. | Phase 1 (critical fix) |
| **M1** — Unused chromadb service in Docker Compose | Remove `chromadb:` service block from `docker-compose.yml`. Backend uses embedded `PersistentClient`. | Phase 1 (cleanup) |

---

## 9. Non-Functional Requirements

### Performance
- Agent execution P50 latency ≤ 3 s for single-step tasks, P50 ≤ 15 s for 3-step plans
- RAG hybrid search adds ≤ 200 ms over existing ANN-only path (pgvector and BM25 run in parallel)
- New `agent_executions` table: write latency ≤ 5 ms (async, non-blocking)
- Celery agent queue: max 5 concurrent tasks per worker (configurable via `AGENT_WORKER_CONCURRENCY`)

### Security
- All new agent routes require `get_current_user` + `require_roles("user")` — same as existing routes
- Every MCP tool invocation writes an `AuditLog` entry — existing `MCPBroker` guarantee preserved
- `tool_confirmation_required` flow: user explicit consent for all destructive MCP tools (`GmailConnector.send`, `GitHubConnector.create_issue`, etc.)
- Agent execution results stored in `agent_executions` are scoped to `user_id` — cross-user access impossible
- New `BrowserConnector` and `DatabaseConnector`: read-only operations only; no writes

### Observability
- Agent events emit `ObservabilityEvent` entries using existing event bus:
  - `AGENT_STEP_START`, `AGENT_STEP_END`, `TOOL_CALL`, `TOOL_RESULT` event types
- Prometheus metrics: `agent_execution_duration_seconds` (histogram), `agent_step_count` (counter), `mcp_tool_invocations_total` (counter by tool_name)
- All agent trace IDs propagated via existing OpenTelemetry instrumentation

### Compatibility
- WebSocket protocol: fully backward-compatible (existing clients unaffected by new event types)
- REST API: no breaking changes to existing endpoints
- Android: no existing feature module is deleted or restructured
- Database: migration 17 only adds columns and tables — no column removals or type changes

---

## 10. Dependency Map — New vs. Existing

```
NEW components and their dependencies on existing code:

agent_worker.py (NEW)
    └── AgentOrchestrator (existing agents/orchestrator.py)
    └── LLMService (existing llm/service.py)
    └── RAGService (existing services/rag_service.py)
    └── MCPBroker (existing services/mcp_broker.py)
    └── celery_app (existing workers/celery_app.py)

RequestClassifier (NEW — agents/classifier.py)
    └── No dependencies — pure string analysis

WebSocket router update (existing api/websocket/router.py)
    └── AgentOrchestrator (existing)
    └── RequestClassifier (NEW)
    └── AIOrchestrator (existing — kept for SIMPLE_CHAT path)

openai_provider.py, claude_provider.py, ollama_provider.py (NEW llm/providers/)
    └── LLMProvider ABC (existing llm/base.py)
    └── OpenAIClient, ClaudeClient, OllamaClient (existing services/llm_clients.py)

feature-mcp (NEW Android module)
    └── MCPTool model (existing domain/model/MCPTool.kt)
    └── MCPRepository interface (NEW domain/repository/MCPRepository.kt)
    └── core-network OkHttpClient/Retrofit (existing)
    └── core-ui components (existing)

AgentProgressCard, ToolConfirmationDialog, RetrievalSourcesCard (NEW core-ui)
    └── Material3 theme (existing core-ui/AppTheme)
    └── No business logic dependencies

ChatDetailViewModel update (existing feature-chat)
    └── AuthRepository (existing domain/repository/AuthRepository.kt)
    └── AIStreamClient extended StreamEvent (existing core-ai — additive only)
```

---

*End of Target Architecture Document. No production code was changed during the preparation of this document.*
