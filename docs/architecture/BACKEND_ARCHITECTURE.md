# Backend Architecture
## Android AI Assistant — Enterprise Edition

---

## Overview

The backend is a **FastAPI modular monolith** — all code lives in one deployable unit, but
modules have explicit boundaries so each can be extracted into a standalone microservice later
without major refactoring.

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
│   ├── generation/    # POST /resume/generate, /email/generate, etc.
│   ├── images/        # POST /images/analyze
│   ├── transcription/ # POST /transcription/start, /transcription/{id}
│   ├── translation/   # POST /translation
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
├── database/          # SQLAlchemy engine, session factory
└── prompts/           # Versioned prompt templates
```

---

## Service Layer

Each service implements one cohesive business capability:

| Service | Responsibility |
|---------|---------------|
| `auth_service.py` | Registration, login, JWT issuance, refresh rotation, lockout, Google OAuth2 |
| `ai_orchestrator.py` | Provider resolution, prompt construction, memory injection, LLM streaming, fallback |
| `rag_service.py` | Document ingestion dispatch, retrieval, citation assembly |
| `memory_service.py` | Memory upsert, top-K retrieval from ChromaDB |
| `mcp_broker.py` | Tool registry, discovery, invocation, audit logging |
| `prompt_service.py` | Prompt template CRUD, versioning, variable interpolation |
| `productivity_service.py` | TodoItem, CalendarEvent, Reminder, HabitDefinition business logic + AI integration |
| `admin_service.py` | User management, metrics aggregation, audit log queries |
| `safety_service.py` | Prompt injection pattern detection, input sanitisation |
| `llm_clients.py` | Concrete LLM client implementations (OpenAI, Gemini, Claude, Ollama, Llama, Mistral) |

---

## Repository Pattern

Each repository encapsulates all database access for one aggregate. Repositories contain
**zero business logic** — services compose them to implement workflows.

```python
class UserRepository:
    async def create(self, db: AsyncSession, user_in: UserCreate) -> User: ...
    async def get_by_email(self, db: AsyncSession, email: str) -> User | None: ...
    async def get_by_id(self, db: AsyncSession, user_id: UUID) -> User | None: ...
    async def update(self, db: AsyncSession, user: User, update: UserUpdate) -> User: ...
    async def deactivate(self, db: AsyncSession, user_id: UUID) -> None: ...
```

---

## Middleware Stack (Applied in Order)

1. **CORSMiddleware** — Allow configured origins
2. **RequestSizeMiddleware** — Reject payloads above 50 MB (HTTP 413)
3. **LoggingMiddleware** — Emit structured JSON log per request (correlation ID, user ID,
   endpoint, status, duration)
4. **RateLimitMiddleware** — 60 req/min per authenticated user (Redis sliding window);
   HTTP 429 with `Retry-After` header on breach
5. **JWT Authentication** — `Depends(get_current_user)` on all protected routes; HTTP 401
   if missing / expired / revoked

---

## AI Orchestrator Pipeline

```
Request (user message + conv_id + provider + user_id)
  │
  1. Resolve provider → look up active BaseLLMClient
  2. Retrieve top-3 memories → MemoryService → ChromaDB
  3. Fetch conversation history → PostgreSQL
  4. Context summarisation → if > 80% of token window used
  5. Build PromptContext → system prompt + memories + history + user message
  6. Detect prompt injection → SafetyService → HTTP 400 if detected
  7. Invoke provider adapter → stream tokens via WebSocket
  8. Record token usage → PostgreSQL token_usage table
  9. MCP tool call if needed → MCP Broker → external tool API
```

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

    @property
    @abstractmethod
    def cost_per_input_token(self) -> Decimal: ...

    @property
    @abstractmethod
    def cost_per_output_token(self) -> Decimal: ...
```

Concrete implementations: `OpenAIClient`, `GeminiClient`, `ClaudeClient`, `OllamaClient`,
`LlamaClient`, `MistralClient`.

---

## Celery Workers

| Worker | Task | Trigger |
|--------|------|---------|
| `rag_worker.py` | `ingest_document` | Document uploaded to MinIO |
| `notification_worker.py` | `send_push_notification` | Ingestion complete, message delivered |
| `gdpr_worker.py` | `delete_user_data` | User account deletion request |
| `metrics.py` | `aggregate_metrics` | Scheduled (hourly) |

Workers communicate via Redis as broker. Results stored in Redis.

---

## Data Access Layer

```
FastAPI Router
  └─► Service
        └─► Repository  (parameterised queries only — never string interpolation)
              └─► SQLAlchemy 2.x AsyncSession  →  PostgreSQL 15+
```

All queries use SQLAlchemy ORM or explicit parameterised text queries. No raw string
interpolation into SQL is ever performed. Input sanitisation is applied at the service layer
before repository calls.

---

## Configuration Management

`config/settings.py` uses `pydantic-settings` to load all configuration from environment
variables with type validation and secret redaction:

```python
class Settings(BaseSettings):
    database_url: PostgresDsn
    redis_url: RedisDsn
    secret_key: SecretStr          # JWT signing key (min 32 chars)
    encryption_key: SecretStr      # AES-256 key for API key storage
    openai_api_key: SecretStr | None = None
    gemini_api_key: SecretStr | None = None
    anthropic_api_key: SecretStr | None = None
    model_config = SettingsConfigDict(env_file=".env")
```

Sensitive values are never logged or returned in API responses. The `SecretStr` type prevents
accidental string serialisation.

---

## Health and Readiness Endpoints

| Endpoint | Response |
|----------|---------|
| `GET /health` | `{"status":"ok","version":"1.0.0"}` |
| `GET /ready` | `{"status":"ready","checks":{"postgres":"ok","redis":"ok","chromadb":"ok"}}` |

`/ready` verifies all downstream service connections before reporting ready. Used by Docker
Compose `healthcheck` and Kubernetes readiness probes.
