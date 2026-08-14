"""Integration tests for the GDPR data-export and account-deletion flows.

Covers:
1. Data export enqueues Celery task and returns job ID
2. Data export requires authentication
3. Successful account deletion schedules Celery task
4. Account deletion fails if email does not match
5. Account deletion requires authentication
6. Account deletion returns 404 if user not found
7. Export archive covers all data types (worker task logic)
8. Deletion removes data for target user only, not other users
9. Deletion handles missing ChromaDB gracefully (no CHROMA_HOST set)

Requirements: 21.2
Cross-references: 28.1, 28.2
"""

from __future__ import annotations

import os
import sys
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

# ---------------------------------------------------------------------------
# Set required env vars BEFORE any app imports
# ---------------------------------------------------------------------------
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-key")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test-key")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")
os.environ.setdefault("LOKI_URL", "")
os.environ.setdefault("ENVIRONMENT", "development")

from app.api.users.router import router as users_router
from app.models.job import JobStatus
from app.security.jwt_handler import create_access_token

# ---------------------------------------------------------------------------
# Minimal FastAPI app — only the users router, no global middleware overhead
# ---------------------------------------------------------------------------

_app = FastAPI()
_app.include_router(users_router)

# ---------------------------------------------------------------------------
# Test data helpers
# ---------------------------------------------------------------------------

_USER_EMAIL = "user@example.com"


def _make_user(
    *,
    user_id: uuid.UUID | None = None,
    email: str = _USER_EMAIL,
) -> MagicMock:
    """Build a mock User ORM object."""
    user = MagicMock()
    user.id = user_id or uuid.uuid4()
    user.email = email
    return user


def _make_job(
    *,
    job_id: uuid.UUID | None = None,
    user_id: uuid.UUID | None = None,
    job_type: str = "data_export",
) -> MagicMock:
    """Build a mock Job ORM object."""
    job = MagicMock()
    job.id = job_id or uuid.uuid4()
    job.user_id = user_id or uuid.uuid4()
    job.job_type = job_type
    job.status = JobStatus.queued
    return job


def _make_token(user_id: uuid.UUID, role: str = "user") -> str:
    """Generate a valid JWT for use in Authorization headers."""
    return create_access_token(user_id=user_id, role=role)


def _auth_headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _delete(client: TestClient, url: str, *, body: dict, headers: dict):
    """Issue a DELETE request with a JSON body.

    ``TestClient`` (httpx-backed) exposes ``json=`` on ``client.request()``
    but not directly on ``client.delete()``.  Using the lower-level method is
    the portable solution.
    """
    return client.request("DELETE", url, json=body, headers=headers)


# ---------------------------------------------------------------------------
# FastAPI dependency overrides
# ---------------------------------------------------------------------------


def _override_get_db(mock_session: AsyncMock):
    """Return a FastAPI dependency override for get_db yielding mock_session."""

    async def _dep():
        try:
            yield mock_session
            await mock_session.commit()
        except Exception:
            await mock_session.rollback()
            raise
        finally:
            await mock_session.close()

    return _dep


def _make_mock_db_session() -> AsyncMock:
    """Return a minimal async DB session mock."""
    session = AsyncMock()
    session.add = MagicMock()
    session.flush = AsyncMock()
    session.commit = AsyncMock()
    session.rollback = AsyncMock()
    session.close = AsyncMock()
    return session


# ===========================================================================
# Scenario 1–2 — POST /users/me/export
# ===========================================================================


class TestDataExportEndpoint:
    """HTTP-level tests for POST /users/me/export.

    Requirements: 28.1, 21.2
    """

    def test_export_enqueues_celery_task_and_returns_job_id(self) -> None:
        """POST /users/me/export with valid JWT enqueues task and returns 200 with job_id.

        Verifies:
        - HTTP 200 is returned.
        - Response contains a valid UUID ``job_id``.
        - Response contains a non-empty ``estimated_completion`` string.
        - ``export_user_data_task.delay`` is called exactly once with
          ``(str(user_id), str(job_id))``.

        Requirements: 28.1, 21.2
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id)
        mock_db = _make_mock_db_session()
        job = _make_job(user_id=user_id)

        from app.database import get_db

        _app.dependency_overrides = {get_db: _override_get_db(mock_db)}

        try:
            with (
                patch("app.api.users.router.JobRepository") as MockJobRepo,
                # The router does a local import of the task inside the handler;
                # patch at the source module so the imported name is replaced.
                patch(
                    "app.workers.gdpr_worker.export_user_data_task",
                    new_callable=MagicMock,
                ) as mock_task,
                patch("app.security.dependencies._is_jti_revoked", return_value=False),
            ):
                MockJobRepo.return_value.create = AsyncMock(return_value=job)

                with TestClient(_app) as client:
                    resp = client.post(
                        "/users/me/export",
                        headers=_auth_headers(token),
                    )
        finally:
            _app.dependency_overrides = {}

        assert resp.status_code == 200
        body = resp.json()
        assert "job_id" in body
        # Must be a valid UUID matching the mocked job
        assert uuid.UUID(body["job_id"]) == job.id
        assert body["estimated_completion"] != ""
        # Celery task dispatched with correct arguments
        mock_task.delay.assert_called_once_with(str(user_id), str(job.id))

    def test_export_without_auth_returns_401(self) -> None:
        """POST /users/me/export without Authorization header returns HTTP 401.

        Requirements: 28.1, 21.2
        """
        with TestClient(_app) as client:
            resp = client.post("/users/me/export")

        assert resp.status_code == 401


# ===========================================================================
# Scenario 3–6 — DELETE /users/me
# ===========================================================================


class TestAccountDeletionEndpoint:
    """HTTP-level tests for DELETE /users/me.

    Requirements: 28.2, 21.2
    """

    def test_deletion_with_correct_email_schedules_task_and_returns_200(self) -> None:
        """DELETE /users/me with valid JWT and matching email schedules deletion.

        Verifies:
        - HTTP 200 is returned.
        - Response contains ``scheduled_at`` and ``estimated_completion`` fields.
        - ``delete_user_data_task.delay`` is called exactly once with ``(str(user_id),)``.

        Requirements: 28.2, 21.2
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id)
        user = _make_user(user_id=user_id, email=_USER_EMAIL)
        mock_db = _make_mock_db_session()

        from app.database import get_db

        _app.dependency_overrides = {get_db: _override_get_db(mock_db)}

        try:
            with (
                patch("app.api.users.router.UserRepository") as MockUserRepo,
                patch(
                    "app.workers.gdpr_worker.delete_user_data_task",
                    new_callable=MagicMock,
                ) as mock_task,
                patch("app.security.dependencies._is_jti_revoked", return_value=False),
            ):
                MockUserRepo.return_value.get_by_id = AsyncMock(return_value=user)

                with TestClient(_app) as client:
                    resp = _delete(
                        client,
                        "/users/me",
                        body={"email": _USER_EMAIL},
                        headers=_auth_headers(token),
                    )
        finally:
            _app.dependency_overrides = {}

        assert resp.status_code == 200
        body = resp.json()
        assert "scheduled_at" in body
        assert "estimated_completion" in body
        assert body["estimated_completion"] != ""
        mock_task.delay.assert_called_once_with(str(user_id))

    def test_deletion_with_wrong_email_returns_400(self) -> None:
        """DELETE /users/me with mismatched email returns HTTP 400.

        The deletion task must NOT be dispatched when the email does not match.

        Requirements: 28.2, 21.2
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id)
        user = _make_user(user_id=user_id, email=_USER_EMAIL)
        mock_db = _make_mock_db_session()

        from app.database import get_db

        _app.dependency_overrides = {get_db: _override_get_db(mock_db)}

        try:
            with (
                patch("app.api.users.router.UserRepository") as MockUserRepo,
                patch(
                    "app.workers.gdpr_worker.delete_user_data_task",
                    new_callable=MagicMock,
                ) as mock_task,
                patch("app.security.dependencies._is_jti_revoked", return_value=False),
            ):
                MockUserRepo.return_value.get_by_id = AsyncMock(return_value=user)

                with TestClient(_app) as client:
                    resp = _delete(
                        client,
                        "/users/me",
                        body={"email": "wrong@example.com"},
                        headers=_auth_headers(token),
                    )
        finally:
            _app.dependency_overrides = {}

        assert resp.status_code == 400
        mock_task.delay.assert_not_called()

    def test_deletion_without_auth_returns_401(self) -> None:
        """DELETE /users/me without Authorization header returns HTTP 401.

        Requirements: 28.2, 21.2
        """
        with TestClient(_app) as client:
            resp = _delete(client, "/users/me", body={"email": _USER_EMAIL}, headers={})

        assert resp.status_code == 401

    def test_deletion_returns_404_when_user_not_found(self) -> None:
        """DELETE /users/me returns HTTP 404 when the user record does not exist.

        The deletion task must NOT be dispatched.

        Requirements: 28.2, 21.2
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id)
        mock_db = _make_mock_db_session()

        from app.database import get_db

        _app.dependency_overrides = {get_db: _override_get_db(mock_db)}

        try:
            with (
                patch("app.api.users.router.UserRepository") as MockUserRepo,
                patch(
                    "app.workers.gdpr_worker.delete_user_data_task",
                    new_callable=MagicMock,
                ) as mock_task,
                patch("app.security.dependencies._is_jti_revoked", return_value=False),
            ):
                MockUserRepo.return_value.get_by_id = AsyncMock(return_value=None)

                with TestClient(_app) as client:
                    resp = _delete(
                        client,
                        "/users/me",
                        body={"email": _USER_EMAIL},
                        headers=_auth_headers(token),
                    )
        finally:
            _app.dependency_overrides = {}

        assert resp.status_code == 404
        mock_task.delay.assert_not_called()

    def test_deletion_email_check_is_case_insensitive(self) -> None:
        """DELETE /users/me accepts the email confirmation regardless of letter case.

        'USER@EXAMPLE.COM' must match 'user@example.com'.

        Requirements: 28.2, 21.2
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id)
        user = _make_user(user_id=user_id, email=_USER_EMAIL)
        mock_db = _make_mock_db_session()

        from app.database import get_db

        _app.dependency_overrides = {get_db: _override_get_db(mock_db)}

        try:
            with (
                patch("app.api.users.router.UserRepository") as MockUserRepo,
                patch(
                    "app.workers.gdpr_worker.delete_user_data_task",
                    new_callable=MagicMock,
                ),
                patch("app.security.dependencies._is_jti_revoked", return_value=False),
            ):
                MockUserRepo.return_value.get_by_id = AsyncMock(return_value=user)

                with TestClient(_app) as client:
                    resp = _delete(
                        client,
                        "/users/me",
                        body={"email": _USER_EMAIL.upper()},
                        headers=_auth_headers(token),
                    )
        finally:
            _app.dependency_overrides = {}

        assert resp.status_code == 200


# ===========================================================================
# Scenario 7 — Export worker covers all data types
# ===========================================================================


class TestExportWorkerTask:
    """Direct tests of the ``_run_export`` async function in the GDPR worker.

    These tests bypass HTTP and Celery entirely to validate the data-assembly
    pipeline inside the task.

    Requirements: 28.1, 21.2
    """

    @pytest.mark.asyncio
    async def test_export_archive_contains_all_data_types(self) -> None:
        """_run_export assembles an archive with all 10 data-type keys, each non-empty.

        Also verifies ``job_repo.update_status`` is called with
        ``JobStatus.completed`` and the archive is passed in ``result_payload``.

        Requirements: 28.1, 21.2
        """
        from app.workers.gdpr_worker import _run_export

        user_id = str(uuid.uuid4())
        job_id = str(uuid.uuid4())

        # Build lightweight row mocks that _row_to_dict can traverse.
        def _make_row(**kwargs) -> MagicMock:
            row = MagicMock()
            col_mocks = []
            for name, value in kwargs.items():
                col = MagicMock()
                col.name = name
                col_mocks.append(col)
                setattr(row, name, value)
            tbl = MagicMock()
            tbl.columns = col_mocks
            row.__table__ = tbl
            return row

        conv_row = _make_row(id=user_id, user_id=user_id, title="Chat 1")
        msg_row = _make_row(id=str(uuid.uuid4()), conversation_id=user_id, content="Hi")
        doc_row = _make_row(id=str(uuid.uuid4()), user_id=user_id, title="Doc 1")
        mem_row = _make_row(id=str(uuid.uuid4()), user_id=user_id, content="Mem 1")
        note_row = _make_row(id=str(uuid.uuid4()), user_id=user_id, body="Note 1")
        todo_row = _make_row(id=str(uuid.uuid4()), user_id=user_id, title="Todo 1")
        cal_row = _make_row(id=str(uuid.uuid4()), user_id=user_id, title="Event 1")
        rem_row = _make_row(id=str(uuid.uuid4()), user_id=user_id, title="Rem 1")
        habit_def_row = _make_row(id=str(uuid.uuid4()), user_id=user_id, name="Habit 1")
        habit_entry_row = _make_row(id=str(uuid.uuid4()), user_id=user_id)

        # _run_export calls db.execute(...) in order:
        # 0=Conversation, 1=Document, 2=Memory, 3=Note, 4=TodoItem,
        # 5=CalendarEvent, 6=Reminder, 7=HabitDefinition, 8=HabitEntry, 9=Messages
        fetch_returns = [
            [conv_row],  # conversations
            [doc_row],  # documents
            [mem_row],  # memories
            [note_row],  # notes
            [todo_row],  # todo_items
            [cal_row],  # calendar_events
            [rem_row],  # reminders
            [habit_def_row],  # habit_definitions
            [habit_entry_row],  # habit_entries
            [msg_row],  # messages
        ]

        call_index = [0]

        def _scalars_result(rows):
            r = MagicMock()
            r.scalars.return_value.all.return_value = rows
            return r

        async def _execute_side_effect(_query):
            idx = call_index[0]
            call_index[0] += 1
            return _scalars_result(
                fetch_returns[idx] if idx < len(fetch_returns) else []
            )

        mock_db = AsyncMock()
        mock_db.commit = AsyncMock()
        mock_db.execute = AsyncMock(side_effect=_execute_side_effect)

        mock_ctx = AsyncMock()
        mock_ctx.__aenter__ = AsyncMock(return_value=mock_db)
        mock_ctx.__aexit__ = AsyncMock(return_value=False)

        update_status_calls: list[tuple] = []

        async def _fake_update_status(job_uuid, status, **kwargs):
            update_status_calls.append((job_uuid, status, kwargs))

        mock_job_repo_instance = MagicMock()
        mock_job_repo_instance.update_status = AsyncMock(
            side_effect=_fake_update_status
        )

        mock_task = MagicMock()

        # JobRepository is a *local import* inside _run_export; patch the class
        # at its defining module so that instantiation returns our mock.
        with (
            patch("app.workers.gdpr_worker.AsyncSessionLocal", return_value=mock_ctx),
            patch(
                "app.repositories.job_repository.JobRepository",
                return_value=mock_job_repo_instance,
            ),
        ):
            result = await _run_export(mock_task, user_id, job_id)

        assert result["status"] == "completed"
        assert result["job_id"] == job_id

        # update_status must have been called at least twice (running + completed)
        assert len(update_status_calls) >= 2

        completed_call = next(
            (c for c in update_status_calls if c[1] == JobStatus.completed),
            None,
        )
        assert completed_call is not None, (
            "job_repo.update_status was never called with JobStatus.completed"
        )

        archive = completed_call[2].get("result_payload", {})
        expected_keys = {
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
        missing = expected_keys - set(archive.keys())
        assert not missing, f"Archive is missing keys: {missing}"

        for key in expected_keys:
            assert len(archive[key]) > 0, f"Archive key '{key}' is empty"


# ===========================================================================
# Scenario 8–9 — Delete worker task
# ===========================================================================


class TestDeleteWorkerTask:
    """Direct tests of the ``_run_delete`` async function in the GDPR worker.

    Requirements: 28.2, 21.2
    """

    @pytest.mark.asyncio
    async def test_deletion_removes_only_target_user_not_others(self) -> None:
        """_run_delete deletes exactly the target user row — no other users touched.

        Verifies:
        - ``db.delete()`` is called exactly once.
        - The object passed is the mock for the target user.
        - Return status is "completed".

        Requirements: 28.2, 21.2
        """
        from app.workers.gdpr_worker import _run_delete

        user_id = str(uuid.uuid4())

        mock_user = MagicMock()
        mock_user.id = uuid.UUID(user_id)

        mock_db = AsyncMock()
        mock_db.commit = AsyncMock()
        mock_db.delete = AsyncMock()

        execute_result = MagicMock()
        execute_result.scalar_one_or_none.return_value = mock_user
        mock_db.execute = AsyncMock(return_value=execute_result)

        mock_ctx = AsyncMock()
        mock_ctx.__aenter__ = AsyncMock(return_value=mock_db)
        mock_ctx.__aexit__ = AsyncMock(return_value=False)

        mock_settings = MagicMock()
        mock_settings.CHROMA_HOST = None
        mock_settings.CHROMADB_HOST = None

        with (
            patch("app.workers.gdpr_worker.AsyncSessionLocal", return_value=mock_ctx),
            patch("app.config.settings.get_settings", return_value=mock_settings),
        ):
            result = await _run_delete(MagicMock(), user_id)

        assert result["status"] == "completed"
        assert result["user_id"] == user_id
        # Exactly one delete call — the target user only
        mock_db.delete.assert_called_once_with(mock_user)

    @pytest.mark.asyncio
    async def test_deletion_handles_chromadb_exception_gracefully(self) -> None:
        """_run_delete does not raise when the ChromaDB client throws.

        ChromaDB deletion is best-effort.  PostgreSQL deletion must still run
        and the task must return "completed".

        Requirements: 28.2, 21.2
        """
        from app.workers.gdpr_worker import _run_delete

        user_id = str(uuid.uuid4())
        mock_user = MagicMock()
        mock_user.id = uuid.UUID(user_id)

        mock_db = AsyncMock()
        mock_db.commit = AsyncMock()
        mock_db.delete = AsyncMock()

        execute_result = MagicMock()
        execute_result.scalar_one_or_none.return_value = mock_user
        mock_db.execute = AsyncMock(return_value=execute_result)

        mock_ctx = AsyncMock()
        mock_ctx.__aenter__ = AsyncMock(return_value=mock_db)
        mock_ctx.__aexit__ = AsyncMock(return_value=False)

        # Simulate ChromaDB configured but throwing on delete_collection
        mock_chroma_client = MagicMock()
        mock_chroma_client.delete_collection = MagicMock(
            side_effect=Exception("ChromaDB connection refused")
        )

        mock_settings = MagicMock()
        mock_settings.CHROMA_HOST = "chroma-host"
        mock_settings.CHROMADB_HOST = None
        mock_settings.CHROMA_PORT = 8001
        mock_settings.CHROMADB_PORT = None

        # chromadb may not be installed; inject a fake sys.modules entry so the
        # ``import chromadb`` inside _run_delete resolves to our mock.
        fake_chromadb = MagicMock()
        fake_chromadb.HttpClient = MagicMock(return_value=mock_chroma_client)

        with (
            patch("app.workers.gdpr_worker.AsyncSessionLocal", return_value=mock_ctx),
            patch("app.config.settings.get_settings", return_value=mock_settings),
            patch.dict(sys.modules, {"chromadb": fake_chromadb}),
        ):
            result = await _run_delete(MagicMock(), user_id)

        # Must not raise; must complete PostgreSQL deletion
        assert result["status"] == "completed"
        assert result["user_id"] == user_id
        mock_db.delete.assert_called_once_with(mock_user)

    @pytest.mark.asyncio
    async def test_deletion_handles_missing_chromadb_host_gracefully(self) -> None:
        """_run_delete completes cleanly when no CHROMA_HOST is configured.

        When ChromaDB is not configured the worker skips embedding deletion
        and proceeds to PostgreSQL deletion without raising.

        Requirements: 28.2, 21.2
        """
        from app.workers.gdpr_worker import _run_delete

        user_id = str(uuid.uuid4())
        mock_user = MagicMock()
        mock_user.id = uuid.UUID(user_id)

        mock_db = AsyncMock()
        mock_db.commit = AsyncMock()
        mock_db.delete = AsyncMock()

        execute_result = MagicMock()
        execute_result.scalar_one_or_none.return_value = mock_user
        mock_db.execute = AsyncMock(return_value=execute_result)

        mock_ctx = AsyncMock()
        mock_ctx.__aenter__ = AsyncMock(return_value=mock_db)
        mock_ctx.__aexit__ = AsyncMock(return_value=False)

        mock_settings = MagicMock()
        mock_settings.CHROMA_HOST = None
        mock_settings.CHROMADB_HOST = None

        with (
            patch("app.workers.gdpr_worker.AsyncSessionLocal", return_value=mock_ctx),
            patch("app.config.settings.get_settings", return_value=mock_settings),
        ):
            result = await _run_delete(MagicMock(), user_id)

        assert result["status"] == "completed"
        assert result["user_id"] == user_id
        mock_db.delete.assert_called_once_with(mock_user)
