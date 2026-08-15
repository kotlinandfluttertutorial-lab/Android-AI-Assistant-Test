"""Property-based tests for JWT authentication enforcement.

Property 1: JWT Authentication Enforcement
Validates: Requirements 9.1

Approach
--------
Uses Hypothesis to sample from a set of protected API endpoints and verify
that every form of invalid/missing/expired JWT always yields HTTP 401 (or 403
for wrong-scheme headers), and that no user-sensitive fields are leaked in
error responses.

A minimal FastAPI test application is constructed with representative protected
endpoints (mirroring the real router structure) instead of importing the full
``app.main:app``.  This avoids optional dependencies (e.g. ``python-multipart``)
that are not installed in the test environment.

The real ``get_current_user`` dependency from ``app.security.dependencies`` is
wired into every endpoint, so the JWT validation logic being tested is
identical to production.

``_is_jti_revoked`` is patched to return ``False`` unconditionally so that
Redis connectivity is not required.

Requirements: 9.1
"""

from __future__ import annotations

import os

# ---------------------------------------------------------------------------
# Set required env vars BEFORE any app imports (mirrors conftest.py pattern).
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

import uuid
from datetime import timedelta
from unittest.mock import AsyncMock, patch

from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

from app.security.dependencies import get_current_user
from app.security.jwt_handler import create_access_token

# ---------------------------------------------------------------------------
# Minimal test app — mirrors the dependency structure of the real app but
# avoids importing optional dependencies (e.g. python-multipart for file
# upload routes).  Each endpoint below corresponds to a real protected router.
# ---------------------------------------------------------------------------

_test_app = FastAPI()


# --- conversations router representation ---
@_test_app.get("/conversations", dependencies=[Depends(get_current_user)])
async def _conversations() -> dict:
    return {"router": "conversations"}


# --- chat router representation ---
@_test_app.get("/chat/", dependencies=[Depends(get_current_user)])
async def _chat() -> dict:
    return {"router": "chat"}


# --- documents router representation ---
@_test_app.get("/documents/", dependencies=[Depends(get_current_user)])
async def _documents() -> dict:
    return {"router": "documents"}


# --- memory router representation ---
@_test_app.get("/memory/", dependencies=[Depends(get_current_user)])
async def _memory() -> dict:
    return {"router": "memory"}


# --- analytics router representation ---
@_test_app.get("/analytics/", dependencies=[Depends(get_current_user)])
async def _analytics() -> dict:
    return {"router": "analytics"}


# --- notifications router representation ---
@_test_app.get("/notifications/", dependencies=[Depends(get_current_user)])
async def _notifications() -> dict:
    return {"router": "notifications"}


# --- productivity/todos router representation ---
@_test_app.get("/productivity/todos", dependencies=[Depends(get_current_user)])
async def _productivity_todos() -> dict:
    return {"router": "productivity-todos"}


# --- productivity/reminders router representation ---
@_test_app.get("/productivity/reminders", dependencies=[Depends(get_current_user)])
async def _productivity_reminders() -> dict:
    return {"router": "productivity-reminders"}


# --- productivity/habits router representation ---
@_test_app.get("/productivity/habits", dependencies=[Depends(get_current_user)])
async def _productivity_habits() -> dict:
    return {"router": "productivity-habits"}


# --- prompts router representation ---
@_test_app.get("/prompts", dependencies=[Depends(get_current_user)])
async def _prompts() -> dict:
    return {"router": "prompts"}


# --- admin router representation (require_admin wraps get_current_user) ---
@_test_app.get("/admin/metrics", dependencies=[Depends(get_current_user)])
async def _admin_metrics() -> dict:
    return {"router": "admin-metrics"}


@_test_app.get("/admin/users", dependencies=[Depends(get_current_user)])
async def _admin_users() -> dict:
    return {"router": "admin-users"}


@_test_app.get("/admin/sessions", dependencies=[Depends(get_current_user)])
async def _admin_sessions() -> dict:
    return {"router": "admin-sessions"}


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# User-data fields that must NEVER appear in a 401/403 response body.
USER_DATA_FIELDS = {"sub", "email", "user_id", "role", "password", "token"}

# A fixed user UUID for token construction (value is irrelevant to auth).
_SAMPLE_USER_ID = uuid.UUID("deadbeef-dead-beef-dead-beefdeadbeef")

# ---------------------------------------------------------------------------
# Protected endpoints sampled by the property tests.
# Each tuple is (HTTP method, path) for a route registered on _test_app.
# ---------------------------------------------------------------------------
_PROTECTED_ENDPOINTS: list[tuple[str, str]] = [
    ("GET", "/conversations"),
    ("GET", "/chat/"),
    ("GET", "/documents/"),
    ("GET", "/memory/"),
    ("GET", "/analytics/"),
    ("GET", "/notifications/"),
    ("GET", "/productivity/todos"),
    ("GET", "/productivity/reminders"),
    ("GET", "/productivity/habits"),
    ("GET", "/prompts"),
    ("GET", "/admin/metrics"),
    ("GET", "/admin/users"),
    ("GET", "/admin/sessions"),
]

# ---------------------------------------------------------------------------
# Hypothesis strategies
# ---------------------------------------------------------------------------

protected_endpoint_strategy = st.sampled_from(_PROTECTED_ENDPOINTS)

# ASCII-safe alphabet for HTTP headers (letters + digits + safe punctuation).
# Avoids non-ASCII characters that httpx rejects when encoding headers.
_ASCII_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"

# Garbage tokens that look nothing like a real JWT (no dots).
_garbage_no_dots = st.text(
    min_size=1,
    max_size=200,
    alphabet=_ASCII_ALPHABET,
).filter(lambda s: "." not in s and len(s) > 0)

# Three-part garbage (looks structurally like a JWT but isn't signed correctly).
_garbage_three_part = st.tuples(
    st.text(min_size=1, max_size=50, alphabet=_ASCII_ALPHABET),
    st.text(min_size=1, max_size=50, alphabet=_ASCII_ALPHABET),
    st.text(min_size=1, max_size=50, alphabet=_ASCII_ALPHABET),
).map(lambda t: f"{t[0]}.{t[1]}.{t[2]}")

invalid_token_strategy = st.one_of(_garbage_no_dots, _garbage_three_part)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_expired_token() -> str:
    """Return a syntactically valid JWT that is already expired."""
    return create_access_token(
        _SAMPLE_USER_ID,
        "user",
        expires_delta=timedelta(seconds=-1),
    )


def assert_no_user_data_in_response(response) -> None:
    """Assert that no sensitive user-data keys appear in the response body.

    Non-JSON bodies (e.g. empty 401) pass automatically — they carry no field
    names at all.
    """
    try:
        body = response.json()
    except Exception:
        return  # non-JSON body → no fields to leak

    if isinstance(body, dict):
        leaked = USER_DATA_FIELDS & set(body.keys())
        assert not leaked, (
            f"User data fields {leaked} found in {response.status_code} "
            f"response: {body}"
        )


# ---------------------------------------------------------------------------
# TestClient setup — patch Redis to avoid network calls.
# ---------------------------------------------------------------------------

# Patch at the point of use inside the dependency module so that every request
# through the TestClient goes through the mock.
_REDIS_PATCH_TARGET = "app.security.dependencies._is_jti_revoked"


def _make_client() -> TestClient:
    return TestClient(_test_app, raise_server_exceptions=False)


# ---------------------------------------------------------------------------
# Property 1a: Missing JWT → always HTTP 401
#
# Validates: Requirements 9.1
# ---------------------------------------------------------------------------


@given(endpoint=protected_endpoint_strategy)
@settings(max_examples=30, suppress_health_check=[HealthCheck.too_slow])
def test_missing_jwt_always_returns_401(endpoint: tuple[str, str]) -> None:
    """**Validates: Requirements 9.1**

    For every protected endpoint, sending a request with NO Authorization
    header must yield HTTP 401, and the response body must not contain any
    user-data fields.
    """
    method, path = endpoint
    with patch(_REDIS_PATCH_TARGET, new=AsyncMock(return_value=False)):
        client = _make_client()
        response = client.request(method, path)

    assert response.status_code == 401, (
        f"{method} {path} with no Authorization header returned "
        f"{response.status_code}, expected 401. Body: {response.text}"
    )
    assert_no_user_data_in_response(response)


# ---------------------------------------------------------------------------
# Property 1b: Invalid / garbage JWT → always HTTP 401
#
# Validates: Requirements 9.1
# ---------------------------------------------------------------------------


@given(endpoint=protected_endpoint_strategy, token=invalid_token_strategy)
@settings(max_examples=30, suppress_health_check=[HealthCheck.too_slow])
def test_invalid_jwt_always_returns_401(endpoint: tuple[str, str], token: str) -> None:
    """**Validates: Requirements 9.1**

    For every protected endpoint, sending a request with a garbage or
    structurally-incorrect Bearer token must yield HTTP 401, and the
    response body must not contain any user-data fields.
    """
    method, path = endpoint
    headers = {"Authorization": f"Bearer {token}"}
    with patch(_REDIS_PATCH_TARGET, new=AsyncMock(return_value=False)):
        client = _make_client()
        response = client.request(method, path, headers=headers)

    assert response.status_code == 401, (
        f"{method} {path} with garbage token returned "
        f"{response.status_code}, expected 401. "
        f"Token (truncated): {token[:40]!r}. Body: {response.text}"
    )
    assert_no_user_data_in_response(response)


# ---------------------------------------------------------------------------
# Property 1c: Expired JWT → always HTTP 401
#
# Validates: Requirements 9.1
# ---------------------------------------------------------------------------


@given(endpoint=protected_endpoint_strategy)
@settings(max_examples=30, suppress_health_check=[HealthCheck.too_slow])
def test_expired_jwt_always_returns_401(endpoint: tuple[str, str]) -> None:
    """**Validates: Requirements 9.1**

    For every protected endpoint, an expired (but correctly signed) JWT must
    be rejected with HTTP 401.  The response body must not contain any
    user-data fields.
    """
    method, path = endpoint
    expired_token = _make_expired_token()
    headers = {"Authorization": f"Bearer {expired_token}"}
    with patch(_REDIS_PATCH_TARGET, new=AsyncMock(return_value=False)):
        client = _make_client()
        response = client.request(method, path, headers=headers)

    assert response.status_code == 401, (
        f"{method} {path} with expired token returned "
        f"{response.status_code}, expected 401. Body: {response.text}"
    )
    assert_no_user_data_in_response(response)


# ---------------------------------------------------------------------------
# Property 1d: Wrong-scheme Authorization header → always HTTP 401 or 403
#
# Validates: Requirements 9.1
#
# FastAPI's HTTPBearer returns 403 when the scheme is present but not Bearer,
# so both 401 and 403 are acceptable here.
# ---------------------------------------------------------------------------


@given(
    endpoint=protected_endpoint_strategy,
    credentials=st.text(
        min_size=1,
        max_size=50,
        alphabet=_ASCII_ALPHABET,
    ),
)
@settings(max_examples=30, suppress_health_check=[HealthCheck.too_slow])
def test_wrong_scheme_returns_401_or_403(
    endpoint: tuple[str, str], credentials: str
) -> None:
    """**Validates: Requirements 9.1**

    For every protected endpoint, an ``Authorization: Basic <credentials>``
    header (wrong scheme) must yield HTTP 401 or 403.  The response body must
    not contain any user-data fields.
    """
    method, path = endpoint
    headers = {"Authorization": f"Basic {credentials}"}
    with patch(_REDIS_PATCH_TARGET, new=AsyncMock(return_value=False)):
        client = _make_client()
        response = client.request(method, path, headers=headers)

    assert response.status_code in (401, 403), (
        f"{method} {path} with Basic auth header returned "
        f"{response.status_code}, expected 401 or 403. Body: {response.text}"
    )
    assert_no_user_data_in_response(response)
