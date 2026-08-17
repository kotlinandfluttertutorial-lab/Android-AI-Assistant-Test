# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : workers
# File    : notification_worker.py
# Purpose : notification_worker — workers module
#
# Architecture Layer : Celery Worker
# Pattern Used       : Celery Worker Task
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Celery worker tasks for push notifications.

Tasks
-----
- ``send_message_delivery_notification_task`` — legacy task: notify user
  when a previously-failed queued message is delivered (Req 16.2).
- ``send_push_notification`` — general-purpose FCM push notification task
  with automatic retry and failure logging (Req 16.1, 16.2, 16.5, 16.6, 16.7).
- ``refresh_device_token`` — update a user's FCM device token in the database;
  stores a Redis retry counter on DB failure (Req 16.7).

Requirements: 16.1, 16.2, 16.5, 16.6, 16.7
"""

from __future__ import annotations

import asyncio
import logging

from celery.exceptions import MaxRetriesExceededError

# Import AsyncSessionLocal at module level so tests can patch it cleanly.
from app.database import AsyncSessionLocal
from app.workers.celery_app import celery_app

logger = logging.getLogger(__name__)

# Redis key pattern for FCM token retry counter
_FCM_RETRY_KEY_PREFIX = "fcm_token_retry:"


# ---------------------------------------------------------------------------
# send_push_notification
# ---------------------------------------------------------------------------


@celery_app.task(
    bind=True,
    name="app.workers.notification_worker.send_push_notification",
    autoretry_for=(Exception,),
    max_retries=3,
    retry_backoff=True,
    default_retry_delay=5,
)
def send_push_notification(  # type: ignore[misc]
    self,
    user_id: str,
    title: str,
    body: str,
    data: dict[str, str] | None = None,
) -> dict[str, str]:
    """Celery task: send a generic FCM push notification to a user.

    Args:
        user_id: String UUID of the target user.
        title:   Notification title.
        body:    Notification body text.
        data:    Optional dict of extra key-value pairs to include in the FCM
                 data payload.

    Returns:
        ``{"status": "sent", "user_id": user_id}`` on success.
        ``{"status": "skipped", "reason": "firebase_not_configured"}`` when
        Firebase credentials are absent.

    Requirements: 16.1, 16.2, 16.5, 16.6
    """
    return asyncio.run(
        _run_send_push_notification(self, user_id, title, body, data or {})
    )


async def _run_send_push_notification(
    task: object,
    user_id: str,
    title: str,
    body: str,
    data: dict[str, str],
) -> dict[str, str]:
    """Async implementation of the push notification dispatch."""
    from app.config.settings import get_settings

    settings = get_settings()
    credentials_path = settings.FIREBASE_CREDENTIALS_PATH

    if not credentials_path:
        logger.warning(
            "send_push_notification: FIREBASE_CREDENTIALS_PATH not set; skipping."
        )
        return {"status": "skipped", "reason": "firebase_not_configured"}

    def _send() -> None:
        import firebase_admin
        from firebase_admin import credentials, messaging

        if not firebase_admin._apps:
            cred = credentials.Certificate(credentials_path)
            firebase_admin.initialize_app(cred)

        topic = f"user_{user_id}"
        message = messaging.Message(
            topic=topic,
            notification=messaging.Notification(title=title, body=body),
            data=data,
        )
        messaging.send(message)
        logger.info("send_push_notification: FCM message sent to user=%s", user_id)

    try:
        await asyncio.to_thread(_send)
        return {"status": "sent", "user_id": user_id}
    except MaxRetriesExceededError:
        # All retries exhausted — write a failure record to the DB
        logger.error(
            "send_push_notification: max retries exceeded for user=%s", user_id
        )
        await _log_push_failure(user_id, title, body)
        return {"status": "failed", "user_id": user_id}


async def _log_push_failure(user_id: str, title: str, body: str) -> None:
    """Write a push notification failure record to the error_log table."""
    try:
        from sqlalchemy import text

        async with AsyncSessionLocal() as db:
            await db.execute(
                text(
                    "INSERT INTO error_log (user_id, error_type, message) "
                    "VALUES (:user_id, 'push_notification_failed', :message)"
                ),
                {"user_id": user_id, "message": f"Push failed: {title} — {body}"},
            )
            await db.commit()
    except Exception as exc:
        logger.warning("_log_push_failure: could not write to error_log: %s", exc)


# ---------------------------------------------------------------------------
# refresh_device_token
# ---------------------------------------------------------------------------


@celery_app.task(
    bind=True,
    name="app.workers.notification_worker.refresh_device_token",
    max_retries=3,
    default_retry_delay=5,
)
def refresh_device_token(  # type: ignore[misc]
    self, user_id: str, old_token: str, new_token: str
) -> dict[str, str]:
    """Celery task: update a user's FCM device token in the database.

    On DB failure, stores a retry counter in Redis key
    ``fcm_token_retry:{user_id}`` set to 10 so the token update is retried
    on the next 10 successful API requests.

    Args:
        user_id:   String UUID of the user.
        old_token: The previous FCM device token (for audit/logging).
        new_token: The new FCM device token to persist.

    Returns:
        ``{"status": "updated", "user_id": user_id}`` on success.

    Requirements: 16.7
    """
    return asyncio.run(_run_refresh_device_token(self, user_id, old_token, new_token))


async def _run_refresh_device_token(
    task: object, user_id: str, old_token: str, new_token: str
) -> dict[str, str]:
    """Async implementation of the FCM token refresh."""
    import uuid

    from sqlalchemy import select

    from app.models.user import User

    try:
        user_uuid = uuid.UUID(user_id)
        async with AsyncSessionLocal() as db:
            result = await db.execute(select(User).where(User.id == user_uuid))
            user = result.scalar_one_or_none()
            if user is not None:
                user.fcm_token = new_token
                await db.commit()
                logger.info(
                    "refresh_device_token: updated FCM token for user=%s", user_id
                )
            else:
                logger.warning("refresh_device_token: user=%s not found in DB", user_id)
        return {"status": "updated", "user_id": user_id}
    except Exception as exc:
        logger.error(
            "refresh_device_token: DB update failed for user=%s: %s", user_id, exc
        )
        # Store retry counter in Redis so the token update is retried later
        await _set_token_retry_counter(user_id, new_token)
        return {"status": "retry_scheduled", "user_id": user_id}


async def _set_token_retry_counter(user_id: str, new_token: str) -> None:
    """Set Redis retry counter for FCM token update."""
    try:
        from app.database.redis import get_redis_client

        redis_client = get_redis_client()
        key = f"{_FCM_RETRY_KEY_PREFIX}{user_id}"
        # Store the retry count (10) so the token update is retried
        await redis_client.set(key, 10)
        logger.info(
            "_set_token_retry_counter: Redis retry counter set for user=%s", user_id
        )
    except Exception as exc:
        logger.warning(
            "_set_token_retry_counter: could not set Redis retry counter: %s", exc
        )


@celery_app.task(
    bind=True,
    name="app.workers.notification_worker.send_message_delivery_notification_task",
    max_retries=2,
    default_retry_delay=5,
)
def send_message_delivery_notification_task(  # type: ignore[misc]
    self,
    user_id: str,
    message_id: str,
    conversation_id: str,
) -> dict[str, str]:
    """Celery task: send an FCM push notification for a delivered queued message.

    Called when a previously-failed queued message is successfully delivered.
    This is a best-effort notification; failures are logged but not re-raised.

    Args:
        user_id: String UUID of the user to notify.
        message_id: String UUID of the delivered message.
        conversation_id: String UUID of the conversation the message belongs to.

    Returns:
        dict with ``status`` and relevant IDs.
    """
    try:
        asyncio.run(_send_delivery_notification(user_id, message_id, conversation_id))
        return {
            "status": "sent",
            "user_id": user_id,
            "message_id": message_id,
            "conversation_id": conversation_id,
        }
    except (
        Exception
    ) as exc:  # Best-effort: retry up to max_retries, then give up silently
        logger.warning(
            "send_message_delivery_notification_task: attempt %d failed for "
            "user=%s message=%s: %s",
            self.request.retries,
            user_id,
            message_id,
            exc,
        )
        try:
            raise self.retry(exc=exc, countdown=5, max_retries=2)
        except Exception:
            # All retries exhausted — log and swallow; never propagate
            logger.error(
                "send_message_delivery_notification_task: all retries exhausted "
                "for user=%s message=%s; notification not sent.",
                user_id,
                message_id,
            )
            return {
                "status": "failed",
                "user_id": user_id,
                "message_id": message_id,
                "conversation_id": conversation_id,
            }


async def _send_delivery_notification(
    user_id: str,
    message_id: str,
    conversation_id: str,
) -> None:
    """Async implementation of the FCM delivery notification.

    Uses the same Firebase initialisation pattern as rag_service.

    Args:
        user_id: String UUID of the user to notify.
        message_id: String UUID of the delivered message.
        conversation_id: String UUID of the conversation.
    """
    from app.config.settings import get_settings

    settings = get_settings()
    credentials_path = settings.FIREBASE_CREDENTIALS_PATH

    if not credentials_path:
        logger.debug(
            "FIREBASE_CREDENTIALS_PATH not set; skipping delivery push notification."
        )
        return

    def _send() -> None:
        import firebase_admin
        from firebase_admin import credentials, messaging

        if not firebase_admin._apps:
            cred = credentials.Certificate(credentials_path)
            firebase_admin.initialize_app(cred)

        topic = f"user_{user_id}_messages"
        message = messaging.Message(
            topic=topic,
            notification=messaging.Notification(
                title="Message Delivered",
                body="Your queued message was successfully delivered.",
            ),
            data={
                "message_id": message_id,
                "conversation_id": conversation_id,
                "event": "message_delivered",
            },
        )
        messaging.send(message)
        logger.info(
            "FCM delivery notification sent for user=%s message=%s conversation=%s",
            user_id,
            message_id,
            conversation_id,
        )

    await asyncio.to_thread(_send)
