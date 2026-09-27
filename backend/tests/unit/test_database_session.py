"""Unit tests for app.database session management and get_db() dependency.

Tests cover:
  - get_db() yields an AsyncSession
  - get_db() commits the session when no exception is raised
  - get_db() rolls back the session when an exception propagates
  - get_db() always closes the session (even on exception)
  - expire_on_commit=False is set on the session factory
  - pool_pre_ping=True is set on the engine

Teaching notes (Phase 3 — FastAPI Backend):
  - get_db() is a FastAPI async generator dependency:
        async def get_db() -> AsyncGenerator[AsyncSession, None]
    FastAPI's Depends() system drives the generator — it calls next() to get
    the session, injects it into the route handler, then calls close() on the
    generator after the response is sent (even on error).

  - The commit/rollback pattern means route handlers never call db.commit()
    themselves — the dependency handles it. This is the "unit of work" pattern:
    each HTTP request is one transaction.

  - expire_on_commit=False prevents SQLAlchemy from expiring all ORM instances
    after a commit, which would trigger a SELECT for every attribute access
    in the response serialization phase (a common async gotcha).

  - pool_pre_ping=True makes the engine validate each connection before handing
    it to a request. Without it, stale connections (dropped by the DB after idle
    timeout) cause cryptic asyncpg "server closed connection unexpectedly" errors.

Requirements: 9.3, 9.10
"""

from __future__ import annotations

import os
from unittest.mock import AsyncMock, MagicMock, patch, call

import pytest

os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("AES_ENCRYPTION_KEY", "dGVzdGtleXRlc3RrZXl0ZXN0a2V5dGVzdA==")


# ---------------------------------------------------------------------------
# Tests for get_db()
# ---------------------------------------------------------------------------


class TestGetDb:
    """get_db() must commit on success, rollback on error, always close."""

    async def _drive_generator(
        self,
        gen,
        raise_after: Exception | None = None,
    ):
        """Drive an async generator dependency as FastAPI would.

        FastAPI calls ``anext(gen)`` to get the yielded session, injects it
        into the handler, then calls ``aclose()`` to run the finally block.
        If the handler raises, FastAPI propagates the exception into the
        generator via ``gen.athrow(exc)``.

        Returns the session that was yielded.
        """
        session = await gen.__anext__()
        if raise_after is not None:
            try:
                await gen.athrow(type(raise_after), raise_after, None)
            except type(raise_after):
                pass
        else:
            try:
                await gen.__anext__()
            except StopAsyncIteration:
                pass
        return session

    async def test_get_db_yields_session(self) -> None:
        """get_db() must yield exactly one AsyncSession per request."""
        mock_session = AsyncMock()
        mock_session.__aenter__ = AsyncMock(return_value=mock_session)
        mock_session.__aexit__ = AsyncMock(return_value=False)

        mock_session_factory = MagicMock()
        mock_session_factory.return_value.__aenter__ = AsyncMock(
            return_value=mock_session
        )
        mock_session_factory.return_value.__aexit__ = AsyncMock(return_value=False)

        with patch("app.database.AsyncSessionLocal", mock_session_factory):
            from app.database import get_db

            gen = get_db()
            session = await gen.__anext__()
            assert session is mock_session
            try:
                await gen.__anext__()
            except StopAsyncIteration:
                pass

    async def test_get_db_commits_on_success(self) -> None:
        """get_db() must call session.commit() after the handler returns."""
        mock_session = AsyncMock()
        mock_cm = AsyncMock()
        mock_cm.__aenter__ = AsyncMock(return_value=mock_session)
        mock_cm.__aexit__ = AsyncMock(return_value=False)

        with patch("app.database.AsyncSessionLocal", return_value=mock_cm):
            from app.database import get_db

            gen = get_db()
            await gen.__anext__()  # yield session (handler runs here)
            try:
                await gen.__anext__()  # drive to finally
            except StopAsyncIteration:
                pass

        mock_session.commit.assert_awaited_once()
        mock_session.rollback.assert_not_awaited()

    async def test_get_db_rolls_back_on_exception(self) -> None:
        """get_db() must call session.rollback() when the handler raises."""
        mock_session = AsyncMock()
        mock_cm = AsyncMock()
        mock_cm.__aenter__ = AsyncMock(return_value=mock_session)
        mock_cm.__aexit__ = AsyncMock(return_value=False)

        with patch("app.database.AsyncSessionLocal", return_value=mock_cm):
            from app.database import get_db

            gen = get_db()
            await gen.__anext__()  # yield session
            try:
                await gen.athrow(ValueError, ValueError("simulated handler error"), None)
            except ValueError:
                pass

        mock_session.rollback.assert_awaited_once()
        mock_session.commit.assert_not_awaited()

    async def test_get_db_always_closes_session(self) -> None:
        """get_db() must call session.close() regardless of success or error."""
        mock_session = AsyncMock()
        mock_cm = AsyncMock()
        mock_cm.__aenter__ = AsyncMock(return_value=mock_session)
        mock_cm.__aexit__ = AsyncMock(return_value=False)

        with patch("app.database.AsyncSessionLocal", return_value=mock_cm):
            from app.database import get_db

            # Success path
            gen = get_db()
            await gen.__anext__()
            try:
                await gen.__anext__()
            except StopAsyncIteration:
                pass

        mock_session.close.assert_awaited_once()

    async def test_get_db_closes_session_on_exception(self) -> None:
        """close() must be called even when the handler raises."""
        mock_session = AsyncMock()
        mock_cm = AsyncMock()
        mock_cm.__aenter__ = AsyncMock(return_value=mock_session)
        mock_cm.__aexit__ = AsyncMock(return_value=False)

        with patch("app.database.AsyncSessionLocal", return_value=mock_cm):
            from app.database import get_db

            gen = get_db()
            await gen.__anext__()
            try:
                await gen.athrow(RuntimeError, RuntimeError("error"), None)
            except RuntimeError:
                pass

        mock_session.close.assert_awaited_once()

    async def test_get_db_re_raises_exception_after_rollback(self) -> None:
        """The original exception must propagate to the route handler."""
        mock_session = AsyncMock()
        mock_cm = AsyncMock()
        mock_cm.__aenter__ = AsyncMock(return_value=mock_session)
        mock_cm.__aexit__ = AsyncMock(return_value=False)

        with patch("app.database.AsyncSessionLocal", return_value=mock_cm):
            from app.database import get_db

            gen = get_db()
            await gen.__anext__()

            with pytest.raises(ValueError, match="propagated"):
                await gen.athrow(ValueError, ValueError("propagated"), None)


# ---------------------------------------------------------------------------
# Tests for engine configuration
# ---------------------------------------------------------------------------


class TestEngineConfiguration:
    """The async engine must be configured correctly for production use."""

    def test_session_factory_expire_on_commit_false(self) -> None:
        """expire_on_commit=False prevents extra SELECTs after commit.

        Without this, every attribute access after a commit triggers a
        lazy-load SELECT — a serious performance trap in async SQLAlchemy.
        """
        from app.database import AsyncSessionLocal

        # kw_args is a dict of keyword arguments passed to the session constructor
        assert AsyncSessionLocal.kw.get("expire_on_commit") is False

    def test_engine_pool_pre_ping_enabled(self) -> None:
        """pool_pre_ping=True validates connections before handing them out.

        Prevents 'server closed connection unexpectedly' on stale pool connections
        that were dropped by PostgreSQL's idle connection timeout.
        """
        from app.database import engine

        # The pool pre-ping flag is stored on the engine's pool
        assert engine.pool._pre_ping is True

    def test_engine_pool_size_is_20(self) -> None:
        """Pool size of 20 is appropriate for a single Uvicorn worker.

        Cloud Run allows 20+ concurrent requests per instance; one connection
        per concurrent request avoids connection starvation.
        """
        from app.database import engine

        assert engine.pool.size() == 20

    def test_engine_pool_max_overflow_is_10(self) -> None:
        """max_overflow=10 allows burst capacity above the base pool_size.

        Total max connections = pool_size (20) + max_overflow (10) = 30.
        This keeps us within PostgreSQL's default max_connections (100) even
        with multiple Cloud Run instances running concurrently.
        """
        from app.database import engine

        assert engine.pool._max_overflow == 10
