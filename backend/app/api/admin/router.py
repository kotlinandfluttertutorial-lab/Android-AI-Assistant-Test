# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/admin
# File    : router.py
# Purpose : FastAPI router defining all HTTP endpoints for the admin domain
#
# Architecture Layer : API Router
# Pattern Used       : FastAPI Router
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Admin router — /admin/* endpoints.

All endpoints in this router require the ``admin`` role.
- Missing / invalid JWT → HTTP 401 (raised by ``get_current_user`` dependency)
- Valid JWT but non-admin role → HTTP 403 (raised by ``require_admin`` dependency)

Endpoint summary
----------------
GET  /admin/metrics               Real-time platform metrics (Req 15.1)
GET  /admin/users                 Paginated, searchable user list (Req 15.2)
PATCH /admin/users/{id}           Promote / demote / deactivate (Req 15.2, 15.4)
GET  /admin/audit-logs            Paginated, filterable audit log (Req 15.5)
GET  /admin/errors                Top-10 error types in 24 h (Req 15.6)
GET  /admin/feedback              Paginated feedback list (Req 15.7)
POST /admin/feedback/export       Export feedback as CSV (Req 15.7)
GET  /admin/sessions              Real-time active session list (Req 15.9)
GET  /admin/remote-config         List remote config entries (Req 15.8)
PATCH /admin/remote-config/{key}  Update a config key (Req 15.8)
POST /admin/remote-config/publish Push config to Firebase (Req 15.8)

Requirements: 15.1, 15.2, 15.3, 15.4, 15.5, 15.6, 15.7, 15.8, 15.9
"""

from __future__ import annotations

import logging
import uuid
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from redis.asyncio import Redis
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.database.redis import get_redis
from app.schemas.admin import (
    ActiveSessionsResponse,
    CeleryMetricsResponse,
    ErrorSummaryResponse,
    MetricsResponse,
    PaginatedAuditLogsResponse,
    PaginatedFeedbackResponse,
    PaginatedUsersResponse,
    RemoteConfigEntry,
    RemoteConfigListResponse,
    RemoteConfigPublishResponse,
    RemoteConfigUpdateRequest,
    UsageAnalyticsResponse,
    UserUpdateRequest,
    UserUpdateResponse,
)
from app.schemas.privacy import (
    EpsilonResponse,
    EpsilonUpdateRequest,
    PrivacyBudgetResponse,
    UserBudgetEntry,
)
from app.security.rbac import require_admin
from app.services import admin_service

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/admin",
    tags=["admin"],
    # All admin endpoints require the admin role; missing/invalid JWT → 401,
    # wrong role → 403 (both handled automatically by the dependency chain).
    dependencies=[Depends(require_admin)],
)


# ---------------------------------------------------------------------------
# GET /admin/providers/keys
# ---------------------------------------------------------------------------


@router.get(
    "/providers/keys",
    summary="API key configuration status for LLM providers",
)
async def get_provider_key_status() -> list[dict]:
    """Return the configured/not-configured status for each LLM provider API key.

    **Never** returns the actual key values — only boolean ``configured`` flags.
    This allows admins to verify which providers are active without exposing
    secret credentials.

    Returns:
        List of ``{"provider": str, "configured": bool}`` objects.

    Requirements: 9.10
    """
    from app.config.settings import get_settings

    settings = get_settings()

    providers = [
        {"provider": "openai", "configured": bool(settings.OPENAI_API_KEY)},
        {"provider": "gemini", "configured": bool(settings.GEMINI_API_KEY)},
        {"provider": "anthropic", "configured": bool(settings.ANTHROPIC_API_KEY)},
        {"provider": "ollama", "configured": bool(settings.OLLAMA_BASE_URL)},
    ]
    return providers


# ---------------------------------------------------------------------------
# GET /admin/metrics
# ---------------------------------------------------------------------------


@router.get(
    "/metrics",
    response_model=MetricsResponse,
    summary="Real-time platform metrics",
)
async def get_metrics(
    db: AsyncSession = Depends(get_db),
    redis: Redis = Depends(get_redis),
) -> MetricsResponse:
    """Return real-time platform metrics.

    Includes: active users, messages/hour, total token consumption,
    per-provider cost breakdown, and hourly error rate.

    Requirements: 15.1
    """
    return await admin_service.get_metrics(db, redis)


# ---------------------------------------------------------------------------
# GET /admin/users
# ---------------------------------------------------------------------------


@router.get(
    "/users",
    response_model=PaginatedUsersResponse,
    summary="Paginated, searchable user list",
)
async def list_users(
    page: int = Query(default=1, ge=1, description="Page number (1-based)"),
    page_size: int = Query(default=20, ge=1, le=100, description="Items per page"),
    search: str | None = Query(
        default=None, description="Filter by email or display_name"
    ),
    db: AsyncSession = Depends(get_db),
) -> PaginatedUsersResponse:
    """Return a paginated list of all users.

    Optionally filter by ``search`` (case-insensitive match on email and
    display_name).

    Requirements: 15.2
    """
    return await admin_service.list_users(
        db, page=page, page_size=page_size, search=search
    )


# ---------------------------------------------------------------------------
# PATCH /admin/users/{user_id}
# ---------------------------------------------------------------------------


@router.patch(
    "/users/{user_id}",
    response_model=UserUpdateResponse,
    summary="Promote, demote, or deactivate a user",
)
async def update_user(
    user_id: uuid.UUID,
    body: UserUpdateRequest,
    db: AsyncSession = Depends(get_db),
    redis: Redis = Depends(get_redis),
) -> UserUpdateResponse:
    """Apply an admin action to a user.

    **Actions:** ``promote`` (user→premium), ``demote`` (premium→user),
    ``make_admin`` (→admin), ``remove_admin`` (admin→user),
    ``deactivate``, ``reactivate``.

    On ``deactivate``: immediately revokes all active refresh tokens and sets
    a Redis force-logout marker so in-flight JWTs are rejected.

    Requirements: 15.2, 15.4
    """
    try:
        return await admin_service.update_user(db, redis, user_id, body.action)
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(exc),
        ) from exc


# ---------------------------------------------------------------------------
# GET /admin/audit-logs
# ---------------------------------------------------------------------------


@router.get(
    "/audit-logs",
    response_model=PaginatedAuditLogsResponse,
    summary="Paginated, filterable audit log",
)
async def get_audit_logs(
    page: int = Query(default=1, ge=1),
    page_size: int = Query(default=20, ge=1, le=100),
    user_id: uuid.UUID | None = Query(default=None, description="Filter by user UUID"),
    event_type: str | None = Query(
        default=None,
        description="Filter by event type: login, logout, token_refresh, failed_login, mcp_invoke",
    ),
    date_from: datetime | None = Query(
        default=None, description="ISO-8601 start datetime (UTC)"
    ),
    date_to: datetime | None = Query(
        default=None, description="ISO-8601 end datetime (UTC)"
    ),
    db: AsyncSession = Depends(get_db),
) -> PaginatedAuditLogsResponse:
    """Return a paginated audit log.

    Supports filtering by user_id, event_type, and date range.

    Requirements: 15.5
    """
    return await admin_service.get_audit_logs(
        db,
        page=page,
        page_size=page_size,
        user_id=user_id,
        event_type=event_type,
        date_from=date_from,
        date_to=date_to,
    )


# ---------------------------------------------------------------------------
# GET /admin/errors
# ---------------------------------------------------------------------------


@router.get(
    "/errors",
    response_model=ErrorSummaryResponse,
    summary="Top-10 error types in last 24 hours",
)
async def get_errors(
    db: AsyncSession = Depends(get_db),
) -> ErrorSummaryResponse:
    """Return the top-10 most frequent error types in the last 24 hours.

    Each entry includes count, last-seen timestamp, a sample message, and the
    first 500 characters of the most recent stack trace.

    Requirements: 15.6
    """
    return await admin_service.get_error_summary(db)


# ---------------------------------------------------------------------------
# GET /admin/feedback
# ---------------------------------------------------------------------------


@router.get(
    "/feedback",
    response_model=PaginatedFeedbackResponse,
    summary="Paginated user feedback list",
)
async def list_feedback(
    page: int = Query(default=1, ge=1),
    page_size: int = Query(default=20, ge=1, le=100),
    category: str | None = Query(default=None, description="Filter by category tag"),
    db: AsyncSession = Depends(get_db),
) -> PaginatedFeedbackResponse:
    """Return a paginated list of user-submitted feedback items.

    Requirements: 15.7
    """
    return await admin_service.list_feedback(
        db, page=page, page_size=page_size, category=category
    )


# ---------------------------------------------------------------------------
# POST /admin/feedback/export
# ---------------------------------------------------------------------------


@router.post(
    "/feedback/export",
    summary="Export all feedback as CSV",
    responses={
        200: {
            "content": {"text/csv": {}},
            "description": "CSV file containing all feedback records",
        }
    },
)
async def export_feedback(
    db: AsyncSession = Depends(get_db),
) -> StreamingResponse:
    """Export all user feedback as a downloadable CSV file.

    The CSV contains columns: id, user_id, category, content, created_at.

    Requirements: 15.7
    """
    csv_content = await admin_service.export_feedback_csv(db)

    def _iter_csv():
        yield csv_content

    return StreamingResponse(
        _iter_csv(),
        media_type="text/csv",
        headers={"Content-Disposition": "attachment; filename=feedback_export.csv"},
    )


# ---------------------------------------------------------------------------
# GET /admin/sessions
# ---------------------------------------------------------------------------


@router.get(
    "/sessions",
    response_model=ActiveSessionsResponse,
    summary="Real-time active session list",
)
async def get_sessions(
    redis: Redis = Depends(get_redis),
) -> ActiveSessionsResponse:
    """Return the list of currently active user sessions.

    Each entry includes device type, geographic region, current feature in
    use, and session duration.  Session data is read from Redis.

    Requirements: 15.9
    """
    return await admin_service.get_active_sessions(redis)


# ---------------------------------------------------------------------------
# GET /admin/remote-config
# ---------------------------------------------------------------------------


@router.get(
    "/remote-config",
    response_model=RemoteConfigListResponse,
    summary="List all remote config entries",
)
async def get_remote_config(
    redis: Redis = Depends(get_redis),
) -> RemoteConfigListResponse:
    """Return all Firebase Remote Config key-value entries.

    Values are read from Redis; the last publish timestamp is included.

    Requirements: 15.8
    """
    return await admin_service.get_remote_config(redis)


# ---------------------------------------------------------------------------
# PATCH /admin/remote-config/{key}
# ---------------------------------------------------------------------------


@router.patch(
    "/remote-config/{key}",
    response_model=RemoteConfigEntry,
    summary="Update a remote config key",
)
async def update_remote_config(
    key: str,
    body: RemoteConfigUpdateRequest,
    redis: Redis = Depends(get_redis),
) -> RemoteConfigEntry:
    """Set or update the value of a remote config key.

    Changes are staged in Redis and only pushed to Firebase when
    ``POST /admin/remote-config/publish`` is called.

    Requirements: 15.8
    """
    return await admin_service.update_remote_config_key(
        redis, key, body.value, body.description
    )


# ---------------------------------------------------------------------------
# POST /admin/remote-config/publish
# ---------------------------------------------------------------------------


@router.post(
    "/remote-config/publish",
    response_model=RemoteConfigPublishResponse,
    summary="Publish staged remote config to Firebase",
)
async def publish_remote_config(
    redis: Redis = Depends(get_redis),
) -> RemoteConfigPublishResponse:
    """Push all staged remote config entries to Firebase Remote Config.

    If the firebase-admin SDK is not installed or FIREBASE_REMOTE_CONFIG_ENABLED
    is False, entries remain staged in Redis (no redeployment required).

    Requirements: 15.8
    """
    return await admin_service.publish_remote_config(redis)


# ---------------------------------------------------------------------------
# GET /admin/celery-metrics
# ---------------------------------------------------------------------------


@router.get(
    "/celery-metrics",
    response_model=CeleryMetricsResponse,
    summary="Celery worker metrics — queue depth, active tasks, failed tasks",
)
async def get_celery_metrics() -> CeleryMetricsResponse:
    """Return current Celery worker metrics.

    Exposes: queue depth (pending tasks), active tasks (currently executing),
    and failed tasks (permanently failed after retries exhausted).

    Falls back to all zeros if the broker is unreachable or no workers are
    online so that admin dashboards remain available even in degraded state.

    Requirements: 27.4, 15.1
    """
    from app.workers.celery_app import celery_app

    return await admin_service.get_celery_metrics(celery_app)


# ---------------------------------------------------------------------------
# GET /admin/usage-analytics
# ---------------------------------------------------------------------------


@router.get(
    "/usage-analytics",
    response_model=UsageAnalyticsResponse,
    summary="Token usage analytics grouped by feature and provider",
)
async def get_usage_analytics(
    db: AsyncSession = Depends(get_db),
) -> UsageAnalyticsResponse:
    """Return token usage analytics broken down by feature and LLM provider.

    Returns per-provider request counts, total tokens consumed, and estimated
    USD cost aggregated from the token_usage table.

    Requirements: 15.3
    """
    return await admin_service.get_usage_analytics(db)


# ---------------------------------------------------------------------------
# PUT /admin/privacy/epsilon
# ---------------------------------------------------------------------------


@router.put(
    "/privacy/epsilon",
    response_model=EpsilonResponse,
    summary="Update differential-privacy epsilon",
)
async def update_epsilon(
    body: EpsilonUpdateRequest,
    redis: Redis = Depends(get_redis),
) -> EpsilonResponse:
    """Set the global differential-privacy epsilon (ε) used for Laplace noise injection.

    The new value is stored in Redis key ``"dp:epsilon"`` and immediately
    picked up by subsequent ``store_memory`` calls without requiring a restart.

    Args:
        body: ``EpsilonUpdateRequest`` containing the new epsilon value.
        redis: Async Redis client (injected by FastAPI).

    Returns:
        ``EpsilonResponse`` with the accepted epsilon value and mechanism name.

    Raises:
        HTTP 422: If ``epsilon`` is outside the valid range [0.1, 10.0].

    Requirements: 37.2, 37.6
    """
    if not (0.1 <= body.epsilon <= 10.0):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"epsilon must be between 0.1 and 10.0, got {body.epsilon}",
        )
    await redis.set("dp:epsilon", str(body.epsilon))
    return EpsilonResponse(epsilon=body.epsilon, mechanism="Laplace")


# ---------------------------------------------------------------------------
# GET /admin/privacy/budget
# ---------------------------------------------------------------------------


@router.get(
    "/privacy/budget",
    response_model=PrivacyBudgetResponse,
    summary="Per-user differential-privacy budget consumption",
)
async def get_privacy_budgets(
    redis: Redis = Depends(get_redis),
) -> PrivacyBudgetResponse:
    """Return cumulative differential-privacy budget consumed per user.

    Budgets are tracked in Redis keys of the form ``"privacy_budget:{user_id}"``.
    Each key is incremented by the epsilon value used whenever a memory is stored
    for that user.

    Args:
        redis: Async Redis client (injected by FastAPI).

    Returns:
        ``PrivacyBudgetResponse`` with a list of per-user budget entries.

    Requirements: 37.7
    """
    keys: list[str] = await redis.keys("privacy_budget:*")

    budget_entries: list[UserBudgetEntry] = []
    for key in sorted(keys):
        raw = await redis.get(key)
        if raw is not None:
            # Key format: "privacy_budget:{user_id}"
            user_id_str = key.split("privacy_budget:", 1)[-1]
            try:
                consumed = float(raw)
            except ValueError:
                consumed = 0.0
            budget_entries.append(
                UserBudgetEntry(user_id=user_id_str, consumed_budget=consumed)
            )

    return PrivacyBudgetResponse(
        budgets=budget_entries,
        total_users_tracked=len(budget_entries),
    )


# ---------------------------------------------------------------------------
# POST /admin/rag/reindex
# ---------------------------------------------------------------------------


class ReindexResponse(BaseModel):
    """Response body for POST /admin/rag/reindex."""

    status: str
    files_indexed: int
    chunks_indexed: int
    collection_size: int
    errors: list[str]


@router.post(
    "/rag/reindex",
    summary="Re-index the DevOps knowledge base into ChromaDB",
    description=(
        "Ingests all Markdown and text files from the knowledge/ folder into the "
        "shared 'devops_knowledge' ChromaDB collection. "
        "Idempotent — safe to call multiple times. "
        "Use after adding new knowledge documents or after a Cloud Run deployment "
        "that wiped the ephemeral ChromaDB filesystem. "
        "Requires admin role."
    ),
)
async def reindex_knowledge_base() -> ReindexResponse:
    """Re-index all documents from knowledge/ into the devops_knowledge ChromaDB collection.

    This endpoint is the HTTP equivalent of running
    ``python backend/scripts/seed_knowledge.py`` from the CLI.

    Returns a summary of files indexed, chunks created, and any errors.
    """
    from pathlib import Path as _Path

    from scripts.seed_knowledge import KNOWLEDGE_DIR, seed_async

    result = await seed_async(knowledge_dir=_Path(KNOWLEDGE_DIR))

    return ReindexResponse(
        status=result.get("status", "unknown"),
        files_indexed=result.get("files", 0),
        chunks_indexed=result.get("chunks", 0),
        collection_size=result.get("collection_size", 0),
        errors=result.get("errors", []),
    )
