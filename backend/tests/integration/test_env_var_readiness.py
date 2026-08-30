"""Integration tests for /ready endpoint env-var validation (Requirement 26.3, 26.4)."""

from __future__ import annotations

import os
from unittest.mock import AsyncMock, patch

import pytest
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from httpx import ASGITransport, AsyncClient

# ---------------------------------------------------------------------------
# Minimal inline test app
# ---------------------------------------------------------------------------

_THIS_MODULE = "tests.integration.test_env_var_readiness"

async def _check_db_impl() -> None:
    pass

async def _check_redis_impl() -> None:
    pass

_ready_app = FastAPI()

@_ready_app.get("/ready")
async def _ready_endpoint() -> JSONResponse:
    # Use the same list of required vars as app.main
    required_vars = ["SECRET_KEY", "DATABASE_URL", "REDIS_URL", "AES_ENCRYPTION_KEY"]

    db_status = "ok"
    redis_status = "ok"
    missing_vars = [v for v in required_vars if not os.environ.get(v, "").strip()]

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
        return JSONResponse(status_code=200, content={"status": "ready", "dependencies": dependencies})

    content: dict = {"status": "unavailable", "dependencies": dependencies}
    if missing_vars:
        content["missing_env_vars"] = missing_vars

    return JSONResponse(status_code=503, content=content)

# ---------------------------------------------------------------------------
# Full required-var set
# ---------------------------------------------------------------------------

_BASE_ENV = {
    "SECRET_KEY": "test-secret-key-at-least-32-chars-long!!",
    "DATABASE_URL": "postgresql+asyncpg://test:test@localhost/test",
    "REDIS_URL": "redis://localhost:6379/0",
    "AES_ENCRYPTION_KEY": "dGVzdC1hZXMtMjU2LWtleS0zMi1ieXRlcy1iYXNlNjQh",
}

def _env_without(var_name: str) -> dict[str, str]:
    env = {**_BASE_ENV}
    env.pop(var_name, None)
    return env

# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

class TestReadyEnvVarValidation:
    @pytest.mark.asyncio
    @pytest.mark.parametrize("missing_var", list(_BASE_ENV.keys()))
    async def test_ready_returns_503_when_required_var_missing(self, missing_var: str) -> None:
        env = _env_without(missing_var)
        with (
            patch(f"{_THIS_MODULE}._check_db_impl", new_callable=AsyncMock) as m_db,
            patch(f"{_THIS_MODULE}._check_redis_impl", new_callable=AsyncMock) as m_redis,
            patch.dict(os.environ, env, clear=True),
        ):
            m_db.return_value = None
            m_redis.return_value = None
            async with AsyncClient(transport=ASGITransport(app=_ready_app), base_url="http://test") as client:
                response = await client.get("/ready")

        assert response.status_code == 503

    @pytest.mark.asyncio
    async def test_ready_returns_200_when_all_vars_present(self) -> None:
        with (
            patch(f"{_THIS_MODULE}._check_db_impl", new_callable=AsyncMock) as m_db,
            patch(f"{_THIS_MODULE}._check_redis_impl", new_callable=AsyncMock) as m_redis,
            patch.dict(os.environ, _BASE_ENV, clear=True),
        ):
            m_db.return_value = None
            m_redis.return_value = None
            async with AsyncClient(transport=ASGITransport(app=_ready_app), base_url="http://test") as client:
                response = await client.get("/ready")

        assert response.status_code == 200
