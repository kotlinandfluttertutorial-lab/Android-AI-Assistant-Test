"""Integration tests for /ready endpoint env-var validation (Requirement 26.3, 26.4).

Verifies that GET /ready returns HTTP 503 with a structured body identifying
missing required environment variables.  Uses a minimal inline FastAPI app that
mirrors the production /ready handler (calling get_missing_env_vars() from
app.main directly), with the DB and Redis dependency checks patched so they
don't need live services.

Validates: Requirements 26.3, 26.4
"""

from __future__ import annotations

import os
from unittest.mock import AsyncMock, patch

import pytest
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text

# Ensure required vars are set before importing any app modules.
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault(
    "AES_ENCRYPTION_KEY",
    "dGVzdC1hZXMtMjU2LWtleS0zMi1ieXRlcy1iYXNlNjQh",
)

# ---------------------------------------------------------------------------
# Minimal inline test app — mirrors production /ready handler from app.main,
# calling get_missing_env_vars() so the env-var check is exercised.
# ---------------------------------------------------------------------------

_THIS_MODULE = "tests.integration.test_env_var_readiness"


async def _check_db_impl() -> None:
    """Stub for production _check_db; patched in every test."""
    from app.database import engine

    async with engine.connect() as conn:
        await conn.execute(text("SELECT 1"))


async def _check_redis_impl() -> None:
    """Stub for production _check_redis; patched in every test."""
    from app.database.redis import get_redis_client

    rc = get_redis_client()
    await rc.ping()


_ready_app = FastAPI()


@_ready_app.get("/ready")
async def _ready_endpoint() -> JSONResponse:
    """Mirrors production ready() from app.main including env-var validation."""
    from app.main import get_missing_env_vars

    db_status = "ok"
    redis_status = "ok"
    missing_vars = get_missing_env_vars()

    try:
        await _check_db_impl()
    except Exception:
        db_status = "unreachable"

    try:
        await _check_redis_impl()
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


# ---------------------------------------------------------------------------
# Full required-var set used as the baseline environment.
# ---------------------------------------------------------------------------

_BASE_ENV = {
    "SECRET_KEY": "test-secret-key-at-least-32-chars-long!!",
    "DATABASE_URL": "postgresql+asyncpg://test:test@localhost/test",
    "REDIS_URL": "redis://localhost:6379/0",
    "AES_ENCRYPTION_KEY": "dGVzdC1hZXMtMjU2LWtleS0zMi1ieXRlcy1iYXNlNjQh",
}


def _env_without(var_name: str) -> dict[str, str]:
    """Return a copy of _BASE_ENV with *var_name* removed."""
    env = {**os.environ, **_BASE_ENV}
    env.pop(var_name, None)
    return env


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


class TestReadyEnvVarValidation:
    """/ready returns HTTP 503 with missing_env_vars when a required var is absent.

    Validates: Requirements 26.3, 26.4
    """

    @pytest.mark.asyncio
    @pytest.mark.parametrize("missing_var", list(_BASE_ENV.keys()))
    async def test_ready_returns_503_when_required_var_missing(
        self, missing_var: str
    ) -> None:
        """GET /ready returns HTTP 503 for each missing required variable.

        Validates: Requirements 26.3, 26.4
        """
        with (
            patch(f"{_THIS_MODULE}._check_db_impl", new_callable=AsyncMock) as m_db,
            patch(
                f"{_THIS_MODULE}._check_redis_impl", new_callable=AsyncMock
            ) as m_redis,
            patch.dict(os.environ, _env_without(missing_var), clear=True),
        ):
            m_db.return_value = None
            m_redis.return_value = None
            async with AsyncClient(
                transport=ASGITransport(app=_ready_app),
                base_url="http://test",
            ) as client:
                response = await client.get("/ready")

        assert (
            response.status_code == 503
        ), f"Expected 503 when {missing_var!r} is missing, got {response.status_code}"

    @pytest.mark.asyncio
    @pytest.mark.parametrize("missing_var", list(_BASE_ENV.keys()))
    async def test_ready_body_contains_missing_var_name(self, missing_var: str) -> None:
        """GET /ready body includes the missing variable name in missing_env_vars.

        Validates: Requirement 26.4
        """
        with (
            patch(f"{_THIS_MODULE}._check_db_impl", new_callable=AsyncMock) as m_db,
            patch(
                f"{_THIS_MODULE}._check_redis_impl", new_callable=AsyncMock
            ) as m_redis,
            patch.dict(os.environ, _env_without(missing_var), clear=True),
        ):
            m_db.return_value = None
            m_redis.return_value = None
            async with AsyncClient(
                transport=ASGITransport(app=_ready_app),
                base_url="http://test",
            ) as client:
                response = await client.get("/ready")

        body = response.json()
        assert "missing_env_vars" in body, (
            f"Expected 'missing_env_vars' key in /ready response body when "
            f"{missing_var!r} is absent, got: {body!r}"
        )
        assert (
            missing_var in body["missing_env_vars"]
        ), f"Expected {missing_var!r} listed in missing_env_vars, got {body['missing_env_vars']!r}"

    @pytest.mark.asyncio
    async def test_ready_returns_503_with_status_unavailable(self) -> None:
        """GET /ready body contains status=unavailable when a var is missing.

        Validates: Requirement 26.4
        """
        with (
            patch(f"{_THIS_MODULE}._check_db_impl", new_callable=AsyncMock) as m_db,
            patch(
                f"{_THIS_MODULE}._check_redis_impl", new_callable=AsyncMock
            ) as m_redis,
            patch.dict(os.environ, _env_without("AES_ENCRYPTION_KEY"), clear=True),
        ):
            m_db.return_value = None
            m_redis.return_value = None
            async with AsyncClient(
                transport=ASGITransport(app=_ready_app),
                base_url="http://test",
            ) as client:
                response = await client.get("/ready")

        body = response.json()
        assert body["status"] == "unavailable"
        assert "AES_ENCRYPTION_KEY" in body["missing_env_vars"]

    @pytest.mark.asyncio
    async def test_ready_returns_200_when_all_vars_present_and_dependencies_healthy(
        self,
    ) -> None:
        """GET /ready returns HTTP 200 when all required vars are set and deps are up.

        Validates: Requirement 26.4
        """
        with (
            patch(f"{_THIS_MODULE}._check_db_impl", new_callable=AsyncMock) as m_db,
            patch(
                f"{_THIS_MODULE}._check_redis_impl", new_callable=AsyncMock
            ) as m_redis,
            patch.dict(os.environ, _BASE_ENV, clear=False),
        ):
            m_db.return_value = None
            m_redis.return_value = None
            async with AsyncClient(
                transport=ASGITransport(app=_ready_app),
                base_url="http://test",
            ) as client:
                response = await client.get("/ready")

        assert response.status_code == 200
        body = response.json()
        assert body["status"] == "ready"
        assert "missing_env_vars" not in body

    @pytest.mark.asyncio
    async def test_ready_503_when_missing_var_and_db_unreachable(self) -> None:
        """GET /ready returns 503 and reports both missing var and DB unreachable.

        Validates: Requirements 26.3, 26.4
        """
        with (
            patch(f"{_THIS_MODULE}._check_db_impl", new_callable=AsyncMock) as m_db,
            patch(
                f"{_THIS_MODULE}._check_redis_impl", new_callable=AsyncMock
            ) as m_redis,
            patch.dict(os.environ, _env_without("SECRET_KEY"), clear=True),
        ):
            m_db.side_effect = Exception("DB connection refused")
            m_redis.return_value = None
            async with AsyncClient(
                transport=ASGITransport(app=_ready_app),
                base_url="http://test",
            ) as client:
                response = await client.get("/ready")

        assert response.status_code == 503
        body = response.json()
        assert body["dependencies"]["database"] == "unreachable"
        assert "SECRET_KEY" in body["missing_env_vars"]
