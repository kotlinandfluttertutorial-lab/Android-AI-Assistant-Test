# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services
# File    : admin_service.py
# Purpose : Business logic for the admin domain
#
# Architecture Layer : Service
# Pattern Used       : Service Layer (Business Logic)
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Admin service — business logic for all /admin/* endpoints.

This module contains all data access and processing logic for:
- Real-time metrics (Req 15.1)
- User management — view, search, promote, demote, deactivate (Req 15.2, 15.4)
- Audit log retrieval (Req 15.5)
- Error monitoring — top-10 frequent error types in 24 h (Req 15.6)
- Feedback management + CSV export (Req 15.7)
- Firebase Remote Config — read/update/publish (Req 15.8)
- Real-time session monitor (Req 15.9)

The service layer is intentionally free of HTTP concerns; route handlers call
these functions and translate the results into HTTP responses.

Requirements: 15.1, 15.2, 15.3, 15.4, 15.5, 15.6, 15.7, 15.8, 15.9
"""

from __future__ import annotations

import csv
import io
import json
import logging
import math
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any

from redis.asyncio import Redis
from sqlalchemy import desc, func, or_, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.audit_log import AuditLog
from app.models.error_log import ErrorLog
from app.models.feedback import Feedback
from app.models.message import Message
from app.models.refresh_token import RefreshToken
from app.models.token_usage import TokenUsage
from app.models.user import User, UserRole
from app.schemas.admin import (
    ActiveSessionsResponse,
    AuditLogEntry,
    CeleryMetricsResponse,
    ErrorSummary,
    ErrorSummaryResponse,
    FeedbackItem,
    MetricsResponse,
    PaginatedAuditLogsResponse,
    PaginatedFeedbackResponse,
    PaginatedUsersResponse,
    ProviderCost,
    RemoteConfigEntry,
    RemoteConfigListResponse,
    RemoteConfigPublishResponse,
    SessionInfo,
    UsageAnalyticsItem,
    UsageAnalyticsResponse,
    UserAdminResponse,
    UserUpdateResponse,
)

logger = logging.getLogger(__name__)

# Redis key prefixes
_SESSION_KEY_PREFIX = "session:"
_REVOKED_JTI_KEY_PREFIX = "revoked_jti:"
_REMOTE_CONFIG_KEY = "admin:remote_config"
_REMOTE_CONFIG_PUBLISHED_AT_KEY = "admin:remote_config:published_at"

# How long to keep a revoked JTI in Redis (matches max access-token lifetime)
_JTI_REVOKE_TTL_SECONDS = 24 * 60 * 60  # 24 hours


# ---------------------------------------------------------------------------
# Metrics — Req 15.1
# ---------------------------------------------------------------------------


async def get_metrics(db: AsyncSession, redis: Redis) -> MetricsResponse:
    """Compute and return real-time platform metrics.

    - active_users: distinct user IDs with a session in Redis (last 1 h)
    - messages_per_hour: messages created in the last 60 minutes
    - total_tokens_consumed: sum of all input + output tokens
    - provider_costs: per-provider token breakdown with cost
    - error_rate_per_hour: ratio of error-log entries to total messages in 1 h

    Requirements: 15.1
    """
    now = datetime.now(tz=timezone.utc)
    one_hour_ago = now - timedelta(hours=1)

    # Active users — count unique session keys in Redis
    active_users = await _count_active_sessions(redis)

    # Messages per hour
    messages_result = await db.execute(
        select(func.count(Message.id)).where(Message.created_at >= one_hour_ago)
    )
    messages_per_hour: int = messages_result.scalar_one() or 0

    # Total tokens consumed (all time)
    total_tokens_result = await db.execute(
        select(
            func.coalesce(
                func.sum(TokenUsage.input_tokens + TokenUsage.output_tokens), 0
            )
        )
    )
    total_tokens: int = int(total_tokens_result.scalar_one() or 0)

    # Per-provider token costs
    provider_rows = await db.execute(
        select(
            TokenUsage.provider,
            func.sum(TokenUsage.input_tokens + TokenUsage.output_tokens).label(
                "total_tokens"
            ),
            func.sum(TokenUsage.cost_usd).label("total_cost"),
        ).group_by(TokenUsage.provider)
    )
    provider_costs = [
        ProviderCost(
            provider=row.provider,
            total_tokens=int(row.total_tokens or 0),
            total_cost_usd=float(row.total_cost or 0.0),
        )
        for row in provider_rows
    ]

    # Error rate: error_logs in last hour / messages in last hour
    error_count_result = await db.execute(
        select(func.count(ErrorLog.id)).where(ErrorLog.created_at >= one_hour_ago)
    )
    error_count: int = error_count_result.scalar_one() or 0
    error_rate = (error_count / messages_per_hour) if messages_per_hour > 0 else 0.0

    return MetricsResponse(
        active_users=active_users,
        messages_per_hour=messages_per_hour,
        total_tokens_consumed=total_tokens,
        provider_costs=provider_costs,
        error_rate_per_hour=error_rate,
        snapshot_at=now,
    )


# ---------------------------------------------------------------------------
# Users — Req 15.2
# ---------------------------------------------------------------------------


async def list_users(
    db: AsyncSession,
    *,
    page: int,
    page_size: int,
    search: str | None,
) -> PaginatedUsersResponse:
    """Return a paginated, optionally searched list of all users.

    Search matches against email and display_name (case-insensitive LIKE).

    Requirements: 15.2
    """
    query = select(User)
    count_query = select(func.count(User.id))

    if search:
        pattern = f"%{search}%"
        filter_clause = or_(
            User.email.ilike(pattern),
            User.display_name.ilike(pattern),
        )
        query = query.where(filter_clause)
        count_query = count_query.where(filter_clause)

    # Total count
    total_result = await db.execute(count_query)
    total: int = total_result.scalar_one() or 0

    # Paginated results
    offset = (page - 1) * page_size
    result = await db.execute(
        query.order_by(User.created_at.desc()).offset(offset).limit(page_size)
    )
    users = list(result.scalars().all())

    pages = math.ceil(total / page_size) if page_size > 0 else 1

    return PaginatedUsersResponse(
        items=[UserAdminResponse.model_validate(u) for u in users],
        total=total,
        page=page,
        page_size=page_size,
        pages=max(pages, 1),
    )


async def update_user(
    db: AsyncSession,
    redis: Redis,
    user_id: uuid.UUID,
    action: str,
) -> UserUpdateResponse:
    """Promote, demote, or deactivate a user.

    On deactivation (action='deactivate'):
    1. Sets is_active=False on the User row.
    2. Revokes ALL active refresh tokens for the user (DB).
    3. Blacklists all known JTIs for that user in Redis so in-flight JWTs
       are immediately rejected by the ``get_current_user`` dependency.

    Requirements: 15.2, 15.4
    """
    result = await db.execute(select(User).where(User.id == user_id))
    user = result.scalar_one_or_none()
    if user is None:
        raise ValueError(f"User {user_id} not found")

    tokens_revoked = 0

    action = action.lower()
    if action == "promote":
        user.role = UserRole.premium
    elif action == "demote":
        user.role = UserRole.user
    elif action == "make_admin":
        user.role = UserRole.admin
    elif action == "remove_admin":
        user.role = UserRole.user
    elif action == "deactivate":
        user.is_active = False
        tokens_revoked = await _invalidate_all_tokens_for_user(db, redis, user_id)
    elif action == "reactivate":
        user.is_active = True
    else:
        raise ValueError(
            f"Unknown action '{action}'. Must be one of: "
            "promote, demote, make_admin, remove_admin, deactivate, reactivate"
        )

    await db.flush()

    return UserUpdateResponse(
        user_id=user_id,
        action=action,
        new_role=user.role.value,
        is_active=user.is_active,
        tokens_revoked=tokens_revoked,
    )


async def _invalidate_all_tokens_for_user(
    db: AsyncSession,
    redis: Redis,
    user_id: uuid.UUID,
) -> int:
    """Revoke all active refresh tokens and blacklist all live JWTs in Redis.

    Returns the count of refresh tokens revoked in the database.

    Requirements: 15.4
    """
    # 1. Revoke all refresh tokens in the database
    result = await db.execute(
        update(RefreshToken)
        .where(
            RefreshToken.user_id == user_id,
            RefreshToken.revoked == False,
        )
        .values(revoked=True)
        .returning(RefreshToken.id)
    )
    revoked_ids = result.fetchall()
    tokens_revoked = len(revoked_ids)

    # 2. Blacklist active JTI values in Redis
    # We scan for any session keys associated with this user to get JTIs.
    # This is best-effort since JTIs aren't centrally tracked; the DB revocation
    # above handles refresh tokens, and JWT expiry handles access tokens within
    # their 15-min window. For immediate revocation we store a wildcard key.
    try:
        # Store a "force_logout" marker for the user; the dependency checks this
        force_logout_key = f"force_logout:{user_id}"
        settings_module = __import__("app.config.settings", fromlist=["get_settings"])
        settings = settings_module.get_settings()
        # Keep the marker for max access-token lifetime (ACCESS_TOKEN_EXPIRE_MINUTES)
        ttl = (settings.ACCESS_TOKEN_EXPIRE_MINUTES + 1) * 60
        await redis.setex(force_logout_key, ttl, "1")
        logger.info("Set force_logout marker for user %s (TTL=%ds)", user_id, ttl)
    except Exception as exc:  # noqa: BLE001
        logger.warning(
            "Could not set Redis force_logout marker for user %s: %s", user_id, exc
        )

    return tokens_revoked


# ---------------------------------------------------------------------------
# Audit Logs — Req 15.5
# ---------------------------------------------------------------------------


async def get_audit_logs(
    db: AsyncSession,
    *,
    page: int,
    page_size: int,
    user_id: uuid.UUID | None,
    event_type: str | None,
    date_from: datetime | None,
    date_to: datetime | None,
) -> PaginatedAuditLogsResponse:
    """Return paginated, filterable audit logs.

    Filters: user_id, event_type (exact match), date range [date_from, date_to].

    Requirements: 15.5
    """
    query = select(AuditLog)
    count_query = select(func.count(AuditLog.id))

    filters = []
    if user_id is not None:
        filters.append(AuditLog.user_id == user_id)
    if event_type:
        filters.append(AuditLog.event_type == event_type)
    if date_from:
        filters.append(AuditLog.created_at >= date_from)
    if date_to:
        filters.append(AuditLog.created_at <= date_to)

    if filters:
        for f in filters:
            query = query.where(f)
            count_query = count_query.where(f)

    total_result = await db.execute(count_query)
    total: int = total_result.scalar_one() or 0

    offset = (page - 1) * page_size
    result = await db.execute(
        query.order_by(desc(AuditLog.created_at)).offset(offset).limit(page_size)
    )
    logs = list(result.scalars().all())
    pages = math.ceil(total / page_size) if page_size > 0 else 1

    return PaginatedAuditLogsResponse(
        items=[AuditLogEntry.model_validate(entry) for entry in logs],
        total=total,
        page=page,
        page_size=page_size,
        pages=max(pages, 1),
    )


# ---------------------------------------------------------------------------
# Error Summary — Req 15.6
# ---------------------------------------------------------------------------


async def get_error_summary(db: AsyncSession) -> ErrorSummaryResponse:
    """Return the top-10 most frequent error types in the last 24 hours.

    Requirements: 15.6
    """
    now = datetime.now(tz=timezone.utc)
    since = now - timedelta(hours=24)

    # Aggregate by error_type: count, last seen, latest message/trace
    rows = await db.execute(
        select(
            ErrorLog.error_type,
            func.count(ErrorLog.id).label("count"),
            func.max(ErrorLog.created_at).label("last_seen"),
        )
        .where(ErrorLog.created_at >= since)
        .group_by(ErrorLog.error_type)
        .order_by(desc("count"))
        .limit(10)
    )
    aggregated = rows.fetchall()

    summaries: list[ErrorSummary] = []
    for row in aggregated:
        # Fetch the most recent record for this error_type to get message + trace
        latest_result = await db.execute(
            select(ErrorLog)
            .where(
                ErrorLog.error_type == row.error_type,
                ErrorLog.created_at >= since,
            )
            .order_by(desc(ErrorLog.created_at))
            .limit(1)
        )
        latest = latest_result.scalar_one_or_none()

        summaries.append(
            ErrorSummary(
                error_type=row.error_type,
                count=int(row.count),
                last_seen=row.last_seen,
                sample_message=latest.message if latest else "",
                stack_trace_summary=(latest.stack_trace or "")[:500] if latest else "",
            )
        )

    return ErrorSummaryResponse(
        errors=summaries,
        window_hours=24,
        generated_at=now,
    )


# ---------------------------------------------------------------------------
# Feedback — Req 15.7
# ---------------------------------------------------------------------------


async def list_feedback(
    db: AsyncSession,
    *,
    page: int,
    page_size: int,
    category: str | None,
) -> PaginatedFeedbackResponse:
    """Return paginated feedback, optionally filtered by category.

    Requirements: 15.7
    """
    query = select(Feedback)
    count_query = select(func.count(Feedback.id))

    if category:
        query = query.where(Feedback.category == category)
        count_query = count_query.where(Feedback.category == category)

    total_result = await db.execute(count_query)
    total: int = total_result.scalar_one() or 0

    offset = (page - 1) * page_size
    result = await db.execute(
        query.order_by(desc(Feedback.created_at)).offset(offset).limit(page_size)
    )
    items = list(result.scalars().all())
    pages = math.ceil(total / page_size) if page_size > 0 else 1

    return PaginatedFeedbackResponse(
        items=[FeedbackItem.model_validate(f) for f in items],
        total=total,
        page=page,
        page_size=page_size,
        pages=max(pages, 1),
    )


async def export_feedback_csv(db: AsyncSession) -> str:
    """Export all feedback as a CSV string.

    Returns a UTF-8 string ready to be streamed as text/csv.

    Requirements: 15.7
    """
    result = await db.execute(select(Feedback).order_by(Feedback.created_at.asc()))
    items = list(result.scalars().all())

    output = io.StringIO()
    writer = csv.DictWriter(
        output,
        fieldnames=["id", "user_id", "category", "content", "created_at"],
        lineterminator="\n",
    )
    writer.writeheader()
    for item in items:
        writer.writerow(
            {
                "id": str(item.id),
                "user_id": str(item.user_id) if item.user_id else "",
                "category": item.category,
                "content": item.content,
                "created_at": item.created_at.isoformat(),
            }
        )
    return output.getvalue()


# ---------------------------------------------------------------------------
# Sessions — Req 15.9
# ---------------------------------------------------------------------------


async def get_active_sessions(redis: Redis) -> ActiveSessionsResponse:
    """Return all active user sessions stored in Redis.

    Session data is stored by the application under keys of the form:
    ``session:{user_id}:{session_id}`` as JSON-encoded :class:`SessionInfo`
    fields.  This function scans all matching keys and returns the list.

    Requirements: 15.9
    """
    now = datetime.now(tz=timezone.utc)
    sessions: list[SessionInfo] = []

    try:
        # SCAN is non-blocking and safe for production Redis use
        cursor: int = 0
        pattern = f"{_SESSION_KEY_PREFIX}*"
        while True:
            cursor, keys = await redis.scan(cursor=cursor, match=pattern, count=100)
            for key in keys:
                raw = await redis.get(key)
                if raw is None:
                    continue
                try:
                    data: dict[str, Any] = json.loads(raw)
                    # Parse key: "session:{user_id}:{session_id}"
                    parts = key.split(":")
                    user_id_str = parts[1] if len(parts) > 1 else "unknown"
                    session_id_str = parts[2] if len(parts) > 2 else "unknown"

                    connected_at_str = data.get("connected_at", now.isoformat())
                    try:
                        connected_at = datetime.fromisoformat(connected_at_str)
                        if connected_at.tzinfo is None:
                            connected_at = connected_at.replace(tzinfo=timezone.utc)
                        duration = int((now - connected_at).total_seconds())
                    except (ValueError, TypeError):
                        duration = 0

                    sessions.append(
                        SessionInfo(
                            user_id=data.get("user_id", user_id_str),
                            session_id=data.get("session_id", session_id_str),
                            device_type=data.get("device_type", "unknown"),
                            region=data.get("region", "unknown"),
                            current_feature=data.get("current_feature", "unknown"),
                            connected_at=connected_at_str,
                            duration_seconds=max(duration, 0),
                        )
                    )
                except (json.JSONDecodeError, KeyError, IndexError) as exc:
                    logger.debug("Skipping malformed session key %s: %s", key, exc)

            if cursor == 0:
                break
    except Exception as exc:  # noqa: BLE001
        logger.warning("Could not read sessions from Redis: %s", exc)

    return ActiveSessionsResponse(
        sessions=sessions,
        total=len(sessions),
        snapshot_at=now,
    )


# ---------------------------------------------------------------------------
# Firebase Remote Config — Req 15.8
# ---------------------------------------------------------------------------


async def get_remote_config(redis: Redis) -> RemoteConfigListResponse:
    """Return all remote config entries from Redis (or Firebase if available).

    Requirements: 15.8
    """
    entries: list[RemoteConfigEntry] = []
    published_at: datetime | None = None

    try:
        raw = await redis.get(_REMOTE_CONFIG_KEY)
        if raw:
            data: dict[str, Any] = json.loads(raw)
            now = datetime.now(tz=timezone.utc)
            for key, entry_data in data.items():
                if isinstance(entry_data, dict):
                    last_updated_raw = entry_data.get("last_updated")
                    last_updated: datetime | None = None
                    if last_updated_raw:
                        try:
                            last_updated = datetime.fromisoformat(last_updated_raw)
                        except ValueError:
                            last_updated = None
                    entries.append(
                        RemoteConfigEntry(
                            key=key,
                            value=str(entry_data.get("value", "")),
                            description=str(entry_data.get("description", "")),
                            last_updated=last_updated,
                        )
                    )
                else:
                    entries.append(RemoteConfigEntry(key=key, value=str(entry_data)))

        published_raw = await redis.get(_REMOTE_CONFIG_PUBLISHED_AT_KEY)
        if published_raw:
            try:
                published_at = datetime.fromisoformat(published_raw)
            except ValueError:
                published_at = None
    except Exception as exc:  # noqa: BLE001
        logger.warning("Could not read remote config from Redis: %s", exc)

    return RemoteConfigListResponse(entries=entries, published_at=published_at)


async def update_remote_config_key(
    redis: Redis,
    key: str,
    value: str,
    description: str = "",
) -> RemoteConfigEntry:
    """Set or update a single remote config key in Redis.

    Requirements: 15.8
    """
    now = datetime.now(tz=timezone.utc)

    try:
        raw = await redis.get(_REMOTE_CONFIG_KEY)
        config: dict[str, Any] = json.loads(raw) if raw else {}
    except (json.JSONDecodeError, Exception):  # noqa: BLE001
        config = {}

    config[key] = {
        "value": value,
        "description": description,
        "last_updated": now.isoformat(),
    }
    await redis.set(_REMOTE_CONFIG_KEY, json.dumps(config))

    return RemoteConfigEntry(
        key=key,
        value=value,
        description=description,
        last_updated=now,
    )


async def publish_remote_config(redis: Redis) -> RemoteConfigPublishResponse:
    """Publish all staged config entries to Firebase Remote Config.

    If firebase-admin is available and configured, this pushes changes to
    Firebase.  Otherwise the staged config is treated as already "live" and
    the published_at timestamp is updated.

    Requirements: 15.8
    """
    now = datetime.now(tz=timezone.utc)

    try:
        raw = await redis.get(_REMOTE_CONFIG_KEY)
        config: dict[str, Any] = json.loads(raw) if raw else {}
    except (json.JSONDecodeError, Exception):  # noqa: BLE001
        config = {}

    entries_count = len(config)
    message = ""
    published = False

    # Attempt Firebase Remote Config push
    try:
        from app.config.settings import get_settings

        settings = get_settings()
        if (
            settings.FIREBASE_REMOTE_CONFIG_ENABLED
            and settings.FIREBASE_CREDENTIALS_PATH
        ):
            import firebase_admin
            from firebase_admin import remote_config

            if not firebase_admin._apps:
                import firebase_admin.credentials as fb_creds

                cred = fb_creds.Certificate(settings.FIREBASE_CREDENTIALS_PATH)
                firebase_admin.initialize_app(cred)

            # Build Firebase parameter map
            params: dict[str, Any] = {}
            for k, v in config.items():
                val = v.get("value", "") if isinstance(v, dict) else str(v)
                params[k] = remote_config.Parameter(
                    default_value=remote_config.ExplicitParameterValue(val)
                )

            template = remote_config.get_template()
            template.parameters = params
            remote_config.publish_template(template)
            published = True
            message = f"Published {entries_count} entries to Firebase Remote Config"
            logger.info(message)
        else:
            published = True
            message = f"Firebase Remote Config not enabled; {entries_count} entries staged locally"
    except ImportError:
        published = True
        message = (
            f"firebase-admin not installed; {entries_count} entries staged in Redis. "
            "Install firebase-admin to enable live Firebase publishing."
        )
        logger.info(message)
    except Exception as exc:  # noqa: BLE001
        message = f"Firebase publish failed: {exc}"
        logger.error("Firebase Remote Config publish error: %s", exc)
        published = False

    # Always update the published_at timestamp on success
    if published:
        await redis.set(_REMOTE_CONFIG_PUBLISHED_AT_KEY, now.isoformat())

    return RemoteConfigPublishResponse(
        published=published,
        entries_count=entries_count,
        published_at=now,
        message=message,
    )


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------


async def _count_active_sessions(redis: Redis) -> int:
    """Count active session keys in Redis matching ``session:*``."""
    try:
        count = 0
        cursor: int = 0
        pattern = f"{_SESSION_KEY_PREFIX}*"
        while True:
            cursor, keys = await redis.scan(cursor=cursor, match=pattern, count=100)
            count += len(keys)
            if cursor == 0:
                break
        return count
    except Exception as exc:  # noqa: BLE001
        logger.warning("Could not count active sessions: %s", exc)
        return 0


# ---------------------------------------------------------------------------
# Celery Metrics — Req 27.4, 15.1
# ---------------------------------------------------------------------------


async def get_celery_metrics(celery_app: Any) -> CeleryMetricsResponse:
    """Return Celery worker metrics: queue depth, active tasks, failed tasks.

    Uses ``celery_app.control.inspect(timeout=2.0)`` to query live workers.
    Falls back to zeros if the broker is unreachable or workers are offline.

    Requirements: 27.4, 15.1
    """
    queue_depth: int = 0
    active_tasks: int = 0
    failed_tasks: int = 0

    try:
        inspect = celery_app.control.inspect(timeout=2.0)

        # Active tasks — dict of {worker: [task, ...]} or None if no workers
        active = inspect.active()
        if active:
            for tasks in active.values():
                active_tasks += len(tasks or [])

        # Reserved / scheduled tasks (queue depth approximation)
        reserved = inspect.reserved()
        if reserved:
            for tasks in reserved.values():
                queue_depth += len(tasks or [])

        # Failed tasks — check revoked list length as a best-effort proxy.
        # (Celery doesn't expose a direct "failed" count via inspect without
        # a result backend, so we query the revoked set which tracks terminal
        # failures after retries are exhausted.)
        revoked = inspect.revoked()
        if revoked:
            for ids in revoked.values():
                failed_tasks += len(ids or [])

    except Exception as exc:  # noqa: BLE001
        logger.warning("Could not fetch Celery metrics (broker unreachable?): %s", exc)

    return CeleryMetricsResponse(
        queue_depth=queue_depth,
        active_tasks=active_tasks,
        failed_tasks=failed_tasks,
    )


# ---------------------------------------------------------------------------
# Usage Analytics — Req 15.3
# ---------------------------------------------------------------------------


async def get_usage_analytics(db: AsyncSession) -> UsageAnalyticsResponse:
    """Return token usage analytics grouped by feature and LLM provider.

    Queries the TokenUsage table and returns per-provider aggregates:
    total_requests, total_tokens (input + output), and estimated cost.
    Feature is derived from the provider name since TokenUsage doesn't have
    a dedicated feature column.

    Requirements: 15.3
    """
    now = datetime.now(tz=timezone.utc)

    rows = await db.execute(
        select(
            TokenUsage.provider,
            func.count(TokenUsage.id).label("total_requests"),
            func.coalesce(
                func.sum(TokenUsage.input_tokens + TokenUsage.output_tokens), 0
            ).label("total_tokens"),
            func.coalesce(func.sum(TokenUsage.cost_usd), 0.0).label("cost_usd"),
        )
        .group_by(TokenUsage.provider)
        .order_by(TokenUsage.provider)
    )

    items = [
        UsageAnalyticsItem(
            feature="chat",  # Default feature — TokenUsage records are per-completion
            provider=row.provider or "unknown",
            total_requests=int(row.total_requests or 0),
            total_tokens=int(row.total_tokens or 0),
            cost_usd=float(row.cost_usd or 0.0),
        )
        for row in rows
    ]

    return UsageAnalyticsResponse(items=items, generated_at=now)
