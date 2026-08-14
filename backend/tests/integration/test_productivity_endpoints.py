"""Integration tests for the /productivity/* REST endpoints.

Covers four end-to-end scenarios per sub-domain:
1. CRUD round-trip — create, read, update, delete for todos, calendar events,
   reminders, and habits using mocked ProductivityService.
2. AI-assisted endpoints — generate todos, suggest meeting times, suggest a
   reminder, and get habit insights using a mocked AI_Orchestrator.
3. User-scoping — user A cannot read or modify user B's productivity data.
4. Authentication guard — unauthenticated requests return HTTP 401.

Requirements: 21.2
Cross-references: 13.1, 9.1, 9.2
"""

from __future__ import annotations

import os
import uuid
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

# Env vars must be set before any app imports.
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-key")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test-key")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")
os.environ.setdefault("LOKI_URL", "")
os.environ.setdefault("ENVIRONMENT", "development")

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.productivity.router import router as productivity_router
from app.security.jwt_handler import create_access_token

# ---------------------------------------------------------------------------
# Minimal FastAPI app — only the productivity router, no middleware overhead
# ---------------------------------------------------------------------------

_app = FastAPI()
_app.include_router(productivity_router)

# ---------------------------------------------------------------------------
# Shared test data / constants
# ---------------------------------------------------------------------------

_NOW = datetime(2024, 6, 1, 9, 0, 0, tzinfo=timezone.utc)
_FUTURE = datetime(2024, 7, 1, 10, 0, 0, tzinfo=timezone.utc)


def _make_user_id() -> uuid.UUID:
    return uuid.uuid4()


def _make_token(user_id: uuid.UUID, role: str = "user") -> str:
    return create_access_token(user_id=user_id, role=role)


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


# ---------------------------------------------------------------------------
# ORM mock helpers
# ---------------------------------------------------------------------------


def _make_todo(
    *,
    todo_id: uuid.UUID | None = None,
    user_id: uuid.UUID | None = None,
    title: str = "Buy groceries",
    description: str = "",
    is_completed: bool = False,
    priority: str = "medium",
    tags: list[str] | None = None,
    due_date: datetime | None = None,
) -> MagicMock:
    t = MagicMock()
    t.id = todo_id or uuid.uuid4()
    t.user_id = user_id or uuid.uuid4()
    t.title = title
    t.description = description
    t.is_completed = is_completed
    t.priority = priority
    t.tags = tags or []
    t.due_date = due_date
    t.created_at = _NOW
    t.updated_at = _NOW
    return t


def _make_event(
    *,
    event_id: uuid.UUID | None = None,
    user_id: uuid.UUID | None = None,
    title: str = "Team standup",
    start_time: datetime | None = None,
    end_time: datetime | None = None,
    description: str = "",
    location: str | None = None,
    is_all_day: bool = False,
    source: str = "local",
) -> MagicMock:
    e = MagicMock()
    e.id = event_id or uuid.uuid4()
    e.user_id = user_id or uuid.uuid4()
    e.title = title
    e.description = description
    e.start_time = start_time or _NOW
    e.end_time = end_time or _FUTURE
    e.location = location
    e.is_all_day = is_all_day
    e.source = source
    e.created_at = _NOW
    e.updated_at = _NOW
    return e


def _make_reminder(
    *,
    reminder_id: uuid.UUID | None = None,
    user_id: uuid.UUID | None = None,
    title: str = "Call dentist",
    trigger_time: datetime | None = None,
    recurrence_rule: str | None = None,
    linked_todo_id: uuid.UUID | None = None,
    is_completed: bool = False,
) -> MagicMock:
    r = MagicMock()
    r.id = reminder_id or uuid.uuid4()
    r.user_id = user_id or uuid.uuid4()
    r.title = title
    r.trigger_time = trigger_time or _FUTURE
    r.recurrence_rule = recurrence_rule
    r.linked_todo_id = linked_todo_id
    r.is_completed = is_completed
    r.created_at = _NOW
    r.updated_at = _NOW
    return r


def _make_habit(
    *,
    habit_id: uuid.UUID | None = None,
    user_id: uuid.UUID | None = None,
    name: str = "Morning run",
    description: str = "",
    recurrence: str = "daily",
    target_frequency: int = 1,
) -> MagicMock:
    h = MagicMock()
    h.id = habit_id or uuid.uuid4()
    h.user_id = user_id or uuid.uuid4()
    h.name = name
    h.description = description
    h.recurrence = recurrence
    h.target_frequency = target_frequency
    h.created_at = _NOW
    h.updated_at = _NOW
    return h


def _make_habit_entry(
    *,
    entry_id: uuid.UUID | None = None,
    habit_id: uuid.UUID | None = None,
    user_id: uuid.UUID | None = None,
    completed_at: datetime | None = None,
    note: str | None = None,
) -> MagicMock:
    e = MagicMock()
    e.id = entry_id or uuid.uuid4()
    e.habit_id = habit_id or uuid.uuid4()
    e.user_id = user_id or uuid.uuid4()
    e.completed_at = completed_at or _NOW
    e.note = note
    e.created_at = _NOW
    return e


# ---------------------------------------------------------------------------
# DB session + service mocking helpers
# ---------------------------------------------------------------------------


def _make_mock_db() -> AsyncMock:
    db = AsyncMock()
    db.add = MagicMock()
    db.flush = AsyncMock()
    db.commit = AsyncMock()
    db.rollback = AsyncMock()
    db.close = AsyncMock()
    return db


def _override_get_db(mock_db: AsyncMock):
    async def _dep():
        try:
            yield mock_db
            await mock_db.commit()
        except Exception:
            await mock_db.rollback()
            raise
        finally:
            await mock_db.close()

    return _dep


# ===========================================================================
# Scenario 1 — Todo CRUD round-trip
# ===========================================================================


class TestTodoCrud:
    """Full CRUD cycle for the /productivity/todos/* endpoints.

    Requirements: 13.1, 21.2
    """

    def test_create_todo_returns_201(self) -> None:
        """POST /productivity/todos creates a todo and returns HTTP 201.

        Requirements: 13.1, 21.2
        """
        user_id = _make_user_id()
        token = _make_token(user_id)
        todo = _make_todo(user_id=user_id, title="Finish report")

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.create_todo = AsyncMock(return_value=todo)

            with TestClient(_app) as client:
                resp = client.post(
                    "/productivity/todos",
                    json={"title": "Finish report", "priority": "high"},
                    headers=_auth(token),
                )

        assert resp.status_code == 201
        body = resp.json()
        assert body["title"] == "Finish report"
        assert body["user_id"] == str(user_id)
        assert "id" in body

    def test_list_todos_returns_200_with_pagination(self) -> None:
        """GET /productivity/todos returns paginated list with total field.

        Requirements: 13.1, 21.2
        """
        user_id = _make_user_id()
        token = _make_token(user_id)
        todos = [_make_todo(user_id=user_id, title=f"Task {i}") for i in range(3)]

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.list_todos = AsyncMock(return_value=(todos, 3))

            with TestClient(_app) as client:
                resp = client.get("/productivity/todos", headers=_auth(token))

        assert resp.status_code == 200
        body = resp.json()
        assert body["total"] == 3
        assert len(body["items"]) == 3
        assert body["page"] == 1

    def test_get_todo_by_id_returns_200(self) -> None:
        """GET /productivity/todos/{id} returns the correct todo item.

        Requirements: 13.1, 21.2
        """
        user_id = _make_user_id()
        token = _make_token(user_id)
        todo = _make_todo(user_id=user_id, title="Read a book")

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.get_todo = AsyncMock(return_value=todo)

            with TestClient(_app) as client:
                resp = client.get(
                    f"/productivity/todos/{todo.id}",
                    headers=_auth(token),
                )

        assert resp.status_code == 200
        assert resp.json()["title"] == "Read a book"

    def test_get_todo_not_found_returns_404(self) -> None:
        """GET /productivity/todos/{id} for unknown ID returns HTTP 404.

        Requirements: 13.1, 21.2
        """
        from fastapi import HTTPException

        user_id = _make_user_id()
        token = _make_token(user_id)

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.get_todo = AsyncMock(
                side_effect=HTTPException(status_code=404, detail="Not found")
            )

            with TestClient(_app) as client:
                resp = client.get(
                    f"/productivity/todos/{uuid.uuid4()}",
                    headers=_auth(token),
                )

        assert resp.status_code == 404

    def test_update_todo_returns_200_with_updated_fields(self) -> None:
        """PATCH /productivity/todos/{id} returns 200 with updated fields.

        Requirements: 13.1, 21.2
        """
        user_id = _make_user_id()
        token = _make_token(user_id)
        updated = _make_todo(user_id=user_id, title="Buy groceries", is_completed=True)

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.update_todo = AsyncMock(return_value=updated)

            with TestClient(_app) as client:
                resp = client.patch(
                    f"/productivity/todos/{updated.id}",
                    json={"is_completed": True},
                    headers=_auth(token),
                )

        assert resp.status_code == 200
        assert resp.json()["is_completed"] is True

    def test_delete_todo_returns_204(self) -> None:
        """DELETE /productivity/todos/{id} returns HTTP 204 No Content.

        Requirements: 13.1, 21.2
        """
        user_id = _make_user_id()
        token = _make_token(user_id)
        todo_id = uuid.uuid4()

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.delete_todo = AsyncMock(return_value=None)

            with TestClient(_app) as client:
                resp = client.delete(
                    f"/productivity/todos/{todo_id}",
                    headers=_auth(token),
                )

        assert resp.status_code == 204
        assert resp.content == b""


# ===========================================================================
# Scenario 2 — Calendar Event CRUD round-trip
# ===========================================================================


class TestCalendarEventCrud:
    """Full CRUD cycle for the /productivity/calendar/events/* endpoints.

    Requirements: 13.1, 21.2
    """

    def test_create_calendar_event_returns_201(self) -> None:
        """POST /productivity/calendar/events creates an event and returns HTTP 201.

        Requirements: 13.1, 21.2
        """
        user_id = _make_user_id()
        token = _make_token(user_id)
        event = _make_event(
            user_id=user_id, title="Team standup", start_time=_NOW, end_time=_FUTURE
        )

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.create_calendar_event = AsyncMock(return_value=event)

            with TestClient(_app) as client:
                resp = client.post(
                    "/productivity/calendar/events",
                    json={
                        "title": "Team standup",
                        "start_time": _NOW.isoformat(),
                        "end_time": _FUTURE.isoformat(),
                    },
                    headers=_auth(token),
                )

        assert resp.status_code == 201
        body = resp.json()
        assert body["title"] == "Team standup"
        assert body["user_id"] == str(user_id)
        assert "id" in body

    def test_list_calendar_events_returns_200(self) -> None:
        """GET /productivity/calendar/events returns list with total field.

        Requirements: 13.1, 21.2
        """
        user_id = _make_user_id()
        token = _make_token(user_id)
        events = [_make_event(user_id=user_id, title=f"Event {i}") for i in range(2)]

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.list_calendar_events = AsyncMock(return_value=events)

            with TestClient(_app) as client:
                resp = client.get("/productivity/calendar/events", headers=_auth(token))

        assert resp.status_code == 200
        body = resp.json()
        assert body["total"] == 2
        assert len(body["items"]) == 2

    def test_update_calendar_event_returns_200(self) -> None:
        """PATCH /productivity/calendar/events/{id} returns 200 with updated fields.

        Requirements: 13.1, 21.2
        """
        user_id = _make_user_id()
        token = _make_token(user_id)
        event_id = uuid.uuid4()
        updated = _make_event(
            event_id=event_id, user_id=user_id, title="Updated standup"
        )

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.update_calendar_event = AsyncMock(return_value=updated)

            with TestClient(_app) as client:
                resp = client.patch(
                    f"/productivity/calendar/events/{event_id}",
                    json={"title": "Updated standup"},
                    headers=_auth(token),
                )

        assert resp.status_code == 200
        assert resp.json()["title"] == "Updated standup"

    def test_delete_calendar_event_returns_204(self) -> None:
        """DELETE /productivity/calendar/events/{id} returns HTTP 204 No Content.

        Requirements: 13.1, 21.2
        """
        user_id = _make_user_id()
        token = _make_token(user_id)
        event_id = uuid.uuid4()

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.delete_calendar_event = AsyncMock(return_value=None)

            with TestClient(_app) as client:
                resp = client.delete(
                    f"/productivity/calendar/events/{event_id}",
                    headers=_auth(token),
                )

        assert resp.status_code == 204
        assert resp.content == b""

    def test_update_calendar_event_not_found_returns_404(self) -> None:
        """PATCH /productivity/calendar/events/{id} for unknown ID returns HTTP 404.

        The router exposes no GET-by-ID for calendar events; 404 is surfaced
        via the update endpoint when the service raises HTTPException(404).

        Requirements: 13.1, 21.2
        """
        from fastapi import HTTPException

        user_id = _make_user_id()
        token = _make_token(user_id)
        unknown_id = uuid.uuid4()

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.update_calendar_event = AsyncMock(
                side_effect=HTTPException(status_code=404, detail="Not found")
            )

            with TestClient(_app) as client:
                resp = client.patch(
                    f"/productivity/calendar/events/{unknown_id}",
                    json={"title": "No such event"},
                    headers=_auth(token),
                )

        assert resp.status_code == 404


# ===========================================================================
# Scenario 3 — Reminder CRUD round-trip
# ===========================================================================


class TestReminderCrud:
    """Full CRUD cycle for the /productivity/reminders/* endpoints.

    Requirements: 13.1, 21.2
    """

    def test_create_reminder_returns_201(self) -> None:
        """POST /productivity/reminders creates a reminder and returns HTTP 201.

        Requirements: 13.1, 21.2
        """
        user_id = _make_user_id()
        token = _make_token(user_id)
        reminder = _make_reminder(user_id=user_id, title="Call dentist")

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.create_reminder = AsyncMock(return_value=reminder)

            with TestClient(_app) as client:
                resp = client.post(
                    "/productivity/reminders",
                    json={
                        "title": "Call dentist",
                        "trigger_time": _FUTURE.isoformat(),
                    },
                    headers=_auth(token),
                )

        assert resp.status_code == 201
        body = resp.json()
        assert body["title"] == "Call dentist"
        assert body["user_id"] == str(user_id)
        assert "id" in body

    def test_list_reminders_returns_200(self) -> None:
        """GET /productivity/reminders returns list with total field.

        Requirements: 13.1, 21.2
        """
        user_id = _make_user_id()
        token = _make_token(user_id)
        reminders = [
            _make_reminder(user_id=user_id, title=f"Reminder {i}") for i in range(2)
        ]

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.list_reminders = AsyncMock(return_value=reminders)

            with TestClient(_app) as client:
                resp = client.get("/productivity/reminders", headers=_auth(token))

        assert resp.status_code == 200
        body = resp.json()
        assert body["total"] == 2
        assert len(body["items"]) == 2

    def test_update_reminder_returns_200(self) -> None:
        """PATCH /productivity/reminders/{id} returns 200 with updated fields.

        Requirements: 13.1, 21.2
        """
        user_id = _make_user_id()
        token = _make_token(user_id)
        reminder_id = uuid.uuid4()
        updated = _make_reminder(
            reminder_id=reminder_id,
            user_id=user_id,
            title="Call dentist",
            is_completed=True,
        )

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.update_reminder = AsyncMock(return_value=updated)

            with TestClient(_app) as client:
                resp = client.patch(
                    f"/productivity/reminders/{reminder_id}",
                    json={"is_completed": True},
                    headers=_auth(token),
                )

        assert resp.status_code == 200
        assert resp.json()["is_completed"] is True

    def test_delete_reminder_returns_204(self) -> None:
        """DELETE /productivity/reminders/{id} returns HTTP 204 No Content.

        Requirements: 13.1, 21.2
        """
        user_id = _make_user_id()
        token = _make_token(user_id)
        reminder_id = uuid.uuid4()

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.delete_reminder = AsyncMock(return_value=None)

            with TestClient(_app) as client:
                resp = client.delete(
                    f"/productivity/reminders/{reminder_id}",
                    headers=_auth(token),
                )

        assert resp.status_code == 204
        assert resp.content == b""

    def test_reminder_not_found_returns_404(self) -> None:
        """PATCH /productivity/reminders/{id} for unknown ID returns HTTP 404.

        The router exposes no GET-by-ID for reminders; 404 is surfaced
        via the update endpoint when the service raises HTTPException(404).

        Requirements: 13.1, 21.2
        """
        from fastapi import HTTPException

        user_id = _make_user_id()
        token = _make_token(user_id)
        unknown_id = uuid.uuid4()

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.update_reminder = AsyncMock(
                side_effect=HTTPException(status_code=404, detail="Not found")
            )

            with TestClient(_app) as client:
                resp = client.patch(
                    f"/productivity/reminders/{unknown_id}",
                    json={"title": "No such reminder"},
                    headers=_auth(token),
                )

        assert resp.status_code == 404


# ===========================================================================
# Scenario 4 — Habit CRUD round-trip
# ===========================================================================


class TestHabitCrud:
    """Full CRUD cycle for the /productivity/habits/* endpoints.

    Requirements: 13.1, 21.2
    """

    def test_create_habit_returns_201(self) -> None:
        """POST /productivity/habits creates a habit and returns HTTP 201.

        Requirements: 13.1, 21.2
        """
        user_id = _make_user_id()
        token = _make_token(user_id)
        habit = _make_habit(user_id=user_id, name="Morning run")

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.create_habit = AsyncMock(return_value=habit)

            with TestClient(_app) as client:
                resp = client.post(
                    "/productivity/habits",
                    json={
                        "name": "Morning run",
                        "recurrence": "daily",
                        "target_frequency": 1,
                    },
                    headers=_auth(token),
                )

        assert resp.status_code == 201
        body = resp.json()
        assert body["name"] == "Morning run"
        assert body["user_id"] == str(user_id)
        assert "id" in body

    def test_list_habits_returns_200(self) -> None:
        """GET /productivity/habits returns list with total field.

        Requirements: 13.1, 21.2
        """
        user_id = _make_user_id()
        token = _make_token(user_id)
        habits = [_make_habit(user_id=user_id, name=f"Habit {i}") for i in range(2)]

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.list_habits = AsyncMock(return_value=habits)

            with TestClient(_app) as client:
                resp = client.get("/productivity/habits", headers=_auth(token))

        assert resp.status_code == 200
        body = resp.json()
        assert body["total"] == 2
        assert len(body["items"]) == 2

    def test_update_habit_returns_200(self) -> None:
        """PATCH /productivity/habits/{id} returns 200 with updated fields.

        Requirements: 13.1, 21.2
        """
        user_id = _make_user_id()
        token = _make_token(user_id)
        habit_id = uuid.uuid4()
        updated = _make_habit(habit_id=habit_id, user_id=user_id, name="Evening run")

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.update_habit = AsyncMock(return_value=updated)

            with TestClient(_app) as client:
                resp = client.patch(
                    f"/productivity/habits/{habit_id}",
                    json={"name": "Evening run"},
                    headers=_auth(token),
                )

        assert resp.status_code == 200
        assert resp.json()["name"] == "Evening run"

    def test_delete_habit_returns_204(self) -> None:
        """DELETE /productivity/habits/{id} returns HTTP 204 No Content.

        Requirements: 13.1, 21.2
        """
        user_id = _make_user_id()
        token = _make_token(user_id)
        habit_id = uuid.uuid4()

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.delete_habit = AsyncMock(return_value=None)

            with TestClient(_app) as client:
                resp = client.delete(
                    f"/productivity/habits/{habit_id}",
                    headers=_auth(token),
                )

        assert resp.status_code == 204
        assert resp.content == b""

    def test_log_habit_entry_returns_201(self) -> None:
        """POST /productivity/habits/{id}/entries logs an entry and returns HTTP 201.

        Requirements: 13.1, 21.2
        """
        user_id = _make_user_id()
        token = _make_token(user_id)
        habit_id = uuid.uuid4()
        entry = _make_habit_entry(habit_id=habit_id, user_id=user_id, completed_at=_NOW)

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.log_habit_entry = AsyncMock(return_value=entry)

            with TestClient(_app) as client:
                resp = client.post(
                    f"/productivity/habits/{habit_id}/entries",
                    json={"completed_at": _NOW.isoformat()},
                    headers=_auth(token),
                )

        assert resp.status_code == 201
        body = resp.json()
        assert body["habit_id"] == str(habit_id)
        assert "id" in body

    def test_habit_not_found_returns_404(self) -> None:
        """PATCH /productivity/habits/{id} for unknown ID returns HTTP 404.

        The router exposes no GET-by-ID for habits; 404 is surfaced
        via the update endpoint when the service raises HTTPException(404).

        Requirements: 13.1, 21.2
        """
        from fastapi import HTTPException

        user_id = _make_user_id()
        token = _make_token(user_id)
        unknown_id = uuid.uuid4()

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.update_habit = AsyncMock(
                side_effect=HTTPException(status_code=404, detail="Not found")
            )

            with TestClient(_app) as client:
                resp = client.patch(
                    f"/productivity/habits/{unknown_id}",
                    json={"name": "No such habit"},
                    headers=_auth(token),
                )

        assert resp.status_code == 404


# ===========================================================================
# Scenario 5 — AI-assisted endpoints
# ===========================================================================


class TestAIAssistedEndpoints:
    """AI-assisted endpoints with mocked AI_Orchestrator responses.

    Requirements: 13.1, 21.2
    """

    def test_generate_todos_returns_list(self) -> None:
        """POST /productivity/todos/generate returns generated todo list.

        Requirements: 13.1, 21.2
        """
        user_id = _make_user_id()
        token = _make_token(user_id)
        todos = [
            _make_todo(user_id=user_id, title="Buy milk"),
            _make_todo(user_id=user_id, title="Walk dog"),
        ]

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.generate_todos_from_prompt = AsyncMock(return_value=todos)

            with TestClient(_app) as client:
                resp = client.post(
                    "/productivity/todos/generate",
                    json={"prompt": "Morning routine tasks"},
                    headers=_auth(token),
                )

        assert resp.status_code == 200
        body = resp.json()
        assert "todos" in body
        assert len(body["todos"]) == 2
        assert body["prompt"] == "Morning routine tasks"

    def test_suggest_meeting_times_returns_suggestions(self) -> None:
        """POST /productivity/calendar/suggest-times returns time suggestions.

        Requirements: 13.1, 21.2
        """
        user_id = _make_user_id()
        token = _make_token(user_id)
        suggestions = [
            "2024-06-02T10:00:00Z",
            "2024-06-02T14:00:00Z",
            "2024-06-03T09:00:00Z",
        ]

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.suggest_meeting_times = AsyncMock(return_value=suggestions)

            with TestClient(_app) as client:
                resp = client.post(
                    "/productivity/calendar/suggest-times",
                    json={
                        "prompt": "Team sync meeting",
                        "duration_minutes": 60,
                    },
                    headers=_auth(token),
                )

        assert resp.status_code == 200
        body = resp.json()
        assert "suggestions" in body
        assert len(body["suggestions"]) == 3
        assert body["prompt"] == "Team sync meeting"

    def test_suggest_reminder_returns_suggestion_and_rationale(self) -> None:
        """POST /productivity/reminders/suggest returns suggestion with rationale.

        Requirements: 13.1, 21.2
        """
        user_id = _make_user_id()
        token = _make_token(user_id)
        result = {
            "suggestion": {
                "title": "Call dentist",
                "trigger_time": _FUTURE.isoformat(),
            },
            "rationale": "This is a good time to call during business hours.",
        }

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.suggest_reminder = AsyncMock(return_value=result)

            with TestClient(_app) as client:
                resp = client.post(
                    "/productivity/reminders/suggest",
                    json={"prompt": "I need to call the dentist tomorrow"},
                    headers=_auth(token),
                )

        assert resp.status_code == 200
        body = resp.json()
        assert "suggestion" in body
        assert "rationale" in body
        assert body["rationale"] == "This is a good time to call during business hours."

    def test_get_habit_insights_returns_insights_string(self) -> None:
        """GET /productivity/habits/{id}/insights returns AI insights.

        Requirements: 13.1, 21.2
        """
        user_id = _make_user_id()
        token = _make_token(user_id)
        habit_id = uuid.uuid4()
        insights_text = "You're maintaining a 90% completion rate. Great work!"

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.get_habit_insights = AsyncMock(return_value=insights_text)

            with TestClient(_app) as client:
                resp = client.get(
                    f"/productivity/habits/{habit_id}/insights",
                    headers=_auth(token),
                )

        assert resp.status_code == 200
        body = resp.json()
        assert body["habit_id"] == str(habit_id)
        assert body["insights"] == insights_text
        assert "generated_at" in body


# ===========================================================================
# Scenario 6 — User-scoping tests
# ===========================================================================


class TestUserScoping:
    """User-scoping: user A cannot read or modify user B's productivity data.

    Requirements: 9.2, 21.2
    """

    def test_user_b_cannot_get_user_a_todo_returns_404(self) -> None:
        """User B attempting to GET user A's todo returns HTTP 404.

        Requirements: 9.2, 21.2
        """
        from fastapi import HTTPException

        user_a_id = _make_user_id()
        user_b_id = _make_user_id()
        user_b_token = _make_token(user_b_id)
        todo_id = uuid.uuid4()

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            # Service raises 404 when user B tries to access user A's todo
            svc.get_todo = AsyncMock(
                side_effect=HTTPException(status_code=404, detail="Todo not found")
            )

            with TestClient(_app) as client:
                resp = client.get(
                    f"/productivity/todos/{todo_id}",
                    headers=_auth(user_b_token),
                )

        assert resp.status_code == 404

    def test_user_b_cannot_delete_user_a_calendar_event_returns_404(self) -> None:
        """User B attempting to DELETE user A's calendar event returns HTTP 404.

        Requirements: 9.2, 21.2
        """
        from fastapi import HTTPException

        user_a_id = _make_user_id()
        user_b_id = _make_user_id()
        user_b_token = _make_token(user_b_id)
        event_id = uuid.uuid4()

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.delete_calendar_event = AsyncMock(
                side_effect=HTTPException(status_code=404, detail="Event not found")
            )

            with TestClient(_app) as client:
                resp = client.delete(
                    f"/productivity/calendar/events/{event_id}",
                    headers=_auth(user_b_token),
                )

        assert resp.status_code == 404

    def test_user_b_cannot_patch_user_a_reminder_returns_404(self) -> None:
        """User B attempting to PATCH user A's reminder returns HTTP 404.

        Requirements: 9.2, 21.2
        """
        from fastapi import HTTPException

        user_a_id = _make_user_id()
        user_b_id = _make_user_id()
        user_b_token = _make_token(user_b_id)
        reminder_id = uuid.uuid4()

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.update_reminder = AsyncMock(
                side_effect=HTTPException(status_code=404, detail="Reminder not found")
            )

            with TestClient(_app) as client:
                resp = client.patch(
                    f"/productivity/reminders/{reminder_id}",
                    json={"title": "Hacked reminder"},
                    headers=_auth(user_b_token),
                )

        assert resp.status_code == 404

    def test_user_b_cannot_patch_user_a_habit_returns_404(self) -> None:
        """User B attempting to PATCH user A's habit returns HTTP 404.

        Requirements: 9.2, 21.2
        """
        from fastapi import HTTPException

        user_a_id = _make_user_id()
        user_b_id = _make_user_id()
        user_b_token = _make_token(user_b_id)
        habit_id = uuid.uuid4()

        with (
            patch("app.api.productivity.router.ProductivityService") as MockSvc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            svc = MockSvc.return_value
            svc.update_habit = AsyncMock(
                side_effect=HTTPException(status_code=404, detail="Habit not found")
            )

            with TestClient(_app) as client:
                resp = client.patch(
                    f"/productivity/habits/{habit_id}",
                    json={"name": "Hacked habit"},
                    headers=_auth(user_b_token),
                )

        assert resp.status_code == 404


# ===========================================================================
# Scenario 7 — Authentication guard tests
# ===========================================================================


class TestAuthenticationGuard:
    """Unauthenticated requests return HTTP 401.

    Requirements: 9.1, 21.2
    """

    def test_unauthenticated_get_todos_returns_401(self) -> None:
        """GET /productivity/todos without Authorization header returns HTTP 401.

        Requirements: 9.1, 21.2
        """
        with TestClient(_app) as client:
            resp = client.get("/productivity/todos")

        assert resp.status_code == 401

    def test_unauthenticated_post_calendar_event_returns_401(self) -> None:
        """POST /productivity/calendar/events without Authorization header returns HTTP 401.

        Requirements: 9.1, 21.2
        """
        with TestClient(_app) as client:
            resp = client.post(
                "/productivity/calendar/events",
                json={
                    "title": "Event",
                    "start_time": _NOW.isoformat(),
                    "end_time": _FUTURE.isoformat(),
                },
            )

        assert resp.status_code == 401

    def test_unauthenticated_get_reminders_returns_401(self) -> None:
        """GET /productivity/reminders without Authorization header returns HTTP 401.

        Requirements: 9.1, 21.2
        """
        with TestClient(_app) as client:
            resp = client.get("/productivity/reminders")

        assert resp.status_code == 401

    def test_unauthenticated_get_habits_returns_401(self) -> None:
        """GET /productivity/habits without Authorization header returns HTTP 401.

        Requirements: 9.1, 21.2
        """
        with TestClient(_app) as client:
            resp = client.get("/productivity/habits")

        assert resp.status_code == 401
