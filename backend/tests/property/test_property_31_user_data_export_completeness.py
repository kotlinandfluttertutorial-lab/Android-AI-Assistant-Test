"""Property-based tests for user data export completeness.

Property 31: User Data Export Completeness
**Validates: Requirements 28.1**

Strategy:
  - Generate one or two distinct user UUIDs
  - Generate varying entity counts (0–5) for each of the five required data
    types: conversations, messages, documents, memories, notes
  - Mock AsyncSessionLocal and db.execute to return per-user fake rows
  - Call _run_export directly and inspect the resulting archive

Assertions:
  - Property 31A: Archive for user A contains exactly the entities created for
    user A across all five required data types
  - Property 31B: Archive for user A contains no entries with user B's user_id
    (and vice-versa)
  - Property 31C: Archive always has all 10 required top-level keys regardless
    of whether any data exists for the user

Requirements: 28.1
"""

from __future__ import annotations

import asyncio
import os
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

# ---------------------------------------------------------------------------
# Environment variables must be set BEFORE any app imports
# ---------------------------------------------------------------------------
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-openai-key-for-testing")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key-for-testing")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test-key-for-testing")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")
os.environ.setdefault("LOKI_URL", "")
os.environ.setdefault("ENVIRONMENT", "development")

from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

# ---------------------------------------------------------------------------
# Required archive keys (all 10 from _run_export)
# ---------------------------------------------------------------------------

_REQUIRED_ARCHIVE_KEYS = {
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

# Five required keys called out in Requirement 28.1
_FIVE_REQUIRED_KEYS = {"conversations", "messages", "documents", "memories", "notes"}


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _run_async(coro):
    """Run an async coroutine synchronously (for use in Hypothesis tests)."""
    try:
        loop = asyncio.get_event_loop()
        if loop.is_closed():
            raise RuntimeError("loop closed")
        return loop.run_until_complete(coro)
    except RuntimeError:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        try:
            return loop.run_until_complete(coro)
        finally:
            loop.close()
            asyncio.set_event_loop(None)


def _make_row(**kwargs) -> MagicMock:
    """Build a lightweight ORM-row mock compatible with _row_to_dict.

    Each keyword argument becomes a column name and value on the mock object.
    The mock exposes ``__table__.columns`` as a list of column mocks so that
    ``_row_to_dict`` can iterate them correctly.
    """
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


def _scalars_result(rows: list) -> MagicMock:
    """Return a mock that mimics the SQLAlchemy execute() result object."""
    r = MagicMock()
    r.scalars.return_value.all.return_value = rows
    return r


def _build_user_rows(user_id_str: str, counts: dict[str, int]) -> dict[str, list]:
    """Create fake ORM rows for every data type for the given user.

    Messages are attached to the first conversation if any conversations exist.
    If there are no conversations, messages are always empty (matching the
    real _run_export behaviour where messages are fetched via conversation IDs).
    """
    uid = user_id_str

    conversations = [
        _make_row(id=str(uuid.uuid4()), user_id=uid, title=f"Chat {i}")
        for i in range(counts["conversations"])
    ]

    # Messages are linked via conversation_id — use the first conversation's id
    # if conversations exist, otherwise produce no messages regardless of count.
    conv_id_for_msgs = conversations[0].id if conversations else None
    messages = (
        [
            _make_row(
                id=str(uuid.uuid4()),
                conversation_id=conv_id_for_msgs,
                content=f"Msg {i}",
            )
            for i in range(counts["messages"])
        ]
        if conv_id_for_msgs is not None
        else []
    )

    documents = [
        _make_row(id=str(uuid.uuid4()), user_id=uid, title=f"Doc {i}")
        for i in range(counts["documents"])
    ]
    memories = [
        _make_row(id=str(uuid.uuid4()), user_id=uid, content=f"Mem {i}")
        for i in range(counts["memories"])
    ]
    notes = [
        _make_row(id=str(uuid.uuid4()), user_id=uid, body=f"Note {i}")
        for i in range(counts["notes"])
    ]
    todo_items = [
        _make_row(id=str(uuid.uuid4()), user_id=uid, title=f"Todo {i}")
        for i in range(counts["todo_items"])
    ]
    calendar_events = [
        _make_row(id=str(uuid.uuid4()), user_id=uid, title=f"Event {i}")
        for i in range(counts["calendar_events"])
    ]
    reminders = [
        _make_row(id=str(uuid.uuid4()), user_id=uid, title=f"Rem {i}")
        for i in range(counts["reminder"])
    ]
    habit_definitions = [
        _make_row(id=str(uuid.uuid4()), user_id=uid, name=f"Habit {i}")
        for i in range(counts["habit_definitions"])
    ]
    habit_entries = [
        _make_row(id=str(uuid.uuid4()), user_id=uid)
        for i in range(counts["habit_entries"])
    ]

    return {
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


def _build_mock_db_context(rows: dict[str, list]) -> tuple[AsyncMock, AsyncMock]:
    """Wire up a mock AsyncSessionLocal context and db.execute for one user.

    _run_export calls db.execute in this fixed order:
      0  Conversation
      1  Document
      2  Memory
      3  Note
      4  TodoItem
      5  CalendarEvent
      6  Reminder
      7  HabitDefinition
      8  HabitEntry
      9  Message  (only if conversations exist)

    Returns (mock_ctx, mock_db).
    """
    fetch_returns = [
        rows["conversations"],
        rows["documents"],
        rows["memories"],
        rows["notes"],
        rows["todo_items"],
        rows["calendar_events"],
        rows["reminders"],
        rows["habit_definitions"],
        rows["habit_entries"],
    ]
    if rows["conversations"]:
        fetch_returns.append(rows["messages"])

    call_index = [0]

    async def _execute_side_effect(_query):
        idx = call_index[0]
        call_index[0] += 1
        return _scalars_result(fetch_returns[idx] if idx < len(fetch_returns) else [])

    mock_db = AsyncMock()
    mock_db.commit = AsyncMock()
    mock_db.execute = AsyncMock(side_effect=_execute_side_effect)

    mock_ctx = AsyncMock()
    mock_ctx.__aenter__ = AsyncMock(return_value=mock_db)
    mock_ctx.__aexit__ = AsyncMock(return_value=False)

    return mock_ctx, mock_db


def _make_job_repo_mock() -> MagicMock:
    """Return a JobRepository mock that records update_status calls."""
    update_status_calls: list[tuple] = []

    async def _fake_update_status(job_uuid, status, **kwargs):
        update_status_calls.append((job_uuid, status, kwargs))

    mock_instance = MagicMock()
    mock_instance.update_status = AsyncMock(side_effect=_fake_update_status)
    mock_instance._calls = update_status_calls
    return mock_instance


async def _run_export_for_user(user_id_str: str, rows: dict[str, list]) -> dict:
    """Invoke _run_export with mocked DB and return the final archive payload."""
    from app.workers.gdpr_worker import _run_export

    job_id = str(uuid.uuid4())
    mock_ctx, _ = _build_mock_db_context(rows)
    mock_job_repo = _make_job_repo_mock()

    with (
        patch("app.workers.gdpr_worker.AsyncSessionLocal", return_value=mock_ctx),
        patch(
            "app.repositories.job_repository.JobRepository",
            return_value=mock_job_repo,
        ),
    ):
        result = await _run_export(MagicMock(), user_id_str, job_id)

    assert result["status"] == "completed", (
        f"_run_export returned status={result['status']!r} instead of 'completed'"
    )

    # Extract archive from the completed update_status call
    from app.models.job import JobStatus

    completed_call = next(
        (c for c in mock_job_repo._calls if c[1] == JobStatus.completed),
        None,
    )
    assert completed_call is not None, "No update_status(completed) call found"
    return completed_call[2].get("result_payload", {})


# ---------------------------------------------------------------------------
# Strategies
# ---------------------------------------------------------------------------

_count_strategy = st.integers(min_value=0, max_value=5)

_counts_strategy = st.fixed_dictionaries(
    {
        "conversations": _count_strategy,
        "messages": _count_strategy,
        "documents": _count_strategy,
        "memories": _count_strategy,
        "notes": _count_strategy,
        "todo_items": _count_strategy,
        "calendar_events": _count_strategy,
        "reminder": _count_strategy,
        "habit_definitions": _count_strategy,
        "habit_entries": _count_strategy,
    }
)

_distinct_users_strategy = st.fixed_dictionaries(
    {
        "user_a": st.uuids(),
        "user_b": st.uuids(),
    }
).filter(lambda d: d["user_a"] != d["user_b"])


# ===========================================================================
# Property 31A — Export completeness for a single user
# **Validates: Requirements 28.1**
# ===========================================================================


@given(
    user_id=st.uuids(),
    counts=_counts_strategy,
)
@settings(
    max_examples=50,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_31a_export_completeness_single_user(
    user_id: uuid.UUID,
    counts: dict[str, int],
) -> None:
    """**Validates: Requirements 28.1**

    Property 31A: The data export archive for a user contains exactly the
    entities that were stored for that user across all five required data types:
    conversations, messages, documents, memories, notes.

    The number of entries in each archive key must match the number of fake rows
    inserted for that key.  Messages are counted based on how many message rows
    are actually reachable via conversation IDs (so zero conversations → zero
    messages regardless of the messages count input).
    """
    uid_str = str(user_id)
    rows = _build_user_rows(uid_str, counts)
    archive = _run_async(_run_export_for_user(uid_str, rows))

    # Verify all required keys are present
    missing = _REQUIRED_ARCHIVE_KEYS - set(archive.keys())
    assert not missing, (
        f"Property 31A violated: archive missing keys {missing}. user_id={uid_str}"
    )

    # Verify completeness for each of the five required types
    expected_conv_count = counts["conversations"]
    assert len(archive["conversations"]) == expected_conv_count, (
        f"Property 31A violated: expected {expected_conv_count} conversations, "
        f"got {len(archive['conversations'])}. user_id={uid_str}"
    )

    # Messages are only fetched if there are conversations
    expected_msg_count = len(rows["messages"])  # already 0 when no conversations
    assert len(archive["messages"]) == expected_msg_count, (
        f"Property 31A violated: expected {expected_msg_count} messages, "
        f"got {len(archive['messages'])}. user_id={uid_str}, "
        f"conversations={expected_conv_count}"
    )

    assert len(archive["documents"]) == counts["documents"], (
        f"Property 31A violated: expected {counts['documents']} documents, "
        f"got {len(archive['documents'])}. user_id={uid_str}"
    )

    assert len(archive["memories"]) == counts["memories"], (
        f"Property 31A violated: expected {counts['memories']} memories, "
        f"got {len(archive['memories'])}. user_id={uid_str}"
    )

    assert len(archive["notes"]) == counts["notes"], (
        f"Property 31A violated: expected {counts['notes']} notes, "
        f"got {len(archive['notes'])}. user_id={uid_str}"
    )


# ===========================================================================
# Property 31B — Cross-user data isolation in export
# **Validates: Requirements 28.1**
# ===========================================================================


@given(
    users=_distinct_users_strategy,
    counts_a=_counts_strategy,
    counts_b=_counts_strategy,
)
@settings(
    max_examples=50,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_31b_cross_user_data_isolation(
    users: dict,
    counts_a: dict[str, int],
    counts_b: dict[str, int],
) -> None:
    """**Validates: Requirements 28.1**

    Property 31B: The data export archive for user A must contain no entities
    belonging to user B, and vice-versa.

    We run _run_export once for user A (with only user A's rows served by the
    mock) and once for user B (with only user B's rows) and confirm that each
    archive's user_id field matches the requesting user and that no foreign
    user_id values appear in any entity row.
    """
    uid_a = str(users["user_a"])
    uid_b = str(users["user_b"])

    rows_a = _build_user_rows(uid_a, counts_a)
    rows_b = _build_user_rows(uid_b, counts_b)

    archive_a = _run_async(_run_export_for_user(uid_a, rows_a))
    archive_b = _run_async(_run_export_for_user(uid_b, rows_b))

    # user_id field at the top level must match
    assert archive_a.get("user_id") == uid_a, (
        f"Property 31B violated: archive_a.user_id={archive_a.get('user_id')!r}, "
        f"expected {uid_a!r}"
    )
    assert archive_b.get("user_id") == uid_b, (
        f"Property 31B violated: archive_b.user_id={archive_b.get('user_id')!r}, "
        f"expected {uid_b!r}"
    )

    # No entity row in user A's archive should have user B's user_id
    for key in (
        "conversations",
        "documents",
        "memories",
        "notes",
        "todo_items",
        "calendar_events",
        "reminders",
        "habit_definitions",
        "habit_entries",
    ):
        for entry in archive_a.get(key, []):
            entry_uid = entry.get("user_id")
            assert entry_uid != uid_b, (
                f"Property 31B violated: archive for user A contains an entry in "
                f"'{key}' with user B's user_id. "
                f"user_a={uid_a}, user_b={uid_b}, entry_user_id={entry_uid!r}"
            )

    # No entity row in user B's archive should have user A's user_id
    for key in (
        "conversations",
        "documents",
        "memories",
        "notes",
        "todo_items",
        "calendar_events",
        "reminders",
        "habit_definitions",
        "habit_entries",
    ):
        for entry in archive_b.get(key, []):
            entry_uid = entry.get("user_id")
            assert entry_uid != uid_a, (
                f"Property 31B violated: archive for user B contains an entry in "
                f"'{key}' with user A's user_id. "
                f"user_a={uid_a}, user_b={uid_b}, entry_user_id={entry_uid!r}"
            )


# ===========================================================================
# Property 31C — Archive always contains all required keys
# **Validates: Requirements 28.1**
# ===========================================================================


@given(
    user_id=st.uuids(),
    counts=_counts_strategy,
)
@settings(
    max_examples=50,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_31c_archive_always_has_all_required_keys(
    user_id: uuid.UUID,
    counts: dict[str, int],
) -> None:
    """**Validates: Requirements 28.1**

    Property 31C: The export archive must always contain all 10 required
    top-level keys regardless of whether the user has any data in each
    category.  Even a user with zero entities in all types must receive an
    archive with every key present (set to an empty list).
    """
    uid_str = str(user_id)
    rows = _build_user_rows(uid_str, counts)
    archive = _run_async(_run_export_for_user(uid_str, rows))

    missing_keys = _REQUIRED_ARCHIVE_KEYS - set(archive.keys())
    assert not missing_keys, (
        f"Property 31C violated: archive is missing keys {missing_keys} for "
        f"user_id={uid_str}, counts={counts}"
    )

    # Each key must be a list (not None or absent)
    for key in _REQUIRED_ARCHIVE_KEYS:
        assert isinstance(archive[key], list), (
            f"Property 31C violated: archive['{key}'] is {type(archive[key]).__name__}, "
            f"expected list. user_id={uid_str}"
        )


# ===========================================================================
# Deterministic edge-case tests (complement the property tests above)
# ===========================================================================


class TestExportCompletenessEdgeCases:
    """Deterministic edge cases complementing the Hypothesis property tests."""

    def test_user_with_no_data_returns_empty_archive(self) -> None:
        """A user with 0 entities in all types receives an archive with all keys as
        empty lists."""
        uid_str = str(uuid.uuid4())
        zero_counts = {
            "conversations": 0,
            "messages": 0,
            "documents": 0,
            "memories": 0,
            "notes": 0,
            "todo_items": 0,
            "calendar_events": 0,
            "reminder": 0,
            "habit_definitions": 0,
            "habit_entries": 0,
        }
        rows = _build_user_rows(uid_str, zero_counts)
        archive = _run_async(_run_export_for_user(uid_str, rows))

        missing = _REQUIRED_ARCHIVE_KEYS - set(archive.keys())
        assert not missing, f"Empty-user archive missing keys: {missing}"

        for key in _REQUIRED_ARCHIVE_KEYS:
            assert archive[key] == [], (
                f"Expected empty list for '{key}' but got {archive[key]!r}"
            )

    def test_user_with_only_conversations_and_messages(self) -> None:
        """When a user has conversations but no other data, messages are linked via
        conversation IDs and should appear in the archive."""
        uid_str = str(uuid.uuid4())
        counts = {
            "conversations": 2,
            "messages": 3,
            "documents": 0,
            "memories": 0,
            "notes": 0,
            "todo_items": 0,
            "calendar_events": 0,
            "reminder": 0,
            "habit_definitions": 0,
            "habit_entries": 0,
        }
        rows = _build_user_rows(uid_str, counts)
        archive = _run_async(_run_export_for_user(uid_str, rows))

        assert len(archive["conversations"]) == 2, (
            f"Expected 2 conversations, got {len(archive['conversations'])}"
        )
        # messages are linked to the first conversation; all 3 should be present
        assert len(archive["messages"]) == 3, (
            f"Expected 3 messages, got {len(archive['messages'])}"
        )
        # All other types should be empty
        for key in (
            "documents",
            "memories",
            "notes",
            "todo_items",
            "calendar_events",
            "reminders",
            "habit_definitions",
            "habit_entries",
        ):
            assert archive[key] == [], (
                f"Expected empty list for '{key}', got {archive[key]!r}"
            )

    def test_two_users_archives_have_no_cross_contamination(self) -> None:
        """Deterministic two-user test: each archive must contain only its own user's
        data with no cross-contamination across any entity type."""
        uid_a = str(uuid.uuid4())
        uid_b = str(uuid.uuid4())

        counts_a = {
            "conversations": 2,
            "messages": 3,
            "documents": 1,
            "memories": 2,
            "notes": 1,
            "todo_items": 1,
            "calendar_events": 0,
            "reminder": 1,
            "habit_definitions": 1,
            "habit_entries": 2,
        }
        counts_b = {
            "conversations": 1,
            "messages": 4,
            "documents": 3,
            "memories": 0,
            "notes": 2,
            "todo_items": 0,
            "calendar_events": 2,
            "reminder": 0,
            "habit_definitions": 0,
            "habit_entries": 1,
        }

        rows_a = _build_user_rows(uid_a, counts_a)
        rows_b = _build_user_rows(uid_b, counts_b)

        archive_a = _run_async(_run_export_for_user(uid_a, rows_a))
        archive_b = _run_async(_run_export_for_user(uid_b, rows_b))

        # Check user_id at the top of each archive
        assert archive_a["user_id"] == uid_a
        assert archive_b["user_id"] == uid_b

        # User A's archive must not contain any row with uid_b
        for key in ("conversations", "documents", "memories", "notes"):
            for entry in archive_a.get(key, []):
                assert entry.get("user_id") != uid_b, (
                    f"Cross-contamination: archive_a['{key}'] contains uid_b={uid_b}"
                )

        # User B's archive must not contain any row with uid_a
        for key in ("conversations", "documents", "memories", "notes"):
            for entry in archive_b.get(key, []):
                assert entry.get("user_id") != uid_a, (
                    f"Cross-contamination: archive_b['{key}'] contains uid_a={uid_a}"
                )

        # Verify correct counts for user A
        assert len(archive_a["conversations"]) == counts_a["conversations"]
        assert len(archive_a["documents"]) == counts_a["documents"]
        assert len(archive_a["memories"]) == counts_a["memories"]
        assert len(archive_a["notes"]) == counts_a["notes"]

        # Verify correct counts for user B
        assert len(archive_b["conversations"]) == counts_b["conversations"]
        assert len(archive_b["documents"]) == counts_b["documents"]
        assert len(archive_b["memories"]) == counts_b["memories"]
        assert len(archive_b["notes"]) == counts_b["notes"]
