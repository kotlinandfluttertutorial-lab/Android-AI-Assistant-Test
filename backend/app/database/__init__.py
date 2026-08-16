"""Database package — SQLAlchemy async engine, session factory, and FastAPI dependency.

This module is the single entry-point for all database connectivity in the backend.
It creates:

- ``engine`` — an async SQLAlchemy engine backed by asyncpg (PostgreSQL).
- ``AsyncSessionLocal`` — a session factory for per-request database sessions.
- ``Base`` — the shared ``DeclarativeBase`` used by every ORM model.
- ``get_db`` — an async FastAPI dependency that yields a session and closes it after
  the request completes (even on error).

Typical usage inside a FastAPI route::

    from app.database import get_db
    from sqlalchemy.ext.asyncio import AsyncSession
    from fastapi import Depends

    @router.get("/items/{item_id}")
    async def read_item(item_id: str, db: AsyncSession = Depends(get_db)):
        result = await db.execute(select(Item).where(Item.id == item_id))
        return result.scalar_one_or_none()

Requirements: 9.3, 9.10
"""

from __future__ import annotations

from collections.abc import AsyncGenerator

from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy.orm import DeclarativeBase

from app.config.settings import get_settings

# ---------------------------------------------------------------------------
# Shared declarative base
# ---------------------------------------------------------------------------


class Base(DeclarativeBase):
    """Shared SQLAlchemy ``DeclarativeBase`` for all ORM models.

    Every model in ``app/models/`` imports and subclasses this ``Base`` so that
    Alembic's ``autogenerate`` can discover every table through a single metadata
    object (``Base.metadata``).
    """


# ---------------------------------------------------------------------------
# Async engine
# ---------------------------------------------------------------------------


def _build_engine() -> AsyncEngine:
    """Construct the async SQLAlchemy engine from application settings.

    The engine is created lazily so that tests can override ``DATABASE_URL``
    before the first import.  Pool settings are tuned for a production
    single-process Uvicorn deployment (20 connections, 10 overflow).
    """
    settings = get_settings()
    return create_async_engine(
        settings.DATABASE_URL,
        # Echo SQL statements only in debug-level environments; avoids leaking
        # query content in production logs.
        echo=settings.LOG_LEVEL == "DEBUG",
        pool_pre_ping=True,  # validates connections before checkout
        pool_size=20,
        max_overflow=10,
    )


engine = _build_engine()

# ---------------------------------------------------------------------------
# Async session factory
# ---------------------------------------------------------------------------

AsyncSessionLocal: async_sessionmaker[AsyncSession] = async_sessionmaker(
    bind=engine,
    class_=AsyncSession,
    expire_on_commit=False,  # avoids extra SELECT after commit in async context
)

# ---------------------------------------------------------------------------
# FastAPI dependency
# ---------------------------------------------------------------------------


async def get_db() -> AsyncGenerator[AsyncSession, None]:
    """FastAPI dependency that yields a per-request async database session.

    The session is committed automatically only if no exception is raised.
    On exception the session is rolled back and the error re-raised.  The
    session is always closed in the ``finally`` block.

    Inject into route handlers with ``db: AsyncSession = Depends(get_db)``.
    """
    async with AsyncSessionLocal() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise
        finally:
            await session.close()
