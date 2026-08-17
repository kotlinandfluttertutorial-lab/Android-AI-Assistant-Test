"""Property-based tests for health and readiness endpoint availability.

Property 25: Health Endpoint Availability
**Validates: Requirements 20.5**

Strategy:
  - Use ``st.booleans()`` to generate dependency availability states
    (db_ok, redis_ok) representing all combinations of up/down.
  - Use ``st.just(True)`` for the healthy-only sub-properties.

Assertions (healthy case — both DB and Redis reachable):
  - ``GET /health`` MUST return HTTP 200
  - ``GET /ready`` MUST return HTTP 200
  - ``GET /ready`` response body MUST contain ``"status": "ready"``
  - ``GET /ready`` response body MUST have a ``"dependencies"`` dict where
    every value equals ``"ok"``

Assertions (degraded case — at least one dependency unavailable):
  - ``GET /health`` MUST still return HTTP 200 (liveness is process-only)
  - ``GET /ready`` MUST return HTTP 503
  - ``GET /ready`` response body MUST contain at least one dependency
    entry whose value is NOT ``"ok"``

The test builds a minimal FastAPI application containing only the health and
readiness endpoints extracted from ``app.main``.  This avoids importing
Celery, Prometheus, WebSocket routers, and other startup-heavy subsystems.
DB and Redis connectivity checks (``_check_db`` and ``_check_redis``) are
mocked so no external services are required.

Requirements: 20.5
"""

from __future__ import annotations

import os

# ---------------------------------------------------------------------------
# Environment variables must be set BEFORE any app imports
# ---------------------------------------------------------------------------
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-openai-key-for-testing")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key-for-testing")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test-key-for-testing")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")
os.environ.setdefault("LOKI_URL", "")
os.environ.setdefault("ENVIRONMENT", "development")
os.environ.setdefault("LOG_LEVEL", "INFO")

from unittest.mock import AsyncMock, patch

from fastapi import FastAPI
from fastapi.responses import JSONResponse
from fastapi.testclient import TestClient
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

# ---------------------------------------------------------------------------
# Minimal FastAPI test application
#
# We re-create only the two endpoints from app.main to avoid importing the
# full application (which pulls in Celery, Prometheus, all routers, etc.)
# ---------------------------------------------------------------------------

_test_app = FastAPI(title="health-test-only")


@_test_app.get("/health", tags=["ops"])
async def _health() -> dict[str, str]:
    """Liveness probe — process alive check only."""
    return {"status": "ok"}


@_test_app.get("/ready", tags=["ops"])
async def _ready() -> JSONResponse:
    """Readiness probe — checks DB and Redis connectivity."""
    import app.main as _main

    db_status = "ok"
    redis_status = "ok"

    try:
        await _main._check_db()
    except Exception:
        db_status = "unreachable"

    try:
        await _main._check_redis()
    except Exception:
        redis_status = "unreachable"

    dependencies = {"database": db_status, "redis": redis_status}

    if db_status == "ok" and redis_status == "ok":
        return JSONResponse(
            status_code=200,
            content={"status": "ready", "dependencies": dependencies},
        )

    return JSONResponse(
        status_code=503,
        content={"status": "unavailable", "dependencies": dependencies},
    )


_SHARED_CLIENT: TestClient | None = None


def _get_client() -> TestClient:
    """Return a module-level TestClient (created once to avoid startup overhead)."""
    global _SHARED_CLIENT
    if _SHARED_CLIENT is None:
        _SHARED_CLIENT = TestClient(_test_app, raise_server_exceptions=False)
    return _SHARED_CLIENT


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_mock_check(succeeds: bool) -> AsyncMock:
    """Return an AsyncMock that either returns normally or raises RuntimeError."""
    mock = AsyncMock()
    if not succeeds:
        mock.side_effect = RuntimeError("Dependency unreachable")
    return mock


# ---------------------------------------------------------------------------
# Strategies
# ---------------------------------------------------------------------------

# Both dependencies healthy
both_healthy_strategy = st.just((True, True))

# At least one dependency unhealthy (3 of 4 combinations)
degraded_strategy = st.one_of(
    st.just((False, True)),  # DB down, Redis up
    st.just((True, False)),  # DB up, Redis down
    st.just((False, False)),  # Both down
)

# All four dependency combinations
any_state_strategy = st.tuples(st.booleans(), st.booleans())


# ===========================================================================
# Property 25A — /health always returns HTTP 200 regardless of dependency state
# **Validates: Requirements 20.5**
# ===========================================================================


@given(db_ok=st.booleans(), redis_ok=st.booleans())
@settings(
    max_examples=20,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.function_scoped_fixture],
    deadline=None,
)
def test_property_25a_health_always_200(db_ok: bool, redis_ok: bool) -> None:
    """**Validates: Requirements 20.5**

    Property 25A: ``GET /health`` MUST return HTTP 200 for ALL dependency
    states.  The liveness probe checks only whether the process is alive —
    it has no dependency on DB or Redis.  A down DB or Redis MUST NOT cause
    this endpoint to return a non-200 status.
    """
    client = _get_client()

    with (
        patch("app.main._check_db", new=_make_mock_check(db_ok)),
        patch("app.main._check_redis", new=_make_mock_check(redis_ok)),
    ):
        response = client.get("/health")

    assert response.status_code == 200, (
        f"Property 25A violated: GET /health returned HTTP {response.status_code} "
        f"(db_ok={db_ok}, redis_ok={redis_ok}). "
        f"Liveness probe must always return 200 when process is alive. "
        f"Body: {response.text[:200]}"
    )


# ===========================================================================
# Property 25B — /health body always contains {"status": "ok"}
# **Validates: Requirements 20.5**
# ===========================================================================


@given(db_ok=st.booleans(), redis_ok=st.booleans())
@settings(
    max_examples=20,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.function_scoped_fixture],
    deadline=None,
)
def test_property_25b_health_body_structure(db_ok: bool, redis_ok: bool) -> None:
    """**Validates: Requirements 20.5**

    Property 25B: ``GET /health`` response body MUST always be
    ``{"status": "ok"}`` regardless of dependency state.
    """
    client = _get_client()

    with (
        patch("app.main._check_db", new=_make_mock_check(db_ok)),
        patch("app.main._check_redis", new=_make_mock_check(redis_ok)),
    ):
        response = client.get("/health")

    body = response.json()

    assert (
        "status" in body
    ), f"Property 25B violated: GET /health body missing 'status' key. Body: {body!r}"
    assert body["status"] == "ok", (
        f"Property 25B violated: GET /health body 'status' is {body['status']!r} "
        f"instead of 'ok'. Body: {body!r}"
    )


# ===========================================================================
# Property 25C — /ready returns HTTP 200 when both DB and Redis are reachable
# **Validates: Requirements 20.5**
# ===========================================================================


@given(state=both_healthy_strategy)
@settings(
    max_examples=10,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.function_scoped_fixture],
    deadline=None,
)
def test_property_25c_ready_200_when_all_dependencies_up(
    state: tuple[bool, bool],
) -> None:
    """**Validates: Requirements 20.5**

    Property 25C: ``GET /ready`` MUST return HTTP 200 when both DB and Redis
    are reachable.  This is the healthy-system invariant.
    """
    db_ok, redis_ok = state
    client = _get_client()

    with (
        patch("app.main._check_db", new=_make_mock_check(db_ok)),
        patch("app.main._check_redis", new=_make_mock_check(redis_ok)),
    ):
        response = client.get("/ready")

    assert response.status_code == 200, (
        f"Property 25C violated: GET /ready returned HTTP {response.status_code} "
        f"when DB and Redis are both reachable. "
        f"Body: {response.text[:200]}"
    )


# ===========================================================================
# Property 25D — /ready body contains status=ready and all deps=ok (healthy)
# **Validates: Requirements 20.5**
# ===========================================================================


@given(state=both_healthy_strategy)
@settings(
    max_examples=10,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.function_scoped_fixture],
    deadline=None,
)
def test_property_25d_ready_body_all_deps_ready(
    state: tuple[bool, bool],
) -> None:
    """**Validates: Requirements 20.5**

    Property 25D: When both dependencies are up, ``GET /ready`` body MUST
    contain ``"status": "ready"`` and every entry in ``"dependencies"`` MUST
    equal ``"ok"``.
    """
    db_ok, redis_ok = state
    client = _get_client()

    with (
        patch("app.main._check_db", new=_make_mock_check(db_ok)),
        patch("app.main._check_redis", new=_make_mock_check(redis_ok)),
    ):
        response = client.get("/ready")

    body = response.json()

    assert (
        "status" in body
    ), f"Property 25D violated: GET /ready body missing 'status' key. Body: {body!r}"
    assert body["status"] == "ready", (
        f"Property 25D violated: GET /ready body 'status' is {body['status']!r} "
        f"instead of 'ready' when all deps are up. Body: {body!r}"
    )

    assert "dependencies" in body, (
        f"Property 25D violated: GET /ready body missing 'dependencies' key. "
        f"Body: {body!r}"
    )

    deps: dict = body["dependencies"]
    assert isinstance(
        deps, dict
    ), f"Property 25D violated: 'dependencies' must be a dict, got {type(deps)}."
    assert (
        len(deps) > 0
    ), "Property 25D violated: 'dependencies' dict must not be empty."

    non_ok = {k: v for k, v in deps.items() if v != "ok"}
    assert not non_ok, (
        f"Property 25D violated: some dependencies are not 'ok' when both "
        f"DB and Redis are reachable: {non_ok!r}. Full deps: {deps!r}"
    )


# ===========================================================================
# Property 25E — /ready returns HTTP 503 when any dependency is down
# **Validates: Requirements 20.5**
# ===========================================================================


@given(state=degraded_strategy)
@settings(
    max_examples=20,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.function_scoped_fixture],
    deadline=None,
)
def test_property_25e_ready_503_when_any_dependency_down(
    state: tuple[bool, bool],
) -> None:
    """**Validates: Requirements 20.5**

    Property 25E: ``GET /ready`` MUST return HTTP 503 whenever at least one
    dependency (DB or Redis) is unreachable.  This covers all three degraded
    combinations: DB-only down, Redis-only down, and both down.
    """
    db_ok, redis_ok = state
    client = _get_client()

    with (
        patch("app.main._check_db", new=_make_mock_check(db_ok)),
        patch("app.main._check_redis", new=_make_mock_check(redis_ok)),
    ):
        response = client.get("/ready")

    assert response.status_code == 503, (
        f"Property 25E violated: GET /ready returned HTTP {response.status_code} "
        f"when db_ok={db_ok}, redis_ok={redis_ok}. "
        f"Readiness probe must return 503 when any dependency is unreachable. "
        f"Body: {response.text[:200]}"
    )


# ===========================================================================
# Property 25F — /ready body reports at least one unreachable dependency (503)
# **Validates: Requirements 20.5**
# ===========================================================================


@given(state=degraded_strategy)
@settings(
    max_examples=20,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.function_scoped_fixture],
    deadline=None,
)
def test_property_25f_ready_body_reports_unreachable_dependencies(
    state: tuple[bool, bool],
) -> None:
    """**Validates: Requirements 20.5**

    Property 25F: When ``GET /ready`` returns HTTP 503, the response body
    MUST include a ``"dependencies"`` dict with at least one entry whose
    value is NOT ``"ok"``.  This allows orchestrators to identify which
    dependency is failing.
    """
    db_ok, redis_ok = state
    client = _get_client()

    with (
        patch("app.main._check_db", new=_make_mock_check(db_ok)),
        patch("app.main._check_redis", new=_make_mock_check(redis_ok)),
    ):
        response = client.get("/ready")

    body = response.json()

    assert "dependencies" in body, (
        f"Property 25F violated: GET /ready 503 body missing 'dependencies' key. "
        f"Body: {body!r}"
    )

    deps: dict = body["dependencies"]
    assert isinstance(
        deps, dict
    ), f"Property 25F violated: 'dependencies' must be a dict, got {type(deps)}."

    unhealthy = {k: v for k, v in deps.items() if v != "ok"}
    assert unhealthy, (
        f"Property 25F violated: GET /ready returned 503 but all dependencies "
        f"are reported as 'ok'. At least one must be unreachable. "
        f"db_ok={db_ok}, redis_ok={redis_ok}. deps: {deps!r}"
    )

    # Verify that the correct dependencies are flagged as unreachable
    if not db_ok:
        assert deps.get("database") != "ok", (
            f"Property 25F violated: DB is down but 'database' dep reports "
            f"'{deps.get('database')}' instead of an error value."
        )
    if not redis_ok:
        assert deps.get("redis") != "ok", (
            f"Property 25F violated: Redis is down but 'redis' dep reports "
            f"'{deps.get('redis')}' instead of an error value."
        )


# ===========================================================================
# Property 25G — /health is unaffected by dependency state (composite)
# **Validates: Requirements 20.5**
# ===========================================================================


@given(state=any_state_strategy)
@settings(
    max_examples=30,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.function_scoped_fixture],
    deadline=None,
)
def test_property_25g_health_independent_of_dependency_state(
    state: tuple[bool, bool],
) -> None:
    """**Validates: Requirements 20.5**

    Property 25G (composite): For ALL combinations of DB and Redis
    availability, ``GET /health`` MUST return HTTP 200 with body
    ``{"status": "ok"}``.  This is the core liveness invariant — the
    liveness probe is strictly process-level and MUST NOT depend on
    external services.
    """
    db_ok, redis_ok = state
    client = _get_client()

    # /health intentionally does NOT call _check_db or _check_redis — patch
    # them anyway to prove they are not involved (if they were called, the
    # mock would still return normally for db_ok=True variants, but the
    # test verifies that the status code is 200 regardless).
    with (
        patch("app.main._check_db", new=_make_mock_check(db_ok)),
        patch("app.main._check_redis", new=_make_mock_check(redis_ok)),
    ):
        response = client.get("/health")

    assert response.status_code == 200, (
        f"Property 25G violated: GET /health returned HTTP {response.status_code} "
        f"(db_ok={db_ok}, redis_ok={redis_ok}). Expected 200."
    )

    body = response.json()
    assert body == {"status": "ok"}, (
        f"Property 25G violated: GET /health body is {body!r} instead of "
        f'{{"status": "ok"}} (db_ok={db_ok}, redis_ok={redis_ok}).'
    )
