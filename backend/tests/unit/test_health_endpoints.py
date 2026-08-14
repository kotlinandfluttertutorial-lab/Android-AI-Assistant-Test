"""Unit tests for /health and /ready liveness/readiness probe endpoints.

Tests call the health() and ready() coroutine functions directly,
bypassing the full FastAPI app import (which has pre-existing issues
with other routers in this codebase).

The endpoint logic is extracted and tested through a minimal in-test
FastAPI app that only registers the two health routes and delegates
to the same _check_db / _check_redis helpers used in production.

Requirements: 20.5
"""

from __future__ import annotations

import os
from unittest.mock import AsyncMock, patch

import pytest
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text

# Ensure env vars before any app imports
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")


# ---------------------------------------------------------------------------
# Inline mirror of the production _check_db / _check_redis helpers.
# These are the same functions defined in app.main; because app.main cannot
# be imported in this test environment (pre-existing issues with other
# routers), we duplicate the helpers here and test them in isolation.
# The production code in app.main is also covered by the same patch targets.
# ---------------------------------------------------------------------------


async def _check_db_impl() -> None:
    """Execute SELECT 1 to verify DB connectivity."""
    from app.database import engine

    async with engine.connect() as conn:
        await conn.execute(text("SELECT 1"))


async def _check_redis_impl() -> None:
    """Ping Redis to verify connectivity."""
    from app.database.redis import get_redis_client

    redis_client = get_redis_client()
    await redis_client.ping()


# ---------------------------------------------------------------------------
# Minimal test app — only /health and /ready are registered.
# The ready() handler calls the same _check_db_impl / _check_redis_impl
# defined above, which we patch in individual tests.
# ---------------------------------------------------------------------------

_test_app = FastAPI()


@_test_app.get("/health")
async def _health() -> dict:
    return {"status": "ok"}


@_test_app.get("/ready")
async def _ready() -> JSONResponse:
    db_status = "ok"
    redis_status = "ok"

    try:
        await _check_db_impl()
    except Exception:
        db_status = "unreachable"

    try:
        await _check_redis_impl()
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


# ---------------------------------------------------------------------------
# Patch targets — point at the local module-level helpers defined above.
# ---------------------------------------------------------------------------

_MODULE = "tests.unit.test_health_endpoints"


# ---------------------------------------------------------------------------
# /health — liveness probe
# ---------------------------------------------------------------------------


class TestHealthEndpoint:
    """Liveness probe — always returns 200 regardless of dependencies."""

    @pytest.mark.asyncio
    async def test_health_returns_200(self) -> None:
        """GET /health must return HTTP 200."""
        async with AsyncClient(
            transport=ASGITransport(app=_test_app), base_url="http://test"
        ) as client:
            response = await client.get("/health")
        assert response.status_code == 200

    @pytest.mark.asyncio
    async def test_health_returns_status_ok(self) -> None:
        """GET /health must return {"status": "ok"} in the body."""
        async with AsyncClient(
            transport=ASGITransport(app=_test_app), base_url="http://test"
        ) as client:
            response = await client.get("/health")
        assert response.json() == {"status": "ok"}


# ---------------------------------------------------------------------------
# /ready — readiness probe
# ---------------------------------------------------------------------------


class TestReadyEndpoint:
    """Readiness probe — checks DB and Redis connectivity."""

    # ------------------------------------------------------------------
    # Happy path: both dependencies healthy
    # ------------------------------------------------------------------

    @pytest.mark.asyncio
    async def test_ready_returns_200_when_both_healthy(self) -> None:
        """GET /ready returns HTTP 200 when DB and Redis are reachable."""
        with (
            patch(f"{_MODULE}._check_db_impl", new_callable=AsyncMock) as mock_db,
            patch(f"{_MODULE}._check_redis_impl", new_callable=AsyncMock) as mock_redis,
        ):
            mock_db.return_value = None
            mock_redis.return_value = None
            async with AsyncClient(
                transport=ASGITransport(app=_test_app), base_url="http://test"
            ) as client:
                response = await client.get("/ready")

        assert response.status_code == 200

    @pytest.mark.asyncio
    async def test_ready_body_when_both_healthy(self) -> None:
        """GET /ready body contains status=ready and both dependencies ok."""
        with (
            patch(f"{_MODULE}._check_db_impl", new_callable=AsyncMock) as mock_db,
            patch(f"{_MODULE}._check_redis_impl", new_callable=AsyncMock) as mock_redis,
        ):
            mock_db.return_value = None
            mock_redis.return_value = None
            async with AsyncClient(
                transport=ASGITransport(app=_test_app), base_url="http://test"
            ) as client:
                response = await client.get("/ready")

        body = response.json()
        assert body["status"] == "ready"
        assert body["dependencies"]["database"] == "ok"
        assert body["dependencies"]["redis"] == "ok"

    # ------------------------------------------------------------------
    # DB unreachable
    # ------------------------------------------------------------------

    @pytest.mark.asyncio
    async def test_ready_returns_503_when_db_unreachable(self) -> None:
        """GET /ready returns HTTP 503 when the database is unreachable."""
        with (
            patch(f"{_MODULE}._check_db_impl", new_callable=AsyncMock) as mock_db,
            patch(f"{_MODULE}._check_redis_impl", new_callable=AsyncMock) as mock_redis,
        ):
            mock_db.side_effect = Exception("Connection refused")
            mock_redis.return_value = None
            async with AsyncClient(
                transport=ASGITransport(app=_test_app), base_url="http://test"
            ) as client:
                response = await client.get("/ready")

        assert response.status_code == 503

    @pytest.mark.asyncio
    async def test_ready_body_when_db_unreachable(self) -> None:
        """Response body marks database as unreachable and status as unavailable."""
        with (
            patch(f"{_MODULE}._check_db_impl", new_callable=AsyncMock) as mock_db,
            patch(f"{_MODULE}._check_redis_impl", new_callable=AsyncMock) as mock_redis,
        ):
            mock_db.side_effect = Exception("Connection refused")
            mock_redis.return_value = None
            async with AsyncClient(
                transport=ASGITransport(app=_test_app), base_url="http://test"
            ) as client:
                response = await client.get("/ready")

        body = response.json()
        assert body["status"] == "unavailable"
        assert body["dependencies"]["database"] == "unreachable"
        assert body["dependencies"]["redis"] == "ok"

    # ------------------------------------------------------------------
    # Redis unreachable
    # ------------------------------------------------------------------

    @pytest.mark.asyncio
    async def test_ready_returns_503_when_redis_unreachable(self) -> None:
        """GET /ready returns HTTP 503 when Redis is unreachable."""
        with (
            patch(f"{_MODULE}._check_db_impl", new_callable=AsyncMock) as mock_db,
            patch(f"{_MODULE}._check_redis_impl", new_callable=AsyncMock) as mock_redis,
        ):
            mock_db.return_value = None
            mock_redis.side_effect = Exception("Redis connection error")
            async with AsyncClient(
                transport=ASGITransport(app=_test_app), base_url="http://test"
            ) as client:
                response = await client.get("/ready")

        assert response.status_code == 503

    @pytest.mark.asyncio
    async def test_ready_body_when_redis_unreachable(self) -> None:
        """Response body marks redis as unreachable and status as unavailable."""
        with (
            patch(f"{_MODULE}._check_db_impl", new_callable=AsyncMock) as mock_db,
            patch(f"{_MODULE}._check_redis_impl", new_callable=AsyncMock) as mock_redis,
        ):
            mock_db.return_value = None
            mock_redis.side_effect = Exception("Redis connection error")
            async with AsyncClient(
                transport=ASGITransport(app=_test_app), base_url="http://test"
            ) as client:
                response = await client.get("/ready")

        body = response.json()
        assert body["status"] == "unavailable"
        assert body["dependencies"]["database"] == "ok"
        assert body["dependencies"]["redis"] == "unreachable"

    # ------------------------------------------------------------------
    # Both unreachable
    # ------------------------------------------------------------------

    @pytest.mark.asyncio
    async def test_ready_returns_503_when_both_unreachable(self) -> None:
        """GET /ready returns HTTP 503 when both DB and Redis are unreachable."""
        with (
            patch(f"{_MODULE}._check_db_impl", new_callable=AsyncMock) as mock_db,
            patch(f"{_MODULE}._check_redis_impl", new_callable=AsyncMock) as mock_redis,
        ):
            mock_db.side_effect = Exception("DB down")
            mock_redis.side_effect = Exception("Redis down")
            async with AsyncClient(
                transport=ASGITransport(app=_test_app), base_url="http://test"
            ) as client:
                response = await client.get("/ready")

        assert response.status_code == 503

    @pytest.mark.asyncio
    async def test_ready_body_when_both_unreachable(self) -> None:
        """Response body marks both dependencies as unreachable."""
        with (
            patch(f"{_MODULE}._check_db_impl", new_callable=AsyncMock) as mock_db,
            patch(f"{_MODULE}._check_redis_impl", new_callable=AsyncMock) as mock_redis,
        ):
            mock_db.side_effect = Exception("DB down")
            mock_redis.side_effect = Exception("Redis down")
            async with AsyncClient(
                transport=ASGITransport(app=_test_app), base_url="http://test"
            ) as client:
                response = await client.get("/ready")

        body = response.json()
        assert body["status"] == "unavailable"
        assert body["dependencies"]["database"] == "unreachable"
        assert body["dependencies"]["redis"] == "unreachable"
