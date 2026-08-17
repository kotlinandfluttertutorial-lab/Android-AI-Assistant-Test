# Backend Architecture
## Android AI Assistant — Enterprise Edition

---

## Overview

The backend is a **FastAPI modular monolith** — all code lives in one deployable unit,
but modules have explicit boundaries so each can be extracted into a standalone microservice
later without major refactoring.

---

## Directory Structure

```
backend/app/
├── api/               # FastAPI routers (thin HTTP layer only)
│   ├── auth/          # POST /auth/register, /auth/login, /auth/refresh, /auth/logout
│   ├── chat/          # POST /chat/complete
│   ├── conversations/ # CRUD /conversations/*
│   ├── rag/           # POST /documents/upload, GET /documents, DELETE /documents/{id}
│   ├── memory/        # GET /memory, DELETE /memory/{id}
│   ├── mcp/           # GET /tools, POST /tools/{name}/invoke
│   ├── admin/         # /admin/* (admin role required)
│   ├── analytics/     # GET /analytics/*
│   ├── notifications/ # POST /notifications/token
│   ├── productivity/  # /todos/*, /calendar/*, /reminders/*, /habits/*
│   ├── prompts/       # GET/POST /prompts/*
│   ├── users/         # GET/PATCH /users/me
│   └── websocket/     # WS /ws/chat/{conv_id}
├── services/          # Business logic
├── repositories/      # Database access layer
├── models/            # SQLAlchemy 2.x ORM models
├── schemas/           # Pydantic v2 request/response schemas
├── workers/           # Celery task definitions
├── middleware/        # Auth, logging, CORS, rate limiting, request size
├── security/          # JWT, RBAC, prompt injection detection, encryption
├── config/            # pydantic-settings from environment variables
├── database/          # SQLAlchemy engine, session factory (Redis client)
└── prompts/           # Versioned prompt templates
```

---

## Service Layer

Each service implements one cohesive business capability:

| Service | Responsibility |
|---------|---------------|
| `auth_service.py` | Registration, login, JWT issuance, refresh rotation, lockout, Google OAuth2 |
| `ai_orchestrator.py` | Provider resolution, prompt construction, memory injection, LLM streaming |
| `rag_service.py` | Document ingestion dispatch, retrieval, citation assembly |
| `memory_service.py` | Memory upsert, top-K retrieval from ChromaDB |
| `mcp_broker.py` | Tool registry, discovery, invocation, audit logging |
| `prompt_service.py` | Prompt template CRUD, versioning, variable interpolation |
| `productivity_service.py` | TodoItem, CalendarEvent, Reminder, HabitDefinition business logic |
| `admin_service.py` | User management, metrics aggregation, audit log queries |
| `safety_service.py` | Prompt injection pattern detection, input sanitisation |
| `llm_clients.py` | Concrete LLM client implementations (OpenAI, Gemini, Claude, Ollama, Llama, Mistral) |

---

## Repository Pattern

Each repository encapsulates all database access for one aggregate:

```python
class UserRepository:
    async def create(self, db: AsyncSession, user_in: UserCreate) -> User: ...
    async def get_by_email(self, db: AsyncSession, email: str) -> User | None: ...
    async def get_by_id(self, db: AsyncSession, user_id: UUID) -> User | None: ...
    async def update(self, db: AsyncSession, user: User, update: UserUpdate) -> User: ...
    async def deactivate(self, db: AsyncSession, user_id: UUID) -> None: ...
```

Repositories **never** contain business logic. Services compose repositories to implement workflows.

---

## Middleware Stack (Applied in Order)

1. **CORSMiddleware** — Allow configured origins
2. **RequestSizeMiddleware** — Reject payloads above 50 MB
3. **LoggingMiddleware** — Emit structured JSON log per request (correlation ID, user ID, endpoint, status, duration)
4. **RateLimitMiddleware** — 60 req/min per authenticated user (Redis sliding window); HTTP 429 on breach
5. **JWT Authentication** — Injected via FastAPI `Depends(get_current_user)` on all protected routes

---

## AI Orchestrator

The `AIOrchestrator` is the single point of contact for all LLM interactions:

1. **Resolve provider** — Look up the active `BaseLLMClient` for the requested provider
2. **Build prompt** — Retrieve top-3 memories from `MemoryService`, fetch conversation history, interpolate system prompt template
3. **Safety filter** — `SafetyService.detect_prompt_injection()` — reject with HTTP 400 if detected
4. **Stream / complete** — Call the provider client; for streaming, emit WebSocket events `{"type":"token","data":"..."}`
5. **Persist** — Save the completed message to PostgreSQL; record token usage in `token_usage` table
6. **Fallback** — If the primary provider fails, retry with the configured fallback provider

Provider Adapter Pattern:
```python
class BaseLLMClient(ABC):
    @abstractmethod
    async def stream(self, context: PromptContext) -> AsyncIterator[str]: ...
    @abstractmethod
    async def complete(self, context: PromptContext) -> str: ...
    @property
    @abstractmethod
    def max_context_tokens(self) -> int: ...
```

Concrete implementations: `OpenAIClient`, `GeminiClient`, `ClaudeClient`, `OllamaClient`, `LlamaClient`, `MistralClient`.

---

## Celery Workers

| Worker | Task | Trigger |
|--------|------|---------|
| `rag_worker.py` | `ingest_document` | Document uploaded to MinIO |
| `notification_worker.py` | `send_push_notification` | Ingestion complete, queued message delivered |
| `gdpr_worker.py` | `delete_user_data` | User account deletion request |
| `metrics.py` | `aggregate_metrics` | Scheduled (hourly) |

Workers communicate via Redis as broker. Results stored in Redis.

---

## Configuration Management

`config/settings.py` uses `pydantic-settings` to load all configuration from environment variables with validation:

```python
class Settings(BaseSettings):
    database_url: str
    redis_url: str
    secret_key: str
    openai_api_key: str
    # ... all config
    model_config = SettingsConfigDict(env_file=".env")
```

All sensitive values are never logged or returned in API responses.
