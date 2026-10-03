# Implementation Plan — MCP, Agent, and Document/RAG Modules

> **Document Type:** Phased Implementation Plan  
> **Date:** 2026-10-01  
> **Status:** Phase 0 (pre-integration interfaces) complete — Phases 0–6 ready for development  
> **Prerequisite reading:** `docs/architecture/current-state.md`, `docs/architecture/target-architecture.md`  
> **Interface reference:** `docs/architecture/domain-interfaces.md`  
> **Constraint:** No production functionality is changed until each phase is fully tested and reviewed

---

> ### ✅ Pre-Phase: Core Domain Interfaces — COMPLETE
>
> Before Phases 0–6 begin, all domain-level port/adapter interfaces have been
> created and tested. These are the contracts every phase builds against.
>
> **Backend** — `backend/app/interfaces/core.py`:  
> `IPlanner`, `IAgentExecutor`, `IMemoryStore`, `IDocumentLoader`, `IEmbeddingProvider`,
> `IVectorStore`, `IRetriever`, `IMCPClient`, `IToolExecutor` + 10 value objects.  
> **70 backend tests passing.**
>
> **Android** — `domain/src/main/kotlin/com/aiassistant/domain/agent/`:  
> `MemoryStore`, `DocumentLoader`, `EmbeddingProvider`, `VectorStore`, `Retriever`,
> `MCPClient`, `ToolExecutor` + associated value objects and error types.  
> **130 Android tests passing.**
>
> Existing interfaces reused without modification: `LLMProvider`, `Agent`, `MCPToolConnector`
> (backend); `LlmClient`, `Agent`, `AgentOrchestrator`, `AgentPlanner`, `AgentRouter`,
> `AgentRegistry`, `Tool`, `ToolRegistry` (Android).
>
> Full interface reference: `docs/architecture/domain-interfaces.md`

---

## Table of Contents

1. [Overview & Phasing Strategy](#1-overview--phasing-strategy)
2. [Phase 0 — Pre-flight Fixes (Unblocking Work)](#2-phase-0--pre-flight-fixes-unblocking-work)
3. [Phase 1 — Agent Wiring & Critical Fixes](#3-phase-1--agent-wiring--critical-fixes)
4. [Phase 2 — LLM Layer Unification](#4-phase-2--llm-layer-unification)
5. [Phase 3 — MCP First-Class Integration](#5-phase-3--mcp-first-class-integration)
6. [Phase 4 — Document/RAG Enhancement](#6-phase-4--documentrag-enhancement)
7. [Phase 5 — Android Agent Protocol & MCP UI](#7-phase-5--android-agent-protocol--mcp-ui)
8. [Phase 6 — Deduplication & Cleanup](#8-phase-6--deduplication--cleanup)
9. [Task Reference Table](#9-task-reference-table)
10. [File Change Index](#10-file-change-index)
11. [Test Coverage Requirements](#11-test-coverage-requirements)
12. [Rollback Procedures](#12-rollback-procedures)
13. [Definition of Done](#13-definition-of-done)

---

## 1. Overview & Phasing Strategy

### Phases at a Glance

| Phase | Name | Touches | Risk | Duration |
|---|---|---|---|---|
| **0** | Pre-flight Fixes | Backend venv, Docker Compose | Low | 1 day |
| **1** | Agent Wiring & Critical Fixes | Backend WebSocket router, Android JWT, agent startup wiring | Medium | 3–4 days |
| **2** | LLM Layer Unification | Backend `llm/providers/`, `services/llm_clients.py` | Medium | 2–3 days |
| **3** | MCP First-Class Integration | Backend `mcp_broker.py`, `agents/tool_agent.py`, WebSocket router | Medium | 3–4 days |
| **4** | Document/RAG Enhancement | Backend `rag_service.py`, Alembic migration, Celery worker | Medium | 3–4 days |
| **5** | Android Agent Protocol & MCP UI | Android `core-ai`, `feature-chat`, `feature-mcp` (new) | Low–Medium | 4–5 days |
| **6** | Deduplication & Cleanup | Android core-on-device-ai, feature-on-device-ai, Docker Compose | Low | 2 days |

**Total estimated calendar time: ~18–23 working days across 6 phases**

### Ordering Rationale

- Phase 0 unblocks all backend tests — nothing else can be verified without this.
- Phase 1 must precede Phases 3–5 because it wires `AgentOrchestrator` as the primary path.
- Phase 2 can run in parallel with Phase 3 — they touch different files.
- Phases 4 and 5 are independent and can run in parallel after Phase 1 is merged.
- Phase 6 is pure cleanup and has no dependencies on Phases 2–5.

### Rule: Each Phase Ships as a Single PR

Every phase is merged independently, fully tested, and must pass CI before the next phase begins. This ensures any regression is attributable to exactly one phase.

---

## 2. Phase 0 — Pre-flight Fixes (Unblocking Work)

**Goal:** Restore the ability to run backend tests and fix the Docker Compose inconsistency. Zero production code changes.

### Tasks

#### P0-T1: Install missing `pgvector` Python package

**File:** `backend/venv311/` (not tracked in git — local fix)  
**Also:** Verify `requirements.txt` already has `pgvector==0.3.6` ✓

```powershell
# Run in backend directory
.\venv311\Scripts\pip.exe install pgvector==0.3.6
# Verify
.\venv311\Scripts\python.exe -c "import pgvector; print('OK')"
```

After fixing, run the full backend test suite to establish a baseline:
```powershell
.\venv311\Scripts\pytest.exe tests/unit/ -x --tb=short 2>&1 | Tee-Object baseline-test-results.txt
```

**Acceptance:** All previously-failing test collection errors are gone. Record pass/fail count as the baseline.

#### P0-T2: Remove unused `chromadb` Docker Compose service

**File:** `docker-compose.yml`

The `chromadb:` service block is inconsistent with the backend's use of embedded `PersistentClient` (see current-state.md §11, M1). Remove the service.

```yaml
# REMOVE this block from docker-compose.yml:
  chromadb:
    image: chromadb/chroma:1.5.9
    restart: unless-stopped
    networks:
      - ai_assistant_net
    ports:
      - "127.0.0.1:8001:8000"
```

**Acceptance:** `docker compose up` starts without the chromadb container. Backend health check passes. ChromaDB embedded client initialises from `CHROMA_PERSIST_DIR`.

#### P0-T3: Document environment setup in RUNNING.md

**File:** `backend/RUNNING.md`

Add a section explaining the `pgvector` requirement and the correct venv setup steps, so future developers don't hit the same issue.

---

## 3. Phase 1 — Agent Wiring & Critical Fixes

**Goal:** Wire `AgentOrchestrator` into the WebSocket router as the agent execution path. Fix the JWT placeholder in Android. Both changes are prerequisite for all subsequent agent and MCP work.

**Dependency:** Phase 0 must be merged first (tests must be runnable).

### Backend Tasks

#### P1-T1: Add `RequestClassifier` to agents module

**New file:** `backend/app/agents/classifier.py`

```python
"""Request type classifier — routes messages to simple chat or agent execution."""
from enum import Enum

class RequestType(Enum):
    SIMPLE_CHAT  = "simple_chat"
    AGENT_TASK   = "agent_task"

class RequestClassifier:
    """Classify an incoming user message as simple chat or an agent task.

    Classification is intentionally lightweight (no LLM call). The heuristics
    cover the majority of cases; the client can override by setting
    provider="agent" in the WebSocket message.

    Rules (in priority order):
    1. provider == "agent"    → AGENT_TASK (explicit override)
    2. message contains tool trigger keywords (@tool, /rag, /code, /search) → AGENT_TASK
    3. message length > 200 chars AND contains a question word → AGENT_TASK
    4. default → SIMPLE_CHAT
    """
    _TOOL_KEYWORDS = frozenset(["@tool", "/rag", "/code", "/search", "/web"])
    _QUESTION_WORDS = frozenset(["what", "how", "why", "when", "where", "who", "find", "search", "analyze", "summarize"])

    @classmethod
    def classify(cls, message: str, provider: str = "") -> RequestType:
        if provider == "agent":
            return RequestType.AGENT_TASK
        lower = message.lower()
        if any(kw in lower for kw in cls._TOOL_KEYWORDS):
            return RequestType.AGENT_TASK
        if len(message) > 200 and any(w in lower.split() for w in cls._QUESTION_WORDS):
            return RequestType.AGENT_TASK
        return RequestType.SIMPLE_CHAT
```

**Test:** `tests/unit/agents/test_classifier.py` — cover all 4 classification rules.

#### P1-T2: Build `AgentRegistry` at startup

**File:** `backend/app/agents/__init__.py` — add `build_registry()` function

```python
# backend/app/agents/__init__.py (add to existing file)
from app.agents.registry import AgentRegistry
from app.agents.chat_agent import ChatAgent
from app.agents.rag_agent import RagAgent
from app.agents.code_agent import CodeAgent
from app.agents.tool_agent import ToolAgent
from app.agents.image_agent import ImageAgent
from app.agents.voice_agent import VoiceAgent
from app.agents.web_agent import WebAgent
from app.agents.pdf_agent import PdfAgent


def build_registry(
    llm_service,
    rag_service,
    mcp_broker,
    memory_service,
) -> AgentRegistry:
    """Construct and return an AgentRegistry populated with all built-in agents."""
    registry = AgentRegistry()
    registry.register(ChatAgent(llm_service=llm_service, memory_service=memory_service))
    registry.register(RagAgent(rag_service=rag_service, llm_service=llm_service))
    registry.register(CodeAgent(llm_service=llm_service))
    registry.register(ToolAgent(mcp_broker=mcp_broker, llm_service=llm_service))
    registry.register(ImageAgent(llm_service=llm_service))
    registry.register(VoiceAgent(llm_service=llm_service))
    registry.register(WebAgent(llm_service=llm_service))
    registry.register(PdfAgent(rag_service=rag_service, llm_service=llm_service))
    return registry
```

#### P1-T3: Add FastAPI dependency for `AgentOrchestrator`

**New file:** `backend/app/api/websocket/agent_deps.py`

```python
"""FastAPI dependency providers for the agent execution subsystem."""
from functools import lru_cache
from fastapi import Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.agents import build_registry
from app.agents.orchestrator import AgentOrchestrator
from app.agents.planner import AgentPlanner
from app.agents.router import AgentRouter
from app.database import get_db  # existing dependency
from app.llm.service import get_llm_service
from app.services.rag_service import RAGService
from app.services.mcp_broker import MCPBroker
from app.services.memory_service import MemoryService


def get_agent_orchestrator(
    db: AsyncSession = Depends(get_db),
) -> AgentOrchestrator:
    llm_service  = get_llm_service()
    rag_service  = RAGService()
    mcp_broker   = MCPBroker(db)
    memory_svc   = MemoryService()
    registry     = build_registry(llm_service, rag_service, mcp_broker, memory_svc)
    router       = AgentRouter()
    planner      = AgentPlanner()
    return AgentOrchestrator(registry=registry, router=router, planner=planner)
```

#### P1-T4: Update WebSocket router to dispatch through `RequestClassifier`

**File:** `backend/app/api/websocket/router.py`

Changes to `_handle_messages()`:
1. Import `RequestClassifier`, `RequestType`, `get_agent_orchestrator`
2. After receiving a `user_message`, call `RequestClassifier.classify(msg, provider)`
3. Route to `_handle_agent_task()` (new) or existing `_handle_simple_chat()` (rename of current path)
4. `_handle_agent_task()` iterates `AgentOrchestrator.execute()` and maps each `AgentEvent` to a WS frame per the event table in `target-architecture.md §3.2`

```python
# Pseudocode addition to _handle_messages() — preserves existing simple chat path
from app.agents.classifier import RequestClassifier, RequestType
from app.agents.models import (
    AgentStartedEvent, AgentThinkingEvent, AgentTokenEvent,
    AgentToolStartedEvent, AgentToolCompletedEvent, AgentToolFailedEvent,
    AgentRetrievalCompletedEvent, AgentCompletedEvent, AgentFailedEvent,
)

async def _handle_agent_task(ws, user, conversation_id, raw_msg, agent_orchestrator):
    from app.agents.models import AgentRequest
    request = AgentRequest(
        user_id=str(user.id),
        input=raw_msg["user_message"],
        conversation_id=conversation_id,
    )
    async for event in await agent_orchestrator.execute(request):
        frame = _agent_event_to_ws_frame(event)
        if frame:
            await ws.send_json(frame)

def _agent_event_to_ws_frame(event) -> dict | None:
    """Map AgentEvent → WebSocket JSON frame."""
    match type(event).__name__:
        case "AgentStartedEvent":
            return {"type": "agent_started", "agent_name": event.agent_name}
        case "AgentThinkingEvent":
            return {"type": "agent_thinking", "thought": event.thought}
        case "AgentTokenEvent":
            return {"type": "token", "data": event.token}   # existing shape
        case "AgentToolStartedEvent":
            return {"type": "tool_started", "tool_name": event.tool_name, "tool_input": event.tool_input}
        case "AgentToolCompletedEvent":
            return {"type": "tool_completed", "tool_name": event.tool_name, "result_summary": event.result_summary}
        case "AgentToolFailedEvent":
            return {"type": "tool_failed", "tool_name": event.tool_name, "error": event.error}
        case "AgentRetrievalCompletedEvent":
            return {"type": "retrieval_completed", "chunk_count": event.chunk_count, "sources": event.sources}
        case "AgentCompletedEvent":
            result = event.result
            return {"type": "done", "usage": {"inputTokens": result.usage.input_tokens if result.usage else 0,
                                               "outputTokens": result.usage.output_tokens if result.usage else 0}}
        case "AgentFailedEvent":
            return {"type": "error", "message": event.result.error.message if event.result.error else "Agent failed"}
        case _:
            return None
```

**Tests:**
- `tests/unit/test_websocket_router.py` — add test cases for `AGENT_TASK` routing path
- `tests/unit/agents/test_classifier.py` — new test file

**Acceptance criteria for P1-T4:**
- SIMPLE_CHAT messages still follow the `AIOrchestrator` path (existing tests pass)
- AGENT_TASK messages go through `AgentOrchestrator` and emit agent event frames
- Existing WebSocket test suite (`test_websocket_router.py`) remains green

#### P1-T5: Add `AgentOrchestrator` startup dependency to `lifespan`

**File:** `backend/app/main.py`

In `lifespan()`, verify all services that `build_registry()` depends on (`LLMService`, `RAGService`) are available. Log agent registry contents at INFO level at startup.

### Android Tasks

#### P1-T6: Fix JWT placeholder in `ChatDetailViewModel`

**File:** `feature-chat/src/main/kotlin/.../ChatDetailViewModel.kt`

```kotlin
// BEFORE (broken):
val jwt = "placeholder_jwt"

// AFTER (correct):
private val jwt: String
    get() = runBlocking { authRepository.getAccessToken() } ?: ""

// OR with proper coroutine injection:
private fun startStreaming(conversationId: String) {
    viewModelScope.launch {
        val token = authRepository.getAccessToken()
        if (token.isNullOrBlank()) {
            _uiState.update { it.copy(error = DomainError.Unauthenticated) }
            return@launch
        }
        aiStreamClient.connect(conversationId, token)
            .collect { event -> handleStreamEvent(event) }
    }
}
```

`authRepository: AuthRepository` is added as a constructor parameter annotated `@Inject`.

**Test:** Update `ChatDetailViewModelTest` to provide a fake `AuthRepository` returning a test token.

### Phase 1 Acceptance Criteria

- [ ] All backend unit tests from Phase 0 baseline still pass
- [ ] `RequestClassifier` unit tests pass (100% branch coverage)
- [ ] WebSocket router tests cover both `SIMPLE_CHAT` and `AGENT_TASK` dispatch paths
- [ ] Android `ChatDetailViewModelTest` passes with real JWT injection
- [ ] `docker compose up` → `/health` returns 200 → `/ready` returns 200

---

## 4. Phase 2 — LLM Layer Unification

**Goal:** Wrap the four remaining `BaseLLMClient` implementations (`OpenAIClient`, `ClaudeClient`, `OllamaClient`, `LlamaClient`) behind the `LLMProvider` interface. Update `AIOrchestrator` to call `LLMService` rather than resolving `BaseLLMClient` instances directly. **No external LLM behaviour changes.**

**Dependency:** Phase 0 (tests runnable). Can run in parallel with Phase 3.

### Tasks

#### P2-T1: Create `OpenAIProvider`

**New file:** `backend/app/llm/providers/openai_provider.py`

```python
"""OpenAI LLM provider — wraps the existing OpenAIClient."""
from collections.abc import AsyncIterator
from app.llm.base import LLMProvider, LLMRequest, LLMResponse, LLMUsage
from app.services.llm_clients import OpenAIClient


class OpenAIProvider(LLMProvider):
    """Thin adapter: delegates all calls to the existing OpenAIClient."""

    def __init__(self, model_override: str | None = None) -> None:
        self._client = OpenAIClient()
        self._model = model_override or "gpt-4o"

    @property
    def provider_name(self) -> str:
        return "openai"

    @property
    def model_name(self) -> str:
        return self._model

    async def generate(self, request: LLMRequest) -> LLMResponse:
        # Delegate to existing OpenAIClient.complete()
        result = await self._client.complete(
            prompt=request.prompt,
            system_prompt=request.system_prompt,
            conversation_id=request.conversation_id,
        )
        return LLMResponse(
            text=result.text,
            provider=self.provider_name,
            model=self._model,
            usage=LLMUsage(
                input_tokens=result.usage.input_tokens,
                output_tokens=result.usage.output_tokens,
            ),
        )

    async def stream(self, request: LLMRequest) -> AsyncIterator[str]:
        async for token in self._client.stream(
            prompt=request.prompt,
            system_prompt=request.system_prompt,
        ):
            yield token
```

#### P2-T2: Create `ClaudeProvider`

**New file:** `backend/app/llm/providers/claude_provider.py`

Same pattern as `OpenAIProvider`, wrapping `ClaudeClient`. Model default: `claude-3-5-sonnet-20241022`.

#### P2-T3: Create `OllamaProvider`

**New file:** `backend/app/llm/providers/ollama_provider.py`

Wraps `OllamaClient`, `LlamaClient`, and `MistralClient`. Model is selected via constructor parameter. `provider_name` returns `"ollama"`.

#### P2-T4: Register new providers in `LLMService`

**File:** `backend/app/llm/service.py`

Update `LLMService._create_provider()` to also resolve `"openai"`, `"claude"`, `"ollama"`, `"llama"`, `"mistral"` by returning the new wrapper providers.

#### P2-T5: Update `AIOrchestrator` to use `LLMService`

**File:** `backend/app/services/ai_orchestrator.py`

Replace `AIOrchestrator._resolve_provider()` (which currently instantiates `BaseLLMClient` subclasses directly) with a call to `LLMService.generate()` / `LLMService.stream()`.

The change is internal — the WebSocket contract and response format are unchanged.

**Test file:** `tests/unit/test_ai_orchestrator_provider_selection.py` — existing file, update to verify that `LLMService` is called, not `BaseLLMClient` directly.

#### P2-T6: Add unit tests for the three new providers

**New test files:**
- `tests/unit/test_openai_provider.py`
- `tests/unit/test_claude_provider.py`
- `tests/unit/test_ollama_provider.py`

Each test mocks the underlying `BaseLLMClient` and verifies the `LLMProvider` interface contract (correct `provider_name`, `model_name`, `generate()` returns `LLMResponse`, `stream()` yields strings).

### Phase 2 Acceptance Criteria

- [ ] All existing LLM tests pass: `test_llm_clients.py`, `test_llm_service.py`, `test_gemini_provider.py`
- [ ] `test_ai_orchestrator_provider_selection.py` updated and passing
- [ ] New provider tests pass for OpenAI, Claude, Ollama
- [ ] No change to any WS message format or REST response schema

---

## 5. Phase 3 — MCP First-Class Integration

**Goal:** Make `ToolAgent` the authorised path for all MCP tool execution. Add streaming tool invocation. Add the tool confirmation dialog flow over WebSocket.

**Dependency:** Phase 1 (agent wiring must be in place).

### Backend Tasks

#### P3-T1: Add `invoke_with_streaming` to `MCPBroker`

**File:** `backend/app/services/mcp_broker.py`

New method alongside the existing `invoke()` (which is **not** modified):

```python
async def invoke_with_streaming(
    self,
    tool_name: str,
    params: dict,
    user_id: str,
    event_callback,  # async callable(AgentEvent)
) -> MCPToolResult:
    """Invoke a tool and emit agent events during execution.

    Calls event_callback with:
    - AgentToolStartedEvent   before invoking the connector
    - AgentToolConfirmationRequiredEvent if requires_confirmation() is True
    - AgentToolCompletedEvent on success
    - AgentToolFailedEvent    on error

    The existing AuditLog write is preserved (requirement: every invocation logged).
    """
    from app.agents.models import (
        AgentToolStartedEvent, AgentToolCompletedEvent,
        AgentToolFailedEvent, AgentToolConfirmationRequiredEvent,
    )
    # ... implementation
```

#### P3-T2: Update `ToolAgent` to use `invoke_with_streaming`

**File:** `backend/app/agents/tool_agent.py`

Replace the direct `MCPBroker.invoke()` call with `MCPBroker.invoke_with_streaming()`, passing the agent execution's event emitter as the callback.

#### P3-T3: Handle `tool_confirm` WebSocket message in router

**File:** `backend/app/api/websocket/router.py`

In `_handle_messages()`, handle `msg_type == "tool_confirm"`:

```python
if msg_type == "tool_confirm":
    # Resume the suspended ToolAgent waiting for user confirmation
    await agent_session_store.confirm_tool(
        user_id=str(user.id),
        conversation_id=conversation_id,
        tool_name=raw_msg.get("tool_name"),
        confirmed=raw_msg.get("confirmed", False),
    )
    continue
```

`AgentSessionStore` is a new thin Redis-backed class that holds a `asyncio.Event` per pending confirmation. The `ToolAgent` awaits it.

**New file:** `backend/app/agents/session_store.py`

#### P3-T4: Add `GET /mcp/tools` schema validation

**File:** `backend/app/api/mcp/router.py` — verify existing endpoint returns correct `MCPToolSchema` list. No functional change; just add test assertions.

#### P3-T5: Add unit tests for MCP broker streaming

**File:** `tests/unit/test_mcp_broker.py` — add test cases for `invoke_with_streaming`, covering:
- Normal execution emits `AgentToolStartedEvent` then `AgentToolCompletedEvent`
- `requires_confirmation=True` emits `AgentToolConfirmationRequiredEvent`
- Connector error emits `AgentToolFailedEvent`
- Audit log is written in all cases

### Android Tasks (MCP-side only — UI deferred to Phase 5)

#### P3-T6: Parse `tool_confirmation_required` in `AIStreamClientImpl`

**File:** `core-ai/src/main/kotlin/.../AIStreamClientImpl.kt`

Add parsing for `tool_confirmation_required` WS frame → `StreamEvent.ToolConfirmationRequired(toolName, description)`.

### Phase 3 Acceptance Criteria

- [ ] `test_mcp_broker.py` covers streaming invocation — all new cases pass
- [ ] `ToolAgent` correctly emits tool events during MCP execution
- [ ] `tool_confirm` message stops the pending confirmation and resumes execution
- [ ] All existing MCP connector tests pass unchanged
- [ ] Audit log verified present for all invocation outcomes

---

## 6. Phase 4 — Document/RAG Enhancement

**Goal:** Add hybrid search (pgvector cosine + BM25 full-text) to the existing RAG retrieval pipeline. Add document-level metadata extraction. Add Alembic migration 0017.

**Dependency:** Phase 0 (tests runnable). Independent of Phases 1–3.

### Tasks

#### P4-T1: Alembic migration 0017

**New file:** `backend/alembic/versions/0017_hybrid_search_and_agent_executions.py`

```python
"""Add tsvector column, document metadata, and agent_executions table.

Revision ID: 0017
Revises: 0016
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB

def upgrade():
    # Full-text search on document chunks
    op.add_column("document_chunks",
        sa.Column("fts_vector", sa.Text(), nullable=True))  # populated by trigger / worker
    op.execute(
        "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_document_chunks_fts "
        "ON document_chunks USING GIN (to_tsvector('english', chunk_text))"
    )

    # Document-level metadata
    op.add_column("documents", sa.Column("doc_title_extracted", sa.Text(), nullable=True))
    op.add_column("documents", sa.Column("doc_metadata", JSONB, nullable=True))

    # Agent execution persistence
    op.create_table(
        "agent_executions",
        sa.Column("id", sa.UUID(), primary_key=True, server_default=sa.text("gen_random_uuid()")),
        sa.Column("user_id", sa.UUID(), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("execution_id", sa.Text(), nullable=False, unique=True),
        sa.Column("status", sa.Text(), nullable=False, server_default="STARTED"),
        sa.Column("request_json", JSONB, nullable=False),
        sa.Column("result_json", JSONB, nullable=True),
        sa.Column("error_json", JSONB, nullable=True),
        sa.Column("started_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("agent_name", sa.Text(), nullable=True),
        sa.Column("steps_taken", sa.Integer(), nullable=False, server_default="0"),
    )
    op.create_index("idx_agent_executions_user", "agent_executions", ["user_id", "started_at"])

def downgrade():
    op.drop_table("agent_executions")
    op.drop_column("documents", "doc_metadata")
    op.drop_column("documents", "doc_title_extracted")
    op.execute("DROP INDEX CONCURRENTLY IF EXISTS idx_document_chunks_fts")
    op.drop_column("document_chunks", "fts_vector")
```

**Important:** Use `CREATE INDEX CONCURRENTLY` to avoid locking the table on production.

#### P4-T2: Add BM25 full-text search path to `RAGService`

**File:** `backend/app/services/rag_service.py`

New private method `_bm25_search(user_id, query, top_k)`:

```python
async def _bm25_search(
    self,
    user_id: str,
    query: str,
    top_k: int,
) -> list[RetrievedChunk]:
    """BM25 full-text search using PostgreSQL tsvector."""
    from sqlalchemy import text
    sql = text("""
        SELECT dc.id, dc.document_id, dc.chunk_text, dc.chunk_index, dc.page_number,
               ts_rank(to_tsvector('english', dc.chunk_text),
                       plainto_tsquery('english', :query)) AS bm25_score,
               d.title AS document_title
        FROM document_chunks dc
        JOIN documents d ON d.id = dc.document_id
        WHERE d.user_id = :user_id
          AND to_tsvector('english', dc.chunk_text) @@ plainto_tsquery('english', :query)
        ORDER BY bm25_score DESC
        LIMIT :top_k
    """)
    rows = await self._db.execute(sql, {"user_id": user_id, "query": query, "top_k": top_k})
    return [RetrievedChunk(...) for row in rows.fetchall()]
```

#### P4-T3: Add pgvector cosine search path to `RAGService`

**File:** `backend/app/services/rag_service.py`

New private method `_pgvector_search(user_id, embedding, top_k)`:

```python
async def _pgvector_search(
    self,
    user_id: str,
    embedding: list[float],
    top_k: int,
) -> list[RetrievedChunk]:
    """ANN search using the pgvector cosine operator."""
    from sqlalchemy import text
    sql = text("""
        SELECT dc.id, dc.document_id, dc.chunk_text, dc.chunk_index, dc.page_number,
               1 - (dc.embedding <=> CAST(:embedding AS vector)) AS cosine_score,
               d.title AS document_title
        FROM document_chunks dc
        JOIN documents d ON d.id = dc.document_id
        WHERE d.user_id = :user_id
          AND dc.embedding IS NOT NULL
        ORDER BY dc.embedding <=> CAST(:embedding AS vector)
        LIMIT :top_k
    """)
    rows = await self._db.execute(sql, {
        "user_id": user_id,
        "embedding": str(embedding),
        "top_k": top_k,
    })
    return [RetrievedChunk(...) for row in rows.fetchall()]
```

#### P4-T4: Implement Reciprocal Rank Fusion in `RAGService`

**File:** `backend/app/services/rag_service.py`

New private method `_reciprocal_rank_fusion(result_lists, top_k, k=60)`.

Update `query_documents()` to call all three retrieval paths in parallel using `asyncio.gather()` and fuse results via RRF.

Add `retrieval_path` field to `RetrievedChunk` (one of `"chroma_ann"`, `"pgvector_cosine"`, `"bm25_fts"`, `"rrf_fused"`).

#### P4-T5: Extend ingestion worker with FTS index update

**File:** `backend/app/workers/rag_worker.py`

After `embed_and_store()` completes, call a new `RAGService.update_fts_metadata()` method that updates the `documents.doc_title_extracted` and `documents.doc_metadata` columns (extracted by `pypdf` / `python-docx`).

The `fts_vector` column uses a `GENERATED ALWAYS AS` expression in PostgreSQL — no explicit update needed once the migration runs.

#### P4-T6: Add hybrid search endpoint

**File:** `backend/app/api/rag/router.py`

```python
@router.post("/documents/search")
async def hybrid_search(
    request: HybridSearchRequest,
    current_user: User = Depends(get_current_user),
    rag_service: RAGService = Depends(get_rag_service),
) -> HybridSearchResponse:
    """Search documents using hybrid ANN + pgvector + BM25 retrieval."""
    result = await rag_service.query_documents(
        user_id=str(current_user.id),
        query=request.query,
        top_k=request.top_k,
        search_mode=request.search_mode,  # "hybrid" | "ann" | "bm25"
    )
    return HybridSearchResponse(answer=result.answer, citations=result.citations)
```

#### P4-T7: Add tests for hybrid search

**File:** `tests/unit/test_rag_service.py` — extend with:
- `test_bm25_search_returns_results`
- `test_pgvector_search_returns_results`
- `test_rrf_fusion_deduplicates_chunks`
- `test_hybrid_search_combines_all_paths`

**File:** `tests/unit/test_rag_retrieval.py` — verify citation `retrieval_path` field is populated.

### Phase 4 Acceptance Criteria

- [ ] Migration 0017 applies cleanly (`alembic upgrade head`)
- [ ] Migration 0017 rolls back cleanly (`alembic downgrade -1`)
- [ ] All existing RAG tests pass: `test_rag_service.py`, `test_rag_retrieval.py`, `test_rag_pipeline_26_3.py`
- [ ] New hybrid search tests pass
- [ ] Ingestion worker end-to-end: document ingested, FTS index populated, metadata extracted
- [ ] P50 query latency regression < 200 ms (measured by property test)

---

## 7. Phase 5 — Android Agent Protocol & MCP UI

**Goal:** Update Android to handle all new WebSocket event types from Phase 1. Add the `feature-mcp` module. Add three new Compose components. No production UI changes to existing features.

**Dependency:** Phase 1 (Android JWT fix is a prerequisite). Phase 3 for `tool_confirmation_required` handling.

### Tasks

#### P5-T1: Extend `StreamEvent` sealed class

**File:** `core-ai/src/main/kotlin/com/aiassistant/core/ai/StreamEvent.kt`

Add new variants as documented in `target-architecture.md §4.2`. All new cases have a default fallthrough in the parser — unknown frame types return `null` and are silently ignored by existing `collect {}` code.

#### P5-T2: Update `AIStreamClientImpl.parseEvent()`

**File:** `core-ai/src/main/kotlin/com/aiassistant/core/ai/AIStreamClientImpl.kt`

Extend the `when (type)` block in `parseEvent()`. Unknown types return `null` (already handled by the existing `else` branch in the callbackFlow).

#### P5-T3: Update `ChatDetailViewModel` to handle new events

**File:** `feature-chat/src/main/kotlin/.../ChatDetailViewModel.kt`

```kotlin
// Extend handleStreamEvent() — additive only
is StreamEvent.AgentStarted         -> updateAgentState(agentName = event.agentName)
is StreamEvent.AgentThinking        -> updateAgentThought(event.thought)
is StreamEvent.ToolStarted          -> addActiveToolCall(event.toolName)
is StreamEvent.ToolCompleted        -> resolveToolCall(event.toolName, event.resultSummary)
is StreamEvent.ToolFailed           -> failToolCall(event.toolName, event.error)
is StreamEvent.RetrievalCompleted   -> updateRetrievalSources(event.sources)
is StreamEvent.ToolConfirmationRequired -> showConfirmationDialog(event.toolName, event.description)
```

Add corresponding fields to `ChatDetailUiState`:
```kotlin
val agentName: String? = null
val agentThought: String? = null
val activeToolCall: String? = null
val toolCallHistory: List<ToolCallRecord> = emptyList()
val pendingConfirmation: PendingConfirmation? = null
val retrievalSources: List<String> = emptyList()
```

#### P5-T4: Add `AgentProgressCard` Compose component

**File:** `core-ui/src/main/kotlin/.../AgentProgressCard.kt`

```kotlin
@Composable
fun AgentProgressCard(
    agentName: String?,
    thought: String?,
    activeToolCall: String?,
    toolCallHistory: List<ToolCallRecord>,
    modifier: Modifier = Modifier,
) {
    // Shows: agent name badge, animated thinking dots when thought != null,
    // tool spinner when activeToolCall != null, completed tool chips in history
}
```

#### P5-T5: Add `ToolConfirmationDialog` Compose component

**File:** `core-ui/src/main/kotlin/.../ToolConfirmationDialog.kt`

```kotlin
@Composable
fun ToolConfirmationDialog(
    toolName: String,
    description: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
)
```

When user confirms, `ChatDetailViewModel.confirmTool()` sends `{"type":"tool_confirm","tool_name":"...","confirmed":true}` over WebSocket via `AIStreamClientImpl`.

#### P5-T6: Add `RetrievalSourcesCard` Compose component

**File:** `core-ui/src/main/kotlin/.../RetrievalSourcesCard.kt`

Shows a collapsible list of RAG retrieval sources with a badge indicating the retrieval path (`ANN` / `Keyword` / `Hybrid`).

#### P5-T7: Create `feature-mcp` module

**New Gradle module** following the existing feature module pattern:

```
feature-mcp/
├── build.gradle.kts
└── src/main/kotlin/com/aiassistant/feature/mcp/
    ├── MCPToolsScreen.kt
    ├── MCPToolsViewModel.kt
    ├── MCPToolsUiState.kt
    └── di/
        └── MCPDataModule.kt
```

**Domain additions:**
- `domain/repository/MCPRepository.kt` — `getTools(): Flow<List<MCPTool>>`, `invokeTool(name: String, params: Map<String, Any>): Result<MCPToolResult>`

**Data additions:**
- `data/remote/mcp/MCPApiService.kt` — Retrofit interface for `/mcp/tools` and `/mcp/invoke`
- `data/remote/mcp/MCPRemoteDataSource.kt`
- `data/repository/MCPRepositoryImpl.kt`
- `data/di/MCPDataModule.kt`

**App module:** Add `feature-mcp` to `:app`'s `dependencies {}` block and wire navigation in `AppNavHost`.

#### P5-T8: Update `ChatDetailScreen` to render agent UI

**File:** `feature-chat/src/main/kotlin/.../ChatDetailScreen.kt`

```kotlin
// Additive composable in the chat detail layout
if (uiState.agentName != null || uiState.activeToolCall != null) {
    AgentProgressCard(
        agentName = uiState.agentName,
        thought = uiState.agentThought,
        activeToolCall = uiState.activeToolCall,
        toolCallHistory = uiState.toolCallHistory,
    )
}

uiState.pendingConfirmation?.let { confirmation ->
    ToolConfirmationDialog(
        toolName = confirmation.toolName,
        description = confirmation.description,
        onConfirm = { viewModel.confirmTool(confirmation.toolName, true) },
        onDismiss = { viewModel.confirmTool(confirmation.toolName, false) },
    )
}
```

### Phase 5 Acceptance Criteria

- [ ] Android unit tests for `ChatDetailViewModel` cover all new `StreamEvent` types
- [ ] `AgentProgressCard`, `ToolConfirmationDialog`, `RetrievalSourcesCard` render correctly in Compose previews
- [ ] `MCPToolsScreen` shows the list of tools from `GET /mcp/tools`
- [ ] `feature-mcp` Hilt module wires cleanly (no injection errors at runtime)
- [ ] Existing `ChatDetailViewModelTest` cases still pass (no regression)
- [ ] Android build with all flavors (`local` / `stage` / `production`) succeeds

---

## 8. Phase 6 — Deduplication & Cleanup

**Goal:** Remove duplicated files, eliminate dead code, improve maintainability. Zero behaviour change.

**Dependency:** All previous phases merged and stable.

### Tasks

#### P6-T1: Consolidate `core-on-device-ai` and `feature-on-device-ai` (D3)

1. Audit both modules to confirm identical file list
2. Delete from `feature-on-device-ai/src/`: `DeviceCapabilityDetector.kt`, `HardwareCapabilityDetector.kt`, `ModelManifest.kt`, `OnDeviceAiInitializer.kt`, `OnDeviceCapabilityChecker.kt`, `OnDeviceCapabilityState.kt`, `OnDeviceEngine.kt`, `OnDeviceInferenceClient.kt`, `OnDeviceModelManager.kt`, `RamMonitor.kt`, `StubOnDeviceEngine.kt`, `OnDeviceAiModule.kt`
3. Add `implementation(project(":core-on-device-ai"))` to `feature-on-device-ai/build.gradle.kts`
4. Update imports in `feature-on-device-ai` screens and ViewModels to use `core-on-device-ai` package

#### P6-T2: Remove `app/HomeDashboard.kt` and `app/HomeDashboardViewModel.kt` (D5)

1. Confirm `feature-dashboard` fully covers the functionality
2. Delete `app/src/main/kotlin/.../HomeDashboard.kt`
3. Delete `app/src/main/kotlin/.../HomeDashboardViewModel.kt`
4. Update `AppNavHost` to reference `feature-dashboard` screens only

#### P6-T3: Consolidate on-device model management (D6)

1. Move `ManageModelsScreen` and `ManageModelsViewModel` from `feature-on-device-rag` to `feature-on-device-ai` (or keep in `feature-on-device-rag` but delegate model logic to `core-on-device-ai`)
2. `feature-on-device-rag` and `feature-on-device-ai` both call `core-on-device-ai.OnDeviceModelManager`

#### P6-T4: Update Docker Compose file (M1 — if not done in Phase 0)

Verify the `chromadb:` service block was removed in Phase 0 and `docker-compose.local.yml` / `docker-compose.prod.yml` are also clean.

#### P6-T5: Remove duplicate Kotlin file header comments

**Scope:** Files with the double-copied module comment block (cosmetic). Apply find-replace across all affected files in a single commit. This is purely cosmetic and has no test coverage requirement.

### Phase 6 Acceptance Criteria

- [ ] `feature-on-device-ai` compiles without its own copies of the deduplicated files
- [ ] `feature-on-device-rag` model management delegates to `core-on-device-ai`
- [ ] `app` module compiles without `HomeDashboard.kt` and `HomeDashboardViewModel.kt`
- [ ] Android build with all flavors succeeds
- [ ] No new lint warnings introduced

---

## 9. Task Reference Table

| Task ID | Phase | Description | Files Changed | New/Modified |
|---|---|---|---|---|
| P0-T1 | 0 | Install pgvector in venv311 | venv311 (local) | Fix |
| P0-T2 | 0 | Remove chromadb Docker service | `docker-compose.yml` | Modified |
| P0-T3 | 0 | Document env setup | `backend/RUNNING.md` | Modified |
| P1-T1 | 1 | Add `RequestClassifier` | `agents/classifier.py` | New |
| P1-T2 | 1 | Build `AgentRegistry` at startup | `agents/__init__.py` | Modified |
| P1-T3 | 1 | FastAPI dep for `AgentOrchestrator` | `api/websocket/agent_deps.py` | New |
| P1-T4 | 1 | WebSocket router agent dispatch | `api/websocket/router.py` | Modified |
| P1-T5 | 1 | Startup agent registry log | `app/main.py` | Modified |
| P1-T6 | 1 | Fix Android JWT placeholder | `feature-chat/ChatDetailViewModel.kt` | Modified |
| P2-T1 | 2 | `OpenAIProvider` | `llm/providers/openai_provider.py` | New |
| P2-T2 | 2 | `ClaudeProvider` | `llm/providers/claude_provider.py` | New |
| P2-T3 | 2 | `OllamaProvider` | `llm/providers/ollama_provider.py` | New |
| P2-T4 | 2 | Register providers in `LLMService` | `llm/service.py` | Modified |
| P2-T5 | 2 | Update `AIOrchestrator` to use `LLMService` | `services/ai_orchestrator.py` | Modified |
| P2-T6 | 2 | Provider unit tests | `tests/unit/test_openai_provider.py`, etc. | New |
| P3-T1 | 3 | `MCPBroker.invoke_with_streaming` | `services/mcp_broker.py` | Modified |
| P3-T2 | 3 | `ToolAgent` uses streaming invoke | `agents/tool_agent.py` | Modified |
| P3-T3 | 3 | Handle `tool_confirm` WS message | `api/websocket/router.py` | Modified |
| P3-T4 | 3 | `AgentSessionStore` (Redis-backed) | `agents/session_store.py` | New |
| P3-T5 | 3 | MCP streaming tests | `tests/unit/test_mcp_broker.py` | Modified |
| P3-T6 | 3 | Android parse `tool_confirmation_required` | `core-ai/AIStreamClientImpl.kt` | Modified |
| P4-T1 | 4 | Alembic migration 0017 | `alembic/versions/0017_*.py` | New |
| P4-T2 | 4 | BM25 search in `RAGService` | `services/rag_service.py` | Modified |
| P4-T3 | 4 | pgvector search in `RAGService` | `services/rag_service.py` | Modified |
| P4-T4 | 4 | RRF fusion in `RAGService` | `services/rag_service.py` | Modified |
| P4-T5 | 4 | Ingestion worker + FTS + metadata | `workers/rag_worker.py` | Modified |
| P4-T6 | 4 | `/documents/search` endpoint | `api/rag/router.py` | Modified |
| P4-T7 | 4 | Hybrid search tests | `tests/unit/test_rag_service.py` | Modified |
| P5-T1 | 5 | Extend `StreamEvent` sealed class | `core-ai/StreamEvent.kt` | Modified |
| P5-T2 | 5 | Update `AIStreamClientImpl.parseEvent` | `core-ai/AIStreamClientImpl.kt` | Modified |
| P5-T3 | 5 | `ChatDetailViewModel` new event handling | `feature-chat/ChatDetailViewModel.kt` | Modified |
| P5-T4 | 5 | `AgentProgressCard` | `core-ui/AgentProgressCard.kt` | New |
| P5-T5 | 5 | `ToolConfirmationDialog` | `core-ui/ToolConfirmationDialog.kt` | New |
| P5-T6 | 5 | `RetrievalSourcesCard` | `core-ui/RetrievalSourcesCard.kt` | New |
| P5-T7 | 5 | `feature-mcp` module | `feature-mcp/` (new module) | New |
| P5-T8 | 5 | `ChatDetailScreen` agent UI | `feature-chat/ChatDetailScreen.kt` | Modified |
| P6-T1 | 6 | Dedup on-device-ai modules | `feature-on-device-ai/` | Modified |
| P6-T2 | 6 | Remove `HomeDashboard` dead code | `app/HomeDashboard.kt`, `app/HomeDashboardViewModel.kt` | Deleted |
| P6-T3 | 6 | Consolidate model management | `feature-on-device-rag/`, `feature-on-device-ai/` | Modified |
| P6-T4 | 6 | Final Docker Compose clean-up | `docker-compose.yml` | Modified |
| P6-T5 | 6 | Remove duplicate Kotlin comment headers | Various `.kt` files | Modified |

---

## 10. File Change Index

### New Files

```
backend/app/agents/classifier.py          # P1-T1
backend/app/agents/session_store.py       # P3-T4
backend/app/api/websocket/agent_deps.py   # P1-T3
backend/app/llm/providers/openai_provider.py   # P2-T1
backend/app/llm/providers/claude_provider.py   # P2-T2
backend/app/llm/providers/ollama_provider.py   # P2-T3
backend/alembic/versions/0017_hybrid_search_and_agent_executions.py  # P4-T1
backend/tests/unit/agents/test_classifier.py   # P1-T1
backend/tests/unit/test_openai_provider.py     # P2-T6
backend/tests/unit/test_claude_provider.py     # P2-T6
backend/tests/unit/test_ollama_provider.py     # P2-T6

core-ui/.../AgentProgressCard.kt          # P5-T4
core-ui/.../ToolConfirmationDialog.kt     # P5-T5
core-ui/.../RetrievalSourcesCard.kt       # P5-T6
feature-mcp/build.gradle.kts             # P5-T7
feature-mcp/.../MCPToolsScreen.kt        # P5-T7
feature-mcp/.../MCPToolsViewModel.kt     # P5-T7
feature-mcp/.../MCPToolsUiState.kt       # P5-T7
feature-mcp/.../di/MCPDataModule.kt      # P5-T7
domain/.../repository/MCPRepository.kt  # P5-T7
data/.../remote/mcp/MCPApiService.kt     # P5-T7
data/.../remote/mcp/MCPRemoteDataSource.kt  # P5-T7
data/.../repository/MCPRepositoryImpl.kt    # P5-T7
data/.../di/MCPDataModule.kt             # P5-T7
```

### Modified Files

```
backend/app/agents/__init__.py                 # P1-T2
backend/app/api/websocket/router.py            # P1-T4, P3-T3
backend/app/main.py                            # P1-T5
backend/app/llm/service.py                     # P2-T4
backend/app/services/ai_orchestrator.py        # P2-T5
backend/app/services/mcp_broker.py             # P3-T1
backend/app/agents/tool_agent.py               # P3-T2
backend/app/services/rag_service.py            # P4-T2, P4-T3, P4-T4
backend/app/workers/rag_worker.py              # P4-T5
backend/app/api/rag/router.py                  # P4-T6
backend/tests/unit/test_websocket_router.py    # P1-T4
backend/tests/unit/test_ai_orchestrator_provider_selection.py  # P2-T5
backend/tests/unit/test_mcp_broker.py          # P3-T5
backend/tests/unit/test_rag_service.py         # P4-T7
backend/tests/unit/test_rag_retrieval.py       # P4-T7
backend/RUNNING.md                             # P0-T3
docker-compose.yml                             # P0-T2

core-ai/.../StreamEvent.kt                     # P5-T1
core-ai/.../AIStreamClientImpl.kt              # P5-T2, P3-T6
feature-chat/.../ChatDetailViewModel.kt        # P1-T6, P5-T3
feature-chat/.../ChatDetailScreen.kt           # P5-T8
settings.gradle.kts                            # P5-T7 (include :feature-mcp)
app/build.gradle.kts                           # P5-T7 (depend on :feature-mcp)
```

### Deleted Files (Phase 6 only)

```
app/.../HomeDashboard.kt
app/.../HomeDashboardViewModel.kt
feature-on-device-ai/.../DeviceCapabilityDetector.kt    # (copies; originals stay in core-on-device-ai)
feature-on-device-ai/.../HardwareCapabilityDetector.kt
feature-on-device-ai/.../ModelManifest.kt
feature-on-device-ai/.../OnDeviceAiInitializer.kt
feature-on-device-ai/.../OnDeviceCapabilityChecker.kt
feature-on-device-ai/.../OnDeviceCapabilityState.kt
feature-on-device-ai/.../OnDeviceEngine.kt
feature-on-device-ai/.../OnDeviceInferenceClient.kt
feature-on-device-ai/.../OnDeviceModelManager.kt
feature-on-device-ai/.../RamMonitor.kt
feature-on-device-ai/.../StubOnDeviceEngine.kt
feature-on-device-ai/.../OnDeviceAiModule.kt
```

---

## 11. Test Coverage Requirements

### Backend — minimum coverage thresholds per phase

| Phase | New/modified files | Required tests | Coverage target |
|---|---|---|---|
| P1 | `classifier.py`, `router.py` (agent path) | `test_classifier.py`, updated `test_websocket_router.py` | 100% branch on classifier; 80%+ lines on router update |
| P2 | Three new provider files | Three new test files | 80%+ lines each |
| P3 | `mcp_broker.py` (new method), `tool_agent.py` | Updated `test_mcp_broker.py` | All `invoke_with_streaming` branches covered |
| P4 | `rag_service.py` (three new methods), migration | Updated `test_rag_service.py` | BM25, pgvector, RRF methods each have ≥3 test cases |
| P5 | `feature-mcp`, new Compose components | Android unit tests for ViewModel | `MCPToolsViewModelTest` covers getTools + invokeTool |
| P6 | Deletions and refactors | No new tests required | Existing tests must all pass |

### Android — required test updates

| File | Tests to add or update |
|---|---|
| `ChatDetailViewModelTest` | New cases for each new `StreamEvent` variant |
| `ChatDetailViewModelTest` | JWT injection: token from `AuthRepository`, not placeholder |
| `MCPToolsViewModelTest` (new) | `getTools()` returns list, `invokeTool()` triggers correct API call |
| `AIStreamClientImplTest` (new or existing) | All new WS frame types are parsed to correct `StreamEvent` |

---

## 12. Rollback Procedures

### Per-Phase Rollback

Each phase is a single Git branch. Rollback = revert the merge commit.

```bash
# Revert a phase merge commit
git revert <merge-commit-sha> --no-edit
git push origin main
```

### Database Rollback (Phase 4 only)

Migration 0017 has a `downgrade()` function:

```bash
cd backend
alembic downgrade -1   # reverts to 0016
```

**Note:** The `CONCURRENTLY` index drop (`DROP INDEX CONCURRENTLY`) must be run manually on production — Alembic does not support it inside a transaction. This is documented in the migration file header.

### Redis Rollback

No Redis schema changes. If Phase 3's `AgentSessionStore` introduces issues, the `tool_confirm` path can be disabled via a feature flag in `Settings`:

```python
# settings.py addition
AGENT_TOOL_CONFIRMATION_ENABLED: bool = True
```

The WebSocket router checks this flag before activating the confirmation wait path.

### Android Rollback

Android builds are versioned. Rolling back an Android release means pushing the previous build to the Play Console (or reverting the Play Store rollout percentage to 0%). No database or API schema changes are needed to support the previous app version — all WebSocket protocol changes are additive and backward-compatible.

---

## 13. Definition of Done

A phase is **done** when all of the following are true:

### Code
- [ ] All tasks in the phase are implemented
- [ ] No new `TODO` comments introduced (existing ones are acceptable)
- [ ] No new lint errors (`ruff check` for Python, `detekt` + `ktlint` for Kotlin)
- [ ] No new `mypy` errors (`mypy app/` passes)

### Tests
- [ ] All existing tests from the Phase 0 baseline continue to pass
- [ ] All new tests written for the phase pass
- [ ] Coverage meets the thresholds in §11

### Review
- [ ] PR description references the implementation plan task IDs (e.g., "Implements P1-T1 through P1-T6")
- [ ] PR links to the relevant section in `target-architecture.md`
- [ ] At least one reviewer approved

### Integration
- [ ] `docker compose up` → all services healthy → `/health` 200 → `/ready` 200
- [ ] Android build succeeds for `local` flavor (`./gradlew :app:assembleLo calDebug`)
- [ ] No production functionality regressed (smoke test: send a message via WebSocket, receive `token` + `done` frames)

### Documentation
- [ ] Any deviations from this plan are noted as inline comments in the relevant source files
- [ ] If a task is skipped or deferred, a note is added to this document under the task description

---

*End of Implementation Plan. No production code was changed during the preparation of this document.*
