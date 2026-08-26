"""Android AI Assistant — FastAPI application entry point.

Startup order:
1. Run startup_validation() — checks all required env vars; exits with code 1 if any are missing.
2. Build Settings singleton (reads .env / environment variables).
3. Create FastAPI app with metadata and docs configuration.
4. Register CORS middleware.
5. Mount all API routers.
6. Register health / readiness probe endpoints.
7. Expose Prometheus metrics at /metrics.

Run locally::

    uvicorn app.main:app --reload --host 0.0.0.0 --port 8000

Requirements: 20.5, 20.6, 26.1, 26.3, 26.4, 26.5, 26.6, 27.1, 27.2, 27.3, 27.4
"""

from __future__ import annotations

import logging
import os
import sys
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI

# ---------------------------------------------------------------------------
# Structured JSON logging — must be configured before ANY other import that
# might call logging.basicConfig() (e.g. uvicorn, sqlalchemy).
# configure_logging() reads LOG_LEVEL from the environment; the .env file
# is loaded on the next block so the env var must come from the shell / Cloud
# Run env vars (which is correct — LOG_LEVEL is a non-secret plain var).
# ---------------------------------------------------------------------------
from app.observability.logging_setup import configure_logging  # noqa: E402

configure_logging()

# ---------------------------------------------------------------------------
# Load .env early — before any os.environ reads or pydantic-settings init.
# Using an absolute path anchored to this file means uvicorn can be launched
# from any working directory and still pick up backend/.env correctly.
# ---------------------------------------------------------------------------
_ENV_FILE = Path(__file__).resolve().parents[1] / ".env"  # backend/.env
if _ENV_FILE.exists():
    from dotenv import load_dotenv

    load_dotenv(
        dotenv_path=_ENV_FILE, override=False
    )  # env vars already set take priority

from fastapi.middleware.cors import CORSMiddleware  # noqa: E402
from fastapi.responses import JSONResponse  # noqa: E402
from prometheus_fastapi_instrumentator import Instrumentator  # noqa: E402
from sqlalchemy import text  # noqa: E402

from app.api.admin.router import router as admin_router  # noqa: E402
from app.api.analytics.router import router as analytics_router  # noqa: E402

# ---------------------------------------------------------------------------
# API sub-router imports (stubs — full implementation in subsequent tasks)
# ---------------------------------------------------------------------------
from app.api.auth.router import router as auth_router  # noqa: E402
from app.api.chat.router import router as chat_router  # noqa: E402
from app.api.code.router import router as code_router  # noqa: E402
from app.api.conversations.router import router as conversations_router  # noqa: E402
from app.api.data.router import router as data_router  # noqa: E402
from app.api.generation.router import (  # noqa: E402
    covers_router,
    emails_router,
    resumes_router,
)
from app.api.images.router import router as images_router  # noqa: E402
from app.api.mcp.router import router as mcp_router  # noqa: E402
from app.api.memory.router import router as memory_router  # noqa: E402
from app.api.notifications.router import router as notifications_router  # noqa: E402
from app.api.personas.router import router as personas_router  # noqa: E402
from app.api.productivity.router import router as productivity_router  # noqa: E402
from app.api.prompts.router import router as prompts_router  # noqa: E402
from app.api.rag.router import jobs_router as rag_jobs_router  # noqa: E402
from app.api.rag.router import router as rag_router  # noqa: E402
from app.api.search.router import router as search_router  # noqa: E402
from app.api.suggestions.router import router as suggestions_router  # noqa: E402
from app.api.transcription.router import router as transcription_router  # noqa: E402
from app.api.translation.router import router as translation_router  # noqa: E402
from app.api.usage.router import router as usage_router  # noqa: E402
from app.api.users.router import router as users_router  # noqa: E402
from app.api.websocket.router import router as websocket_router  # noqa: E402
from app.config.settings import get_settings  # noqa: E402
from app.middleware.data_residency import DataResidencyMiddleware  # noqa: E402
from app.middleware.logging_middleware import RequestLoggingMiddleware  # noqa: E402
from app.middleware.rate_limit import RateLimitMiddleware  # noqa: E402
from app.middleware.request_size import RequestBodySizeLimitMiddleware  # noqa: E402

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Required environment variables (Requirements: 26.1, 26.3, 26.5)
# ---------------------------------------------------------------------------
# Each entry is (env_var_name, description).
# All of these MUST be present and non-empty at startup.
# AES_ENCRYPTION_KEY is called out specifically by Requirement 26.5.

REQUIRED_ENV_VARS: list[tuple[str, str]] = [
    ("SECRET_KEY", "JWT secret key for signing tokens"),
    ("DATABASE_URL", "PostgreSQL async connection URL"),
    ("REDIS_URL", "Redis connection URL"),
    (
        "AES_ENCRYPTION_KEY",
        "Base64-encoded AES-256 key for encrypting stored secrets (Req 26.5)",
    ),
]


def startup_validation() -> None:
    """Validate that all required environment variables are present and non-empty.

    Iterates over REQUIRED_ENV_VARS, logs a structured ERROR for every missing
    variable, then calls sys.exit(1) before the server binds to any port.

    AES_ENCRYPTION_KEY is specifically checked per Requirement 26.5.

    This function is called from the lifespan context manager so it runs
    before any requests are accepted.

    Requirements: 26.1, 26.3, 26.5
    """
    missing: list[str] = []

    for var_name, description in REQUIRED_ENV_VARS:
        value = os.environ.get(var_name, "").strip()
        if not value:
            logger.error(
                "STARTUP_VALIDATION_FAILED: required environment variable %r is missing or empty. "
                "Description: %s",
                var_name,
                description,
            )
            missing.append(var_name)

    if missing:
        logger.critical(
            "STARTUP_VALIDATION_FAILED: service cannot start — %d required environment "
            "variable(s) are missing: %s. Exiting with code 1.",
            len(missing),
            ", ".join(missing),
        )
        sys.exit(1)

    logger.info(
        "STARTUP_VALIDATION_PASSED: all %d required environment variables are present.",
        len(REQUIRED_ENV_VARS),
    )


def get_missing_env_vars() -> list[str]:
    """Return a list of required env var names that are currently absent or empty.

    Used at runtime by the /ready endpoint (Requirement 26.4) to surface
    missing configuration in the readiness response body.

    Returns:
        List of missing env var names; empty list means all vars are present.

    Requirements: 26.3, 26.4
    """
    return [
        var_name
        for var_name, _ in REQUIRED_ENV_VARS
        if not os.environ.get(var_name, "").strip()
    ]


# ---------------------------------------------------------------------------
# Lifespan handler (replaces @app.on_event("startup"))
# ---------------------------------------------------------------------------


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Application lifespan: run startup_validation then yield.

    Requirements: 26.1, 26.3, 26.5
    """
    # Validate required env vars before binding to any port.
    startup_validation()

    # Connect Celery signal handlers for Prometheus metrics.
    from app.workers.celery_app import celery_app
    from app.workers.metrics import setup_celery_metrics

    setup_celery_metrics(celery_app)

    # Initialise OpenTelemetry distributed tracing.
    # This patches FastAPI, SQLAlchemy, httpx, and Redis automatically.
    # Controlled by OTEL_ENABLED env var (default: true).
    from app.observability.tracing import setup_tracing

    setup_tracing()

    # Warm up the SentenceTransformer embedding model so the first real
    # request doesn't pay the 30-40 s cold-start cost of loading the model
    # from disk and running a JIT compilation pass.
    try:
        import asyncio as _asyncio

        from app.services.rag_service import rag_service as _rag_service

        def _warmup() -> None:
            model = _rag_service._get_embedding_model()
            # Encode a short dummy sentence to trigger any lazy JIT compilation.
            model.encode(["warmup"], show_progress_bar=False)

        await _asyncio.to_thread(_warmup)
        logger.info("STARTUP: embedding model warmed up successfully.")
    except Exception as _exc:
        # Non-fatal: the model will still load on the first real request.
        logger.warning("STARTUP: embedding model warmup failed (non-fatal): %s", _exc)

    # Check ChromaDB connectivity and log a clear warning if unreachable.
    # This surfaces misconfiguration (wrong host/port) immediately at startup
    # rather than silently returning empty results on every query.
    try:
        import asyncio as _asyncio

        from app.config.settings import get_settings as _get_settings

        def _check_chroma() -> None:
            import chromadb

            s = _get_settings()
            client = chromadb.HttpClient(host=s.CHROMA_HOST, port=s.CHROMA_PORT)
            client.heartbeat()

        await _asyncio.to_thread(_check_chroma)
        logger.info(
            "STARTUP: ChromaDB reachable at %s:%s.",
            get_settings().CHROMA_HOST,
            get_settings().CHROMA_PORT,
        )
    except Exception as _exc:
        logger.warning(
            "STARTUP: ChromaDB NOT reachable at %s:%s — RAG queries will return "
            "empty results until ChromaDB is available. Error: %s",
            get_settings().CHROMA_HOST,
            get_settings().CHROMA_PORT,
            _exc,
        )

    yield
    # Shutdown cleanup (if needed in future) goes here.


settings = get_settings()

# ---------------------------------------------------------------------------
# Application factory
# ---------------------------------------------------------------------------

app = FastAPI(
    title="Android AI Assistant API",
    version="1.0.0",
    description=(
        "Enterprise-grade AI assistant backend providing REST and WebSocket "
        "endpoints for multi-model LLM orchestration, RAG document querying, "
        "memory management, MCP tool integration, and the full productivity suite."
    ),
    docs_url="/docs",
    redoc_url="/redoc",
    openapi_url="/openapi.json",
    # Disable automatic /docs in production to avoid leaking API shape
    # (override per-environment when needed)
    lifespan=lifespan,
)

# ---------------------------------------------------------------------------
# Middleware
# ---------------------------------------------------------------------------

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.CORS_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Rate limiting — must be added after CORS so the CORS headers are still
# present on 429 responses.
app.add_middleware(RateLimitMiddleware)

# Data residency enforcement — applied after rate limiting so blocked
# requests (429) are returned before the residency check runs.
app.add_middleware(DataResidencyMiddleware)

# Request body size limiting — catches oversized payloads before they reach
# schema validation or route handlers. Must be added after rate limiting.
app.add_middleware(RequestBodySizeLimitMiddleware)

# Request logging — registered before RateLimitMiddleware so every request,
# including rate-limited ones, gets a structured log entry and correlation ID.
app.add_middleware(RequestLoggingMiddleware)

# ---------------------------------------------------------------------------
# Routers
# ---------------------------------------------------------------------------

app.include_router(auth_router)
app.include_router(chat_router)
app.include_router(code_router)
app.include_router(conversations_router)
app.include_router(rag_router)
app.include_router(rag_jobs_router)
app.include_router(memory_router)
app.include_router(mcp_router)
app.include_router(admin_router)
app.include_router(analytics_router)
app.include_router(notifications_router)
app.include_router(websocket_router)
app.include_router(productivity_router)
app.include_router(prompts_router)
app.include_router(users_router)
app.include_router(images_router)
app.include_router(transcription_router)
app.include_router(translation_router)
app.include_router(resumes_router)
app.include_router(covers_router)
app.include_router(emails_router)
app.include_router(data_router)
app.include_router(search_router)
app.include_router(usage_router)
app.include_router(personas_router)
app.include_router(suggestions_router)

# ---------------------------------------------------------------------------
# Prometheus instrumentation (Requirements: 27.1–27.4)
# ---------------------------------------------------------------------------

Instrumentator().instrument(
    app,
    # Req 18.2: histogram buckets at 50, 100, 200, 500, 1000, 2000, 5000 ms
    latency_highr_buckets=(0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0),
).expose(app)


# ---------------------------------------------------------------------------
# Health / Readiness probes
# ---------------------------------------------------------------------------


@app.get("/health", tags=["ops"], summary="Liveness probe")
async def health() -> dict[str, str]:
    """Return 200 OK when the application process is alive.

    Used by Kubernetes / ECS liveness probes.  No external dependencies are
    checked here — if the process can respond, it is considered live.
    """
    return {"status": "ok"}


async def _check_db() -> None:
    """Execute a lightweight query to verify database connectivity.

    Raises any exception if the database is unreachable.
    """
    from app.database import engine

    async with engine.connect() as conn:
        await conn.execute(text("SELECT 1"))


async def _check_redis() -> None:
    """Ping Redis to verify connectivity.

    Raises any exception if Redis is unreachable.
    """
    from app.database.redis import get_redis_client

    redis_client = get_redis_client()
    await redis_client.ping()


@app.get("/ready", tags=["ops"], summary="Readiness probe")
async def ready() -> JSONResponse:
    """Return 200 when DB, Redis, and all required env vars are present; 503 otherwise.

    Checks:
    - Required environment variables: calls get_missing_env_vars() to surface
      any missing required variables by name (Requirement 26.4).
    - PostgreSQL: executes ``SELECT 1`` via the async SQLAlchemy engine.
    - Redis: calls ``ping()`` on the async Redis client.

    Response body always includes per-dependency status and the list of any
    missing env vars so orchestrators can pinpoint which dependency is failing.

    Requirements: 20.5, 26.3, 26.4
    """
    db_status = "ok"
    redis_status = "ok"
    missing_vars = get_missing_env_vars()

    try:
        await _check_db()
    except Exception:
        db_status = "unreachable"

    try:
        await _check_redis()
    except Exception:
        redis_status = "unreachable"

    dependencies = {"database": db_status, "redis": redis_status}
    all_ok = db_status == "ok" and redis_status == "ok" and not missing_vars

    if all_ok:
        return JSONResponse(
            status_code=200,
            content={"status": "ready", "dependencies": dependencies},
        )

    content: dict = {"status": "unavailable", "dependencies": dependencies}
    if missing_vars:
        content["missing_env_vars"] = missing_vars

    return JSONResponse(status_code=503, content=content)
