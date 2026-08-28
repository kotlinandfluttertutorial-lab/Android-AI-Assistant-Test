# Backend Code Explained — Line by Line
## Android AI Assistant — For Complete Python Beginners

Every code snippet in this document is taken **directly from the actual project files**.
Each line is explained so you know exactly what it does and why it exists.

---

## Table of Contents
1. [How Python Imports Work](#1-how-python-imports-work)
2. [app/main.py — The Entry Point](#2-appmainpy--the-entry-point)
3. [app/config/settings.py — Configuration](#3-appconfigsettingspy--configuration)
4. [app/database/__init__.py — Database Connection](#4-appdatabaseinitpy--database-connection)
5. [app/models/base.py — ORM Foundation](#5-appmodelsbasepy--orm-foundation)
6. [app/models/user.py — The User Table](#6-appmodelsuserpy--the-user-table)
7. [app/schemas/auth.py — Request/Response Shapes](#7-appschemasauthpy--requestresponse-shapes)
8. [app/security/jwt_handler.py — JWT Tokens](#8-appsecurityjwt_handlerpy--jwt-tokens)
9. [app/api/auth/router.py — Login Endpoints](#9-appapiauthrouterpy--login-endpoints)
10. [app/services/auth_service.py — Auth Business Logic](#10-appservicesauth_servicepy--auth-business-logic)
11. [app/services/llm_clients.py — AI Providers](#11-appservicesllm_clientspy--ai-providers)
12. [app/services/ai_orchestrator.py — The AI Brain](#12-appservicesai_orchestratorpy--the-ai-brain)
13. [app/api/websocket/router.py — Real-Time Chat](#13-appapiwebsocketrouterpy--real-time-chat)
14. [app/middleware/rate_limit.py — Rate Limiting](#14-appmiddlewarerate_limitpy--rate-limiting)
15. [app/api/productivity/router.py — Todos, Habits, Calendar](#15-appapiproductivityrouterpy--todos-habits-calendar)
16. [app/services/rag_service.py — Document AI](#16-appservicesrag_servicepy--document-ai)
17. [app/services/safety_service.py — Security Filters](#17-appservicessafety_servicepy--security-filters)

---

## 1. How Python Imports Work

Before reading any file, understand imports. Every Python file starts with them.

```python
# These three styles all appear in the project:

# 1. Import a whole module — use it as module.function()
import logging
import asyncio

# 2. Import specific things from a module — use them directly
from fastapi import FastAPI, Depends
from datetime import datetime, timezone

# 3. Import from YOUR OWN project files
from app.config.settings import get_settings   # reads backend/app/config/settings.py
from app.models.user import User               # reads backend/app/models/user.py
```

The `from __future__ import annotations` you see at the top of every file:
```python
from __future__ import annotations
# This makes Python handle type hints lazily (avoids circular import errors).
# It's a safety mechanism — just accept it's always there.
```

---

## 2. `app/main.py` — The Entry Point

This file is what runs when you start the server with `uvicorn app.main:app`.

### The required environment variables list

```python
# From main.py lines 57-62
REQUIRED_ENV_VARS: list[tuple[str, str]] = [
    ("SECRET_KEY",        "JWT secret key for signing tokens"),
    ("DATABASE_URL",      "PostgreSQL async connection URL"),
    ("REDIS_URL",         "Redis connection URL"),
    ("AES_ENCRYPTION_KEY","Base64-encoded AES-256 key for encrypting stored secrets"),
]
```

**What this means line by line:**
- `REQUIRED_ENV_VARS` — a variable name (ALL CAPS = it's a constant, never changes)
- `list[tuple[str, str]]` — type hint: a list where each item is a tuple of 2 strings
- Each tuple = `("ENV_VAR_NAME", "human description")`
- These 4 must exist in `.env` or the server refuses to start

### The startup validation function

```python
# From main.py
def startup_validation() -> None:
    #           ↑ return type hint: None means this returns nothing
    missing: list[str] = []
    #        ↑ type hint: a list of strings

    for var_name, description in REQUIRED_ENV_VARS:
        #  ↑ unpacks each tuple into two variables
        value = os.environ.get(var_name, "").strip()
        #                    ↑ get from environment, default to "" if missing
        #                                            ↑ remove spaces from both ends
        if not value:
            # "not value" is True when value is "" (empty string)
            logger.error(
                "STARTUP_VALIDATION_FAILED: required environment variable %r is missing",
                var_name,
                # ↑ %r means "repr" — prints with quotes, like 'SECRET_KEY'
            )
            missing.append(var_name)

    if missing:
        # if the list is not empty
        sys.exit(1)
        # ↑ immediately kills the process with error code 1
        # This prevents the server from starting with broken config
```


### The lifespan context manager

```python
# From main.py
@asynccontextmanager                    # ← decorator: makes this a context manager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    # Everything BEFORE yield runs at startup
    startup_validation()                # check env vars first

    from app.workers.celery_app import celery_app
    from app.workers.metrics import setup_celery_metrics
    setup_celery_metrics(celery_app)    # connect Celery metrics to Prometheus

    yield                               # ← server runs here (handles requests)

    # Everything AFTER yield runs at shutdown (cleanup)
    # (nothing to clean up yet — placeholder for future use)
```

**The `yield` keyword here is special** — it splits startup from shutdown.
When Python hits `yield`, the server starts accepting HTTP requests.
When the server stops (Ctrl+C), Python resumes after `yield` and runs cleanup.

### Creating the FastAPI app

```python
# From main.py
app = FastAPI(
    title="Android AI Assistant API",
    version="1.0.0",
    description="Enterprise-grade AI assistant backend...",
    docs_url="/docs",     # ← Swagger UI lives at http://localhost:8000/docs
    redoc_url="/redoc",   # ← ReDoc alternative docs at /redoc
    openapi_url="/openapi.json",  # ← raw OpenAPI spec JSON
    lifespan=lifespan,    # ← tells FastAPI to use our lifespan function above
)
```

### Adding middleware (the order matters!)

```python
# From main.py — middleware is added in reverse execution order
# The LAST one added runs FIRST on incoming requests

app.add_middleware(CORSMiddleware,
    allow_origins=settings.CORS_ORIGINS,  # e.g. ["http://localhost:3000"]
    allow_credentials=True,
    allow_methods=["*"],    # ← allow GET, POST, PUT, DELETE, PATCH, etc.
    allow_headers=["*"],    # ← allow any request header
)
app.add_middleware(RateLimitMiddleware)              # added 2nd → runs 5th
app.add_middleware(DataResidencyMiddleware)          # added 3rd → runs 4th
app.add_middleware(RequestBodySizeLimitMiddleware)   # added 4th → runs 3rd
app.add_middleware(RequestLoggingMiddleware)         # added 5th → runs 1st (outermost)
```

**Actual execution order for a real request:**
1. `RequestLoggingMiddleware` — assigns correlation ID, logs start
2. `RequestBodySizeLimitMiddleware` — check body size ≤ 1MB
3. `DataResidencyMiddleware` — check geographic region
4. `RateLimitMiddleware` — check rate limit
5. `CORSMiddleware` — check CORS origin
6. Your route handler — the actual business logic

### Health check endpoints

```python
# From main.py
@app.get("/health", tags=["ops"], summary="Liveness probe")
async def health() -> dict[str, str]:
    # No database or Redis check here — if the process responds, it's alive
    return {"status": "ok"}
    # ↑ FastAPI automatically converts this dict to JSON: {"status": "ok"}
```

```python
# From main.py — the more thorough readiness check
@app.get("/ready", tags=["ops"], summary="Readiness probe")
async def ready() -> JSONResponse:
    db_status = "ok"
    redis_status = "ok"
    missing_vars = get_missing_env_vars()   # check .env vars at runtime too

    try:
        await _check_db()       # runs "SELECT 1" on PostgreSQL
    except Exception:
        db_status = "unreachable"   # catch ANY exception — don't crash

    try:
        await _check_redis()    # runs PING on Redis
    except Exception:
        redis_status = "unreachable"

    dependencies = {"database": db_status, "redis": redis_status}
    all_ok = db_status == "ok" and redis_status == "ok" and not missing_vars

    if all_ok:
        return JSONResponse(status_code=200, content={"status": "ready", ...})

    # Something is wrong — return 503 (Service Unavailable)
    return JSONResponse(status_code=503, content={"status": "unavailable", ...})
```

---

## 3. `app/config/settings.py` — Configuration

This file uses **pydantic-settings** to read from `.env` and environment variables.

```python
# From settings.py
from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    # ↑ inheriting from BaseSettings gives automatic .env file reading

    model_config = SettingsConfigDict(
        env_file=".env",            # ← reads backend/.env file
        env_file_encoding="utf-8",
        case_sensitive=False,       # ← DATABASE_URL and database_url are the same
        extra="ignore",             # ← unknown env vars are silently ignored
    )

    # Each field = one environment variable
    DATABASE_URL: str = Field(
        description="PostgreSQL async connection URL..."
        # ↑ No default= here, so it's REQUIRED
    )

    JWT_ALGORITHM: str = Field(
        default="HS256",
        # ↑ Has a default, so it's OPTIONAL — but can be overridden in .env
        description="Algorithm used for JWT signing.",
    )

    ACCESS_TOKEN_EXPIRE_MINUTES: int = Field(
        default=15,
        description="Lifetime of an access token in minutes.",
        ge=1,   # ← ge = "greater than or equal to" 1, so minimum is 1
    )
```

### Properties (computed values, not env vars)

```python
# From settings.py
@property                       # ← @property makes this callable like an attribute
def JWT_SECRET_KEY(self) -> str:
    """Alias for SECRET_KEY for use by the JWT handler."""
    return self.SECRET_KEY      # just returns the real value under a different name

@property
def celery_broker(self) -> str:
    """Celery broker URL, falling back to REDIS_URL when not set."""
    return self.CELERY_BROKER_URL or self.REDIS_URL
    #           ↑ if CELERY_BROKER_URL is "" (empty), use REDIS_URL instead
    # "or" in Python: returns the first truthy value
```

### Field validators (validation rules)

```python
# From settings.py
@field_validator("LOG_LEVEL")   # ← run this function when LOG_LEVEL is set
@classmethod                    # ← it's a class method, not instance method
def validate_log_level(cls, value: str) -> str:
    allowed = {"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"}
    upper = value.upper()       # normalise to uppercase
    if upper not in allowed:
        raise ValueError(
            f"LOG_LEVEL must be one of {allowed}, got {value!r}"
            # ↑ f-string: {value!r} prints the value with quotes
        )
    return upper                # return the normalised value
```

### The `get_settings()` function

```python
# From settings.py
@lru_cache(maxsize=1)   # ← caches the result — only runs ONCE per process
def get_settings() -> Settings:
    return Settings()   # reads .env file the first time only
    # Every subsequent call returns the already-parsed Settings object

# Usage anywhere in the project:
from app.config.settings import get_settings
settings = get_settings()
print(settings.REDIS_URL)   # "redis://localhost:6379/0"
```

**Why `@lru_cache`?** Reading the `.env` file and validating 50+ fields takes time.
Caching means it only happens once. All 20+ routers share the same Settings object.


---

## 4. `app/database/__init__.py` — Database Connection

This file creates the connection to PostgreSQL and the FastAPI dependency for getting a session.

```python
# From database/__init__.py
from sqlalchemy.ext.asyncio import (
    AsyncSession,           # ← type for an async database session
    async_sessionmaker,     # ← factory that creates sessions
    create_async_engine,    # ← creates the connection pool to PostgreSQL
)
from sqlalchemy.orm import DeclarativeBase

class Base(DeclarativeBase):
    """Shared base class — every ORM model inherits from this."""
    # ↑ All models in app/models/ use this same Base
    # This means Alembic can find ALL tables through Base.metadata
    pass   # no extra code needed — inheriting is enough
```

### Building the database engine

```python
# From database/__init__.py
def _build_engine():
    settings = get_settings()
    return create_async_engine(
        settings.DATABASE_URL,
        # Example: "postgresql+asyncpg://user:pass@localhost:5432/aiassistant"
        #                  ↑ tells SQLAlchemy to use asyncpg driver
        echo=settings.LOG_LEVEL == "DEBUG",
        # ↑ echo=True prints every SQL query to the log (only in DEBUG mode)
        pool_pre_ping=True,
        # ↑ before using a connection, run a quick "SELECT 1" to check it's alive
        # This prevents errors from stale connections after DB restart
        pool_size=20,       # ← keep up to 20 persistent connections open
        max_overflow=10,    # ← allow 10 MORE connections if pool is full (30 total)
    )

engine = _build_engine()  # created once when the module is imported
```

### The session factory

```python
# From database/__init__.py
AsyncSessionLocal: async_sessionmaker[AsyncSession] = async_sessionmaker(
    bind=engine,            # ← which database to connect to
    class_=AsyncSession,    # ← what type of session to create
    expire_on_commit=False,
    # ↑ after commit(), don't expire (discard) loaded objects
    # Without this, accessing user.email after db.commit() would trigger
    # an extra SELECT. In async code this causes "greenlet_spawn" errors.
)
```

### The FastAPI dependency — `get_db()`

```python
# From database/__init__.py
async def get_db() -> AsyncGenerator[AsyncSession, None]:
    #                  ↑ AsyncGenerator = async version of a generator
    #                    yields AsyncSession, receives nothing (None)

    async with AsyncSessionLocal() as session:
        # ↑ creates a new session, auto-closes when the block ends
        try:
            yield session
            # ↑ "yield" here is what makes this a generator
            # FastAPI injects the session into route handlers at this point
            # The route handler runs to completion, then control returns here
            await session.commit()
            # ↑ saves all changes to the database after successful request
        except Exception:
            await session.rollback()
            # ↑ if anything went wrong, undo ALL changes (atomic transaction)
            raise
            # ↑ re-raise the exception so FastAPI can return an error response
        finally:
            await session.close()
            # ↑ ALWAYS close the session, even if an exception occurred

# Usage in a route handler:
# @router.get("/users/{id}")
# async def get_user(id: str, db: AsyncSession = Depends(get_db)):
#     #                                        ↑ FastAPI calls get_db() automatically
#     #                                          and injects the yielded session
```

**The lifecycle of one HTTP request:**
1. FastAPI calls `get_db()` before your handler
2. `get_db()` creates a session and yields it
3. Your handler receives the session and runs
4. After your handler returns, `get_db()` resumes and commits
5. Session is closed in `finally`

---

## 5. `app/models/base.py` — ORM Foundation

```python
# From models/base.py
import uuid
from datetime import datetime
from sqlalchemy import DateTime, func
from sqlalchemy.orm import Mapped, mapped_column
from app.database import Base  # re-export so models only need to import from base.py

def uuid_pk() -> Mapped[uuid.UUID]:
    """Return a UUID primary-key column with auto-generation."""
    return mapped_column(
        primary_key=True,       # ← this column is the table's primary key
        default=uuid.uuid4,     # ← Python generates a UUID before INSERT
        #        ↑ note: no () — we pass the FUNCTION, not a value
        # uuid.uuid4 generates a random UUID like: 550e8400-e29b-41d4-a716-446655440000
    )
```

### The TimestampMixin

```python
# From models/base.py
class TimestampMixin:
    """Adds created_at and updated_at to any model that inherits this."""

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),    # ← stores timezone-aware UTC timestamps
        server_default=func.now(),  # ← PostgreSQL sets this to NOW() on INSERT
        nullable=False,             # ← column cannot be NULL
    )

    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),  # ← set to NOW() on INSERT
        onupdate=func.now(),        # ← automatically updated to NOW() on UPDATE
        nullable=False,
    )
```

**`server_default` vs `default`:**
- `default=uuid.uuid4` — Python generates the value before sending to DB
- `server_default=func.now()` — the DATABASE generates the value (PostgreSQL `NOW()`)
- Server defaults work even for raw SQL inserts outside of the ORM


---

## 6. `app/models/user.py` — The User Table

```python
# From models/user.py
import enum
import uuid
from sqlalchemy import Boolean, Enum, String
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.models.base import Base, TimestampMixin, uuid_pk

class UserRole(str, enum.Enum):
    #           ↑ str means the enum values are strings (can compare to "user")
    #                  ↑ enum.Enum makes this a proper Python enumeration
    user    = "user"
    premium = "premium"
    admin   = "admin"

# Usage: UserRole.user == "user"  → True (because of str inheritance)
# Usage: user.role.value → "user"  (to get the raw string)
```

### The User model itself

```python
# From models/user.py
class User(Base, TimestampMixin):
    # ↑ inherits Base (SQLAlchemy table) + TimestampMixin (created_at, updated_at)

    __tablename__ = "users"     # ← the actual table name in PostgreSQL

    id: Mapped[uuid.UUID] = uuid_pk()
    #   ↑ Mapped[T] tells SQLAlchemy AND Python type checkers about the type
    #               ↑ uuid_pk() returns a primary key column definition

    email: Mapped[str] = mapped_column(
        String(255),        # ← VARCHAR(255) in PostgreSQL
        unique=True,        # ← no two users can have the same email
        nullable=False,     # ← this column is required
        index=True,         # ← creates an index for fast lookups by email
    )

    password_hash: Mapped[str] = mapped_column(
        String(255),
        nullable=False,
        comment="bcrypt digest — NEVER store plain text password here",
        # ↑ comment appears in the DB schema, helpful for other developers
    )

    role: Mapped[UserRole] = mapped_column(
        Enum(UserRole, name="user_role", create_type=True),
        # ↑ PostgreSQL ENUM type — only allows "user", "premium", "admin"
        # create_type=True means Alembic creates this enum in the DB
        nullable=False,
        default=UserRole.user,  # ← new users start as "user" role
    )

    is_active: Mapped[bool] = mapped_column(
        Boolean,
        nullable=False,
        default=True,   # ← new users are active by default
    )

    privacy_mode: Mapped[bool] = mapped_column(
        Boolean,
        nullable=False,
        default=False,
        server_default="false",   # ← DB-level default for raw SQL inserts
        comment="When True, memory capture is disabled for this user",
    )
```

### Relationships (foreign keys to other tables)

```python
# From models/user.py
    conversations: Mapped[list["Conversation"]] = relationship(
        "Conversation",             # ← the related model class name (as string)
        back_populates="user",      # ← the Conversation model has a "user" attribute
        cascade="all, delete-orphan",
        # ↑ when this User is deleted:
        #   "all" = cascade all operations (save, delete, etc.)
        #   "delete-orphan" = delete Conversations that no longer have a User
    )

    api_keys: Mapped[list["APIKey"]] = relationship(
        "APIKey", back_populates="user", cascade="all, delete-orphan"
    )
    # ↑ user.api_keys returns a list of APIKey objects for this user

    def __repr__(self) -> str:
        # ↑ __repr__ = what Python shows when you print(user) or in debugger
        return f"<User id={self.id!s} email={self.email!r} role={self.role.value!r}>"
        #                    ↑ !s = str()  ↑ !r = repr() (adds quotes around strings)
```

---

## 7. `app/schemas/auth.py` — Request/Response Shapes

Schemas (Pydantic models) define what JSON the API accepts and returns.
They are different from ORM models — schemas are for HTTP, models are for DB.

```python
# From schemas/auth.py
from pydantic import BaseModel, ConfigDict, EmailStr, Field, field_validator

class RegisterRequest(BaseModel):
    # ↑ inheriting BaseModel makes Pydantic validate the data automatically

    model_config = ConfigDict(str_strip_whitespace=True)
    # ↑ automatically strips leading/trailing spaces from ALL string fields

    email: EmailStr = Field(
        # ↑ EmailStr is a special Pydantic type that validates email format
        description="Valid email address used as the account identifier.",
        examples=["user@example.com"],  # ← shown in Swagger UI docs
    )

    password: str = Field(
        min_length=12,    # ← if shorter, Pydantic raises validation error
        max_length=128,   # ← if longer, Pydantic raises validation error
        description="Password — minimum 12 characters.",
    )

    display_name: str = Field(
        default="",       # ← optional field, defaults to empty string
        max_length=255,
    )

    @field_validator("password")    # ← runs AFTER Pydantic's built-in checks
    @classmethod
    def password_min_length(cls, v: str) -> str:
        #                         ↑ v = the value being validated
        if len(v) < 12:
            raise ValueError("password must be at least 12 characters")
        return v    # ← must return the (possibly transformed) value
```

### Response schemas

```python
# From schemas/auth.py
class LoginResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    # ↑ from_attributes=True allows creating this from a SQLAlchemy ORM object:
    # LoginResponse.model_validate(user_orm_object)

    user_id: uuid.UUID = Field(description="UUID of the authenticated user.")
    email: str
    role: str = Field(description="User role: user | premium | admin.")
    access_token: str   # ← JWT string
    refresh_token: str  # ← opaque random token string
    access_token_expires_at: int    # ← Unix timestamp in milliseconds
    refresh_token_expires_at: int
    token_type: str = Field(default="bearer")
    # ↑ "bearer" is the OAuth2 standard — Android app uses "Bearer <token>"
```

**Schema vs Model:**
```
RegisterRequest  ─── validates incoming JSON ───►  Route handler
Route handler    ─── creates ORM object     ───►  PostgreSQL (via model)
PostgreSQL       ─── returns ORM object     ───►  Route handler
Route handler    ─── creates Response schema ──►  JSON response to Android
```


---

## 8. `app/security/jwt_handler.py` — JWT Tokens

### Creating a JWT access token

```python
# From jwt_handler.py
from jose import JWTError, jwt   # python-jose library
import uuid, hashlib, secrets
from datetime import datetime, timedelta, timezone

def create_access_token(
    user_id: uuid.UUID,
    role: str,
    *,                              # ← everything after * must be keyword argument
    expires_delta: timedelta | None = None,
    # ↑ timedelta | None = either a timedelta object OR None
) -> tuple[str, datetime]:
    # ↑ returns TWO values: the token string and the expiry datetime

    settings = _get_settings()
    now = datetime.now(tz=timezone.utc)    # always use UTC, never local time

    if expires_delta is None:
        # default to settings value (15 minutes)
        expires_delta = timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES)

    expire = now + expires_delta    # e.g. now + 15 minutes
    jti = str(uuid.uuid4())         # unique token ID (for revocation)

    payload: dict[str, Any] = {
        "sub": str(user_id),    # "sub" = subject (who the token is for)
        "role": role,           # "user", "premium", or "admin"
        "jti": jti,             # unique ID for this specific token
        "iat": now,             # "issued at" timestamp
        "exp": expire,          # "expires at" timestamp — jose validates this
    }

    token = jwt.encode(
        payload,
        settings.SECRET_KEY,    # ← used to sign (HMAC-SHA256)
        algorithm=settings.JWT_ALGORITHM,  # "HS256"
    )
    # jwt.encode creates: base64(header).base64(payload).HMAC_signature
    return token, expire
```

### Verifying a JWT (when a request comes in)

```python
# From jwt_handler.py
def verify_access_token(token: str) -> TokenPayload:

    settings = _get_settings()

    try:
        raw_payload: dict[str, Any] = jwt.decode(
            token,
            settings.SECRET_KEY,            # must use SAME key as signing
            algorithms=[settings.JWT_ALGORITHM],
            options={"require": ["sub", "role", "jti", "iat", "exp"]},
            # ↑ "require" means raise an error if any of these claims are missing
        )
    except JWTError as exc:
        # JWTError covers: expired token, bad signature, malformed token
        raise InvalidTokenError(f"JWT validation failed: {exc}") from exc
        # ↑ "from exc" preserves the original exception as context

    # Extra safety check — not all jose versions enforce "require"
    required_claims = {"sub", "role", "jti", "iat", "exp"}
    missing = required_claims - set(raw_payload.keys())
    #                           ↑ set subtraction: what's in required but not in payload?
    if missing:
        raise InvalidTokenError(f"JWT is missing required claims: {missing}")

    # Convert numeric Unix timestamps to Python datetime objects
    iat = datetime.fromtimestamp(raw_payload["iat"], tz=timezone.utc)
    exp = datetime.fromtimestamp(raw_payload["exp"], tz=timezone.utc)

    return TokenPayload(
        sub=raw_payload["sub"],
        role=raw_payload["role"],
        jti=raw_payload["jti"],
        iat=iat,
        exp=exp,
    )
```

### Creating a refresh token

```python
# From jwt_handler.py
def create_refresh_token(
    *,
    family_id: uuid.UUID | None = None,
) -> RefreshTokenData:

    settings = _get_settings()
    now = datetime.now(tz=timezone.utc)

    raw_token = secrets.token_urlsafe(32)
    # ↑ generates 32 random bytes as URL-safe base64 string
    # Result looks like: "dGhpcyBpcyBub3QgYSByZWFsIHRva2Vu"
    # This is what gets sent to the Android app

    token_hash = hashlib.sha256(raw_token.encode()).hexdigest()
    # ↑ SHA-256 hash — THIS is what gets stored in the database
    # Even if the database is stolen, attacker can't reverse the hash to get raw_token

    expires_at = now + timedelta(days=settings.REFRESH_TOKEN_EXPIRE_DAYS)
    resolved_family_id = family_id if family_id is not None else uuid.uuid4()
    # ↑ start new family if family_id is None, otherwise continue existing family

    return RefreshTokenData(
        raw_token=raw_token,        # ← return to Android app (ONCE, never store)
        token_hash=token_hash,      # ← store in database
        expires_at=expires_at,
        family_id=resolved_family_id,
    )
```

---

## 9. `app/api/auth/router.py` — Login Endpoints

### The router declaration

```python
# From auth/router.py
router = APIRouter(prefix="/auth", tags=["auth"])
# ↑ all routes in this file get "/auth" prepended
# tags=["auth"] groups these endpoints in the Swagger UI
```

### POST /auth/register

```python
# From auth/router.py
@router.post(
    "/register",                    # ← full path: POST /auth/register
    response_model=RegisterResponse, # ← FastAPI validates + shapes the response
    status_code=status.HTTP_201_CREATED,  # ← 201 = "Created" (not 200)
    summary="Register a new user account",  # ← shown in Swagger UI
)
async def register(
    body: RegisterRequest,          # ← FastAPI parses + validates JSON body
    request: Request,               # ← gives access to IP, headers, etc.
    db: AsyncSession = Depends(get_db),  # ← FastAPI injects database session
) -> RegisterResponse:
```

**What `Depends(get_db)` actually does:**
1. FastAPI calls `get_db()` before calling `register()`
2. `get_db()` creates a database session and `yield`s it
3. FastAPI passes the yielded session as the `db` argument
4. After `register()` returns, `get_db()` commits and closes the session

```python
# From auth/router.py — inside the register function
    user_repo = UserRepository(db)  # ← repository wraps all user DB queries
    audit = AuditService(db)        # ← records security events

    email = body.email.lower().strip()
    # ↑ normalise email: "  Alice@EXAMPLE.COM  " → "alice@example.com"

    # Check for existing account
    existing = await user_repo.get_by_email(email)
    if existing is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            # ↑ 409 = "Conflict" (resource already exists)
            detail="An account with this email address already exists.",
        )

    password_hash = hash_password(body.password)
    # ↑ body.password is the plain text password the user submitted
    # hash_password() runs bcrypt and returns a safe hash like:
    # "$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj/VdyH6rjkG"

    user = await user_repo.create(
        email=email,
        password_hash=password_hash,
        display_name=body.display_name,
    )
    # ↑ inserts a new row into the "users" table

    access_token, access_exp, refresh_token, refresh_exp = await issue_tokens_for_user(
        db, user.id, user.role.value
    )
    # ↑ unpacking: function returns 4 values, stored in 4 variables

    return RegisterResponse(
        user_id=user.id,
        email=user.email,
        access_token=access_token,
        refresh_token=refresh_token,
        access_token_expires_at=int(access_exp.timestamp() * 1000),
        # ↑ .timestamp() = Unix seconds (float), * 1000 = milliseconds, int() = no decimals
        refresh_token_expires_at=int(refresh_exp.timestamp() * 1000),
    )
```


### POST /auth/login — The Full Picture

```python
# From auth/router.py — login endpoint
async def login(
    body: LoginRequest,
    request: Request,
    db: AsyncSession = Depends(get_db),
    redis: Redis = Depends(get_redis),  # ← also injects a Redis connection
) -> LoginResponse:

    lockout = AccountLockoutService(redis)  # ← uses Redis to track failed attempts
    email = body.email.lower().strip()

    # IMPORTANT: always return the SAME error for wrong email vs wrong password
    # This prevents "user enumeration" — attackers can't tell if an email exists
    _generic_401 = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Invalid email or password.",  # ← vague on purpose
        headers={"WWW-Authenticate": "Bearer"},
    )

    user = await user_repo.get_by_email(email)
    if user is None:
        # User doesn't exist — still log the attempt, still return generic 401
        await audit.log_failed_login(ip_address=ip, email=email, reason="user_not_found")
        raise _generic_401   # ← same error as wrong password

    # Check lockout BEFORE verifying password
    # Why? Verifying bcrypt is slow — checking Redis first avoids the slow path
    try:
        await lockout.check_locked(email)
    except AccountLockedError as exc:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            # ↑ 429 = "Too Many Requests"
            detail=str(exc),
            headers={"Retry-After": str(exc.retry_after_seconds)},
            # ↑ tells the client how many seconds to wait before trying again
        ) from exc

    # Verify the password against the stored bcrypt hash
    if not verify_password(body.password, user.password_hash):
        # ← bcrypt comparison: slow by design (takes ~100ms)
        await lockout.record_failed_attempt(email, display_name=user.display_name)
        # ↑ increment the failed attempt counter in Redis
        raise _generic_401

    # Check account is active
    if not user.is_active:
        raise HTTPException(status_code=401, detail="Account is disabled.")

    # Success! Clear the failed attempts counter
    await lockout.clear_on_success(email)

    # Issue JWT + refresh token
    access_token, access_exp, refresh_token, refresh_exp = await issue_tokens_for_user(
        db, user.id, user.role.value
    )

    # Write to audit log (for security monitoring)
    await audit.log_login(user_id=user.id, ip_address=ip, provider="password")

    return LoginResponse(
        user_id=user.id,
        email=user.email,
        role=user.role.value,   # ← .value converts UserRole.user → "user"
        access_token=access_token,
        refresh_token=refresh_token,
        access_token_expires_at=int(access_exp.timestamp() * 1000),
        refresh_token_expires_at=int(refresh_exp.timestamp() * 1000),
    )
```

---

## 10. `app/services/auth_service.py` — Auth Business Logic

### Token refresh with replay detection

```python
# From auth_service.py
async def refresh_tokens(
    db: AsyncSession,
    raw_refresh_token: str,         # ← the raw token string from the Android app
) -> tuple[str, datetime, str, datetime, str, uuid.UUID]:
    # ↑ returns 6 values: new_access, access_exp, new_refresh, refresh_exp, role, user_id

    repo = RefreshTokenRepository(db)
    token_hash = hash_token(raw_refresh_token)
    # ↑ hash the received token to look it up in the DB (we only store hashes)

    record = await repo.get_by_hash(token_hash)
    if record is None:
        raise InvalidTokenError("refresh token not found")

    if record.revoked:
        raise InvalidTokenError("refresh token has been revoked")

    now = datetime.now(tz=timezone.utc)
    if record.expires_at <= now:
        raise InvalidTokenError("refresh token has expired")

    # *** REPLAY DETECTION — critical security feature ***
    if record.used:
        # This token was already used once!
        # Someone submitted the same token twice — this means:
        # 1. Attacker stole a refresh token, OR
        # 2. Client bug is reusing tokens
        # Either way: NUCLEAR OPTION — revoke EVERYTHING in this family
        count = await repo.revoke_family(record.family_id)
        raise TokenFamilyRevokedError(
            f"replay detected — revoked {count} tokens in family {record.family_id}"
        )
        # ↑ The user will need to log in again with email + password

    # Mark this token as "used" (single-use enforcement)
    await repo.mark_used(record.id)

    # Get user for the new JWT
    user = record.user          # ← relationship: record.user loads User from DB
    if not user.is_active:
        raise InvalidTokenError("user account is not active")

    # Issue new JWT
    new_access_token, access_exp = create_access_token(
        user_id=user.id,
        role=user.role.value,
    )

    # Issue new refresh token (SAME family_id, parent = old token)
    new_refresh_data = create_refresh_token(family_id=record.family_id)
    # ↑ passing family_id=record.family_id continues the rotation chain

    await repo.create(
        user_id=user.id,
        token_hash=new_refresh_data.token_hash,  # ← store hash, not raw token
        expires_at=new_refresh_data.expires_at,
        family_id=new_refresh_data.family_id,
        parent_token_id=record.id,               # ← track the chain
    )

    return (
        new_access_token,
        access_exp,
        new_refresh_data.raw_token,  # ← return raw token to client (once!)
        new_refresh_data.expires_at,
        user.role.value,
        user.id,
    )
```

**The token rotation chain visualised:**
```
Login:          raw_A ─hash─► hash_A (stored, family=F1, parent=None)
1st refresh:    raw_A submitted → hash_A found, mark used=True
                new raw_B ─hash─► hash_B (stored, family=F1, parent=hash_A)
2nd refresh:    raw_B submitted → hash_B found, mark used=True
                new raw_C ─hash─► hash_C (stored, family=F1, parent=hash_B)
Replay attack:  raw_A submitted AGAIN → hash_A found but used=True!
                REVOKE entire family F1 (all tokens)
                User must log in again
```


---

## 11. `app/services/llm_clients.py` — AI Providers

### The abstract base class

```python
# From llm_clients.py
from abc import ABC, abstractmethod    # ABC = Abstract Base Class
from decimal import Decimal            # Decimal for precise money calculations

class BaseLLMClient(ABC):
    # ↑ ABC makes it impossible to create an instance of BaseLLMClient directly
    # You MUST create a subclass that implements all @abstractmethod methods

    _rate_limiter: _ProviderRateLimiter
    # ↑ class-level annotation: subclasses must assign this

    @abstractmethod                     # ← if a subclass doesn't implement this,
    async def stream(self, context: PromptContext) -> AsyncIterator[str]:
        ...                             # ← "..." means "not implemented here"
        # subclasses MUST override this

    @abstractmethod
    async def complete(self, context: PromptContext) -> str:
        ...

    @property                           # ← makes max_context_tokens usable as an attribute
    @abstractmethod                     # ← must be overridden (cannot be called on base)
    def max_context_tokens(self) -> int:
        ...

    # Convenience methods — these are implemented on the base class
    # (subclasses get these for free)
    def get_max_context_tokens(self) -> int:
        return self.max_context_tokens  # ← delegates to the property above

    def get_cost_per_token(self) -> dict[str, Decimal]:
        return {
            "input": self.cost_per_input_token,
            "output": self.cost_per_output_token,
        }
```

### OpenAI client — stream implementation

```python
# From llm_clients.py
class OpenAIClient(BaseLLMClient):

    def __init__(self) -> None:
        settings = get_settings()
        if not settings.OPENAI_API_KEY:
            raise ValueError("OPENAI_API_KEY not configured")
        self.client = AsyncOpenAI(api_key=settings.OPENAI_API_KEY)
        # ↑ AsyncOpenAI = async version of the OpenAI SDK client
        self.model = "gpt-4o"
        self._rate_limiter = _ProviderRateLimiter(
            "openai", settings.LLM_RATE_LIMIT_OPENAI  # default: 60 req/min
        )

    async def stream(self, context: PromptContext) -> AsyncIterator[str]:
        await self._rate_limiter.check(context.user_id)
        # ↑ check Redis before making any API call — raises RateLimitError if exceeded

        # Convert our PromptContext to OpenAI's message format
        messages = [{"role": "system", "content": context.system_prompt}]
        for role, content in context.messages:
            # ↑ iterate over list of (role, content) tuples
            messages.append({"role": role, "content": content})

        stream = await self.client.chat.completions.create(
            model=self.model,          # "gpt-4o"
            messages=messages,
            max_tokens=context.max_tokens,      # e.g. 2048
            temperature=context.temperature,    # e.g. 0.7
            stream=True,               # ← enables streaming mode
        )

        async for chunk in stream:
            # ↑ async for: each iteration awaits the next chunk from OpenAI
            if chunk.choices and chunk.choices[0].delta.content:
                # ↑ chunk.choices[0].delta.content is None for the last chunk
                yield chunk.choices[0].delta.content
                # ↑ yield sends this token to whoever is iterating over stream()
```

**What `yield` does in a streaming context:**
```python
# The caller (AIOrchestrator) does this:
async for token in client.stream(context):
    await websocket.send_json({"type": "token", "data": token})
    # Each token ("Hello", " world", "!") is sent immediately to Android
# This creates the "typing effect" the user sees
```

### Ollama client — local AI with no external calls

```python
# From llm_clients.py
class OllamaClient(BaseLLMClient):

    def __init__(self, model: str = "llama3.2:latest") -> None:
        settings = get_settings()
        self.base_url: str = settings.OLLAMA_BASE_URL
        # ↑ e.g. "http://localhost:11434" — strictly local, no internet
        self.model = model
        self.client = httpx.AsyncClient(
            base_url=self.base_url,
            timeout=120.0,   # ← 2 minutes — local models can be slow
        )
        # httpx is an async HTTP client library (like requests but async)

    async def stream(self, context: PromptContext) -> AsyncIterator[str]:
        await self._rate_limiter.check(context.user_id)

        prompt = self._build_prompt(context)
        payload = {
            "model": self.model,
            "prompt": prompt,
            "stream": True,
            "options": {
                "temperature": context.temperature,
                "num_predict": context.max_tokens,
            },
        }

        async with self.client.stream("POST", "/api/generate", json=payload) as response:
            # ↑ streams the HTTP response line by line (Ollama sends newline-delimited JSON)
            response.raise_for_status()   # raises exception if HTTP 4xx/5xx
            async for line in response.aiter_lines():
                # ↑ iterates over response body one line at a time
                if line.strip():    # skip empty lines
                    chunk = json.loads(line)    # parse JSON from each line
                    if "response" in chunk:
                        yield chunk["response"] # ← one token or partial word
```

### The Redis rate limiter

```python
# From llm_clients.py
class _ProviderRateLimiter:

    def __init__(self, provider_name: str, limit_per_minute: int) -> None:
        self._provider = provider_name     # e.g. "openai"
        self._limit = limit_per_minute    # e.g. 60

    async def check(self, user_id: str | None) -> None:
        if self._limit == 0 or user_id is None:
            return  # 0 = unlimited, None = internal call → skip check

        now = time.time()               # current Unix timestamp (float)
        window = int(now // 60)         # current minute number (integer)
        # ↑ at minute 26345678.4, window = int(26345678.4 // 60) = 439094
        # All requests in the same minute share the same window value

        key = f"{_RATE_KEY_PREFIX}{self._provider}:{user_id}:{window}"
        # ↑ e.g. "llm_rate:openai:abc123:439094"

        try:
            redis_client = await self._get_redis()
            count: int = await redis_client.incr(key)
            # ↑ atomically increment the counter (creates key at 1 if doesn't exist)
            if count == 1:
                await redis_client.expire(key, _RATE_KEY_TTL_SECONDS)
                # ↑ set 120 second expiry on first use (auto-cleanup)

            if count > self._limit:
                retry_after = math.ceil(60 - (now % 60))
                # ↑ seconds until next minute starts
                # now % 60 = seconds into current minute
                # 60 - (seconds into minute) = seconds until next minute
                raise RateLimitError(self._provider, retry_after)

        except RateLimitError:
            raise   # ← re-raise our custom error
        except Exception as exc:
            logger.warning("LLM rate-limit Redis check failed (fail-open): %s", exc)
            # ↑ if Redis is down, LOG the failure but let the request through
            # "fail-open" = default to allowing access when security check fails
            # Alternative "fail-closed" would block all requests when Redis is down
```


---

## 12. `app/services/ai_orchestrator.py` — The AI Brain

### Prompt injection detection

```python
# From ai_orchestrator.py
import re

_INJECTION_PATTERNS: list[re.Pattern[str]] = [
    re.compile(pattern, re.IGNORECASE)
    # ↑ re.compile = pre-compile the regex pattern for speed
    # re.IGNORECASE = case-insensitive ("IGNORE" matches "ignore")
    for pattern in [
        r"ignore\s+(all\s+)?previous\s+instructions?",
        # ↑ r"..." = raw string (backslashes are literal, not escape sequences)
        # \s+ = one or more whitespace characters (space, tab, newline)
        # (all\s+)? = optional group: "all " before "previous"
        # instructions? = "instruction" or "instructions" (s is optional)
        r"you\s+are\s+now\s+",
        r"system\s*:\s*",
        r"\[SYSTEM\]",
        r"</?(inst|s|INST)>",   # LLaMA special tokens
    ]
]

def _detect_prompt_injection_static(text: str) -> bool:
    for pattern in _INJECTION_PATTERNS:
        if pattern.search(text):
            # ↑ .search() checks if pattern exists ANYWHERE in text
            # vs .match() which only checks the START of text
            logger.warning(
                "Prompt injection pattern detected: %r matched in: %.100r",
                pattern.pattern,
                text,
                # ↑ %.100r = at most 100 chars of the text (truncates for safety)
            )
            return True
    return False
```

### The main stream_chat method — step by step

```python
# From ai_orchestrator.py
async def stream_chat(
    self,
    conversation_id: str,
    user_message: str,
    provider: LLMProvider,
    user_id: str,
    ws: WebSocket,          # ← the WebSocket connection to stream tokens over
) -> TokenUsage:

    # Step 1 — Block injection attempts
    if await self._detect_prompt_injection(user_message):
        error_payload = {
            "type": "error",
            "message": "Your message was blocked because it appears to contain a "
                       "prompt injection attempt."
        }
        await ws.send_json(error_payload)  # ← tell the Android app what happened
        raise ValueError(f"Prompt injection detected from user {user_id}")

    conv_uuid = uuid.UUID(conversation_id)
    # ↑ converts string "550e8400-..." to uuid.UUID object for the DB

    # Step 2 — Persist user message first
    user_msg = await self._message_repo.create(
        conversation_id=conv_uuid,
        role=MessageRole.user,
        content=user_message,
        provider=provider.value,    # e.g. "openai"
    )

    # Step 3 — Build full prompt context
    context = await self._build_prompt(conversation_id, user_id, user_message)

    # Step 4 — Get the AI provider client
    client = await self._resolve_provider(provider)
    # ↑ returns OpenAIClient, GeminiClient, etc. based on provider enum

    # Step 5 — Stream tokens
    output_tokens = 0
    collected_tokens: list[str] = []
    llm_context = self._to_llm_prompt_context(context, user_id, client=client)

    try:
        async for token in client.stream(llm_context):
            # ↑ each iteration gets ONE token (e.g. "Hello", " world", "!")
            safe_token = await self._apply_safety_filters(token)
            # ↑ check each token for harmful content before sending
            collected_tokens.append(safe_token)
            output_tokens += _estimate_tokens(safe_token)
            await ws.send_json({"type": "token", "data": safe_token})
            # ↑ immediately send each token to Android app (streaming effect)

    except Exception as primary_exc:
        # Primary provider failed — try fallback
        fallback_provider = self._get_fallback_provider(provider)
        if fallback_provider is None:
            raise   # no fallback configured → propagate the error

        # Notify user of the switch
        await ws.send_json({
            "type": "notice",
            "message": f"The '{provider.value}' provider encountered an error. "
                       f"Switching to '{fallback_provider.value}'."
        })

        # Retry with fallback provider
        fallback_client = await self._resolve_provider(fallback_provider)
        collected_tokens.clear()    # discard any partial response
        output_tokens = 0
        async for token in fallback_client.stream(llm_context):
            safe_token = await self._apply_safety_filters(token)
            collected_tokens.append(safe_token)
            output_tokens += _estimate_tokens(safe_token)
            await ws.send_json({"type": "token", "data": safe_token})

    # Step 6 — Send "done" event with usage stats
    assistant_response = "".join(collected_tokens)  # join all tokens into full string
    input_tokens = context.estimated_tokens
    await ws.send_json({
        "type": "done",
        "usage": {
            "input_tokens": input_tokens,
            "output_tokens": output_tokens,
            "provider": provider.value,
        }
    })

    # Step 7 — Persist AI response to database
    assistant_msg = await self._message_repo.create(
        conversation_id=conv_uuid,
        role=MessageRole.assistant,
        content=assistant_response,
        input_tokens=input_tokens,
        output_tokens=output_tokens,
        provider=provider.value,
    )

    # Step 8 — Calculate cost and save TokenUsage
    cost_usd = (
        Decimal(str(input_tokens)) * client.cost_per_input_token
        + Decimal(str(output_tokens)) * client.cost_per_output_token
    )
    # ↑ Decimal instead of float for precise money math
    # float: 0.1 + 0.2 = 0.30000000000000004  (floating point error)
    # Decimal: Decimal("0.1") + Decimal("0.2") = Decimal("0.3")  (exact)

    token_usage = await self._token_usage_repo.create(
        user_id=uuid.UUID(user_id),
        message_id=assistant_msg.id,
        provider=provider.value,
        input_tokens=input_tokens,
        output_tokens=output_tokens,
        cost_usd=cost_usd,
    )

    await self._db.commit()     # ← save all DB changes (messages + token_usage)
    return token_usage
```

### Provider resolution with lazy caching

```python
# From ai_orchestrator.py
async def _resolve_provider(self, provider: LLMProvider) -> BaseLLMClient:
    if provider in self._provider_cache:
        return self._provider_cache[provider]
        # ↑ return already-created client (avoids creating a new one every request)

    client: BaseLLMClient
    match provider:
        # ↑ Python 3.10+ structural pattern matching (like switch/case)
        case LLMProvider.openai:
            client = OpenAIClient()
        case LLMProvider.gemini:
            client = GeminiClient()
        case LLMProvider.claude:
            client = ClaudeClient()
        case LLMProvider.ollama:
            client = OllamaClient()
        case LLMProvider.llama:
            client = LlamaClient()
        case LLMProvider.mistral:
            client = MistralClient()
        case _:                     # ← default case (like "else")
            raise ValueError(f"Unsupported LLM provider: {provider!r}")

    self._provider_cache[provider] = client
    return client
```


---

## 13. `app/api/websocket/router.py` — Real-Time Chat

WebSockets are different from regular HTTP — they keep the connection open for two-way communication.

### The WebSocket endpoint

```python
# From websocket/router.py
@router.websocket("/chat/{conversation_id}")
#        ↑ @router.websocket not @router.post — different decorator for WS
async def websocket_chat(
    websocket: WebSocket,
    conversation_id: str,       # ← from the URL path
    token: str | None = None,   # ← from query param ?token=eyJ...
) -> None:
```

**Why query param for auth?**
WebSocket connections can't use HTTP headers for the initial upgrade request.
So the JWT is passed as `?token=eyJhbGci...` instead of `Authorization: Bearer`.

```python
# From websocket/router.py
    # Step 1 — Must accept() before we can close() with a status code
    await websocket.accept()
    # ↑ completes the WebSocket handshake (HTTP 101 Switching Protocols)

    try:
        payload = await authenticate_websocket(token)
        # ↑ verifies the JWT token — raises InvalidTokenError if bad
    except InvalidTokenError as exc:
        await websocket.send_json({
            "type": "error",
            "message": "Authentication failed: invalid or missing token.",
        })
        await websocket.close(code=WS_CLOSE_AUTH_FAILURE)
        # ↑ 4001 = custom close code meaning "auth failed"
        return  # ← exits the function, connection is closed

    user_id = payload.sub   # ← the user UUID from the JWT claims
```

### The message handling loop

```python
# From websocket/router.py
async def _handle_messages(websocket, conversation_id, user_id, heartbeat):

    try:
        while True:     # ← loop forever until connection closes
            try:
                data: Any = await websocket.receive_json()
                # ↑ awaits the next message from Android app
                # Blocks here until a message arrives
            except WebSocketDisconnect:
                # ↑ Android app closed the connection (or network dropped)
                return  # ← exit the loop gracefully

            if data.get("type") == "pong":
                heartbeat.pong_received()   # client responded to our ping
                continue    # ← skip to next iteration without processing

            user_message: str = data.get("user_message", "").strip()
            if not user_message:
                await websocket.send_json({
                    "type": "error",
                    "message": "Field 'user_message' is required and must be non-empty.",
                })
                continue    # ← don't crash, just skip and wait for next message

            provider_str: str = data.get("provider", LLMProvider.openai.value)
            try:
                provider = LLMProvider(provider_str)
                # ↑ converts "openai" string to LLMProvider.openai enum value
            except ValueError:
                # "openai" → LLMProvider.openai works
                # "banana" → ValueError
                await websocket.send_json({"type": "error", "message": f"Unknown provider"})
                continue

            await _stream_response(
                websocket=websocket,
                conversation_id=conversation_id,
                user_message=user_message,
                provider=provider,
                user_id=user_id,
            )
```

### Mid-stream disconnect buffering

```python
# From websocket/router.py
class _BufferingWebSocketProxy:
    """Wraps WebSocket — if client disconnects, saves tokens to Redis."""

    def __init__(self, websocket, user_id, conversation_id):
        self._ws = websocket
        self._user_id = user_id
        self._conversation_id = conversation_id
        self._disconnected = False  # ← starts False, becomes True on disconnect

    async def send_json(self, data: dict) -> None:
        if self._disconnected:
            # Already in buffering mode — save to Redis instead of sending
            if data.get("type") == "token":
                await buffer_token(self._user_id, self._conversation_id, data.get("data", ""))
            return  # don't try to send to a closed socket

        try:
            await self._ws.send_json(data)      # try to send normally
        except (WebSocketDisconnect, RuntimeError, OSError):
            # Connection dropped mid-stream!
            self._disconnected = True
            # First token of the disconnect — also buffer it
            if data.get("type") == "token":
                await buffer_token(self._user_id, self._conversation_id, data.get("data", ""))
```

**Why buffer to Redis?**
If the user's phone loses WiFi mid-response, the tokens generated during the
disconnect aren't lost. When they reconnect, `flush_token_buffer()` sends all
the buffered tokens immediately.

---

## 14. `app/middleware/rate_limit.py` — Rate Limiting

```python
# From rate_limit.py
class RateLimitMiddleware:

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        #                   ↑ ASGI interface — scope=request info, receive=read body, send=write response
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return  # ← WebSocket connections bypass rate limiting

        request = Request(scope, receive, send)
        auth_header = request.headers.get("authorization")
        user_id = _extract_user_id_from_header(auth_header)
        # ↑ tries to decode the JWT and extract the "sub" claim

        settings = self._get_settings()
        now = time.time()
        window = int(now // 60)     # current minute number

        if user_id:
            # Tier 1 — authenticated: 60 requests/minute
            limit = settings.RATE_LIMIT_REQUESTS_PER_MINUTE
            key = f"rate:{user_id}:{window}"
            # ↑ e.g. "rate:abc123:439094"

            current_count: int = await redis_client.incr(key)
            # ↑ INCR is atomic in Redis (no race condition even with many workers)
            if current_count == 1:
                await redis_client.expire(key, 120)
                # ↑ auto-delete after 2 minutes (cleaning up old keys)

            if current_count > limit:
                retry_after = math.ceil(60 - (now % 60))
                # ↑ how many seconds until the current minute ends
                response = JSONResponse(
                    status_code=429,
                    content={"detail": f"Rate limit exceeded. Max {limit} req/min."},
                    headers={"Retry-After": str(retry_after)},
                )
                await response(scope, receive, send)
                return  # ← stop here, don't pass to route handler
        else:
            # Tier 2 — unauthenticated: 20 requests/minute per IP
            ip_addr = _extract_client_ip(scope)
            limit = settings.RATE_LIMIT_UNAUTH_REQUESTS_PER_MINUTE
            key = f"rate:ip:{ip_addr}:{window}"
            # ... same logic as above but using IP as the identifier

        # Passed rate limit check — forward to route handler
        await self.app(scope, receive, send)
```

**How `_extract_user_id_from_header` works:**

```python
# From rate_limit.py
def _extract_user_id_from_header(authorization: str | None) -> str | None:

    if not authorization:
        return None     # no Authorization header → unauthenticated

    if not authorization.startswith("Bearer "):
        return None     # wrong format

    token = authorization.removeprefix("Bearer ").strip()
    # ↑ "Bearer eyJhbGci..." → "eyJhbGci..."

    parts = token.split(".")
    if len(parts) != 3:
        return None     # JWT must have exactly 3 parts: header.payload.signature

    payload_b64 = parts[1]  # the middle part is the base64-encoded payload
    padding = 4 - len(payload_b64) % 4
    if padding != 4:
        payload_b64 += "=" * padding    # base64 requires padding to multiple of 4

    payload = json.loads(base64.urlsafe_b64decode(payload_b64))
    # ↑ decode base64 → JSON string → Python dict
    return str(payload["sub"]) if payload.get("sub") else None
    # ↑ extract "sub" claim (user UUID) WITHOUT verifying the signature
    # We don't verify here because: this is just for rate limiting
    # Actual auth verification happens in get_current_user dependency
```


---

## 15. `app/api/productivity/router.py` — Todos, Habits, Calendar

This router shows the standard CRUD pattern used throughout the project.
Once you understand this file, you understand every other router.

### Router-level auth dependency

```python
# From productivity/router.py
router = APIRouter(
    prefix="/productivity",
    tags=["productivity"],
    dependencies=[Depends(get_current_user)],
    # ↑ This applies get_current_user to EVERY endpoint in this router
    # No need to add Depends(get_current_user) to each function individually
)
```

### The full CRUD pattern — Todos as example

```python
# From productivity/router.py

# ── CREATE ──────────────────────────────────────────────────────────────────
@router.post(
    "/todos",
    response_model=TodoResponse,
    status_code=status.HTTP_201_CREATED,    # ← 201 "Created" (not 200)
    summary="Create a to-do item",
)
async def create_todo(
    body: TodoCreate,                       # ← validates request JSON
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> TodoResponse:
    user_id = _current_user_id(current_user)
    # ↑ converts current_user.sub (string) → uuid.UUID

    service = ProductivityService(db)
    # ↑ create service INSIDE the handler (not at module level)
    # This ensures the service always gets the right request-scoped db session

    item = await service.create_todo(user_id, body)
    # ↑ service call — business logic is not in the router

    return TodoResponse.model_validate(item)
    # ↑ convert SQLAlchemy ORM object → Pydantic schema for JSON response
    # model_validate() works because TodoResponse has from_attributes=True

# ── READ (list with pagination) ──────────────────────────────────────────────
@router.get(
    "/todos",
    response_model=TodoListResponse,
    summary="List to-do items (paginated)",
)
async def list_todos(
    page: int = Query(default=1, ge=1, description="1-indexed page number."),
    #       ↑ Query() = value comes from URL query params: /todos?page=2
    page_size: int = Query(default=20, ge=1, le=100),
    is_completed: bool | None = Query(default=None),
    # ↑ bool | None = optional filter — /todos?is_completed=true
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> TodoListResponse:
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    items, total = await service.list_todos(user_id, page, page_size, is_completed)
    # ↑ tuple unpacking: returns (list_of_items, total_count)
    return TodoListResponse(
        items=[TodoResponse.model_validate(item) for item in items],
        # ↑ list comprehension: convert each ORM item to a schema
        total=total,
        page=page,
        page_size=page_size,
    )

# ── UPDATE (partial update) ──────────────────────────────────────────────────
@router.patch(
    "/todos/{todo_id}",
    # ↑ PATCH = partial update (only fields provided are changed)
    # vs PUT = full replacement (all fields required)
    response_model=TodoResponse,
    summary="Update a to-do item",
)
async def update_todo(
    todo_id: uuid.UUID,     # ← FastAPI extracts from URL path /todos/abc123
    body: TodoUpdate,       # ← all fields optional (it's a partial update)
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> TodoResponse:
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    item = await service.update_todo(todo_id, user_id, body)
    # ↑ note: user_id is passed to prevent user A from updating user B's todos
    return TodoResponse.model_validate(item)

# ── DELETE ────────────────────────────────────────────────────────────────────
@router.delete(
    "/todos/{todo_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    # ↑ 204 = "No Content" — successful but nothing to return
    response_model=None,
    # ↑ None = no response body (matches 204)
    summary="Delete a to-do item",
)
async def delete_todo(
    todo_id: uuid.UUID,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> None:                  # ← return type None confirms no response body
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    await service.delete_todo(todo_id, user_id)
    # ← no return statement needed for 204 responses
```

### AI-powered endpoint — generate todos from natural language

```python
# From productivity/router.py
@router.post("/todos/generate", response_model=TodoGenerateResponse)
async def generate_todos(
    body: TodoGenerateRequest,
    provider: str = Query(default="openai"),
    # ↑ which AI to use: /todos/generate?provider=gemini
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> TodoGenerateResponse:
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    llm_provider = LLMProvider(provider)
    # ↑ converts "openai" string → LLMProvider.openai enum

    items = await service.generate_todos_from_prompt(user_id, body.prompt, llm_provider)
    # ↑ sends prompt to AI, AI returns a list of todo items as JSON
    # Example prompt: "Plan a weekend camping trip"
    # AI returns: ["Buy tent", "Pack sleeping bags", "Get food supplies", ...]

    return TodoGenerateResponse(
        todos=[TodoResponse.model_validate(item) for item in items],
        prompt=body.prompt,
    )
```

---

## 16. `app/services/rag_service.py` — Document AI

### Chunking algorithm — the heart of RAG

```python
# From rag_service.py
def chunk_text(
    self,
    text: str,
    chunk_size: int | None = None,
    overlap: int | None = None,
) -> list[ChunkResult]:

    import tiktoken
    # ↑ tiktoken = OpenAI's tokenizer library
    # converts text to tokens (integers) — the unit AI models count

    if chunk_size is None:
        chunk_size = self._settings.RAG_CHUNK_SIZE    # default: 512
    if overlap is None:
        overlap = self._settings.RAG_CHUNK_OVERLAP    # default: 64

    # Enforce maximum overlap = 50% of chunk size
    max_overlap = chunk_size // 2
    overlap = min(overlap, max_overlap)
    # ↑ min() returns the smaller of the two values
    # e.g. overlap=300, max_overlap=256 → overlap becomes 256

    if not text or not text.strip():
        return []   # empty input → empty output

    enc = tiktoken.encoding_for_model("gpt-3.5-turbo")
    tokens = enc.encode(text)
    # ↑ enc.encode() converts text to a list of token IDs (integers)
    # "Hello world!" → [15496, 995, 0]  (approximate)

    stride = chunk_size - overlap   # how far to advance each step
    # e.g. chunk_size=512, overlap=64 → stride=448
    # meaning each new chunk starts 448 tokens after the previous one

    chunks: list[ChunkResult] = []
    start = 0

    while start < len(tokens):
        end = min(start + chunk_size, len(tokens))
        # ↑ don't go past the end of the token list
        chunk_tokens = tokens[start:end]    # ← Python slice: tokens[start] to tokens[end-1]
        chunk_text_str = enc.decode(chunk_tokens)
        # ↑ converts token IDs back to text

        chunks.append(ChunkResult(text=chunk_text_str, page_number=1))

        if end == len(tokens):
            break   # ← reached the end of the document
        start += stride     # ← advance by stride (not chunk_size!)

    return chunks
```

**Visual example of chunking with overlap:**
```
Full text tokens: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]
chunk_size=5, overlap=2, stride=3

Chunk 1: tokens[0:5]  = [1, 2, 3, 4, 5]       start=0
Chunk 2: tokens[3:8]  = [4, 5, 6, 7, 8]       start=3 (stride=3)
Chunk 3: tokens[6:11] = [7, 8, 9, 10, 11]     start=6
Chunk 4: tokens[9:12] = [10, 11, 12]          start=9

Notice: tokens 4,5 appear in chunks 1 AND 2 (the overlap)
This ensures no sentence gets cut exactly at a boundary
```

### Embedding and storing in ChromaDB

```python
# From rag_service.py
async def embed_and_store(
    self,
    chunks: list[ChunkResult],
    document_id: str,
    user_id: str,
    db: "AsyncSession",
) -> None:

    texts = [c.text for c in chunks]
    # ↑ list comprehension: [chunks[0].text, chunks[1].text, ...]

    def _encode() -> list[list[float]]:
        # ↑ this is a SYNCHRONOUS function (not async)
        model = self._get_embedding_model()
        # model = SentenceTransformer("all-MiniLM-L6-v2") — 384-dim embeddings
        embeddings = model.encode(texts, show_progress_bar=False)
        return [emb.tolist() for emb in embeddings]
        # ↑ convert numpy arrays to Python lists for JSON serialization

    embeddings = await asyncio.to_thread(_encode)
    # ↑ run _encode() in a thread pool (it's CPU-bound and blocking)
    # asyncio.to_thread prevents the event loop from freezing during encoding

    collection_name = f"documents_{user_id}"
    # ↑ e.g. "documents_550e8400-e29b-41d4-a716-446655440000"
    # Each user has their OWN ChromaDB collection — complete isolation

    def _store_chroma() -> list[str]:
        import chromadb
        client = chromadb.HttpClient(
            host=self._settings.CHROMA_HOST,    # "localhost"
            port=self._settings.CHROMA_PORT,    # 8001
        )
        collection = client.get_or_create_collection(collection_name)
        # ↑ get if exists, create if not — idempotent
        ids = [f"{document_id}_{i}" for i in range(len(chunks))]
        # ↑ e.g. ["abc123_0", "abc123_1", "abc123_2"]
        collection.add(
            ids=ids,
            embeddings=embeddings,  # ← the actual vectors (lists of floats)
            documents=texts,        # ← original text (stored for retrieval)
            metadatas=[{            # ← metadata for filtering
                "document_id": document_id,
                "chunk_index": i,
                "page_number": chunks[i].page_number,
            } for i in range(len(chunks))],
        )
        return ids

    await asyncio.to_thread(_store_chroma)
    # ↑ also runs in thread (ChromaDB client is blocking)
```


---

## 17. `app/services/safety_service.py` — Security Filters

### InjectionDetector — how audit logging works

```python
# From safety_service.py
class InjectionDetector:

    async def check_input(
        self,
        text: str,
        user_id: str,
        db: AsyncSession,
    ) -> None:

        matched_pattern: re.Pattern[str] | None = None
        for pattern in _INJECTION_PATTERNS:
            if pattern.search(text):
                matched_pattern = pattern
                break   # ← stop at first match (one is enough to block)

        if matched_pattern is None:
            return  # ← clean input, no action needed (normal path)

        logger.warning(
            "Prompt injection detected for user %s. Pattern: %r",
            user_id,
            matched_pattern.pattern,
        )

        # Sanitise BEFORE logging — never log the raw attack payload
        sanitised_text = text
        for pattern in _INJECTION_PATTERNS:
            sanitised_text = pattern.sub("[redacted]", sanitised_text)
            # ↑ .sub() = substitute all matches with "[redacted]"

        # Hash the sanitised text for the audit log
        input_hash = hashlib.sha256(sanitised_text.encode("utf-8")).hexdigest()
        # ↑ SHA-256 produces a fixed 64-char hex string
        # "hello" → "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        # This identifies the attack without storing the attack content

        # Parse user_id string to UUID
        user_uuid: uuid.UUID | None = None
        try:
            user_uuid = uuid.UUID(user_id)
        except (ValueError, AttributeError):
            pass    # ← if UUID parsing fails, still write the audit log

        # Write audit log entry
        audit_entry = AuditLog(
            user_id=user_uuid,
            event_type="prompt_injection",  # ← categorises this event
            ip_address="",      # ← not available at service layer (API layer adds it)
            user_agent="",
            metadata_={
                "input_hash": input_hash,           # ← fingerprint of attack
                "matched_pattern": matched_pattern.pattern,  # ← which rule matched
            },
        )
        db.add(audit_entry)     # ← SQLAlchemy adds to current transaction
        await db.flush()        # ← write to DB but don't commit yet
        # ↑ flush() vs commit():
        #   flush() = sends SQL to DB, but wrapped in current transaction (can rollback)
        #   commit() = makes changes permanent (cannot rollback)

        raise PromptInjectionError(
            f"Prompt injection pattern detected for user {user_id}. Request blocked."
        )
        # ↑ raising here means the caller (route handler) handles the HTTP response
```

### SafetyService — output filtering

```python
# From safety_service.py
_HARMFUL_OUTPUT_PATTERNS: list[re.Pattern[str]] = [
    re.compile(r"<script\b[^>]*>.*?</script>", re.IGNORECASE | re.DOTALL),
    # ↑ matches: <script>alert('xss')</script>
    # \b = word boundary (so "scripting" doesn't match)
    # [^>]* = any characters except ">" (the tag attributes)
    # .*? = any characters (non-greedy, so stops at first </script>)
    # re.DOTALL = "." matches newlines too (for multi-line scripts)
    re.compile(r"javascript\s*:", re.IGNORECASE),
    # ↑ matches: javascript: alert('xss')  (used in onclick, href, etc.)
]

class SafetyService:

    def filter_response(self, text: str) -> str:
        sanitised = text
        for pattern in _HARMFUL_OUTPUT_PATTERNS:
            sanitised = pattern.sub("[content removed]", sanitised)
            # ↑ replace harmful content with safe placeholder

        # Double-check: scan again after all substitutions
        for pattern in _HARMFUL_OUTPUT_PATTERNS:
            if pattern.search(sanitised):
                # Still matches after substitution — something went wrong
                logger.error(
                    "Safety filter FAILED to redact harmful content. "
                    "Pattern %r still matches.",
                    pattern.pattern,
                )
                raise SafetyFilterError(
                    "Safety filter failed to redact; blocking entire response."
                )
                # ↑ This causes AIOrchestrator to block the ENTIRE response
                # Better to show nothing than to show harmful content

        return sanitised    # ← clean text returned to user
```

---

## Python Patterns Quick Reference

Throughout this project you'll see these patterns repeatedly:

```python
# 1. Type Union — value can be one of multiple types
value: str | None     # string OR None (Python 3.10+)
value: str | int      # string OR integer

# 2. F-strings — string interpolation
name = "Alice"
f"Hello, {name}!"             # → "Hello, Alice!"
f"Count: {1 + 2}"             # → "Count: 3"
f"Value: {name!r}"            # → "Value: 'Alice'"  (!r = repr)
f"Short: {long_text:.50}"     # → first 50 chars
f"Trunc: {long_text:.100r}"   # → at most 100 chars with repr

# 3. Dict/list comprehensions — build collections in one line
squares = [x**2 for x in range(5)]     # [0, 1, 4, 9, 16]
evens   = [x for x in range(10) if x % 2 == 0]   # [0, 2, 4, 6, 8]
emails  = {user.id: user.email for user in users}  # dict

# 4. Walrus operator :=  (assign AND use in one expression)
# Not used much here, but you may see it in tests

# 5. Unpacking tuples
a, b, c = (1, 2, 3)       # a=1, b=2, c=3
token, expiry = create_access_token(...)  # function returns a tuple

# 6. Default values with "or"
value = settings.CELERY_BROKER_URL or settings.REDIS_URL
# if CELERY_BROKER_URL is "" (falsy), use REDIS_URL

# 7. Context managers with "async with"
async with AsyncSessionLocal() as session:
    # session is available here
    pass
# session automatically closed here

# 8. Exception handling chain
try:
    result = await some_operation()
except SpecificError as exc:
    raise NewError("message") from exc   # "from exc" = keep original as context
except Exception:                         # catch-all (use sparingly)
    logger.exception("Unexpected error")  # logs full traceback
    raise                                 # re-raise without wrapping

# 9. @property — use method like an attribute
class MyClass:
    @property
    def full_name(self) -> str:
        return f"{self.first} {self.last}"

obj = MyClass()
print(obj.full_name)    # called like attribute, not obj.full_name()

# 10. @classmethod — method belongs to class, not instance
class MyModel(BaseModel):
    @field_validator("email")
    @classmethod
    def validate_email(cls, v: str) -> str:
        #               ↑ cls = the class itself (not an instance)
        return v.lower()
```

---

## Common Errors You Will Encounter

```python
# 1. "greenlet_spawn has not been called" or "no current event loop"
# Cause: calling async code outside an async context
# Fix: add "await" before the call, or use asyncio.run()

# 2. "DetachedInstanceError"
# Cause: accessing a relationship (user.conversations) after session closed
# Fix: load the relationship BEFORE the session closes, or use expire_on_commit=False

# 3. "MissingGreenlet: greenlet_spawn has not been called"
# Cause: SQLAlchemy lazy-loading in async context
# Fix: use selectinload() or joinedload() in your query

# 4. "422 Unprocessable Entity" from FastAPI
# Cause: request body fails Pydantic validation
# Fix: check the response body — it lists exactly which fields failed and why

# 5. "AttributeError: 'coroutine' object has no attribute 'x'"
# Cause: forgot "await" on an async function
# Fix: result = await some_async_function()  (add await)

# 6. "RuntimeError: Task attached to a different loop"
# Cause: mixing asyncio event loops
# Fix: always use the same loop; don't use asyncio.run() inside an async function
```

---

## Reading a File for the First Time — Checklist

When you open any Python file in this project, ask:

1. **What does the docstring at the top say?** (the `"""..."""` below the imports)
2. **What does this file import?** (what does it depend on?)
3. **What classes/functions does this file define?** (what does it provide?)
4. **Is there a class?** If so: what does `__init__` set up?
5. **Are functions `async def` or `def`?** Async = touches DB/network
6. **What do the type hints tell you?** `-> str` means it returns a string
7. **Where is this file used?** Search the project for `from app.services.X import Y`




Perfect! All three comprehensive documents have been created successfully:

✅ Documents Created — Complete Backend Learning Package
1. BACKEND_LEARNING_GUIDE.md (33.9 KB)
Purpose: Complete conceptual guide for Python beginners

Contents:

Part 1-5: What is a backend, architecture overview, Python basics, folder structure, entry point
Part 6-10: Configuration, database models, authentication system, LLM clients, AI orchestrator
Part 11-14: RAG pipeline, memory service, safety service, middleware
Part 15-18: API routes, background tasks, observability, key libraries
Part 19-20: Security architecture, complete data flow
Part 21: Summary and next steps
Best for: Understanding HOW the system works conceptually

2. BACKEND_7DAY_PLAN.md (23.3 KB)
Purpose: Structured 7-day learning path with hands-on exercises

Day-by-day breakdown:

Day 1: Python fundamentals + project orientation (3-4 hrs)
Day 2: Database models, SQLAlchemy, Alembic migrations (3.5 hrs)
Day 3: Authentication system (JWT, passwords, security) (4 hrs)
Day 4: LLM clients + AI Orchestrator (4 hrs)
Day 5: RAG pipeline + Memory service (4.5 hrs)
Day 6: Middleware, API routes, feature completion (4.5 hrs)
Day 7: Testing, observability, final smoke test (4.5 hrs)
Each day has:

Morning (reading) and afternoon (hands-on) sections
Specific exercises to run
Checklist of what "done" looks like
Key concepts to understand
Best for: Learning by DOING with a clear schedule

3. BACKEND_CODE_EXPLAINED.md (79.6 KB) ⭐
Purpose: Line-by-line code explanations from actual project files

17 Major Sections:

How imports work
main.py
 — startup validation, middleware, health checks
settings.py
 — pydantic-settings, validators
__init__.py
 — SQLAlchemy engine, session factory
base.py
 — TimestampMixin, uuid_pk()
user.py
 — User table, relationships
auth.py
 — Pydantic request/response validation
jwt_handler.py
 — JWT creation and verification
router.py
 — /auth/register and /auth/login
auth_service.py
 — Token refresh with replay detection
llm_clients.py
 — All 6 AI providers, rate limiting
ai_orchestrator.py
 — stream_chat() full flow
router.py
 — Real-time streaming, buffering
rate_limit.py
 — Two-tier rate limits
router.py
 — Full CRUD pattern
rag_service.py
 — Chunking algorithm, embeddings
safety_service.py
 — Injection detection, audit logging
Plus:

Python patterns quick reference
Common errors and fixes
Reading checklist for any new file
Best for: Understanding EXACTLY what each line of code does

How to Use These Documents
Complete beginner to Python? → Start with BACKEND_LEARNING_GUIDE.md (read Parts 1-5 first) → Then follow BACKEND_7DAY_PLAN.md day by day → Refer to BACKEND_CODE_EXPLAINED.md when you encounter specific code

Familiar with Python but new to this project? → Skim BACKEND_LEARNING_GUIDE.md Parts 1-5 → Jump straight to days 4-7 of BACKEND_7DAY_PLAN.md → Use BACKEND_CODE_EXPLAINED.md as a reference manual

Want to understand a specific feature? → Use BACKEND_CODE_EXPLAINED.md table of contents to jump to that section

All three documents work together to take you from zero Python knowledge to understanding every line of this 15,000+ line backend codebase in just 7 days.
