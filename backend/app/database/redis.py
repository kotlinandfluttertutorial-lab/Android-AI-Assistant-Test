# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : database
# File    : redis.py
# Purpose : redis — database module
#
# Architecture Layer : Database
# Pattern Used       : Python Module
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Async Redis client — application-level singleton and FastAPI dependency.

The module exposes:

- ``get_redis_client()`` — a cached async Redis client for direct use outside
  of FastAPI request context (e.g. startup, background tasks).
- ``get_redis`` — an async FastAPI dependency that yields the shared client.

All Redis interactions in the application should go through this module so
that test fixtures can easily replace the client.

Usage in route handlers::

    from app.database.redis import get_redis
    from redis.asyncio import Redis
    from fastapi import Depends

    @router.post("/login")
    async def login(redis: Redis = Depends(get_redis)):
        ...

Requirements: 1.5
"""

from __future__ import annotations

from collections.abc import AsyncGenerator
from functools import lru_cache

import redis.asyncio as aioredis
from redis.asyncio import Redis

from app.config.settings import get_settings


@lru_cache(maxsize=1)
def get_redis_client() -> Redis:
    """Return the cached async Redis client singleton.

    The client is created lazily on the first call.  Connection pooling is
    handled internally by the ``redis`` library (default pool size: 10).

    Returns:
        A connected :class:`redis.asyncio.Redis` instance.
    """
    settings = get_settings()
    return aioredis.from_url(  # type: ignore[no-untyped-call,return-value]
        settings.REDIS_URL,
        encoding="utf-8",
        decode_responses=True,
    )


async def get_redis() -> AsyncGenerator[Redis, None]:
    """FastAPI dependency that yields the shared async Redis client.

    The same client instance is reused across requests (it is a connection
    pool under the hood).  There is no per-request teardown required.

    Inject into route handlers with ``redis: Redis = Depends(get_redis)``.
    """
    yield get_redis_client()
