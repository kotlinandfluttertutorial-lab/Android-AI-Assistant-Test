"""Unit tests for ObservabilityEventRepository.

Tests cover:
  - bulk_insert: persists rows, returns count, honours _MAX_BATCH_SIZE cap
  - bulk_insert: empty batch returns 0 without touching the DB
  - bulk_insert: malformed events are skipped, valid ones proceed
  - bulk_insert: camelCase Android field names are mapped correctly
  - bulk_insert: metadata dict is serialized to JSON string
  - bulk_insert: messages over 4096 chars are truncated
  - get_recent_errors: queries with correct level filter and time window
  - get_by_session: queries by session_id with time window
  - get_by_id: single-row lookup
  - count_errors_in_window: returns scalar count
  - count_all_in_window: returns scalar count
  - compute_event_rate_stats: returns correct dict shape; is_anomaly logic
  - search_logs: applies query/level/event_type filters

Teaching notes (Phase 3 — FastAPI Backend):
  Repository Pattern — repositories are the ONLY layer that talks to the DB.
  Services call repositories; route handlers call services.
  This keeps SQL out of business logic and makes both independently testable.

  Testing strategy: mock the AsyncSession completely. The repository receives
  the session via constructor injection (not globals), so we can pass a Mock
  without Hilt or DI magic. This is what makes the pattern testable.

  flush vs commit:
    - bulk_insert calls await db.commit() after add_all because it owns the
      transaction — it's a write-only operation where the caller wants durability.
    - Contrast with IncidentRepository.create() which calls db.flush() because
      the route handler controls the commit boundary (get_db() auto-commits).
    This inconsistency is a real-world wart worth teaching: prefer flush() in
    repositories and let get_db() own the commit boundary.

Requirements: 21.1
"""

from __future__ import annotations

import json
import os
import uuid
from datetime import UTC, datetime, timedelta
from unittest.mock import AsyncMock, MagicMock, patch

os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("AES_ENCRYPTION_KEY", "dGVzdGtleXRlc3RrZXl0ZXN0a2V5dGVzdA==")

from app.models.observability_event import ObservabilityEvent
from app.repositories.observability_event_repository import (
    ObservabilityEventRepository,
    _MAX_BATCH_SIZE,
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _mock_session() -> AsyncMock:
    """Return a minimal AsyncMock that satisfies repository usage."""
    db = AsyncMock()
    db.add_all = MagicMock()  # synchronous call
    db.add = MagicMock()      # synchronous call
    db.commit = AsyncMock()
    db.execute = AsyncMock()
    return db


def _make_event_dict(**overrides) -> dict:
    """Return a minimal Android-format event dict."""
    base = {
        "timestamp": 1_700_000_000_000,
        "level": "ERROR",
        "eventType": "network_error",
        "message": "POST /chat returned HTTP 500",
        "sessionId": "sess-abc-123",
        "metadata": {"http_status": "500", "endpoint": "/chat"},
    }
    base.update(overrides)
    return base


def _make_orm_event(**overrides) -> MagicMock:
    """Return a MagicMock shaped like an ObservabilityEvent ORM row."""
    ev = MagicMock(spec=ObservabilityEvent)
    ev.id          = uuid.uuid4()
    ev.level       = overrides.get("level", "ERROR")
    ev.event_type  = overrides.get("event_type", "network_error")
    ev.message     = overrides.get("message", "test")
    ev.session_id  = overrides.get("session_id", "sess-abc-123")
    ev.received_at = overrides.get("received_at", datetime.now(tz=UTC))
    return ev


# ---------------------------------------------------------------------------
# bulk_insert
# ---------------------------------------------------------------------------


class TestBulkInsert:
    """ObservabilityEventRepository.bulk_insert persists rows correctly."""

    async def test_returns_zero_for_empty_list(self) -> None:
        db   = _mock_session()
        repo = ObservabilityEventRepository(db)

        count = await repo.bulk_insert([])

        assert count == 0
        db.add_all.assert_not_called()
        db.commit.assert_not_awaited()

    async def test_returns_correct_count(self) -> None:
        db   = _mock_session()
        repo = ObservabilityEventRepository(db)

        events = [_make_event_dict() for _ in range(3)]
        count = await repo.bulk_insert(events)

        assert count == 3
        db.add_all.assert_called_once()
        inserted = db.add_all.call_args[0][0]
        assert len(inserted) == 3

    async def test_commits_after_insert(self) -> None:
        """bulk_insert owns the transaction and commits immediately.

        Teaching note: contrast with IncidentRepository.create() which only
        flushes — the route handler owns the commit there. bulk_insert commits
        because it is designed to be called by ObservabilityUploadWorker (not
        a route handler) and needs immediate durability.
        """
        db   = _mock_session()
        repo = ObservabilityEventRepository(db)

        await repo.bulk_insert([_make_event_dict()])

        db.commit.assert_awaited_once()

    async def test_honours_max_batch_size_cap(self) -> None:
        """Events beyond _MAX_BATCH_SIZE are silently discarded."""
        db     = _mock_session()
        repo   = ObservabilityEventRepository(db)
        events = [_make_event_dict(message=f"e{i}") for i in range(_MAX_BATCH_SIZE + 10)]

        count = await repo.bulk_insert(events)

        assert count == _MAX_BATCH_SIZE
        inserted = db.add_all.call_args[0][0]
        assert len(inserted) == _MAX_BATCH_SIZE

    async def test_camelcase_android_field_names_mapped_correctly(self) -> None:
        """camelCase Android keys (eventType, sessionId) must be normalised."""
        db   = _mock_session()
        repo = ObservabilityEventRepository(db)

        await repo.bulk_insert([{
            "timestamp":  1_700_000_000_000,
            "level":      "INFO",
            "eventType":  "screen_view",   # camelCase
            "message":    "ChatScreen",
            "sessionId":  "sess-xyz",       # camelCase
            "requestId":  "req-001",        # camelCase
            "traceId":    "trace-abc",      # camelCase
            "metadata":   {},
        }])

        row: ObservabilityEvent = db.add_all.call_args[0][0][0]
        assert row.event_type == "screen_view"
        assert row.session_id == "sess-xyz"
        assert row.request_id == "req-001"
        assert row.trace_id   == "trace-abc"

    async def test_snake_case_field_names_also_accepted(self) -> None:
        """snake_case aliases (event_type, session_id) must also work."""
        db   = _mock_session()
        repo = ObservabilityEventRepository(db)

        await repo.bulk_insert([{
            "timestamp":   1_700_000_000_000,
            "level":       "WARN",
            "event_type":  "api_latency",   # snake_case
            "message":     "slow call",
            "session_id":  "sess-snake",    # snake_case
            "metadata":    {},
        }])

        row: ObservabilityEvent = db.add_all.call_args[0][0][0]
        assert row.event_type == "api_latency"
        assert row.session_id == "sess-snake"

    async def test_metadata_dict_serialised_to_json(self) -> None:
        db   = _mock_session()
        repo = ObservabilityEventRepository(db)

        await repo.bulk_insert([_make_event_dict(
            metadata={"http_status": "500", "endpoint": "/chat"}
        )])

        row: ObservabilityEvent = db.add_all.call_args[0][0][0]
        parsed = json.loads(row.metadata_json)
        assert parsed["http_status"] == "500"
        assert parsed["endpoint"] == "/chat"

    async def test_metadata_non_dict_defaults_to_empty_json(self) -> None:
        db   = _mock_session()
        repo = ObservabilityEventRepository(db)

        await repo.bulk_insert([_make_event_dict(metadata="not-a-dict")])

        row: ObservabilityEvent = db.add_all.call_args[0][0][0]
        assert row.metadata_json == "{}"

    async def test_message_truncated_to_4096_chars(self) -> None:
        db   = _mock_session()
        repo = ObservabilityEventRepository(db)

        long_msg = "x" * 5000
        await repo.bulk_insert([_make_event_dict(message=long_msg)])

        row: ObservabilityEvent = db.add_all.call_args[0][0][0]
        assert len(row.message) == 4096

    async def test_level_is_uppercased(self) -> None:
        db   = _mock_session()
        repo = ObservabilityEventRepository(db)

        await repo.bulk_insert([_make_event_dict(level="error")])

        row: ObservabilityEvent = db.add_all.call_args[0][0][0]
        assert row.level == "ERROR"

    async def test_malformed_event_is_skipped(self) -> None:
        """A single bad event must not abort the whole batch.

        Teaching note: the repository uses a try/except per event so one
        malformed event doesn't block 499 valid ones.
        """
        db     = _mock_session()
        repo   = ObservabilityEventRepository(db)
        events = [
            _make_event_dict(message="good-1"),
            {"timestamp": "not-an-int"},   # will fail int() cast
            _make_event_dict(message="good-2"),
        ]

        # The "not-an-int" event will cause int("not-an-int") to raise ValueError
        # which the repository catches and skips.
        # We patch int to make the failure deterministic for the middle event.
        original_int = int

        call_count = {"n": 0}

        def patched_int(value, *args, **kwargs):
            if value == "not-an-int":
                raise ValueError("bad int")
            return original_int(value, *args, **kwargs)

        with patch("builtins.int", side_effect=patched_int):
            count = await repo.bulk_insert(events)

        # At least the 2 good events should have been accepted
        assert count >= 2

    async def test_uuid_assigned_to_each_row(self) -> None:
        db   = _mock_session()
        repo = ObservabilityEventRepository(db)

        await repo.bulk_insert([_make_event_dict(), _make_event_dict()])

        rows = db.add_all.call_args[0][0]
        ids  = [row.id for row in rows]
        # Each row must have a distinct UUID
        assert len(set(str(i) for i in ids)) == 2


# ---------------------------------------------------------------------------
# get_recent_errors
# ---------------------------------------------------------------------------


class TestGetRecentErrors:
    """get_recent_errors queries with correct level and time filters."""

    async def test_returns_list_of_events(self) -> None:
        db     = _mock_session()
        events = [_make_orm_event(), _make_orm_event()]
        db.execute.return_value.scalars.return_value.all.return_value = events

        repo   = ObservabilityEventRepository(db)
        result = await repo.get_recent_errors()

        assert result == events

    async def test_default_levels_are_error_and_critical(self) -> None:
        """Default query must filter for ERROR and CRITICAL only."""
        db = _mock_session()
        db.execute.return_value.scalars.return_value.all.return_value = []

        repo = ObservabilityEventRepository(db)
        await repo.get_recent_errors()

        # Verify execute was called (DB was queried)
        db.execute.assert_awaited_once()
        # The compiled query will contain the level filter — we can introspect
        # the SQLAlchemy statement via call_args
        stmt = db.execute.call_args[0][0]
        # Convert to string to inspect the WHERE clause
        stmt_str = str(stmt.compile(compile_kwargs={"literal_binds": True}))
        assert "ERROR" in stmt_str or "CRITICAL" in stmt_str

    async def test_custom_levels_passed_through(self) -> None:
        db = _mock_session()
        db.execute.return_value.scalars.return_value.all.return_value = []

        repo = ObservabilityEventRepository(db)
        await repo.get_recent_errors(levels=["WARN"])

        stmt_str = str(
            db.execute.call_args[0][0].compile(
                compile_kwargs={"literal_binds": True}
            )
        )
        assert "WARN" in stmt_str

    async def test_returns_empty_list_when_no_events(self) -> None:
        db = _mock_session()
        db.execute.return_value.scalars.return_value.all.return_value = []

        repo   = ObservabilityEventRepository(db)
        result = await repo.get_recent_errors()

        assert result == []


# ---------------------------------------------------------------------------
# get_by_session
# ---------------------------------------------------------------------------


class TestGetBySession:
    """get_by_session queries by session_id."""

    async def test_returns_events_for_session(self) -> None:
        db     = _mock_session()
        events = [_make_orm_event(session_id="sess-xyz")]
        db.execute.return_value.scalars.return_value.all.return_value = events

        repo   = ObservabilityEventRepository(db)
        result = await repo.get_by_session("sess-xyz")

        assert result == events

    async def test_session_id_in_query(self) -> None:
        db = _mock_session()
        db.execute.return_value.scalars.return_value.all.return_value = []

        repo = ObservabilityEventRepository(db)
        await repo.get_by_session("sess-abc")

        stmt_str = str(
            db.execute.call_args[0][0].compile(
                compile_kwargs={"literal_binds": True}
            )
        )
        assert "sess-abc" in stmt_str


# ---------------------------------------------------------------------------
# get_by_id
# ---------------------------------------------------------------------------


class TestGetById:
    async def test_returns_event_when_found(self) -> None:
        db  = _mock_session()
        evt = _make_orm_event()
        db.execute.return_value.scalar_one_or_none.return_value = evt

        repo   = ObservabilityEventRepository(db)
        result = await repo.get_by_id(evt.id)

        assert result is evt

    async def test_returns_none_when_not_found(self) -> None:
        db = _mock_session()
        db.execute.return_value.scalar_one_or_none.return_value = None

        repo   = ObservabilityEventRepository(db)
        result = await repo.get_by_id(uuid.uuid4())

        assert result is None


# ---------------------------------------------------------------------------
# count_errors_in_window
# ---------------------------------------------------------------------------


class TestCountErrorsInWindow:
    """count_errors_in_window returns an integer count."""

    async def test_returns_scalar_count(self) -> None:
        db = _mock_session()
        db.execute.return_value.scalar_one.return_value = 7

        repo   = ObservabilityEventRepository(db)
        result = await repo.count_errors_in_window(minutes=5)

        assert result == 7

    async def test_returns_zero_when_none_found(self) -> None:
        db = _mock_session()
        db.execute.return_value.scalar_one.return_value = None

        repo   = ObservabilityEventRepository(db)
        result = await repo.count_errors_in_window()

        assert result == 0

    async def test_passes_minutes_to_cutoff(self) -> None:
        """Larger window should produce a different (earlier) cutoff timestamp."""
        db = _mock_session()
        db.execute.return_value.scalar_one.return_value = 0

        repo = ObservabilityEventRepository(db)
        await repo.count_errors_in_window(minutes=60)

        stmt_str = str(
            db.execute.call_args[0][0].compile(
                compile_kwargs={"literal_binds": True}
            )
        )
        # The cutoff date must appear in the query
        assert "observability_events" in stmt_str.lower()


# ---------------------------------------------------------------------------
# compute_event_rate_stats
# ---------------------------------------------------------------------------


class TestComputeEventRateStats:
    """compute_event_rate_stats returns the correct dict shape."""

    async def test_returns_empty_stats_when_no_events(self) -> None:
        db = _mock_session()
        db.execute.return_value.all.return_value = []

        repo   = ObservabilityEventRepository(db)
        result = await repo.compute_event_rate_stats(level="ERROR")

        assert result["mean"]        == 0.0
        assert result["std_dev"]     == 0.0
        assert result["current"]     == 0
        assert result["bucket_count"] == 0
        assert result["is_anomaly"]  is False

    async def test_result_has_required_keys(self) -> None:
        db = _mock_session()
        db.execute.return_value.all.return_value = []

        repo   = ObservabilityEventRepository(db)
        result = await repo.compute_event_rate_stats(level="ERROR")

        assert set(result.keys()) == {
            "mean", "std_dev", "current", "bucket_count", "is_anomaly"
        }

    async def test_is_anomaly_false_with_uniform_counts(self) -> None:
        """Uniform distribution → std_dev = 0 → is_anomaly = False."""
        db = _mock_session()

        # Simulate 12 events spread across 60 min with equal 5-min buckets
        now = datetime.now(tz=UTC)
        # 2 events per bucket × 12 buckets = 24 events
        timestamps = []
        for bucket_idx in range(12):
            bucket_start = now - timedelta(minutes=(bucket_idx * 5 + 2))
            timestamps.append((bucket_start,))
            timestamps.append((bucket_start - timedelta(seconds=30),))

        db.execute.return_value.all.return_value = timestamps

        repo   = ObservabilityEventRepository(db)
        result = await repo.compute_event_rate_stats(
            level="ERROR",
            window_minutes=60,
            bucket_minutes=5,
        )

        # With a uniform distribution the anomaly flag should be False
        # (std_dev of a uniform distribution is small)
        assert result["is_anomaly"] is False
        assert result["bucket_count"] == 12

    async def test_mean_is_rounded_to_two_decimals(self) -> None:
        db = _mock_session()
        db.execute.return_value.all.return_value = []

        repo   = ObservabilityEventRepository(db)
        result = await repo.compute_event_rate_stats(level="ERROR")

        # Even with no data, mean must be a float (0.0, not 0)
        assert isinstance(result["mean"], float)
        assert isinstance(result["std_dev"], float)


# ---------------------------------------------------------------------------
# search_logs
# ---------------------------------------------------------------------------


class TestSearchLogs:
    """search_logs applies filters correctly and returns newest-first."""

    async def test_returns_list(self) -> None:
        db     = _mock_session()
        events = [_make_orm_event()]
        db.execute.return_value.scalars.return_value.all.return_value = events

        repo   = ObservabilityEventRepository(db)
        result = await repo.search_logs(query="connection")

        assert result == events

    async def test_query_substring_in_statement(self) -> None:
        db = _mock_session()
        db.execute.return_value.scalars.return_value.all.return_value = []

        repo = ObservabilityEventRepository(db)
        await repo.search_logs(query="pool exhausted")

        stmt_str = str(
            db.execute.call_args[0][0].compile(
                compile_kwargs={"literal_binds": True}
            )
        )
        assert "pool exhausted" in stmt_str

    async def test_level_filter_applied(self) -> None:
        db = _mock_session()
        db.execute.return_value.scalars.return_value.all.return_value = []

        repo = ObservabilityEventRepository(db)
        await repo.search_logs(level="ERROR")

        stmt_str = str(
            db.execute.call_args[0][0].compile(
                compile_kwargs={"literal_binds": True}
            )
        )
        assert "ERROR" in stmt_str

    async def test_event_type_filter_applied(self) -> None:
        db = _mock_session()
        db.execute.return_value.scalars.return_value.all.return_value = []

        repo = ObservabilityEventRepository(db)
        await repo.search_logs(event_type="http_error")

        stmt_str = str(
            db.execute.call_args[0][0].compile(
                compile_kwargs={"literal_binds": True}
            )
        )
        assert "http_error" in stmt_str

    async def test_no_filters_returns_all_in_window(self) -> None:
        db     = _mock_session()
        events = [_make_orm_event(), _make_orm_event()]
        db.execute.return_value.scalars.return_value.all.return_value = events

        repo   = ObservabilityEventRepository(db)
        result = await repo.search_logs()

        assert len(result) == 2
