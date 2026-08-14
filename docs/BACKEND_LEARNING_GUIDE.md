# Backend Learning Guide — Android AI Assistant
## For Complete Python Beginners

Welcome! This guide explains every part of the backend in plain language.
No Python experience needed. By the end of the 7-day plan you will understand and be able to contribute to all of it.

---

## Part 1 — What Is the Backend?

The backend is a Python program that runs on a server. Your Android app sends requests to it over the internet, and it replies with data (AI answers, saved notes, chat history, etc.).

Think of it like a waiter in a restaurant:
- **Android app** = customer placing an order
- **Backend** = waiter who takes the order, talks to the kitchen, and brings the food back
- **Database / AI providers** = the kitchen

---

## Part 2 — The Big Picture (Architecture)

```
Android App
    │
    │ HTTPS requests
    ▼
┌─────────────────────────────┐
│        Nginx (port 80)      │  ← reverse proxy (traffic cop)
└────────────┬────────────────┘
             │
    ┌────────▼─────────┐
    │  FastAPI (8000)  │  ← Python web server (YOUR code lives here)
    │                  │
    │  Middleware      │  ← runs on EVERY request before the handler
    │  Routers/APIs    │  ← decides which function handles which URL
    │  Services        │  ← business logic (the "brain")
    │  Models/ORM      │  ← database table definitions
    └──┬────┬────┬─────┘
       │    │    │
  PostgreSQL Redis ChromaDB  ← data storage
       │         │
  Alembic    Celery Worker   ← migrations / background tasks
```


---

## Part 3 — Python Basics You Need First

### Variables and Types
```python
name = "Alice"          # str  (text)
age  = 30               # int  (whole number)
price = 9.99            # float (decimal)
is_active = True        # bool (True/False)
items = ["a", "b"]      # list (ordered collection)
user = {"id": 1}        # dict (key-value pairs)
nothing = None          # None (empty / not set)
```

### Functions
```python
def greet(name: str) -> str:
    return f"Hello, {name}!"

result = greet("Alice")   # result = "Hello, Alice!"
```

### Classes — the building block of this project
```python
class Dog:
    def __init__(self, name: str):   # constructor (runs when you create a Dog)
        self.name = name             # self.x = store data on this object

    def bark(self) -> str:
        return f"{self.name} says woof!"

my_dog = Dog("Rex")
print(my_dog.bark())    # "Rex says woof!"
```

### Async / Await — very important in this project
Normal functions run one at a time. Async functions can wait for slow things
(database, network) without blocking everything else.

```python
import asyncio

async def fetch_data() -> str:
    await asyncio.sleep(1)    # pretend waiting for DB
    return "data ready"

# To call an async function you must also be inside async context
async def main():
    result = await fetch_data()
    print(result)
```

Every function in this backend that touches a database or network is `async`.

### Type hints — you will see these everywhere
```python
def add(x: int, y: int) -> int:   # says: takes two ints, returns int
    return x + y

def get_user(user_id: str) -> dict | None:  # returns dict OR None
    ...
```


---

## Part 4 — The Folder Structure Explained

```
backend/
├── app/
│   ├── main.py           ← START HERE — the app entry point
│   ├── config/
│   │   └── settings.py   ← all configuration (API keys, URLs, limits)
│   ├── api/              ← URL routes (what URLs exist and what they do)
│   │   ├── auth/         ← login, register, refresh token
│   │   ├── chat/         ← send a message to the AI
│   │   ├── rag/          ← upload documents, ask questions about them
│   │   ├── memory/       ← store/retrieve long-term user memories
│   │   ├── productivity/ ← todos, notes, habits, calendar
│   │   ├── websocket/    ← real-time streaming chat
│   │   └── ...
│   ├── services/         ← BRAIN — business logic
│   │   ├── llm_clients.py      ← talks to OpenAI/Gemini/Claude/Ollama
│   │   ├── ai_orchestrator.py  ← coordinates all AI requests
│   │   ├── auth_service.py     ← login, tokens, security
│   │   ├── rag_service.py      ← document processing pipeline
│   │   ├── memory_service.py   ← user memory management
│   │   └── safety_service.py   ← blocks harmful content
│   ├── models/           ← database table definitions
│   │   ├── user.py       ← users table
│   │   ├── conversation.py
│   │   ├── message.py
│   │   └── ...           ← one file per table
│   ├── repositories/     ← database queries (all SQL lives here)
│   ├── middleware/        ← code that runs before every API request
│   │   ├── rate_limit.py ← limits requests per user per minute
│   │   ├── logging_middleware.py
│   │   └── ...
│   ├── security/         ← JWT tokens, passwords, encryption
│   ├── prompts/          ← AI system prompt templates
│   └── workers/          ← background tasks (Celery)
├── alembic/              ← database migration scripts
├── tests/                ← automated tests
├── requirements.txt      ← list of packages this project uses
├── Dockerfile            ← how to package as a Docker container
└── RUNNING.md            ← how to start the server locally
```

---

## Part 5 — Entry Point: `app/main.py`

This is where Python starts when you run `uvicorn app.main:app`.

### What it does, step by step:

**Step 1 — Validates environment variables before starting**
```python
REQUIRED_ENV_VARS = [
    ("SECRET_KEY", "JWT signing key"),
    ("DATABASE_URL", "PostgreSQL URL"),
    ("REDIS_URL", "Redis URL"),
    ("AES_ENCRYPTION_KEY", "encryption key"),
]
```
If any of these are missing in the `.env` file, the server refuses to start and prints an error. This prevents running with a broken config.

**Step 2 — Creates the FastAPI app**
```python
app = FastAPI(title="Android AI Assistant API", version="1.0.0")
```

**Step 3 — Adds middleware (code that runs on EVERY request)**
```python
app.add_middleware(CORSMiddleware, ...)     # allows Android app to talk to this server
app.add_middleware(RateLimitMiddleware)     # stops users from spamming the API
app.add_middleware(DataResidencyMiddleware) # enforces geographic data rules
app.add_middleware(RequestBodySizeLimitMiddleware) # blocks huge requests
app.add_middleware(RequestLoggingMiddleware) # logs every request
```

**Step 4 — Registers all the routes (URLs)**
```python
app.include_router(auth_router)         # /auth/login, /auth/register
app.include_router(chat_router)         # /chat
app.include_router(rag_router)          # /documents/upload
app.include_router(memory_router)       # /memory
# ... 20+ more routers
```

**Step 5 — Health check endpoints**
- `GET /health` → always returns `{"status": "ok"}` if server is alive
- `GET /ready` → checks database AND Redis are also alive, returns 503 if not


---

## Part 6 — Configuration: `app/config/settings.py`

The `Settings` class reads ALL configuration from environment variables (or `.env` file).
This is done using **pydantic-settings** — a library that validates your config at startup.

```python
from app.config.settings import get_settings

settings = get_settings()
print(settings.DATABASE_URL)   # "postgresql+asyncpg://..."
print(settings.OPENAI_API_KEY) # "sk-..."
```

Key settings to know:

| Setting | What it does |
|---------|-------------|
| `DATABASE_URL` | PostgreSQL connection string |
| `REDIS_URL` | Redis connection string |
| `SECRET_KEY` | Used to sign JWT tokens (keep secret!) |
| `OPENAI_API_KEY` | Your OpenAI key |
| `GEMINI_API_KEY` | Your Google Gemini key |
| `ANTHROPIC_API_KEY` | Your Claude key |
| `OLLAMA_BASE_URL` | Local AI server URL |
| `RATE_LIMIT_REQUESTS_PER_MINUTE` | Max requests per user (default: 60) |
| `ACCESS_TOKEN_EXPIRE_MINUTES` | JWT expires after this many minutes (default: 15) |
| `REFRESH_TOKEN_EXPIRE_DAYS` | Refresh token lifetime (default: 30 days) |
| `DEFAULT_LLM_PROVIDER` | Which AI to use by default (default: "gemini") |

`get_settings()` uses `@lru_cache` — this means settings are only read from
environment once, then cached. Very efficient.

---

## Part 7 — Database Models: `app/models/`

Models define the shape of your database tables using Python classes.
This project uses **SQLAlchemy 2.x** (an ORM — Object Relational Mapper).

### What is an ORM?
Instead of writing SQL like `SELECT * FROM users WHERE id = 1`,
you write Python like `await db.get(User, user_id)`.
The ORM translates your Python into SQL automatically.

### The Base class (`base.py`)
Every model extends `Base` and optionally `TimestampMixin`:

```python
class MyModel(Base, TimestampMixin):
    __tablename__ = "my_table"   # the actual table name in PostgreSQL

    id: Mapped[uuid.UUID] = uuid_pk()     # auto-generated UUID primary key
    # TimestampMixin adds:
    # created_at: datetime  ← set automatically when row is created
    # updated_at: datetime  ← updated automatically on every save
```

### The User model (`models/user.py`)
The most important model — stores user accounts:

```python
class User(Base, TimestampMixin):
    __tablename__ = "users"

    id: Mapped[uuid.UUID]       # unique user ID
    email: Mapped[str]          # login email (unique)
    password_hash: Mapped[str]  # bcrypt hashed password (NEVER plain text)
    role: Mapped[UserRole]      # "user", "premium", or "admin"
    is_active: Mapped[bool]     # False = account disabled
    privacy_mode: Mapped[bool]  # True = don't capture memories
    push_token: Mapped[str]     # Firebase notification token
```

### All the models (tables) in this project:
- `users` — user accounts
- `conversations` — AI chat conversations
- `messages` — individual messages in a conversation
- `memories` — long-term AI memory per user
- `documents` — uploaded documents
- `document_chunks` — document pieces for RAG search
- `token_usage` — tracks AI token costs per request
- `refresh_tokens` — JWT refresh tokens
- `api_keys` — user's API keys for external services
- `todo_items`, `notes`, `habits`, `calendar_events`, `reminders` — productivity
- `prompt_templates` — custom AI prompt templates
- `audit_logs` — security event log
- `error_logs`, `spending_alerts`, `feedback`, `jobs`


---

## Part 8 — Authentication System: `app/services/auth_service.py`

Authentication answers the question: "Who are you, and can I trust you?"

### How Login Works (JWT tokens)

**Step 1 — User sends email + password**
**Step 2 — Server checks password hash using bcrypt**
**Step 3 — Server issues two tokens:**

```
Access Token  (lives 15 minutes) — used for every API request
Refresh Token (lives 30 days)    — used only to get a new access token
```

**Step 4 — Android app stores both tokens**
**Step 5 — Every API request includes:** `Authorization: Bearer <access_token>`

### What is a JWT?
A JWT (JSON Web Token) is a string with 3 parts separated by dots:
```
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyLWlkIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c
   HEADER                    PAYLOAD                      SIGNATURE
```
The payload contains the user ID, role, and expiry time.
The signature is created using the `SECRET_KEY` — only the server can create valid tokens.

### Token Refresh Flow
When the access token expires (after 15 min), the app sends the refresh token to get a new access token — without making the user log in again.

**Security feature: Replay Detection**
Each refresh token is single-use. If someone tries to use the same refresh token twice (replay attack), the server:
1. Detects it was already used
2. Revokes ALL tokens in the same "family"
3. Forces the user to log in again

```python
# From auth_service.py — if token already used:
if record.used:
    count = await repo.revoke_family(record.family_id)  # nuclear option
    raise TokenFamilyRevokedError(f"replay detected — revoked {count} tokens")
```

### Account Lockout
After 5 failed login attempts in 10 minutes → account locked for 15 minutes.
This protects against password brute-force attacks.

---

## Part 9 — LLM Clients: `app/services/llm_clients.py`

This is where the backend talks to AI providers.

### The Abstract Base Class Pattern
All 6 AI providers share the same interface (`BaseLLMClient`).
This means the rest of the code doesn't care WHICH provider is being used —
they all work the same way.

```python
class BaseLLMClient(ABC):          # ABC = Abstract Base Class
    @abstractmethod
    async def stream(self, context): ...   # stream tokens one by one
    @abstractmethod
    async def complete(self, context): ... # get full response at once
    @abstractmethod
    def max_context_tokens(self): ...      # how many tokens fit in context
    @abstractmethod
    def cost_per_input_token(self): ...    # price per token
```

### The 6 Providers

| Provider | Class | Model | Context Window | Cost |
|----------|-------|-------|----------------|------|
| OpenAI | `OpenAIClient` | GPT-4o | 128,000 tokens | $0.005/1K input |
| Google | `GeminiClient` | Gemini Flash | 1,000,000 tokens | $0.00125/1K input |
| Anthropic | `ClaudeClient` | Claude 3.5 Sonnet | 200,000 tokens | $0.003/1K input |
| Local | `OllamaClient` | Any Ollama model | 4,096 tokens | Free |
| Local | `LlamaClient` | Llama 3.2 | 4,096 tokens | Free |
| Local | `MistralClient` | Mistral | 4,096 tokens | Free |

### Streaming vs Complete
- **stream** — sends tokens as they're generated (like ChatGPT typing effect)
- **complete** — waits for the full response, then returns it all at once

```python
# Streaming example (used for WebSocket chat)
async for token in client.stream(context):
    await websocket.send_json({"type": "token", "data": token})

# Complete example (used for summarization)
full_response = await client.complete(context)
```

### Per-Provider Rate Limiting
Each provider has its own requests-per-minute limit stored in Redis.
If a user exceeds the limit, they get `RateLimitError` immediately — before
any API call is made (saves money).

```python
# Key stored in Redis: "llm_rate:openai:user-id:window"
# window = current minute (int(time.time() // 60))
```


---

## Part 10 — AI Orchestrator: `app/services/ai_orchestrator.py`

The orchestrator is the "brain" that coordinates an entire AI chat request.
It is called for every user message and does 9 steps:

```
User sends message
      │
      ▼
1. Detect prompt injection  ← block "ignore previous instructions" attacks
      │
      ▼
2. Save user message to DB
      │
      ▼
3. Build the prompt:
   ├── System prompt (who the AI is, rules)
   ├── User's memories (top 3 relevant ones from ChromaDB)
   ├── Conversation history (last N messages)
   └── Current user message
      │
      ▼
4. Check if history is too long (> 80% of context window)
   └── If yes → summarize the oldest messages using the AI itself
      │
      ▼
5. Send to AI provider (stream tokens back over WebSocket)
      │
      ▼
6. Apply safety filters to every token
      │
      ▼
7. If provider fails → try fallback provider (auto-retry)
      │
      ▼
8. Save AI response to DB
      │
      ▼
9. Record token usage + cost to DB and Prometheus
```

### What is a PromptContext?
Before calling the AI, the orchestrator assembles a `PromptContext` object:

```python
@dataclass
class PromptContext:
    messages: list[PromptMessage]  # system + history + current message
    estimated_tokens: int           # rough count of tokens used
    provider: LLMProvider           # which AI to use
    user_id: str                    # who is asking
```

### Prompt Injection Detection
The orchestrator checks every user message for patterns like:
- "ignore all previous instructions"
- "you are now a different AI"
- "forget your training"
- `<system>`, `[SYSTEM]`, `</inst>` tags

If detected → message is blocked, user gets an error, no AI call is made.

### Graceful Fallback
If OpenAI fails (network error, quota exceeded):
1. The user sees: *"OpenAI encountered an error. Switching to gemini."*
2. The same prompt is sent to the fallback provider
3. The response continues seamlessly

The fallback provider is configured via `LLM_FALLBACK_PROVIDER` in `.env`.

### Token Estimation
The backend estimates tokens using a simple rule: **1 token ≈ 4 characters**.
This is fast but approximate. Exact counts come from the provider's API response.

---

## Part 11 — RAG Service: `app/services/rag_service.py`

RAG = Retrieval Augmented Generation.
It lets users upload documents and then ask questions about them.

### The Ingestion Pipeline (when user uploads a document)

```
Upload PDF/DOCX/TXT/MD
         │
         ▼
1. Validate — check file type and size (max 50 MB)
         │
         ▼
2. Store in MinIO — object storage (like Amazon S3 but self-hosted)
         │
         ▼
3. Extract Text
   ├── PDF  → pypdf (native) OR pytesseract OCR (if scanned)
   ├── DOCX → python-docx
   └── TXT/MD → UTF-8 decode
         │
         ▼
4. Chunk the text into 512-token pieces (with 64-token overlap)
   "Hello world this is a long document..."
   → Chunk 1: "Hello world this is..."  (tokens 0-512)
   → Chunk 2: "...this is a long..."    (tokens 448-960)  ← 64 overlap
   → Chunk 3: ...
         │
         ▼
5. Generate embeddings — convert each chunk into a list of ~384 numbers
   (using SentenceTransformer model "all-MiniLM-L6-v2")
   "Hello world" → [0.12, -0.43, 0.87, ... 384 numbers]
         │
         ▼
6. Store vectors in ChromaDB (per-user collection: "documents_{user_id}")
   Store metadata in PostgreSQL (document_chunks table)
```

### The Query Pipeline (when user asks a question about a document)

```
User asks: "What does the contract say about payment terms?"
         │
         ▼
1. Generate embedding for the question (same model as step 5 above)
         │
         ▼
2. Search ChromaDB — find the top 5 chunks closest to the question
         │
         ▼
3. Retrieve chunk metadata from PostgreSQL (filename, page number)
         │
         ▼
4. Build context for the AI:
   "Context from 'contract.pdf' page 3: ...payment is due within 30 days..."
   "Context from 'contract.pdf' page 7: ...late payment fee of 2%..."
         │
         ▼
5. Send to AI with the context + user question
         │
         ▼
6. AI answers using ONLY the document content (with citations)
```

### Why Chunking Overlap?
If a sentence spans the boundary between two chunks, the overlap ensures it
appears fully in at least one chunk. This prevents important context from
being missed.


---

## Part 12 — Memory Service: `app/services/memory_service.py`

The memory service gives the AI a "long-term memory" of each user.

### How it works
- When you chat, the AI can store facts about you: preferences, writing style, important dates
- Next time you chat, those memories are retrieved and injected into the system prompt
- The AI says "I remember you prefer concise answers" — because it actually does remember

### Memory Storage
Memories are stored in TWO places:
1. **PostgreSQL** — the text + metadata (type, when created)
2. **ChromaDB** — vector embedding (for semantic search)

### Retrieval
When building a prompt, the top 3 most relevant memories are fetched:
```python
memories = await memory_service.get_relevant_memories(
    user_id=user_uuid,
    query=current_user_message,  # semantic search
    top_k=3
)
```

### Memory Types
- `fact` — "User's name is Alice"
- `preference` — "User prefers bullet points over paragraphs"
- `style` — "User writes formally in emails"

### Privacy Mode
If user turns on privacy mode (`PATCH /users/me/privacy-mode`):
- New memories are **not** stored
- Existing memories are **not** deleted (still usable)
- User can turn privacy mode off to resume memory capture

### Differential Privacy
Memories are stored with mathematical noise added to their embeddings.
This is "differential privacy" — even if someone stole the ChromaDB data,
they couldn't reconstruct the exact original text. Controlled by `DP_EPSILON` setting.

---

## Part 13 — Safety Service: `app/services/safety_service.py`

Two safety mechanisms protect the system:

### 1. SafetyService — Output Filtering
Scans every AI response before it reaches the user.
Strips harmful patterns like `<script>` tags and `javascript:` URLs.
If stripping fails → blocks the entire response (`SafetyFilterError`).

### 2. InjectionDetector — Input Filtering
Scans every user message for prompt injection attempts.
If detected:
1. Replaces the bad parts with `[redacted]`
2. Computes SHA-256 hash of the redacted input
3. Writes an `AuditLog` entry (never stores the raw attack payload)
4. Raises `PromptInjectionError` → HTTP 400 returned to client

---

## Part 14 — Middleware: `app/middleware/`

Middleware wraps every single HTTP request. Think of it as a security guard
and traffic controller at the door of a building.

### Request flow through middleware (order matters):
```
Request arrives
      │
      ▼
RequestLoggingMiddleware    ← assigns a correlation ID, logs the request
      │
      ▼
RateLimitMiddleware         ← checks: has this user/IP sent too many requests?
      │                       Authenticated: 60 req/min | Unauthenticated: 20 req/min
      ▼
DataResidencyMiddleware     ← checks: is this request from an allowed region?
      │
      ▼
RequestBodySizeLimitMiddleware  ← is the request body too large? (max 1 MB)
      │
      ▼
CORSMiddleware              ← allows Android app's domain to call this API
      │
      ▼
Your Route Handler          ← actual business logic runs here
```

### Rate Limiting — How It Works
Uses Redis as a counter with a 1-minute window:
```
Key: "rate:{user_id}:{window}"  where window = current minute number
Value: number of requests this minute

On each request:
  INCR the key → if result > 60 → return HTTP 429 (Too Many Requests)
  If result == 1 → set TTL = 120 seconds (auto-cleanup)
```

If Redis is down → **fail-open** (requests allowed through with a warning log).
This is a deliberate choice: uptime > strict rate limiting during Redis outages.


---

## Part 15 — Database Migrations: `alembic/`

Alembic is a tool for managing database schema changes over time.

### The Problem It Solves
Your Python models define what tables should look like.
But the actual PostgreSQL database needs SQL commands to CREATE or ALTER tables.
Alembic generates and tracks those SQL commands automatically.

### Key Commands
```cmd
rem Apply all pending migrations (run after every git pull)
alembic upgrade head

rem Check what migration is currently applied
alembic current

rem See all migration history
alembic history

rem After you change a model → generate new migration
alembic revision --autogenerate -m "add privacy_mode to users"

rem Undo the last migration
alembic downgrade -1
```

### Migration File Example
When you run `alembic revision --autogenerate`, it creates a file like:
```python
def upgrade() -> None:
    op.add_column('users', sa.Column('privacy_mode', sa.Boolean(), default=False))

def downgrade() -> None:
    op.drop_column('users', 'privacy_mode')
```
Alembic tracks which migrations have been run so it never runs the same one twice.

---

## Part 16 — Background Tasks: `app/workers/`

Some tasks are too slow to do during a web request (like processing a 50 MB PDF).
These are handled by **Celery** — a background task queue.

### How It Works
```
User uploads PDF via HTTP
         │
         ▼
API saves the file to MinIO
Sends a task to Celery queue (via Redis)
Returns HTTP 200 immediately ← user doesn't wait
         │
         ▼ (separately, in the background)
Celery Worker picks up the task
Extracts text → chunks → embeds → stores in ChromaDB
Updates job status in DB to "completed"
         │
         ▼
User polls GET /rag/jobs/{job_id} to check progress
```

### Why Redis for the Queue?
Redis is both the database for rate limiting AND the message broker for Celery.
Tasks are stored as messages in a Redis list. Workers pop messages and process them.

---

## Part 17 — Observability: Prometheus + Grafana

The backend automatically records metrics about everything that happens.

### What Gets Tracked
- Request count and latency per endpoint
- AI token usage per provider (input tokens, output tokens, cost in USD)
- Error rates
- Background task counts

### Where to See It
- **Prometheus** at `http://localhost:9090` — raw metrics
- **Grafana** at `http://localhost:3000` — visual dashboards (admin / changeme)
- There is a pre-built "AI Cost Dashboard" in `infrastructure/grafana/`

### In Code
```python
# From ai_orchestrator.py — records token usage after every AI response
from app.workers.metrics import record_token_usage
record_token_usage(
    provider="openai",
    input_tokens=150,
    output_tokens=200,
    cost_usd=0.0042,
)
```

---

## Part 18 — API Routes (URLs)

Each API folder in `app/api/` has a `router.py` that defines URL endpoints.

### Pattern: Every router looks like this
```python
from fastapi import APIRouter, Depends
router = APIRouter(prefix="/chat", tags=["chat"])

@router.post("/")                    # POST /chat/
async def send_message(
    body: ChatRequest,               # validated request body (Pydantic)
    current_user = Depends(get_current_user),  # auth check
    db: AsyncSession = Depends(get_db),         # database session
) -> ChatResponse:
    # call a service function
    result = await chat_service.send(body, current_user.id, db)
    return result
```

### Key API Groups

| Prefix | What it does |
|--------|-------------|
| `/auth` | register, login, refresh token, logout |
| `/chat` | send message to AI |
| `/ws` | WebSocket for streaming AI responses |
| `/documents` | upload files for RAG |
| `/rag` | query documents with AI |
| `/memory` | store/retrieve/delete memories |
| `/conversations` | list/get/delete chat history |
| `/users` | profile, privacy mode, settings |
| `/productivity` | todos, notes, habits, calendar, reminders |
| `/admin` | user management, system config (admin only) |
| `/analytics` | usage statistics |
| `/prompts` | custom prompt templates |
| `/images` | AI image generation |
| `/transcription` | speech-to-text |
| `/translation` | text translation |
| `/generation` | resume/cover letter/email writing |
| `/notifications` | push notification device token |
| `/search` | search across conversations and documents |
| `/usage` | token usage history and costs |
| `/health` | server health check |
| `/ready` | readiness check (DB + Redis) |
| `/metrics` | Prometheus metrics |


---

## Part 19 — Key Python Libraries Used

| Library | What It Does | Where Used |
|---------|-------------|------------|
| `fastapi` | Web framework — handles HTTP requests | Throughout `app/api/` |
| `uvicorn` | Runs the FastAPI server | `uvicorn app.main:app` |
| `pydantic` | Data validation — validates request/response shapes | `app/schemas/` |
| `pydantic-settings` | Reads config from `.env` file | `app/config/settings.py` |
| `sqlalchemy` | ORM — Python ↔ PostgreSQL | `app/models/`, `app/repositories/` |
| `alembic` | Database migrations | `alembic/` |
| `asyncpg` | Fast async PostgreSQL driver | Under SQLAlchemy |
| `redis` | Redis client (rate limiting, caching, Celery broker) | `app/middleware/`, `app/workers/` |
| `celery` | Background task queue | `app/workers/` |
| `openai` | OpenAI API client | `app/services/llm_clients.py` |
| `google-generativeai` | Google Gemini API client | `app/services/llm_clients.py` |
| `anthropic` | Anthropic Claude API client | `app/services/llm_clients.py` |
| `httpx` | HTTP client (for Ollama) | `app/services/llm_clients.py` |
| `python-jose` | JWT token creation/verification | `app/security/jwt_handler.py` |
| `passlib`/`bcrypt` | Password hashing | `app/security/password.py` |
| `cryptography` | AES-256 encryption for API keys | `app/security/` |
| `chromadb` | Vector database (semantic search) | `app/services/rag_service.py` |
| `minio` | Object storage (file uploads) | `app/services/rag_service.py` |
| `sentence-transformers` | Text → vector embedding model | `app/services/rag_service.py` |
| `tiktoken` | Tokenization (count tokens) | `app/services/rag_service.py` |
| `pypdf` | PDF text extraction | `app/services/rag_service.py` |
| `pytesseract` | OCR for scanned PDFs | `app/services/rag_service.py` |
| `python-docx` | DOCX text extraction | `app/services/rag_service.py` |
| `firebase-admin` | Push notifications (FCM) | `app/services/` |
| `prometheus-fastapi-instrumentator` | Automatic metrics collection | `app/main.py` |
| `python-dotenv` | Loads `.env` file | `app/config/settings.py` |
| `pytest` | Test framework | `backend/tests/` |
| `pytest-asyncio` | Async test support | `backend/tests/` |

---

## Part 20 — Security Architecture Summary

```
Layer 1 — Transport:    HTTPS only (TLS) — Nginx terminates SSL
Layer 2 — CORS:         Only allowed origins (Android app URL)
Layer 3 — Rate Limits:  60 req/min (auth) / 20 req/min (unauth)
Layer 4 — Body Size:    Max 1 MB JSON / 50 MB files
Layer 5 — Auth:         JWT (15 min) + Refresh tokens (30 days)
Layer 6 — Replay:       Token family revocation on reuse
Layer 7 — Lockout:      5 failed logins → locked 15 min
Layer 8 — Injection:    Regex patterns block prompt injection
Layer 9 — Output:       Safety filter strips harmful AI output
Layer 10 — Encryption:  AES-256 for stored secrets
Layer 11 — Passwords:   bcrypt with work factor 12
Layer 12 — Audit:       Every security event logged to DB
Layer 13 — Privacy:     Differential privacy on memory embeddings
```



---

## Part 21 — Summary: The Complete Data Flow

Here's how ONE user message travels through the entire system:

```
1. Android app sends HTTP POST to /chat/
   Body: {"message": "Hello!", "provider": "openai"}
   Header: Authorization: Bearer eyJhbGci... (JWT)
   
2. Nginx receives on port 80 → forwards to FastAPI on port 8000

3. RequestLoggingMiddleware → assigns correlation ID "req-abc123"

4. RequestBodySizeLimitMiddleware → checks body ≤ 1MB ✓

5. DataResidencyMiddleware → checks X-Client-Region header ✓

6. RateLimitMiddleware → checks Redis:
   Key "rate:user-id:439094" → count=45 (< 60) ✓ pass

7. CORSMiddleware → checks origin header = Android app URL ✓

8. FastAPI routing → matches /chat/ endpoint

9. Depends(get_current_user) → verifies JWT:
   - Decode header.payload.signature
   - Check signature with SECRET_KEY
   - Check exp claim < now()
   - Extract sub (user ID) → current_user

10. Depends(get_db) → creates AsyncSession from pool

11. Route handler chat/router.py:send_message():
    - Validates body with Pydantic
    - Calls ChatService

12. ChatService → calls AIOrchestrator.stream_chat()

13. AIOrchestrator:
    a. Detect prompt injection → check regex patterns ✓
    b. Save user message to PostgreSQL messages table
    c. Build prompt:
       - System prompt from prompts/system_prompts.py
       - Top 3 memories from ChromaDB (semantic search)
       - Last 10 messages from PostgreSQL
       - Current user message
    d. Resolve provider → OpenAIClient
    e. Check LLM rate limit in Redis ✓
    f. Call client.stream(prompt)

14. OpenAIClient → HTTPS to api.openai.com:
    POST https://api.openai.com/v1/chat/completions
    {
      "model": "gpt-4o",
      "messages": [...],
      "stream": true
    }

15. OpenAI streams back tokens:
    {"delta": {"content": "Hello"}}
    {"delta": {"content": " there"}}
    {"delta": {"content": "!"}}

16. AIOrchestrator yields each token:
    - Apply safety filter (check for <script>)
    - Send to WebSocket → Android app
    - Collect for database save

17. After stream completes:
    - Save assistant message to PostgreSQL
    - Calculate cost: 150 input tokens * $0.000005
                    + 8 output tokens * $0.000015
                    = $0.00087
    - Save TokenUsage row to PostgreSQL
    - Record metrics in Prometheus

18. get_db() commits transaction → all saves permanent

19. FastAPI returns HTTP 200 with JSON response

20. Android app displays message in chat UI
```

---

## Where to Go Next

1. **Complete the 7-day plan** in `BACKEND_7DAY_PLAN.md`
2. **Read actual code with the explanations** in `BACKEND_CODE_EXPLAINED.md`
3. **Look at tests** in `backend/tests/` to see how each part is tested
4. **Make a small change** — add a field to User model, generate migration, test it
5. **Watch the logs** — start the server with `uvicorn ... --reload` and watch what happens
6. **Break something on purpose** — remove a required field, see the error message
7. **Build something new** — add a new model, repository, service, and router

The best way to learn is by doing. This guide gives you the foundation.
Now go write some Python code!
