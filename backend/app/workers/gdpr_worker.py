# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : workers
# File    : gdpr_worker.py
# Purpose : gdpr_worker — workers module
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

"""Celery workers for GDPR data-privacy operations.

Tasks
-----
- ``export_user_data_task``   — assemble a full JSON archive and store it in
                                ``job.result_payload``.
- ``delete_user_data_task``   — permanently remove all PostgreSQL rows and
                                ChromaDB embeddings for a user.

Requirements: 28.1, 28.2
"""

from __future__ import annotations

import asyncio
import logging

# Import AsyncSessionLocal at module level so tests can patch it cleanly.
# The import is deferred-safe: the database module only reads settings on
# first connection, not on import.
from app.database import AsyncSessionLocal
from app.workers.celery_app import celery_app

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Helper — serialise ORM row to plain dict
# ---------------------------------------------------------------------------


def _row_to_dict(obj) -> dict:
    """Convert a SQLAlchemy ORM instance to a JSON-serialisable dict.

    UUIDs and datetimes are converted to strings so the result can be stored
    in the JSONB ``result_payload`` column without further processing.
    """
    result = {}
    for col in obj.__table__.columns:
        value = getattr(obj, col.name)
        if value is None:
            result[col.name] = None
        elif hasattr(value, "isoformat"):
            result[col.name] = value.isoformat()
        else:
            result[col.name] = (
                str(value)
                if not isinstance(value, int | float | bool | str | list | dict)
                else value
            )
    return result


# ---------------------------------------------------------------------------
# export_user_data_task
# ---------------------------------------------------------------------------


@celery_app.task(
    bind=True,
    name="app.workers.gdpr_worker.export_user_data_task",
    max_retries=3,
)
def export_user_data_task(self, user_id: str, job_id: str) -> dict:
    """Celery task: assemble a full JSON archive of all user data.

    Stores the archive in ``job.result_payload`` and marks the job
    ``completed``.  On failure, marks the job ``failed``.

    Args:
        user_id: String UUID of the user whose data is being exported.
        job_id:  String UUID of the tracking Job row.

    Returns:
        dict with ``status`` and ``job_id``.

    Requirements: 28.1
    """
    return asyncio.get_event_loop().run_until_complete(
        _run_export(self, user_id, job_id)
    )


async def _run_export(task, user_id: str, job_id: str) -> dict:
    """Async implementation of the data export pipeline."""
    import uuid

    from sqlalchemy import select

    from app.models.calendar_event import CalendarEvent
    from app.models.conversation import Conversation
    from app.models.document import Document
    from app.models.habit import HabitDefinition, HabitEntry
    from app.models.job import JobStatus
    from app.models.memory import Memory
    from app.models.message import Message
    from app.models.note import Note
    from app.models.reminder import Reminder
    from app.models.todo_item import TodoItem
    from app.repositories.job_repository import JobRepository

    user_uuid = uuid.UUID(user_id)
    job_uuid = uuid.UUID(job_id)

    try:
        async with AsyncSessionLocal() as db:
            job_repo = JobRepository(db)

            # Mark job running
            await job_repo.update_status(job_uuid, JobStatus.running)
            await db.commit()

            # ---------------------------------------------------------------
            # Collect all data for the user
            # ---------------------------------------------------------------

            async def _fetch(model, filter_col="user_id"):
                result = await db.execute(
                    select(model).where(getattr(model, filter_col) == user_uuid)
                )
                return [_row_to_dict(row) for row in result.scalars().all()]

            conversations = await _fetch(Conversation)
            documents = await _fetch(Document)
            memories = await _fetch(Memory)
            notes = await _fetch(Note)
            todo_items = await _fetch(TodoItem)
            calendar_events = await _fetch(CalendarEvent)
            reminders = await _fetch(Reminder)
            habit_definitions = await _fetch(HabitDefinition)
            habit_entries = await _fetch(HabitEntry)

            # Messages need to be collected via conversation IDs
            conv_ids = [c["id"] for c in conversations]
            messages: list[dict] = []
            if conv_ids:
                msg_result = await db.execute(
                    select(Message).where(
                        Message.conversation_id.in_(
                            [uuid.UUID(cid) for cid in conv_ids]
                        )
                    )
                )
                messages = [_row_to_dict(row) for row in msg_result.scalars().all()]

            archive = {
                "user_id": user_id,
                "conversations": conversations,
                "messages": messages,
                "documents": documents,
                "memories": memories,
                "notes": notes,
                "todo_items": todo_items,
                "calendar_events": calendar_events,
                "reminders": reminders,
                "habit_definitions": habit_definitions,
                "habit_entries": habit_entries,
            }

            await job_repo.update_status(
                job_uuid,
                JobStatus.completed,
                result_payload=archive,
            )
            await db.commit()

        logger.info(
            "export_user_data_task: completed for user=%s job=%s", user_id, job_id
        )
        return {"status": "completed", "job_id": job_id}

    except Exception as exc:
        logger.error(
            "export_user_data_task: failed for user=%s job=%s: %s",
            user_id,
            job_id,
            exc,
        )
        try:
            async with AsyncSessionLocal() as db:
                from app.models.job import JobStatus
                from app.repositories.job_repository import (
                    JobRepository,
                )

                job_repo = JobRepository(db)
                await job_repo.update_status(
                    job_uuid,
                    JobStatus.failed,
                    error_message=str(exc),
                )
                await db.commit()
        except Exception as inner_exc:
            logger.error(
                "export_user_data_task: could not mark job failed: %s", inner_exc
            )
        return {"status": "failed", "job_id": job_id}


# ---------------------------------------------------------------------------
# delete_user_data_task
# ---------------------------------------------------------------------------


@celery_app.task(
    bind=True,
    name="app.workers.gdpr_worker.delete_user_data_task",
    max_retries=3,
)
def delete_user_data_task(self, user_id: str) -> dict:
    """Celery task: permanently delete all data for a user.

    Performs:
    1. Deletes ChromaDB memory and document embedding collections (best-effort).
    2. Deletes the User row from PostgreSQL (cascades to all related data).

    Args:
        user_id: String UUID of the user to delete.

    Returns:
        dict with ``status`` and ``user_id``.

    Requirements: 28.2
    """
    return asyncio.get_event_loop().run_until_complete(_run_delete(self, user_id))


async def _run_delete(task, user_id: str) -> dict:
    """Async implementation of the permanent user deletion pipeline."""
    import uuid

    user_uuid = uuid.UUID(user_id)

    # ------------------------------------------------------------------
    # Step 1 — delete ChromaDB collections (graceful degradation)
    # ------------------------------------------------------------------
    try:
        from app.config.settings import get_settings

        settings = get_settings()
        chroma_host = getattr(settings, "CHROMA_HOST", None) or getattr(
            settings, "CHROMADB_HOST", None
        )

        if chroma_host:
            import chromadb

            chroma_port = int(
                getattr(settings, "CHROMA_PORT", None)
                or getattr(settings, "CHROMADB_PORT", 8001)
            )
            client = chromadb.HttpClient(host=chroma_host, port=chroma_port)

            for collection_name in (
                f"memories_{user_id}",
                f"documents_{user_id}",
            ):
                try:
                    client.delete_collection(collection_name)
                    logger.info(
                        "delete_user_data_task: deleted ChromaDB collection %s",
                        collection_name,
                    )
                except Exception as chroma_exc:
                    logger.warning(
                        "delete_user_data_task: could not delete ChromaDB collection %s: %s",
                        collection_name,
                        chroma_exc,
                    )
        else:
            logger.debug(
                "delete_user_data_task: ChromaDB not configured, skipping embedding deletion."
            )
    except Exception as exc:
        logger.warning(
            "delete_user_data_task: ChromaDB cleanup failed for user=%s: %s",
            user_id,
            exc,
        )

    # ------------------------------------------------------------------
    # Step 2 — delete User row from PostgreSQL (cascade handles children)
    # ------------------------------------------------------------------
    try:
        from sqlalchemy import select

        from app.models.user import User

        async with AsyncSessionLocal() as db:
            result = await db.execute(select(User).where(User.id == user_uuid))
            user = result.scalar_one_or_none()
            if user is not None:
                await db.delete(user)
                await db.commit()
                logger.info(
                    "delete_user_data_task: deleted user=%s from PostgreSQL", user_id
                )
            else:
                logger.warning(
                    "delete_user_data_task: user=%s not found in PostgreSQL (already deleted?)",
                    user_id,
                )

        return {"status": "completed", "user_id": user_id}

    except Exception as exc:
        logger.error(
            "delete_user_data_task: failed to delete user=%s from PostgreSQL: %s",
            user_id,
            exc,
        )
        return {"status": "failed", "user_id": user_id}
