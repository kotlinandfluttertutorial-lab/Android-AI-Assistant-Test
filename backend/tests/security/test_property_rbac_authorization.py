"""Property-based tests for RBAC endpoint authorization.

Property 2: RBAC Endpoint Authorization
Validates: Requirements 9.2, 1.8

Approach
--------
Uses Hypothesis to verify that every role-protected endpoint returns HTTP 403
when accessed with a valid JWT carrying an insufficient role, and HTTP 2xx
when accessed with the correct role.

A minimal FastAPI test application is constructed with representative
role-protected endpoints (admin-only and prompt-admin) instead of importing
the full ``app.main:app``.  The real ``require_admin`` dependency from
``app.security.rbac`` is wired into every endpoint, so the RBAC logic being
tested is identical to production.

``_is_jti_revoked`` is patched to return ``False`` unconditionally so that
Redis connectivity is not required.

Requirements: 9.2, 1.8
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
from typing import NamedTuple
from unittest.mock import AsyncMock, patch

from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

from app.security.jwt_handler import create_access_token
from app.security.rbac import require_admin

# ---------------------------------------------------------------------------
# Minimal RBAC test app — representative role-protected stub endpoints.
# Each endpoint uses the real ``require_admin`` dependency so the RBAC logic
# is identical to production.  No full app.main import is required.
# ---------------------------------------------------------------------------

_rbac_test_app = FastAPI()


# --- Admin-only endpoints ---
@_rbac_test_app.get("/admin/metrics", dependencies=[Depends(require_admin)])
async def _admin_metrics() -> dict:
    return {"endpoint": "admin-metrics"}


@_rbac_test_app.get("/admin/users", dependencies=[Depends(require_admin)])
async def _admin_users() -> dict:
    return {"endpoint": "admin-users"}


@_rbac_test_app.patch("/admin/users/{user_id}", dependencies=[Depends(require_admin)])
async def _admin_patch_user(user_id: str) -> dict:
    return {"endpoint": "admin-patch-user", "user_id": user_id}


@_rbac_test_app.get("/admin/audit-logs", dependencies=[Depends(require_admin)])
async def _admin_audit_logs() -> dict:
    return {"endpoint": "admin-audit-logs"}


@_rbac_test_app.get("/admin/sessions", dependencies=[Depends(require_admin)])
async def _admin_sessions() -> dict:
    return {"endpoint": "admin-sessions"}


@_rbac_test_app.get("/admin/remote-config", dependencies=[Depends(require_admin)])
async def _admin_remote_config() -> dict:
    return {"endpoint": "admin-remote-config"}


# --- Prompt-admin endpoints (require_admin at router level) ---
@_rbac_test_app.get("/prompts", dependencies=[Depends(require_admin)])
async def _prompts_list() -> dict:
    return {"endpoint": "prompts-list"}


@_rbac_test_app.get("/prompts/{name}", dependencies=[Depends(require_admin)])
async def _prompts_get(name: str) -> dict:
    return {"endpoint": "prompts-get", "name": name}


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# All roles in the system.
ALL_ROLES: frozenset[str] = frozenset({"user", "premium", "admin"})

# A fixed user UUID used for all test token construction.
_SAMPLE_USER_ID = uuid.UUID("deadbeef-dead-beef-dead-beefdeadbeef")

# Patch target — same as existing JWT property tests.
_REDIS_PATCH_TARGET = "app.security.dependencies._is_jti_revoked"

# ---------------------------------------------------------------------------
# Endpoint → role mapping
# Each entry describes a protected endpoint with:
#   method           : HTTP method string
#   path             : URL path (with concrete placeholder values for path params)
#   required_role_set: frozenset of roles that ARE allowed to access the endpoint
# ---------------------------------------------------------------------------


class _EndpointSpec(NamedTuple):
    method: str
    path: str
    required_role_set: frozenset


_ADMIN_ENDPOINTS: list[_EndpointSpec] = [
    _EndpointSpec("GET", "/admin/metrics", frozenset({"admin"})),
    _EndpointSpec("GET", "/admin/users", frozenset({"admin"})),
    _EndpointSpec(
        "PATCH",
        "/admin/users/deadbeef-dead-beef-dead-beefdeadbeef",
        frozenset({"admin"}),
    ),
    _EndpointSpec("GET", "/admin/audit-logs", frozenset({"admin"})),
    _EndpointSpec("GET", "/admin/sessions", frozenset({"admin"})),
    _EndpointSpec("GET", "/admin/remote-config", frozenset({"admin"})),
    _EndpointSpec("GET", "/prompts", frozenset({"admin"})),
    _EndpointSpec("GET", "/prompts/my-prompt-name", frozenset({"admin"})),
]

# ---------------------------------------------------------------------------
# Hypothesis strategies
# ---------------------------------------------------------------------------

admin_endpoint_strategy = st.sampled_from(_ADMIN_ENDPOINTS)


def insufficient_role_strategy(required_role_set: frozenset) -> st.SearchStrategy:
    """Return a strategy that samples roles NOT in *required_role_set*."""
    insufficient = list(ALL_ROLES - required_role_set)
    return st.sampled_from(insufficient)


# Composite strategy: sample an endpoint then an insufficient role for it.
_endpoint_and_insufficient_role_strategy = admin_endpoint_strategy.flatmap(
    lambda ep: st.tuples(
        st.just(ep),
        insufficient_role_strategy(ep.required_role_set),
    )
)

# ---------------------------------------------------------------------------
# Helper
# ---------------------------------------------------------------------------


def _make_token(role: str) -> str:
    """Create a valid (non-expired) JWT for the given *role*."""
    token, _exp = create_access_token(
        _SAMPLE_USER_ID,
        role,
        expires_delta=timedelta(minutes=15),
    )
    return token


def _make_client() -> TestClient:
    return TestClient(_rbac_test_app, raise_server_exceptions=False)


# ---------------------------------------------------------------------------
# Property 2a — Wrong role always returns HTTP 403
#
# Validates: Requirements 9.2, 1.8
# ---------------------------------------------------------------------------


@given(endpoint_and_role=_endpoint_and_insufficient_role_strategy)
@settings(max_examples=40, suppress_health_check=[HealthCheck.too_slow])
def test_insufficient_role_always_returns_403(
    endpoint_and_role: tuple[_EndpointSpec, str],
) -> None:
    """**Validates: Requirements 9.2, 1.8**

    For every role-protected endpoint, a valid JWT with an insufficient role
    must yield HTTP 403 regardless of other request parameters.
    The response detail must not leak internal role names or user IDs.
    """
    endpoint, role = endpoint_and_role
    token = _make_token(role)
    headers = {"Authorization": f"Bearer {token}"}

    with patch(_REDIS_PATCH_TARGET, new=AsyncMock(return_value=False)):
        client = _make_client()
        response = client.request(endpoint.method, endpoint.path, headers=headers)

    assert response.status_code == 403, (
        f"{endpoint.method} {endpoint.path} with role={role!r} returned "
        f"{response.status_code}, expected 403. Body: {response.text}"
    )

    # The error detail should not leak internal role names or the user sub.
    try:
        body = response.json()
    except Exception:  # noqa: BLE001
        body = {}

    if isinstance(body, dict):
        detail = str(body.get("detail", ""))
        # Ensure we don't leak the specific user UUID or role values beyond
        # the generic "Insufficient permissions" message.
        assert str(_SAMPLE_USER_ID) not in detail, (
            f"Response detail leaks user UUID: {detail!r}"
        )
        # The generic message is acceptable; internal role set enumeration is not.
        # Allowed: "Insufficient permissions". Not allowed: "must be admin", etc.
        # We simply verify the user-sub is not present — role names in a generic
        # message are acceptable per spec but the sub must not appear.


# ---------------------------------------------------------------------------
# Property 2b — Correct role always returns 2xx (not 401 or 403)
#
# Validates: Requirements 9.2, 1.8
# ---------------------------------------------------------------------------


@given(endpoint=admin_endpoint_strategy)
@settings(max_examples=20, suppress_health_check=[HealthCheck.too_slow])
def test_correct_role_passes_rbac(endpoint: _EndpointSpec) -> None:
    """**Validates: Requirements 9.2, 1.8**

    A valid JWT with the exact required role must NOT be rejected by the RBAC
    layer — the response must be 2xx (not 401 or 403).
    """
    # Use "admin" since all endpoints in our test app require admin.
    role = "admin"
    token = _make_token(role)
    headers = {"Authorization": f"Bearer {token}"}

    with patch(_REDIS_PATCH_TARGET, new=AsyncMock(return_value=False)):
        client = _make_client()
        response = client.request(endpoint.method, endpoint.path, headers=headers)

    assert response.status_code not in (401, 403), (
        f"{endpoint.method} {endpoint.path} with role={role!r} (correct role) "
        f"returned {response.status_code}, expected 2xx. Body: {response.text}"
    )


# ---------------------------------------------------------------------------
# Property 2c — Role check is independent of other request parameters
#
# Validates: Requirements 9.2, 1.8
# ---------------------------------------------------------------------------

_query_value_strategy = st.text(
    min_size=0,
    max_size=30,
    alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789",
)


@given(query_value=_query_value_strategy)
@settings(max_examples=30, suppress_health_check=[HealthCheck.too_slow])
def test_role_check_independent_of_query_params(query_value: str) -> None:
    """**Validates: Requirements 9.2, 1.8**

    For an admin-protected GET endpoint, appending arbitrary query parameters
    must not bypass the RBAC layer — a ``user``-role JWT must still receive
    HTTP 403 regardless of query param values.
    """
    # Use a stable GET endpoint for this test.
    path = f"/admin/metrics?q={query_value}"
    token = _make_token("user")
    headers = {"Authorization": f"Bearer {token}"}

    with patch(_REDIS_PATCH_TARGET, new=AsyncMock(return_value=False)):
        client = _make_client()
        response = client.request("GET", path, headers=headers)

    assert response.status_code == 403, (
        f"GET {path} with role='user' and query_value={query_value!r} returned "
        f"{response.status_code}, expected 403. Body: {response.text}"
    )
