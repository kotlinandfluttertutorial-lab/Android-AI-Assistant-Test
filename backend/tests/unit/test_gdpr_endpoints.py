"""Unit tests for GDPR data-privacy endpoints and background workers.

Tests cover:
- POST /users/me/export returns 200 with job_id when authenticated
- POST /users/me/export returns 401 when unauthenticated
- DELETE /users/me returns 200 when email matches
- DELETE /users/me returns 400 when email does not match
- DELETE /users/me returns 401 when unauthenticated
- export_user_data_task assembles result_payload with all expected top-level keys
- delete_user_data_task calls db delete and ChromaDB delete

Requirements: 28.1, 28.2
"""

from __future__ import annotations

import os
import uuid
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

# Ensure env vars before any app imports
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

from app.security.jwt_handler import create_access_token

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_token(role: str = "user", user_id: uuid.UUID | None = None) -> str:
    uid = user_id or uuid.uuid4()
    token, _expires = create_access_token(user_id=uid, role=role)
    return token


def _make_user_mock(
    user_id: uuid.UUID | None = None,
    email: str = "user@example.com",
) -> MagicMock:
    uid = user_id or uuid.uuid4()
    mock = MagicMock()
    mock.id = uid
    mock.email = email
    mock.is_active = True
    return mock


def _make_job_mock(
    job_id: uuid.UUID | None = None, user_id: uuid.UUID | None = None
) -> MagicMock:
    jid = job_id or uuid.uuid4()
    uid = user_id or uuid.uuid4()
    mock = MagicMock()
    mock.id = jid
    mock.user_id = uid
    mock.job_type = "data_export"
    mock.status = "queued"
    return mock


# ---------------------------------------------------------------------------
# POST /users/me/export
# ---------------------------------------------------------------------------


class TestExportEndpoint:
    """Tests for POST /users/me/export."""

    @pytest.mark.asyncio
    async def test_export_authenticated_returns_job_id(self) -> None:
        """Authenticated request should return 200 with a job_id UUID."""

        user_id = uuid.uuid4()
        job_id = uuid.uuid4()
        token = _make_token(user_id=user_id)

        mock_job = _make_job_mock(job_id=job_id, user_id=user_id)

        with (
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
            patch(
                "app.repositories.job_repository.JobRepository.create",
                new_callable=AsyncMock,
                return_value=mock_job,
            ),
            patch("app.workers.gdpr_worker.export_user_data_task") as mock_task,
            patch("app.database.get_db") as mock_get_db,
        ):
            mock_db = AsyncMock()
            mock_db.commit = AsyncMock()
            mock_get_db.return_value.__aenter__ = AsyncMock(return_value=mock_db)
            mock_get_db.return_value.__aexit__ = AsyncMock(return_value=False)

            mock_celery_sig = MagicMock()
            mock_task.delay = MagicMock(return_value=mock_celery_sig)

            from app.api.users.router import export_user_data
            from app.security.jwt_handler import verify_access_token

            payload = verify_access_token(token)

            result = await export_user_data(
                current_user=payload,
                db=mock_db,
            )

            assert str(result.job_id) == str(job_id)
            assert result.estimated_completion is not None
            mock_task.delay.assert_called_once_with(str(user_id), str(job_id))

    @pytest.mark.asyncio
    async def test_export_unauthenticated_returns_401(self) -> None:
        """Missing token should result in HTTP 401."""
        from fastapi import HTTPException

        from app.security.dependencies import get_current_user

        with pytest.raises(HTTPException) as exc_info:
            # Pass None to simulate missing Authorization header
            await get_current_user(credentials=None)

        assert exc_info.value.status_code == 401


# ---------------------------------------------------------------------------
# DELETE /users/me
# ---------------------------------------------------------------------------


class TestDeleteEndpoint:
    """Tests for DELETE /users/me."""

    @pytest.mark.asyncio
    async def test_delete_email_matches_returns_200(self) -> None:
        """Correct email confirmation should schedule deletion and return 200."""
        from app.api.users.router import delete_user_account
        from app.schemas.users import AccountDeletionRequest
        from app.security.jwt_handler import verify_access_token

        user_id = uuid.uuid4()
        email = "user@example.com"
        token = _make_token(user_id=user_id)
        payload = verify_access_token(token)

        mock_user = _make_user_mock(user_id=user_id, email=email)
        mock_db = AsyncMock()
        mock_db.commit = AsyncMock()

        with (
            patch(
                "app.repositories.user_repository.UserRepository.get_by_id",
                new_callable=AsyncMock,
                return_value=mock_user,
            ),
            patch("app.workers.gdpr_worker.delete_user_data_task") as mock_task,
        ):
            mock_task.delay = MagicMock()

            body = AccountDeletionRequest(email=email)
            result = await delete_user_account(
                body=body,
                current_user=payload,
                db=mock_db,
            )

        assert "deletion" in result.message.lower() or "data" in result.message.lower()
        assert result.scheduled_at is not None
        assert result.estimated_completion is not None
        mock_task.delay.assert_called_once_with(str(user_id))

    @pytest.mark.asyncio
    async def test_delete_email_mismatch_returns_400(self) -> None:
        """Wrong email in the request body must return HTTP 400."""
        from fastapi import HTTPException

        from app.api.users.router import delete_user_account
        from app.schemas.users import AccountDeletionRequest
        from app.security.jwt_handler import verify_access_token

        user_id = uuid.uuid4()
        token = _make_token(user_id=user_id)
        payload = verify_access_token(token)

        mock_user = _make_user_mock(user_id=user_id, email="real@example.com")
        mock_db = AsyncMock()

        with patch(
            "app.repositories.user_repository.UserRepository.get_by_id",
            new_callable=AsyncMock,
            return_value=mock_user,
        ):
            body = AccountDeletionRequest(email="wrong@example.com")
            with pytest.raises(HTTPException) as exc_info:
                await delete_user_account(
                    body=body,
                    current_user=payload,
                    db=mock_db,
                )

        assert exc_info.value.status_code == 400
        assert "email confirmation" in exc_info.value.detail.lower()

    @pytest.mark.asyncio
    async def test_delete_unauthenticated_returns_401(self) -> None:
        """Missing token should result in HTTP 401."""
        from fastapi import HTTPException

        from app.security.dependencies import get_current_user

        with pytest.raises(HTTPException) as exc_info:
            await get_current_user(credentials=None)

        assert exc_info.value.status_code == 401

    @pytest.mark.asyncio
    async def test_delete_email_case_insensitive(self) -> None:
        """Email comparison should be case-insensitive."""
        from app.api.users.router import delete_user_account
        from app.schemas.users import AccountDeletionRequest
        from app.security.jwt_handler import verify_access_token

        user_id = uuid.uuid4()
        token = _make_token(user_id=user_id)
        payload = verify_access_token(token)

        mock_user = _make_user_mock(user_id=user_id, email="user@example.com")
        mock_db = AsyncMock()
        mock_db.commit = AsyncMock()

        with (
            patch(
                "app.repositories.user_repository.UserRepository.get_by_id",
                new_callable=AsyncMock,
                return_value=mock_user,
            ),
            patch("app.workers.gdpr_worker.delete_user_data_task") as mock_task,
        ):
            mock_task.delay = MagicMock()
            # Supply the email in uppercase — should still match
            body = AccountDeletionRequest(email="USER@EXAMPLE.COM")
            result = await delete_user_account(
                body=body,
                current_user=payload,
                db=mock_db,
            )

        assert result.scheduled_at is not None
        mock_task.delay.assert_called_once_with(str(user_id))


# ---------------------------------------------------------------------------
# Worker: export_user_data_task
# ---------------------------------------------------------------------------


class TestExportWorker:
    """Tests for the export_user_data_task Celery worker."""

    @pytest.mark.asyncio
    async def test_export_worker_assembles_all_expected_keys(self) -> None:
        """The assembled archive must contain all required top-level data keys."""
        user_id = str(uuid.uuid4())
        job_id = str(uuid.uuid4())

        # Build mock ORM rows with __table__.columns support
        def _make_orm_row(**kwargs):
            row = MagicMock()
            col_mocks = []
            for name, value in kwargs.items():
                col = MagicMock()
                col.name = name
                col_mocks.append(col)
                setattr(row, name, value)
            row.__table__ = MagicMock()
            row.__table__.columns = col_mocks
            return row

        conv_row = _make_orm_row(id=str(uuid.uuid4()), user_id=user_id, title="Test")
        msg_row = _make_orm_row(
            id=str(uuid.uuid4()),
            conversation_id=str(uuid.uuid4()),
            content="Hello",
        )
        doc_row = _make_orm_row(
            id=str(uuid.uuid4()), user_id=user_id, file_name="doc.pdf"
        )
        mem_row = _make_orm_row(id=str(uuid.uuid4()), user_id=user_id, content="memory")
        note_row = _make_orm_row(id=str(uuid.uuid4()), user_id=user_id, content="note")
        todo_row = _make_orm_row(id=str(uuid.uuid4()), user_id=user_id, title="todo")
        cal_row = _make_orm_row(id=str(uuid.uuid4()), user_id=user_id, title="event")
        rem_row = _make_orm_row(id=str(uuid.uuid4()), user_id=user_id, title="reminder")
        hab_def_row = _make_orm_row(id=str(uuid.uuid4()), user_id=user_id, name="habit")
        hab_entry_row = _make_orm_row(id=str(uuid.uuid4()), user_id=user_id)

        def _scalars_result(rows):
            m = MagicMock()
            m.scalars.return_value.all.return_value = rows
            return m

        # Map model class name → rows to return
        model_rows = {
            "Conversation": [conv_row],
            "Document": [doc_row],
            "Memory": [mem_row],
            "Note": [note_row],
            "TodoItem": [todo_row],
            "CalendarEvent": [cal_row],
            "Reminder": [rem_row],
            "HabitDefinition": [hab_def_row],
            "HabitEntry": [hab_entry_row],
            "Message": [msg_row],
        }

        captured_payload: dict = {}

        async def _fake_execute(stmt, *args, **kwargs):
            # Inspect the statement to figure out which model it queries
            try:
                entity = stmt.column_descriptions[0]["entity"]
                name = entity.__name__
                rows = model_rows.get(name, [])
            except (AttributeError, IndexError, KeyError):
                rows = [msg_row]

            m = MagicMock()
            m.scalars.return_value.all.return_value = rows
            return m

        async def _fake_update_status(
            job_uuid, status, *, result_payload=None, error_message=None, **kw
        ):
            if result_payload is not None:
                captured_payload.update(result_payload)
            mock_job = MagicMock()
            mock_job.status = status
            return mock_job

        mock_db = AsyncMock()
        mock_db.execute = AsyncMock(side_effect=_fake_execute)
        mock_db.commit = AsyncMock()
        mock_db.__aenter__ = AsyncMock(return_value=mock_db)
        mock_db.__aexit__ = AsyncMock(return_value=False)

        from app.workers import gdpr_worker

        with (
            patch("app.workers.gdpr_worker.AsyncSessionLocal") as mock_session_cls,
            patch(
                "app.repositories.job_repository.JobRepository.update_status",
                new_callable=AsyncMock,
                side_effect=_fake_update_status,
            ),
        ):
            mock_session_cls.return_value = mock_db

            mock_task = MagicMock()
            mock_task.request = MagicMock()

            result = await gdpr_worker._run_export(mock_task, user_id, job_id)

        assert result["status"] == "completed"

        # Verify all top-level keys are present in the captured payload
        expected_keys = {
            "user_id",
            "conversations",
            "messages",
            "documents",
            "memories",
            "notes",
            "todo_items",
            "calendar_events",
            "reminders",
            "habit_definitions",
            "habit_entries",
        }
        assert expected_keys.issubset(set(captured_payload.keys()))


# ---------------------------------------------------------------------------
# Worker: delete_user_data_task
# ---------------------------------------------------------------------------


class TestDeleteWorker:
    """Tests for the delete_user_data_task Celery worker."""

    @pytest.mark.asyncio
    async def test_delete_worker_calls_db_delete_and_chromadb(self) -> None:
        """Worker must delete the User row and attempt ChromaDB cleanup."""
        import sys

        user_id = str(uuid.uuid4())

        mock_user = MagicMock()
        mock_user.id = uuid.UUID(user_id)
        mock_user.email = "user@example.com"

        mock_db = AsyncMock()
        mock_db.delete = AsyncMock()
        mock_db.commit = AsyncMock()
        mock_db.__aenter__ = AsyncMock(return_value=mock_db)
        mock_db.__aexit__ = AsyncMock(return_value=False)

        execute_result = MagicMock()
        execute_result.scalar_one_or_none.return_value = mock_user
        mock_db.execute = AsyncMock(return_value=execute_result)

        mock_chroma_client = MagicMock()
        mock_chroma_client.delete_collection = MagicMock()

        # chromadb may not be installed in the test environment;
        # inject a stub module so the lazy import inside the worker succeeds.
        mock_chromadb = MagicMock()
        mock_chromadb.HttpClient = MagicMock(return_value=mock_chroma_client)

        from app.workers import gdpr_worker

        with (
            patch("app.workers.gdpr_worker.AsyncSessionLocal") as mock_session_cls,
            patch.dict(sys.modules, {"chromadb": mock_chromadb}),
        ):
            mock_session_cls.return_value = mock_db

            with patch("app.config.settings.get_settings") as mock_settings:
                settings_obj = MagicMock()
                settings_obj.CHROMA_HOST = "localhost"
                settings_obj.CHROMA_PORT = 8001
                mock_settings.return_value = settings_obj

                mock_task = MagicMock()
                result = await gdpr_worker._run_delete(mock_task, user_id)

        assert result["status"] == "completed"
        mock_db.delete.assert_called_once_with(mock_user)
        mock_db.commit.assert_called()
        mock_chroma_client.delete_collection.assert_called()

    @pytest.mark.asyncio
    async def test_delete_worker_succeeds_without_chromadb(self) -> None:
        """Worker must still delete the user even when ChromaDB is not configured."""
        user_id = str(uuid.uuid4())

        mock_user = MagicMock()
        mock_user.id = uuid.UUID(user_id)

        mock_db = AsyncMock()
        mock_db.delete = AsyncMock()
        mock_db.commit = AsyncMock()
        mock_db.__aenter__ = AsyncMock(return_value=mock_db)
        mock_db.__aexit__ = AsyncMock(return_value=False)

        execute_result = MagicMock()
        execute_result.scalar_one_or_none.return_value = mock_user
        mock_db.execute = AsyncMock(return_value=execute_result)

        from app.workers import gdpr_worker

        with patch("app.workers.gdpr_worker.AsyncSessionLocal") as mock_session_cls:
            mock_session_cls.return_value = mock_db

            with patch("app.config.settings.get_settings") as mock_settings:
                settings_obj = MagicMock()
                settings_obj.CHROMA_HOST = None
                settings_obj.CHROMADB_HOST = None
                mock_settings.return_value = settings_obj

                mock_task = MagicMock()
                result = await gdpr_worker._run_delete(mock_task, user_id)

        assert result["status"] == "completed"
        mock_db.delete.assert_called_once_with(mock_user)

    @pytest.mark.asyncio
    async def test_delete_worker_handles_missing_user_gracefully(self) -> None:
        """If the user was already deleted, the worker should not raise."""
        user_id = str(uuid.uuid4())

        mock_db = AsyncMock()
        mock_db.delete = AsyncMock()
        mock_db.commit = AsyncMock()
        mock_db.__aenter__ = AsyncMock(return_value=mock_db)
        mock_db.__aexit__ = AsyncMock(return_value=False)

        execute_result = MagicMock()
        execute_result.scalar_one_or_none.return_value = None  # User not found
        mock_db.execute = AsyncMock(return_value=execute_result)

        from app.workers import gdpr_worker

        with patch("app.workers.gdpr_worker.AsyncSessionLocal") as mock_session_cls:
            mock_session_cls.return_value = mock_db

            with patch("app.config.settings.get_settings") as mock_settings:
                settings_obj = MagicMock()
                settings_obj.CHROMA_HOST = None
                settings_obj.CHROMADB_HOST = None
                mock_settings.return_value = settings_obj

                mock_task = MagicMock()
                result = await gdpr_worker._run_delete(mock_task, user_id)

        assert result["status"] == "completed"
        mock_db.delete.assert_not_called()


# ---------------------------------------------------------------------------
# Schema validation
# ---------------------------------------------------------------------------


class TestGdprSchemas:
    """Smoke tests for GDPR Pydantic schemas."""

    def test_data_export_response_schema(self) -> None:
        from app.schemas.users import DataExportResponse

        jid = uuid.uuid4()
        resp = DataExportResponse(
            job_id=jid,
            estimated_completion="2025-01-01T00:00:00+00:00",
        )
        assert resp.job_id == jid
        assert "queued" in resp.message.lower() or "export" in resp.message.lower()

    def test_account_deletion_request_schema(self) -> None:
        from app.schemas.users import AccountDeletionRequest

        req = AccountDeletionRequest(email="  User@Example.COM  ")
        # str_strip_whitespace trims spaces
        assert req.email == "User@Example.COM"

    def test_account_deletion_response_schema(self) -> None:
        from app.schemas.users import AccountDeletionResponse

        now = datetime.now(tz=timezone.utc)
        resp = AccountDeletionResponse(
            scheduled_at=now,
            estimated_completion="2025-01-04T00:00:00+00:00",
        )
        assert resp.scheduled_at == now
        assert "deletion" in resp.message.lower() or "data" in resp.message.lower()
