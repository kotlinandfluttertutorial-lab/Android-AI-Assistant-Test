# Backend Architecture — Phase 3 Learning Guide

A thorough walkthrough of the FastAPI backend: how it is structured, why
each layer exists, how a request travels from HTTP to the database and back,
and how to extend it safely.

---

## Table of Contents

1. [Why FastAPI?](#1-why-fastapi)
2. [Project Structure](#2-project-structure)
3. [Application Startup](#3-application-startup)
4. [The Request Lifecycle](#4-the-request-lifecycle)
5. [The Four Architectural Layers](#5-the-four-architectural-layers)
6. [Database Layer](#6-database-layer)
7. [Configuration (Settings)](#7-configuration-settings)
8. [Security Architecture](#8-security-architecture)
9. [Background Tasks (Celery)](#9-background-tasks-celery)
10. [Observability](#10-observability)
11. [Testing Strategy](#11-testing-strategy)
12. [Key Design Decisions and Trade-offs](#12-key-design-decisions-and-trade-offs)
13. [Interview Questions](#13-interview-questions)
14. [Exercises](#14-exercises)

---

## 1. Why FastAPI?

FastAPI is Python's modern web framework built on ASGI (Asynchronous Server
Gateway Interface). It was chosen over Flask and Django for several reasons
specific to this project:

| Concern | FastAPI | Flask | Django |
|---|---|---|---|
| Async support | Native `async`/`await` | Requires extensions | Partial (ASGI adapter) |
| Request validation | Pydantic (automatic) | Manual | Forms-based |
| Auto OpenAPI docs | Built-in at `/docs` | Plugin required | drf-spectacular |
| Type safety | Full (Pydantic v2) | None | Partial |
| Performance | Starlette + uvicorn | Werkzeug (sync) | WSGI (sync) |
| WebSocket support | Native | Extension | Channels (complex) |

For an AI assistant backend that needs to stream LLM responses over WebSocket,
handle concurrent requests efficiently, and auto-generate API docs that the
Android team can read — FastAPI is the right choice.

---

## 2. Project Structure

```
backend/
├── app/
│   ├── main.py                 ← FastAPI app factory + lifespan + routers
│   ├── api/                    ← HTTP route handlers (thin layer)
│   │   ├── auth/router.py
│   │   ├── chat/router.py
│   │   ├── observability/router.py
│   │   └── ... (30+ routers total)
│   ├── services/               ← Business logic (pure functions)
│   │   ├── auth_service.py
│   │   ├── rag_service.py
│   │   └── ...
│   ├── repositories/           ← SQL queries (one class per table)
│   │   ├── user_repository.py
│   │   ├── incident_repository.py
│   │   └── ...
│   ├── models/                 ← SQLAlchemy ORM models (table definitions)
│   │   ├── user.py
│   │   ├── observability_event.py
│   │   └── ...
│   ├── schemas/                ← Pydantic request/response shapes
│   │   ├── auth.py
│   │   └── ...
│   ├── config/settings.py      ← All env vars via pydantic-settings
│   ├── database/               ← Engine, session factory, get_db()
│   ├── security/               ← JWT, bcrypt, RBAC, exceptions
│   ├── middleware/             ← CORS, rate limit, logging, body size
│   ├── workers/                ← Celery background tasks
│   └── observability/          ← Structured logging + OpenTelemetry
├── alembic/                    ← Database migrations (15 migrations)
├── tests/
│   ├── unit/                   ← Mocked dependencies (55+ files)
│   ├── integration/            ← FastAPI TestClient (17 files)
│   └── security/               ← Hypothesis property tests (5 files)
├── Dockerfile
└── requirements.txt
```

The directory structure directly reflects the four layers described in §5.

---

## 3. Application Startup

`app/main.py` is the entry point for both local development (`uvicorn app.main:app`)
and production (Cloud Run). The startup sequence matters — things happen in a
specific order for good reasons:

```
Process starts
    │
    ▼
configure_logging()            — JSON structured logging BEFORE any other import
    │                             (prevents basicConfig() races from other libs)
    ▼
load_dotenv()                  — reads backend/.env into os.environ
    │
    ▼
lifespan() context manager
    │
    ├── startup_validation()   — checks 4 required env vars; sys.exit(1) if missing
    │                             BEFORE the server binds to any port
    ├── setup_celery_metrics() — Prometheus counters for Celery tasks
    ├── setup_tracing()        — OpenTelemetry auto-instrumentation
    ├── seed_knowledge()       — seeds ChromaDB from knowledge/ directory
    │
    ▼
app = FastAPI(...)             — app created with title, version, lifespan
    │
    ├── add_middleware(CORS)
    ├── add_middleware(RateLimitMiddleware)
    ├── add_middleware(DataResidencyMiddleware)
    ├── add_middleware(RequestBodySizeLimitMiddleware)
    ├── add_middleware(RequestLoggingMiddleware)
    │
    ├── include_router(auth_router)
    ├── include_router(chat_router)
    ├── ... (30+ routers)
    │
    ├── Instrumentator().instrument(app)   — Prometheus /metrics endpoint
    ├── GET /health                        — liveness probe
    └── GET /ready                         — readiness probe (checks DB + Redis)

uvicorn binds to 0.0.0.0:8000 and starts accepting requests

Background (non-blocking):
    └── _background_warmup()   — warm SentenceTransformer model, check ChromaDB
                                  This runs AFTER uvicorn starts so /health responds
                                  immediately even before the model is loaded.

Graceful shutdown (SIGTERM received):
    ├── Cancel _background_warmup task
    └── await engine.dispose()   — close all pooled asyncpg connections cleanly
```

### Why startup_validation() exits with code 1

A missing `SECRET_KEY` means the server cannot sign JWT tokens. Allowing the
server to start and fail later (on the first auth request) is worse than failing
fast — it hides the misconfiguration under HTTP 500 errors. Exiting immediately
at startup makes the deployment fail visibly in CI and logs a clear error message
naming the missing variable.

### Why engine.dispose() belongs in the lifespan teardown

Cloud Run sends SIGTERM before killing the process. Without `engine.dispose()`,
asyncpg connections stay open in the pool until the GC runs — which may never
happen in a SIGTERM-killed process. This exhausts PostgreSQL's `max_connections`
after rolling deploys because the old revision's 20–30 connections are never
cleanly closed.

---

## 4. The Request Lifecycle

Tracing a single `POST /chat/message` request through the entire stack:

```
Android Client
    │  Authorization: Bearer <JWT>
    │  Content-Type: application/json
    │  {"message": "Why did the API fail?", "conversation_id": "uuid"}
    ▼
uvicorn (ASGI server)
    │
    ▼
RequestLoggingMiddleware
    │  Generates UUID correlation ID
    │  Sets X-Correlation-ID on the response
    │  Logs: {"path": "/chat/message", "method": "POST", "correlation_id": "..."}
    ▼
RequestBodySizeLimitMiddleware
    │  Reads Content-Length header
    │  Rejects > 1 MiB with HTTP 413 (before Pydantic sees the body)
    ▼
RateLimitMiddleware
    │  Extracts user_id from JWT (or IP for unauthenticated)
    │  Increments Redis counter: INCR rate:{user_id}:{minute_bucket}
    │  Rejects if > 60/min (auth) or > 20/min (unauth) with HTTP 429
    ▼
DataResidencyMiddleware
    │  Checks X-Client-Region header vs settings.DATA_RESIDENCY_REGION
    │  Rejects write methods if region doesn't match (when configured)
    ▼
FastAPI Router (chat_router, prefix=/chat)
    │  Matches POST /chat/message
    │  Runs @router dependency: Depends(get_current_user)
    │      → validates JWT signature + expiry
    │      → checks JTI against Redis revocation list
    │      → returns TokenPayload(sub=user_id, role="user")
    │  Runs @router dependency: Depends(get_db)
    │      → creates AsyncSession from AsyncSessionLocal
    │      → yields it into the route handler
    ▼
send_message() route handler
    │  Pydantic validates request body → ChatMessageRequest
    │  Calls _handle_chat(body, current_user, db, ...)
    ▼
_handle_chat() (shared business logic)
    │  PromptInjectionDetector.check_input()  — blocks prompt injection
    │  PromptBuilder.build()                  — assembles system + history + user msg
    │  LLMService.generate()                  — calls Gemini / OpenAI / Claude
    ▼
GeminiProvider (LLM layer)
    │  google-genai SDK → Gemini API
    │  Returns LLMResponse(text=..., provider="gemini", usage=...)
    ▼
_handle_chat() builds ChatMessageResponse
    ▼
FastAPI serializes response with Pydantic → JSON
    ▼
get_db() dependency teardown:
    │  await session.commit()  — commits any DB changes made during the request
    │  await session.close()   — returns connection to the pool
    ▼
Response reaches Android client: 200 OK + JSON body
```

### Key insight: thin route handlers

Route handlers (`send_message`) contain almost no business logic:
- Validate the request (Pydantic handles this automatically)
- Call a service function
- Map the result to an HTTP response

This is intentional. Business logic in route handlers is untestable without an
HTTP client. Business logic in services is testable with a plain `pytest` function.

---

## 5. The Four Architectural Layers

### Layer 1 — API (Route Handlers)

**Location:** `app/api/*/router.py`
**Responsibility:** HTTP interface — parse requests, call services, return responses.
**Contains:** Pydantic schemas, `Depends()` injections, HTTP status codes.
**Does NOT contain:** SQL queries, LLM calls, business rules.

```python
# app/api/auth/router.py  — GOOD: thin handler
@router.post("/login", response_model=LoginResponse)
async def login(
    body: LoginRequest,
    db: AsyncSession = Depends(get_db),
) -> LoginResponse:
    user = await user_repo.get_by_email(body.email)
    access_token, access_exp, refresh_token, refresh_exp = \
        await issue_tokens_for_user(db, user.id, user.role.value)
    return LoginResponse(access_token=access_token, ...)
```

### Layer 2 — Services

**Location:** `app/services/*.py`
**Responsibility:** Business logic — the "what" of the application.
**Contains:** Orchestration, validation rules, LLM calls, pure Python.
**Does NOT contain:** SQL (delegates to repositories), HTTP (no Request/Response).

```python
# app/services/auth_service.py — pure business logic
async def issue_tokens_for_user(
    db: AsyncSession,
    user_id: uuid.UUID,
    role: str,
) -> tuple[str, datetime, str, datetime]:
    repo = RefreshTokenRepository(db)
    access_token, access_exp = create_access_token(user_id=user_id, role=role)
    refresh_data = create_refresh_token(family_id=None)
    await repo.create(user_id=user_id, token_hash=refresh_data.token_hash, ...)
    return access_token, access_exp, refresh_data.raw_token, refresh_data.expires_at
```

### Layer 3 — Repositories

**Location:** `app/repositories/*.py`
**Responsibility:** Database access — the "how" of data persistence.
**Contains:** SQLAlchemy queries, `flush()` calls, data mapping.
**Does NOT contain:** Business rules, LLM calls, HTTP status codes.

```python
# app/repositories/user_repository.py
class UserRepository:
    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    async def get_by_email(self, email: str) -> User | None:
        result = await self._db.execute(
            select(User).where(User.email == email)
        )
        return result.scalar_one_or_none()

    async def create(self, email: str, password_hash: str) -> User:
        user = User(id=uuid.uuid4(), email=email, password_hash=password_hash)
        self._db.add(user)
        await self._db.flush()   # get the DB-assigned ID without committing
        return user
```

### Layer 4 — Models

**Location:** `app/models/*.py`
**Responsibility:** Table definitions — what the database stores.
**Contains:** SQLAlchemy ORM classes, column definitions, indexes, relationships.
**Does NOT contain:** Business logic, queries, or HTTP knowledge.

```python
# app/models/user.py
class User(Base):
    __tablename__ = "users"

    id:            Mapped[uuid.UUID] = uuid_pk()
    email:         Mapped[str]       = mapped_column(String(255), unique=True)
    password_hash: Mapped[str]       = mapped_column(String(255))
    role:          Mapped[UserRole]  = mapped_column(default=UserRole.user)
    is_active:     Mapped[bool]      = mapped_column(default=True)
    created_at:    Mapped[datetime]  = mapped_column(server_default=func.now())
```

### Why four layers?

| Question | Layer that answers it |
|---|---|
| "What URL handles this?" | API |
| "What does the business rule say?" | Service |
| "How is this stored in Postgres?" | Repository |
| "What columns does this table have?" | Model |

If SQL appears in a service, or business logic appears in a repository, the layers
are breaking down. This makes code harder to test and reason about.

---

## 6. Database Layer

### Async SQLAlchemy 2.x

SQLAlchemy 2.x with the async extension (`AsyncSession`) is used throughout.
The key difference from synchronous SQLAlchemy:
- All DB operations are `await`ed
- The session is obtained via `async with AsyncSessionLocal() as session:`
- Queries use `await session.execute(select(...))`, not `session.query(...)`

### The `get_db()` dependency

```python
# app/database/__init__.py
async def get_db() -> AsyncGenerator[AsyncSession, None]:
    async with AsyncSessionLocal() as session:
        try:
            yield session          # FastAPI injects this into the route handler
            await session.commit() # auto-commit when handler returns normally
        except Exception:
            await session.rollback()  # rollback on any exception
            raise
        finally:
            await session.close()  # always return connection to pool
```

This implements the **Unit of Work** pattern: each HTTP request is one database
transaction. Route handlers never call `session.commit()` themselves.

### flush() vs commit()

A common source of confusion in SQLAlchemy:

| Method | Effect | When to use |
|---|---|---|
| `flush()` | Sends SQL to DB but does NOT commit | Inside repositories — lets you get a DB-generated ID without ending the transaction |
| `commit()` | Makes changes permanent and visible to other connections | In `get_db()` or in workers that own their own transaction |

```python
# ✅ Repository uses flush() — handler controls the commit via get_db()
async def create(self, ...) -> Incident:
    incident = Incident(id=uuid.uuid4(), ...)
    self._db.add(incident)
    await self._db.flush()  # incident.id is now populated from the DB sequence
    return incident          # handler can use incident.id before committing

# ✅ Worker uses commit() — it owns the transaction
async def bulk_insert(self, events: list[dict]) -> int:
    self._db.add_all(rows)
    await self._db.commit()  # worker runs outside the request lifecycle
    return len(rows)
```

### Connection pool settings

```python
engine = create_async_engine(
    settings.DATABASE_URL,
    pool_pre_ping=True,  # validates connections on checkout (prevents stale conn errors)
    pool_size=20,        # 20 base connections (one per concurrent request)
    max_overflow=10,     # +10 burst capacity = 30 total max
)
```

`pool_pre_ping=True` sends a lightweight `SELECT 1` before handing a connection
to a request. Without it, connections that have been idle longer than PostgreSQL's
`tcp_keepalives_idle` produce `asyncpg.exceptions.ConnectionDoesNotExistError`
with no helpful context.

### Alembic migrations

Database schema changes are managed by Alembic — never by `Base.metadata.create_all()`.

```bash
# Create a new migration after changing a model
alembic revision --autogenerate -m "add_fcm_token_to_users"

# Apply all pending migrations
alembic upgrade head

# Roll back one migration
alembic downgrade -1
```

15 migrations exist (`0001_initial_schema` through `0015_make_chroma_id_nullable`).
Each migration is reversible — every `upgrade()` has a matching `downgrade()`.

---

## 7. Configuration (Settings)

All configuration comes from environment variables via `pydantic-settings`:

```python
# app/config/settings.py
class Settings(BaseSettings):
    SECRET_KEY:    str = Field(description="JWT signing key — REQUIRED")
    DATABASE_URL:  str = Field(description="PostgreSQL async URL — REQUIRED")
    REDIS_URL:     str = Field(description="Redis URL — REQUIRED")

    LLM_TEMPERATURE: float = Field(default=0.3, ge=0.0, le=2.0)

    @property
    def JWT_SECRET_KEY(self) -> str:
        return self.SECRET_KEY  # alias for JWT handler compatibility
```

`get_settings()` is wrapped with `@lru_cache` — settings are parsed from env
exactly once per process, regardless of how many imports call `get_settings()`.

### Priority order (highest to lowest)

```
1. Actual environment variables (set in shell / Cloud Run)
2. backend/.env file
3. Field defaults in Settings class
```

### Required vs optional fields

Fields with no `default=` are **required**. Missing them raises `ValidationError`
at import time.

The `startup_validation()` function adds a second layer of checking for the 4 most
critical variables (`SECRET_KEY`, `DATABASE_URL`, `REDIS_URL`, `AES_ENCRYPTION_KEY`)
— it calls `sys.exit(1)` rather than letting pydantic raise, producing a cleaner error
message in Cloud Run logs.

---

## 8. Security Architecture

### JWT flow

```
POST /auth/login
    │  {email, password}
    ▼
hash_password() comparison via bcrypt (work factor 12)
    │
    ▼
create_access_token(user_id, role)
    │  → signs HS256 JWT with SECRET_KEY
    │  → 15-minute expiry (configurable)
    │  → claims: {sub, role, jti, iat, exp}
    ▼
create_refresh_token(family_id=None)
    │  → generates opaque token + hash
    │  → persists hash to refresh_tokens table
    │  → 30-day expiry
    ▼
Client stores:
    - access_token in memory (never localStorage on web)
    - refresh_token in secure storage (Keychain/Keystore on Android)
```

### Token refresh with replay detection

```
POST /auth/refresh  {refresh_token: "raw-token"}
    │
    ▼
hash_token(raw_token)  →  look up in refresh_tokens table
    │
    ├── Not found → 401
    ├── revoked=True → 401
    ├── expired → 401
    ├── used=True  ←── REPLAY DETECTED
    │       │
    │       └── revoke_family(family_id)
    │               Revokes ALL tokens in the chain:
    │               old_token → new_token1 → new_token2 → ...
    │               Forces re-authentication for BOTH attacker and victim.
    │               (RFC 6819 §5.2.2.3)
    │
    └── Happy path:
            mark_used(current_token)
            issue new JWT + new refresh token (same family_id)
            parent_token_id = current_token.id
```

### RBAC

Three roles are checked at the dependency level:

```python
# Get any authenticated user:
current_user: TokenPayload = Depends(get_current_user)

# Require premium or admin:
current_user: TokenPayload = Depends(require_premium)

# Require admin:
current_user: TokenPayload = Depends(require_admin)
```

Each dependency reads `TokenPayload.role` from the JWT and raises `HTTP 403` if
the role is insufficient. No role check ever appears in business logic.

### Rate limiting

Two tiers backed by a Redis sliding window:

| Tier | Key | Limit |
|---|---|---|
| Authenticated | `rate:{user_id}:{minute_bucket}` | 60 req/min |
| Unauthenticated | `rate:ip:{ip}:{minute_bucket}` | 20 req/min |

The window is per-minute. At the start of a new minute, the counter resets.
The middleware is registered before any business logic so rate-limited requests
never reach the database.

---

## 9. Background Tasks (Celery)

Some operations are too slow or too important to run synchronously in a request:

| Task | Queue | Trigger | Why async |
|---|---|---|---|
| Document ingestion (chunk + embed) | `ingestion` | After file upload | Can take 30–60 s per document |
| Push notifications (FCM) | `notifications` | Events, reminders | Fire-and-forget |
| GDPR data export | `gdpr` | User request | Can take minutes |
| Anomaly detection | `celery` | Beat every 60 s | Periodic |
| Observability upload | `celery` | WorkManager (Android) | Periodic |

```
Android WorkManager
    │  POST /api/v1/observability/events
    ▼
observability router
    │  ObservabilityEventRepository.bulk_insert(events)
    ▼
PostgreSQL (observability_events table)
    │
    ▼  (60 seconds later)
Celery Beat → anomaly_worker.py
    │  ObservabilityEventRepository.count_errors_in_window(minutes=5)
    │  ObservabilityEventRepository.compute_event_rate_stats(level="ERROR")
    │  If threshold exceeded → IncidentRepository.create(...)
    ▼
Incident created → frontend notified via WebSocket or FCM
```

### How Celery workers and the API share the database

Both the FastAPI app and Celery workers use the same `DATABASE_URL`. Celery
workers create their own SQLAlchemy sessions (not via `get_db()`). This is safe
because they use separate connection pools.

---

## 10. Observability

Three signals are collected:

### Structured JSON logs

```python
# app/observability/logging_setup.py
configure_logging()  # called before any imports in main.py
```

Every log line is JSON with fields: `level`, `message`, `correlation_id`,
`path`, `method`, `user_id`, `duration_ms`. Parseable by CloudWatch/Loki.

### Prometheus metrics

Exposed at `GET /metrics`. Auto-instrumented by `prometheus-fastapi-instrumentator`:
- Request count by path, method, status code
- Request duration histogram (buckets: 50ms, 100ms, 200ms, 500ms, 1s, 2s, 5s)
- Custom Celery task counters (success/failure/retry)

### OpenTelemetry distributed tracing

Auto-instruments FastAPI, SQLAlchemy, httpx, and Redis. Spans are exported to
Cloud Trace (GCP) via OTLP gRPC or to a local Jaeger instance.

A correlation ID is generated per request by `RequestLoggingMiddleware` and
added to every log line and the `X-Correlation-ID` response header. This ties
logs, metrics, and traces together for a single request.

---

## 11. Testing Strategy

```
backend/tests/
├── unit/           — Mock all I/O. Test one class/function at a time.
│                     Runs in < 5 seconds. Always green in CI.
├── integration/    — FastAPI TestClient + real in-memory SQLite.
│                     Mock only external services (LLM APIs, Firebase).
│                     Gated: LLM integration tests only run when
│                     RUN_LLM_INTEGRATION_TESTS=true.
└── security/       — Hypothesis property-based tests.
                      Generates thousands of random inputs to find edge cases.
                      Tests: JWT security, RBAC, prompt injection blocking.
```

### Running tests

```bash
# Unit tests (fast, always run)
pytest tests/unit/ -v

# Coverage gate (fails below 70%)
pytest --cov=app --cov-fail-under=70

# Single test file
pytest tests/unit/test_auth_service_layer.py -v

# All tests including integration
pytest tests/ -v

# With LLM integration tests (requires real API keys)
RUN_LLM_INTEGRATION_TESTS=true pytest tests/integration/test_llm_integration.py
```

### How tests mock the database

The `conftest.py` uses an `autouse=True` fixture that patches `get_redis_client`
globally. Route-handler tests use `app.dependency_overrides` to replace `get_db()`
and `get_current_user()` with mocks. Service tests patch `RefreshTokenRepository`
at the module level.

This layered mocking ensures:
- Unit tests never touch a real database
- Service functions can be tested as pure async functions
- Route handlers can be tested with `TestClient` without a running server

---

## 12. Key Design Decisions and Trade-offs

### 12.1 Pydantic v2 throughout

Pydantic v2 is used for both request/response schemas and application settings.
The 10x performance improvement over v1 matters at request validation time for
AI payloads that can be several KB.

Trade-off: pydantic v2's API differs significantly from v1. If copying examples
from Stack Overflow or older tutorials, they may use v1 syntax.

### 12.2 SQLAlchemy 2.x async — flush vs commit

The service pattern where `get_db()` owns the commit is powerful but requires
discipline: repositories use `flush()` not `commit()`. Forgetting this will
either double-commit (harmless but wasteful) or fail to persist changes
when a worker creates its own session.

### 12.3 ChromaDB in embedded mode on Cloud Run

ChromaDB runs as an in-process embedded client (not a separate HTTP service).
This makes local development simple but has one critical implication:
**ChromaDB's collection is wiped on every new Cloud Run revision** because
the container filesystem is ephemeral.

The `seed_knowledge()` call in `lifespan()` re-seeds the knowledge base on
every startup. This works because the seed operation is idempotent
(chromadb.add_documents deduplicates by ID) and takes < 120 seconds.

Migration path: move to a dedicated ChromaDB Cloud Run service (see
`chromadb-server/` directory) or replace ChromaDB with pgvector (migration
0014 already adds vector columns to `document_chunks`).

### 12.4 No API versioning prefix (except /api/v1/observability)

Most routers have no version prefix (`/chat/message`, not `/api/v1/chat/message`).
Only the observability router has `/api/v1` because the Android `ObservabilityUploadWorker`
calls it directly and must be stable.

For future versioning, the pattern to follow is:
```python
# Create a versioned router alias
v2_router = APIRouter(prefix="/api/v2/chat", ...)
app.include_router(v2_router)  # both /chat and /api/v2/chat exist simultaneously
```

### 12.5 Dual vector store strategy

The system ships both ChromaDB (for RAG document retrieval) and pgvector
(migration 0014 adds `vector` columns to `document_chunks`). This is
intentional redundancy: pgvector provides durable storage in PostgreSQL
for a future migration away from ChromaDB.

### 12.6 Token rotation — every refresh produces a new token

This means clients must update their stored refresh token on every `/auth/refresh`
call. Most mobile SDKs handle this automatically. The benefit: replay detection
is possible — if the old token is submitted after it's been used, the server
knows the token was stolen or the client has a bug.

---

## 13. Interview Questions

These are real questions you might face in a Python/FastAPI interview:

**1. What is the difference between ASGI and WSGI? Why does it matter for this backend?**

WSGI (Web Server Gateway Interface) is synchronous — each request blocks a thread
until complete. ASGI (Asynchronous Server Gateway Interface) is async — a single
process can handle many concurrent requests without blocking. For an AI backend
where LLM calls take 1–5 seconds, WSGI would require as many threads as concurrent
users. ASGI (via uvicorn + FastAPI) handles thousands of concurrent requests with
one process.

**2. Why does FastAPI use `Depends()` instead of global variables for the database session?**

Global sessions are not safe for concurrent requests — two requests would share one
session and see each other's uncommitted data. `Depends(get_db)` creates one session
per request, scoped to the request lifetime. It also enables test overrides via
`app.dependency_overrides` without modifying production code.

**3. What does `expire_on_commit=False` do in SQLAlchemy and why is it set here?**

By default, SQLAlchemy expires all ORM instances after a commit. The next attribute
access triggers a SELECT to reload from the database. In async code, this produces
a `MissingGreenlet` error if you access an attribute outside an async context (e.g.,
during Pydantic serialization). `expire_on_commit=False` keeps the Python objects
alive with their current data after commit, avoiding this error entirely.

**4. Explain token rotation. What happens when a stolen refresh token is used?**

Token rotation means each refresh token is single-use. When `/auth/refresh` is called,
the old token is marked `used=True` and a new token is issued in the same family.
If the old token is submitted again (`used=True`), replay is detected: the entire
token family is revoked, forcing re-authentication. This limits the blast radius —
an attacker who steals a token can only use it once before the victim's next refresh
invalidates everything.

**5. Why does `bulk_insert` call `commit()` while `IncidentRepository.create()` calls `flush()`?**

`bulk_insert` is called by `ObservabilityUploadWorker` which runs outside the HTTP
request lifecycle (no `get_db()`). It owns its own transaction and must commit to
make the data durable. `IncidentRepository.create()` is called from a route handler
that uses `get_db()`, which auto-commits after the handler returns. Using `flush()`
lets the repository get the DB-generated ID (`incident.id`) without ending the
transaction prematurely — the handler retains the ability to rollback if a later
step fails.

---

## 14. Exercises

These build on the architecture described above. Each should take 30–60 minutes.

**Exercise 1 — Add a new endpoint**

Add `GET /conversations/{id}/summary` that returns a one-line AI-generated summary
of a conversation. Follow the layered pattern:
1. Add a route handler in `app/api/conversations/router.py`
2. Add a service function in `app/services/conversation_service.py`
3. Call `LLMService.generate()` with a summarization prompt
4. Write a unit test that mocks `LLMService`

**Exercise 2 — Add a new repository method**

Add `ObservabilityEventRepository.get_by_trace_id(trace_id: str)` that returns
all events sharing a trace ID, ordered oldest-first. Then write unit tests that:
- Verify the SQL query includes the trace_id filter
- Return an empty list when no events match
- Return events in the correct order

**Exercise 3 — Understand the middleware stack**

Write a test that sends a request with a body of exactly `MAX_REQUEST_BODY_SIZE + 1`
bytes and verifies the response is HTTP 413. Then verify that the `/api/v1/observability/events`
endpoint is exempt (it must accept larger bodies for batch uploads).

Hint: look at `app/middleware/request_size.py` to find the exemption logic.

**Exercise 4 — Trace a token replay attack**

Write a test in `tests/unit/test_auth_service_layer.py` that simulates this scenario:
1. User logs in → gets `refresh_token_A`
2. Token A is stolen
3. User refreshes → `refresh_token_A` becomes `used=True`, gets `refresh_token_B`
4. Attacker submits `refresh_token_A` → expect `TokenFamilyRevokedError`
5. Verify `revoke_family` was called with A's `family_id`
6. User tries to refresh with `refresh_token_B` → expect `InvalidTokenError`
   (because B was in the same family as A and was revoked in step 4)

**Exercise 5 — Alembic migration**

Add a `last_seen_at` column to the `User` model (nullable `DateTime`).
Then:
1. Create an Alembic migration: `alembic revision --autogenerate -m "add_last_seen_at_to_users"`
2. Verify the generated migration has both `upgrade()` and `downgrade()`
3. Add `UserRepository.update_last_seen(user_id)` that sets the column to `datetime.now(UTC)`
4. Write a unit test for the repository method
