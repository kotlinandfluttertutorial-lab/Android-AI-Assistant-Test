# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : security
# File    : lockout.py
# Purpose : lockout — security module
#
# Architecture Layer : Security
# Pattern Used       : Python Module
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Account lockout service backed by Redis.

Implements the sliding-window failed-login throttle described in Requirement 1.5:

    IF a User submits invalid credentials 5 consecutive times within 10 minutes,
    THEN THE Auth_Service SHALL lock the account for 15 minutes and notify the
    User by email for **every failed attempt within the lockout window**, not
    only when the threshold is reached.

Design
------
Two Redis key namespaces per user email:

``auth:attempts:{email}``
    A Redis *list* of ISO-8601 UTC timestamps (strings), one element per failed
    attempt.  The list is capped by trimming entries older than the window on
    every write.  TTL is set to ``lockout_window_minutes`` so the key
    self-expires when activity stops.

``auth:locked:{email}``
    A Redis *string* (value ``"1"``) with a TTL of ``lockout_duration_minutes``.
    Presence of the key means the account is currently locked.

All Redis calls use the async ``redis.asyncio`` client (included in the
``redis`` package ≥ 4.2).

Email notification
------------------
On every failed attempt while the account is locked (i.e. when
``is_locked()`` returns True before the attempt is recorded), an email is
dispatched via :func:`app.security.email_service.send_failed_login_email`.
The same email is sent on the exact attempt that *causes* the lock.

Thread / concurrency safety
---------------------------
A short Lua script is used for the ``record_failed_attempt`` operation so that
the read-modify-write of the attempts list is atomic from Redis's perspective.

Requirements: 1.5
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime, timedelta

from redis.asyncio import Redis

from app.security.email_service import send_failed_login_email

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Redis key helpers
# ---------------------------------------------------------------------------

_ATTEMPTS_KEY_PREFIX = "auth:attempts:"
_LOCKED_KEY_PREFIX = "auth:locked:"


def _attempts_key(email: str) -> str:
    return f"{_ATTEMPTS_KEY_PREFIX}{email.lower()}"


def _locked_key(email: str) -> str:
    return f"{_LOCKED_KEY_PREFIX}{email.lower()}"


# ---------------------------------------------------------------------------
# Core Lua script — atomic attempt recording
# ---------------------------------------------------------------------------
# Arguments passed to the script via KEYS and ARGV:
#   KEYS[1] = attempts list key
#   ARGV[1] = current UTC timestamp ISO string
#   ARGV[2] = window cutoff UTC timestamp ISO string  (entries older than this are discarded)
#   ARGV[3] = window TTL in seconds (integer)
#
# The script appends the current timestamp, trims old entries, resets the TTL,
# and returns the resulting list length (number of recent failed attempts).

_RECORD_ATTEMPT_SCRIPT = """
local key = KEYS[1]
local now_ts = ARGV[1]
local cutoff_ts = ARGV[2]
local ttl_seconds = tonumber(ARGV[3])

-- Append this attempt
redis.call('RPUSH', key, now_ts)

-- Retrieve all entries and rebuild without stale ones
local all = redis.call('LRANGE', key, 0, -1)
local kept = {}
for _, ts in ipairs(all) do
    if ts >= cutoff_ts then
        table.insert(kept, ts)
    end
end

-- Rewrite the list with only in-window entries
redis.call('DEL', key)
if #kept > 0 then
    redis.call('RPUSH', key, unpack(kept))
    redis.call('EXPIRE', key, ttl_seconds)
end

return #kept
"""


# ---------------------------------------------------------------------------
# Public interface
# ---------------------------------------------------------------------------


async def is_locked(redis: Redis, email: str) -> bool:
    """Return ``True`` if the account identified by *email* is currently locked.

    Args:
        redis: Async Redis client.
        email: The user's email address (case-insensitive).

    Returns:
        ``True`` when the lockout key exists in Redis, ``False`` otherwise.

    Requirements: 1.5
    """
    result = await redis.exists(_locked_key(email))
    return bool(result)


async def get_lockout_ttl(redis: Redis, email: str) -> int:
    """Return the remaining lockout duration in seconds, or 0 if not locked.

    Args:
        redis: Async Redis client.
        email: The user's email address.

    Returns:
        Remaining TTL in seconds (≥ 0).  Returns 0 when the key does not exist.
    """
    ttl = await redis.ttl(_locked_key(email))
    return max(0, ttl)


async def record_failed_attempt(
    redis: Redis,
    email: str,
    *,
    max_attempts: int,
    window_minutes: int,
    lockout_duration_minutes: int,
    display_name: str = "",
) -> tuple[int, bool]:
    """Record a failed login attempt and apply lockout if the threshold is met.

    Workflow
    --------
    1. Atomically append the current timestamp to the attempts list and trim
       entries that fall outside the rolling window.
    2. Count the surviving entries (recent failed attempts).
    3. If the count reaches ``max_attempts``:
       - Set the lockout key with a TTL of ``lockout_duration_minutes``.
       - Send an email notification (this is the threshold-crossing attempt).
    4. If the account was **already locked** before this attempt (meaning
       subsequent failures after the lock was set), also send an email
       notification — per Requirement 1.5 which requires notification on
       *every* failed attempt within the lockout window, not just at threshold.

    Args:
        redis:                    Async Redis client.
        email:                    The user's email address.
        max_attempts:             Number of failures that triggers a lock.
        window_minutes:           Rolling window size in minutes.
        lockout_duration_minutes: How long to lock the account in minutes.
        display_name:             Optional display name for email personalisation.

    Returns:
        A tuple of ``(attempt_count, locked_now)`` where:
        - ``attempt_count`` — the number of failed attempts in the current window.
        - ``locked_now`` — ``True`` if the account just became locked *or* was
          already locked when this attempt arrived.

    Requirements: 1.5
    """
    already_locked = await is_locked(redis, email)

    now_utc = datetime.now(tz=UTC)
    cutoff_utc = now_utc - timedelta(minutes=window_minutes)
    window_ttl_seconds = window_minutes * 60

    # Atomic attempt recording via Lua script
    attempt_count: int = await redis.eval(  # type: ignore[assignment]
        _RECORD_ATTEMPT_SCRIPT,
        1,  # number of KEYS
        _attempts_key(email),
        now_utc.isoformat(),
        cutoff_utc.isoformat(),
        str(window_ttl_seconds),
    )

    just_locked = False

    if not already_locked and attempt_count >= max_attempts:
        # Apply the lock
        lockout_ttl_seconds = lockout_duration_minutes * 60
        await redis.setex(_locked_key(email), lockout_ttl_seconds, "1")
        just_locked = True
        logger.warning(
            "Account locked",
            extra={
                "email": email,
                "attempt_count": attempt_count,
                "lockout_duration_minutes": lockout_duration_minutes,
            },
        )

    # Send email notification when:
    #  a) This attempt just triggered the lock (just_locked=True), OR
    #  b) The account was already locked before this attempt arrived
    if just_locked or already_locked:
        remaining_seconds = await get_lockout_ttl(redis, email)
        try:
            await send_failed_login_email(
                to_email=email,
                display_name=display_name,
                attempt_count=attempt_count,
                lockout_duration_minutes=lockout_duration_minutes,
                remaining_lockout_seconds=remaining_seconds,
            )
        except Exception:
            logger.exception(
                "Failed to send lockout notification email",
                extra={"email": email},
            )

    return attempt_count, (just_locked or already_locked)


async def clear_failed_attempts(redis: Redis, email: str) -> None:
    """Remove all failed-attempt records for a user on successful login.

    Calling this after a successful authentication ensures that a user who
    recovers their password (or waits out the lockout) starts with a clean
    slate on the next window.

    Args:
        redis: Async Redis client.
        email: The user's email address.

    Requirements: 1.5
    """
    await redis.delete(_attempts_key(email))


# ---------------------------------------------------------------------------
# Service class (wraps the module-level functions with injected settings)
# ---------------------------------------------------------------------------


class AccountLockoutService:
    """High-level service for account lockout management.

    Wraps the module-level async functions and binds them to application
    settings so callers do not need to pass configuration parameters on every
    call.

    Usage::

        service = AccountLockoutService(redis_client)
        await service.check_locked(email)                  # raises AccountLockedError if locked
        await service.record_failed_attempt(email, name)   # records attempt + may lock
        await service.clear_on_success(email)              # clears attempts on success

    Requirements: 1.5
    """

    def __init__(self, redis: Redis) -> None:
        """Initialise the service.

        Args:
            redis: Async Redis client shared with the request lifecycle.
        """
        self._redis = redis
        self._settings = None  # lazy

    def _get_settings(self):
        if self._settings is None:
            from app.config.settings import get_settings

            self._settings = get_settings()
        return self._settings

    async def check_locked(self, email: str) -> None:
        """Raise :class:`~app.security.exceptions.AccountLockedError` if locked.

        Args:
            email: The email address to check.

        Raises:
            :class:`~app.security.exceptions.AccountLockedError`: When the
                account is currently locked, including the remaining TTL so
                the HTTP layer can set a ``Retry-After`` header.

        Requirements: 1.5
        """
        from app.security.exceptions import AccountLockedError

        locked = await is_locked(self._redis, email)
        if locked:
            remaining = await get_lockout_ttl(self._redis, email)
            raise AccountLockedError(
                f"Account is locked. Try again in {remaining} seconds.",
                retry_after_seconds=remaining,
            )

    async def record_failed_attempt(
        self,
        email: str,
        display_name: str = "",
    ) -> tuple[int, bool]:
        """Record a failed login attempt, apply lockout if threshold is met.

        Delegates to the module-level :func:`record_failed_attempt` with
        settings-driven parameters.

        Args:
            email:        User email address.
            display_name: User display name (used in notification email).

        Returns:
            ``(attempt_count, locked_now)`` — see :func:`record_failed_attempt`.

        Requirements: 1.5
        """
        s = self._get_settings()
        return await record_failed_attempt(
            self._redis,
            email,
            max_attempts=s.ACCOUNT_LOCKOUT_MAX_ATTEMPTS,
            window_minutes=s.ACCOUNT_LOCKOUT_WINDOW_MINUTES,
            lockout_duration_minutes=s.ACCOUNT_LOCKOUT_DURATION_MINUTES,
            display_name=display_name,
        )

    async def clear_on_success(self, email: str) -> None:
        """Clear all failed-attempt records after a successful login.

        Args:
            email: User email address.

        Requirements: 1.5
        """
        await clear_failed_attempts(self._redis, email)
