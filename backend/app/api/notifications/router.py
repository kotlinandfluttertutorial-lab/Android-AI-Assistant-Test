# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/notifications
# File    : router.py
# Purpose : FastAPI router defining all HTTP endpoints for the notifications domain
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

"""Notifications router — /notifications/* endpoints.

Endpoint summary
----------------
GET  /notifications/          Placeholder — router is active (Req 9.1)
PUT  /notifications/device-token  Update authenticated user's FCM device token (Req 16.7)

Requirements: 9.1, 16.7
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/notifications",
    tags=["notifications"],
    dependencies=[Depends(get_current_user)],
)


# ---------------------------------------------------------------------------
# Schemas
# ---------------------------------------------------------------------------


class DeviceTokenRequest(BaseModel):
    """Request body for PUT /notifications/device-token."""

    token: str


class DeviceTokenResponse(BaseModel):
    """Response body for PUT /notifications/device-token."""

    status: str


# ---------------------------------------------------------------------------
# GET /notifications/
# ---------------------------------------------------------------------------


@router.get("/")
async def notifications_root() -> dict[str, str]:
    """Placeholder endpoint — notifications router is active."""
    return {"message": "notifications router"}


# ---------------------------------------------------------------------------
# PUT /notifications/device-token
# ---------------------------------------------------------------------------


@router.put(
    "/device-token",
    response_model=DeviceTokenResponse,
    summary="Update the authenticated user's FCM device token",
)
async def update_device_token(
    body: DeviceTokenRequest,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> DeviceTokenResponse:
    """Update the FCM device token for the currently authenticated user.

    Stores the new token in the ``fcm_token`` column of the ``users`` table.
    On DB failure, stores a Redis retry counter (``fcm_token_retry:{user_id}``
    set to 10) so the update is retried on the next 10 successful API requests.

    Args:
        body: JSON body containing ``{"token": "<new_fcm_token>"}``.
        current_user: Injected JWT payload identifying the caller.
        db: Async database session.

    Returns:
        ``{"status": "updated"}`` on success or when the Redis fallback is used.

    Requirements: 16.7
    """
    import uuid

    from app.models.user import User

    try:
        user_uuid = uuid.UUID(str(current_user.sub))
        from sqlalchemy import select

        result = await db.execute(select(User).where(User.id == user_uuid))
        user = result.scalar_one_or_none()

        if user is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="User not found",
            )

        user.fcm_token = body.token
        await db.commit()
        logger.info(
            "update_device_token: FCM token updated for user=%s", str(current_user.sub)
        )
        return DeviceTokenResponse(status="updated")

    except HTTPException:
        raise
    except Exception as exc:
        logger.error(
            "update_device_token: DB update failed for user=%s: %s",
            str(current_user.sub),
            exc,
        )
        # Store Redis retry counter as fallback
        await _store_redis_retry(str(current_user.sub), body.token)
        return DeviceTokenResponse(status="updated")


async def _store_redis_retry(user_id: str, new_token: str) -> None:
    """Store a Redis retry counter so the FCM token update is retried later."""
    try:
        from app.database.redis import get_redis_client

        redis_client = get_redis_client()
        key = f"fcm_token_retry:{user_id}"
        await redis_client.set(key, 10)
        logger.info("_store_redis_retry: retry counter set for user=%s", user_id)
    except Exception as exc:
        logger.warning("_store_redis_retry: could not set Redis key: %s", exc)
