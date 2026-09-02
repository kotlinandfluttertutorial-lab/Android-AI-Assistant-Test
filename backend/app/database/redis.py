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

import ssl
from collections.abc import AsyncGenerator
from functools import lru_cache
from urllib.parse import parse_qs, urlencode, urlparse, urlunparse

import redis.asyncio as aioredis
from redis.asyncio import Redis

from app.config.settings import get_settings


def _clean_redis_url(url: str) -> str:
    """Strip ssl_cert_reqs query parameter from a Redis URL.

    The redis-py ``from_url`` helper does not accept ``ssl_cert_reqs`` as a
    URL query parameter — it must be passed as a keyword argument.  Celery's
    kombu transport does accept it in the URL, so the Secret Manager value
    may contain it.  This function removes it so redis-py doesn't choke.
    """
    parsed = urlparse(url)
    if not parsed.query:
        return url
    params = parse_qs(parsed.query, keep_blank_values=True)
    params.pop("ssl_cert_reqs", None)
    new_query = urlencode({k: v[0] for k, v in params.items()})
    return urlunparse(parsed._replace(query=new_query))


@lru_cache(maxsize=1)
def get_redis_client() -> Redis:
    """Return the cached async Redis client singleton.

    The client is created lazily on the first call.  Connection pooling is
    handled internally by the ``redis`` library (default pool size: 10).

    For ``rediss://`` (TLS) URLs (e.g. Upstash), ssl_cert_reqs is passed as
    a keyword argument using the ssl module constant rather than a URL query
    parameter, which redis-py does not support.

    Returns:
        A connected :class:`redis.asyncio.Redis` instance.
    """
    settings = get_settings()
    url = _clean_redis_url(settings.REDIS_URL)

    kwargs: dict = {
        "encoding": "utf-8",
        "decode_responses": True,
    }

    # TLS connections require explicit cert verification options.
    # Upstash and other managed Redis providers present valid CA-signed certs,
    # so CERT_REQUIRED is the secure and correct choice.
    if url.startswith("rediss://"):
        kwargs["ssl_cert_reqs"] = ssl.CERT_REQUIRED

    client: Redis = aioredis.from_url(url, **kwargs)  # type: ignore[no-untyped-call]
    return client


async def get_redis() -> AsyncGenerator[Redis, None]:
    """FastAPI dependency that yields the shared async Redis client.

    The same client instance is reused across requests (it is a connection
    pool under the hood).  There is no per-request teardown required.

    Inject into route handlers with ``redis: Redis = Depends(get_redis)``.
    """
    yield get_redis_client()
