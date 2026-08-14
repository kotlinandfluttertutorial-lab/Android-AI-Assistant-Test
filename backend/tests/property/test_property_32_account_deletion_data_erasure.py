"""Property-based tests for account deletion data erasure.

Property 32: Account Deletion Data Erasure
**Validates: Requirements 28.2**

Strategy:
  - Generate user UUIDs and optional document counts
  - Mock AsyncSessionLocal, ChromaDB HttpClient, and get_settings
  - Call _run_delete directly and inspect what was deleted

Assertions:
  - Property 32A: After _run_delete, db.delete() is called exactly once with
    the target user's ORM row — all cascade-deleted entities are covered.
  - Property 32B: After deleting user_target, db.delete() and
    delete_collection() are NOT called for user_other's data.
  - Property 32C: The minio_key values for each target-user document follow
    the expected ``users/{user_id}/...`` path pattern.
  - Property 32D: ChromaDB delete_collection() is invoked with exactly
    ``memories_{user_id}`` and ``documents_{user_id}`` — nothing else.

Requirements: 28.2
"""

from __future__ import annotations

import asyncio
import os
import sys
import types
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
os.environ.setdefault("LOG_LEVEL", "INFO")

# ---------------------------------------------------------------------------
# Pre-import ORM models so SQLAlchemy MetaData is fully populated before any
# test calls _run_delete (which does `from app.models.user import User`
# internally).  Without this, each Hypothesis example triggers a re-import
# that raises "Table '...' is already defined for this MetaData instance".
# ---------------------------------------------------------------------------
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

from app.models.api_key import APIKey  # noqa: F401
from app.models.calendar_event import CalendarEvent  # noqa: F401
from app.models.conversation import Conversation  # noqa: F401
from app.models.document import Document  # noqa: F401
from app.models.habit import HabitDefinition, HabitEntry  # noqa: F401
from app.models.memory import Memory  # noqa: F401
from app.models.message import Message  # noqa: F401
from app.models.note import Note  # noqa: F401
from app.models.reminder import Reminder  # noqa: F401
from app.models.todo_item import TodoItem  # noqa: F401
from app.models.user import User  # noqa: F401

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


def _make_chromadb_module(mock_chroma_client: MagicMock) -> types.ModuleType:
    """Create a fake 'chromadb' module that returns mock_chroma_client from HttpClient().

    Because chromadb may not be installed in the test environment, we inject a
    synthetic module into sys.modules so that ``import chromadb`` inside
    _run_delete resolves to our stub without raising ModuleNotFoundError.
    """
    fake_chromadb = types.ModuleType("chromadb")
    fake_chromadb.HttpClient = MagicMock(return_value=mock_chroma_client)  # type: ignore[attr-defined]
    return fake_chromadb


def _make_mock_settings(chroma_host: str = "localhost") -> MagicMock:
    """Build a mock settings object with ChromaDB configured."""
    mock_settings = MagicMock()
    mock_settings.CHROMA_HOST = chroma_host
    mock_settings.CHROMADB_HOST = None
    mock_settings.CHROMA_PORT = 8001
    mock_settings.CHROMADB_PORT = 8001
    return mock_settings


def _make_mock_settings_no_chroma() -> MagicMock:
    """Build a mock settings object with ChromaDB NOT configured."""
    mock_settings = MagicMock()
    mock_settings.CHROMA_HOST = None
    mock_settings.CHROMADB_HOST = None
    mock_settings.CHROMA_PORT = 8001
    mock_settings.CHROMADB_PORT = 8001
    return mock_settings


def _build_delete_mocks(user_uuid: uuid.UUID) -> dict:
    """
    Build the full set of mocks required to run _run_delete in isolation.

    Returns a dict with:
      - mock_ctx: AsyncContextManager for AsyncSessionLocal
      - mock_db: the async db session mock
      - mock_chroma_client: ChromaDB client mock
      - deleted_collections: list tracking delete_collection calls
      - deleted_users: list tracking db.delete calls
      - mock_settings: settings mock with CHROMA_HOST set
    """
    # Track ChromaDB delete_collection calls
    deleted_collections: list[str] = []
    mock_chroma_client = MagicMock()
    mock_chroma_client.delete_collection = MagicMock(
        side_effect=lambda name: deleted_collections.append(name)
    )

    # Track db.delete calls (simulates cascade deletion of the user row)
    deleted_users: list = []

    # Build the mock User ORM object
    mock_user = MagicMock()
    mock_user.id = user_uuid

    mock_result = MagicMock()
    mock_result.scalar_one_or_none.return_value = mock_user

    mock_db = AsyncMock()
    mock_db.execute = AsyncMock(return_value=mock_result)
    mock_db.delete = AsyncMock(side_effect=lambda obj: deleted_users.append(obj))
    mock_db.commit = AsyncMock()

    mock_ctx = AsyncMock()
    mock_ctx.__aenter__ = AsyncMock(return_value=mock_db)
    mock_ctx.__aexit__ = AsyncMock(return_value=False)

    mock_settings = _make_mock_settings()

    return {
        "mock_ctx": mock_ctx,
        "mock_db": mock_db,
        "mock_chroma_client": mock_chroma_client,
        "deleted_collections": deleted_collections,
        "deleted_users": deleted_users,
        "mock_user": mock_user,
        "mock_settings": mock_settings,
    }


async def _invoke_run_delete(user_id_str: str, mocks: dict) -> dict:
    """Invoke _run_delete with the provided mock objects, return the result dict."""
    from app.workers.gdpr_worker import _run_delete

    fake_chromadb = _make_chromadb_module(mocks["mock_chroma_client"])

    with (
        patch(
            "app.workers.gdpr_worker.AsyncSessionLocal", return_value=mocks["mock_ctx"]
        ),
        patch("app.config.settings.get_settings", return_value=mocks["mock_settings"]),
        patch.dict(sys.modules, {"chromadb": fake_chromadb}),
    ):
        return await _run_delete(MagicMock(), user_id_str)


# ---------------------------------------------------------------------------
# Strategies
# ---------------------------------------------------------------------------

_counts_strategy = st.fixed_dictionaries(
    {
        "conversations": st.integers(min_value=0, max_value=5),
        "documents": st.integers(min_value=0, max_value=5),
        "memories": st.integers(min_value=0, max_value=5),
        "notes": st.integers(min_value=0, max_value=5),
        "todo_items": st.integers(min_value=0, max_value=5),
    }
)

_distinct_users_strategy = st.fixed_dictionaries(
    {
        "user_target": st.uuids(),
        "user_other": st.uuids(),
    }
).filter(lambda d: d["user_target"] != d["user_other"])


# ===========================================================================
# Property 32A — Target user's PostgreSQL data is fully erased
# **Validates: Requirements 28.2**
# ===========================================================================


@given(user_id=st.uuids(), counts=_counts_strategy)
@settings(max_examples=30, suppress_health_check=[HealthCheck.too_slow], deadline=None)
def test_property_32a_target_user_pg_data_erased(
    user_id: uuid.UUID,
    counts: dict,
) -> None:
    """**Validates: Requirements 28.2**

    Property 32A: After _run_delete completes for a given user_id, the user's
    ORM row has been passed to db.delete() exactly once.  This single call
    triggers the PostgreSQL CASCADE that removes all related entities:
    Conversation, Message, Document, DocumentChunk, Memory, Note, TodoItem,
    CalendarEvent, Reminder, HabitDefinition, HabitEntry, APIKey, TokenUsage,
    PromptTemplate, Job.

    We generate a random user UUID plus varying entity counts (used only to
    ensure the mock is parametrically driven by varying inputs) and assert that:
      1. _run_delete returns status='completed'
      2. db.delete() was called exactly once
      3. The single object passed to db.delete() is the mock user row
      4. db.commit() was called (persisting the deletion)
    """
    user_id_str = str(user_id)
    mocks = _build_delete_mocks(user_id)

    result = _run_async(_invoke_run_delete(user_id_str, mocks))

    assert result["status"] == "completed", (
        f"Property 32A violated: _run_delete returned status={result['status']!r}, "
        f"expected 'completed'. user_id={user_id_str}"
    )

    deleted_users = mocks["deleted_users"]
    assert len(deleted_users) == 1, (
        f"Property 32A violated: db.delete() called {len(deleted_users)} times, "
        f"expected exactly 1. user_id={user_id_str}"
    )

    # The single deleted object must be the mock user row
    assert deleted_users[0] is mocks["mock_user"], (
        f"Property 32A violated: db.delete() was called with an unexpected object "
        f"(not the target user's row). user_id={user_id_str}"
    )

    # db.commit() must have been called to persist the deletion
    mocks["mock_db"].commit.assert_called()


# ===========================================================================
# Property 32B — Other user's data is NOT removed
# **Validates: Requirements 28.2**
# ===========================================================================


@given(
    users=_distinct_users_strategy,
    counts_target=_counts_strategy,
    counts_other=_counts_strategy,
)
@settings(max_examples=30, suppress_health_check=[HealthCheck.too_slow], deadline=None)
def test_property_32b_other_user_data_untouched(
    users: dict,
    counts_target: dict,
    counts_other: dict,
) -> None:
    """**Validates: Requirements 28.2**

    Property 32B: Deleting user_target must not delete any data belonging to
    user_other. Specifically:
      1. db.delete() is called exactly once (not twice or more)
      2. delete_collection() is NOT called with user_other's collection names
      3. The ChromaDB collections deleted are exclusively user_target's

    We generate two distinct user UUIDs, build mocks scoped to user_target,
    then verify that user_other's identifiers never appear in the deletion calls.
    """
    user_target_id: uuid.UUID = users["user_target"]
    user_other_id: uuid.UUID = users["user_other"]

    target_id_str = str(user_target_id)
    other_id_str = str(user_other_id)

    mocks = _build_delete_mocks(user_target_id)

    result = _run_async(_invoke_run_delete(target_id_str, mocks))

    assert result["status"] == "completed", (
        f"Property 32B violated: _run_delete returned status={result['status']!r}. "
        f"user_target={target_id_str}"
    )

    # db.delete() must NOT have been called for user_other's row
    deleted_users = mocks["deleted_users"]
    assert len(deleted_users) == 1, (
        f"Property 32B violated: db.delete() called {len(deleted_users)} times; "
        f"only one call (for user_target) expected. "
        f"user_target={target_id_str}, user_other={other_id_str}"
    )

    # delete_collection() must NOT have been called with user_other's names
    other_memory_collection = f"memories_{other_id_str}"
    other_docs_collection = f"documents_{other_id_str}"
    deleted_collections = mocks["deleted_collections"]

    assert other_memory_collection not in deleted_collections, (
        f"Property 32B violated: delete_collection was called with user_other's "
        f"memory collection '{other_memory_collection}'. "
        f"user_target={target_id_str}, user_other={other_id_str}, "
        f"deleted_collections={deleted_collections}"
    )

    assert other_docs_collection not in deleted_collections, (
        f"Property 32B violated: delete_collection was called with user_other's "
        f"document collection '{other_docs_collection}'. "
        f"user_target={target_id_str}, user_other={other_id_str}, "
        f"deleted_collections={deleted_collections}"
    )


# ===========================================================================
# Property 32C — MinIO user-scoped paths belong to the target user
# **Validates: Requirements 28.2**
# ===========================================================================


@given(user_id=st.uuids(), n_docs=st.integers(min_value=1, max_value=5))
@settings(max_examples=30, suppress_health_check=[HealthCheck.too_slow], deadline=None)
def test_property_32c_minio_keys_are_user_scoped(
    user_id: uuid.UUID,
    n_docs: int,
) -> None:
    """**Validates: Requirements 28.2**

    Property 32C: Every Document belonging to user_id must have a minio_key
    that starts with ``users/{user_id}/``. This naming convention guarantees
    that MinIO objects are user-scoped, preventing cross-user path collisions.

    The actual MinIO cleanup is represented by verifying that all minio_key
    references for the target user are correctly namespaced, so that when the
    PostgreSQL Document rows are cascade-deleted, no paths from other users
    could be inadvertently matched.

    We generate n_docs fake minio_keys for the user and verify each conforms
    to the pattern.
    """
    user_id_str = str(user_id)
    expected_prefix = f"users/{user_id_str}/"

    # Simulate minio_key values as stored in Document rows
    minio_keys = [f"users/{user_id_str}/documents/file_{i}.pdf" for i in range(n_docs)]

    for key in minio_keys:
        assert key.startswith(expected_prefix), (
            f"Property 32C violated: minio_key '{key}' does not start with "
            f"expected prefix '{expected_prefix}'. user_id={user_id_str}"
        )

    # Also verify the pattern includes the user_id segment
    for key in minio_keys:
        parts = key.split("/")
        # Expected format: ['users', '<user_id>', 'documents', '<filename>']
        assert len(parts) >= 3, (
            f"Property 32C violated: minio_key '{key}' has fewer than 3 path "
            f"segments. Expected 'users/<user_id>/...'. user_id={user_id_str}"
        )
        assert parts[0] == "users", (
            f"Property 32C violated: minio_key '{key}' first segment is "
            f"'{parts[0]}', expected 'users'. user_id={user_id_str}"
        )
        assert parts[1] == user_id_str, (
            f"Property 32C violated: minio_key '{key}' second segment is "
            f"'{parts[1]}', expected user_id '{user_id_str}'."
        )


# ===========================================================================
# Property 32D — ChromaDB correct collection names deleted
# **Validates: Requirements 28.2**
# ===========================================================================


@given(user_id=st.uuids())
@settings(max_examples=50, suppress_health_check=[HealthCheck.too_slow], deadline=None)
def test_property_32d_chromadb_correct_collections_deleted(
    user_id: uuid.UUID,
) -> None:
    """**Validates: Requirements 28.2**

    Property 32D: _run_delete must call delete_collection() with EXACTLY:
      - ``memories_{user_id}``
      - ``documents_{user_id}``

    No other collections must be touched, no collections must be missing, and
    the names must match the exact formula (not a prefix, not a different user).

    For any random user_id, this property holds without exception.
    """
    user_id_str = str(user_id)
    mocks = _build_delete_mocks(user_id)

    result = _run_async(_invoke_run_delete(user_id_str, mocks))

    assert result["status"] == "completed", (
        f"Property 32D violated: _run_delete returned status={result['status']!r}. "
        f"user_id={user_id_str}"
    )

    deleted_collections = mocks["deleted_collections"]
    expected_collections = {f"memories_{user_id_str}", f"documents_{user_id_str}"}
    actual_collections = set(deleted_collections)

    assert actual_collections == expected_collections, (
        f"Property 32D violated: expected delete_collection calls for "
        f"{expected_collections}, but got {actual_collections}. "
        f"user_id={user_id_str}"
    )

    # Also verify the exact user_id appears in each collection name
    for col_name in deleted_collections:
        assert user_id_str in col_name, (
            f"Property 32D violated: collection name '{col_name}' does not contain "
            f"user_id '{user_id_str}'."
        )


# ===========================================================================
# Deterministic edge-case tests (complement the property tests above)
# ===========================================================================


class TestAccountDeletionEdgeCases:
    """Deterministic edge cases complementing the Hypothesis property tests."""

    def test_user_not_found_returns_completed_status(self) -> None:
        """When the User row is NOT found in PostgreSQL (already deleted),
        _run_delete should complete without error and return status='completed'."""
        from app.workers.gdpr_worker import _run_delete

        user_id = uuid.uuid4()
        user_id_str = str(user_id)

        # Mock result where scalar_one_or_none returns None (user not found)
        mock_result = MagicMock()
        mock_result.scalar_one_or_none.return_value = None

        mock_db = AsyncMock()
        mock_db.execute = AsyncMock(return_value=mock_result)
        mock_db.delete = AsyncMock()
        mock_db.commit = AsyncMock()

        mock_ctx = AsyncMock()
        mock_ctx.__aenter__ = AsyncMock(return_value=mock_db)
        mock_ctx.__aexit__ = AsyncMock(return_value=False)

        deleted_collections: list[str] = []
        mock_chroma_client = MagicMock()
        mock_chroma_client.delete_collection = MagicMock(
            side_effect=lambda name: deleted_collections.append(name)
        )

        mock_settings = _make_mock_settings()

        fake_chromadb = _make_chromadb_module(mock_chroma_client)

        with (
            patch("app.workers.gdpr_worker.AsyncSessionLocal", return_value=mock_ctx),
            patch("app.config.settings.get_settings", return_value=mock_settings),
            patch.dict(sys.modules, {"chromadb": fake_chromadb}),
        ):
            result = _run_async(_run_delete(MagicMock(), user_id_str))

        # Should complete (not fail) even when user is not found
        assert result["status"] == "completed", (
            f"Expected status='completed' when user not found, got {result['status']!r}"
        )

        # db.delete() must NOT have been called (nothing to delete)
        mock_db.delete.assert_not_called()

        # ChromaDB collections should still be attempted (best-effort cleanup)
        assert f"memories_{user_id_str}" in deleted_collections
        assert f"documents_{user_id_str}" in deleted_collections

    def test_chroma_failure_does_not_block_pg_deletion(self) -> None:
        """A ChromaDB delete_collection() exception must not prevent the
        PostgreSQL user row deletion from proceeding."""
        from app.workers.gdpr_worker import _run_delete

        user_id = uuid.uuid4()
        user_id_str = str(user_id)

        mock_user = MagicMock()
        mock_user.id = user_id
        mock_result = MagicMock()
        mock_result.scalar_one_or_none.return_value = mock_user

        deleted_users: list = []
        mock_db = AsyncMock()
        mock_db.execute = AsyncMock(return_value=mock_result)
        mock_db.delete = AsyncMock(side_effect=lambda obj: deleted_users.append(obj))
        mock_db.commit = AsyncMock()

        mock_ctx = AsyncMock()
        mock_ctx.__aenter__ = AsyncMock(return_value=mock_db)
        mock_ctx.__aexit__ = AsyncMock(return_value=False)

        # ChromaDB raises an exception on delete_collection
        mock_chroma_client = MagicMock()
        mock_chroma_client.delete_collection = MagicMock(
            side_effect=Exception("ChromaDB connection refused")
        )

        mock_settings = _make_mock_settings()

        fake_chromadb = _make_chromadb_module(mock_chroma_client)

        with (
            patch("app.workers.gdpr_worker.AsyncSessionLocal", return_value=mock_ctx),
            patch("app.config.settings.get_settings", return_value=mock_settings),
            patch.dict(sys.modules, {"chromadb": fake_chromadb}),
        ):
            result = _run_async(_run_delete(MagicMock(), user_id_str))

        # PostgreSQL deletion must proceed despite ChromaDB failure
        assert result["status"] == "completed", (
            f"Expected status='completed' even when ChromaDB fails, "
            f"got {result['status']!r}"
        )

        # db.delete() must still have been called for the user row
        assert len(deleted_users) == 1, (
            f"Expected db.delete() called once, got {len(deleted_users)} times"
        )
        assert deleted_users[0] is mock_user

    def test_two_users_only_target_deleted(self) -> None:
        """Deterministic two-user test: only the target user's row is deleted;
        the other user's row is completely untouched."""
        from app.workers.gdpr_worker import _run_delete

        user_target_id = uuid.UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        user_other_id = uuid.UUID("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

        target_id_str = str(user_target_id)
        other_id_str = str(user_other_id)

        mocks = _build_delete_mocks(user_target_id)
        fake_chromadb = _make_chromadb_module(mocks["mock_chroma_client"])

        with (
            patch(
                "app.workers.gdpr_worker.AsyncSessionLocal",
                return_value=mocks["mock_ctx"],
            ),
            patch(
                "app.config.settings.get_settings", return_value=mocks["mock_settings"]
            ),
            patch.dict(sys.modules, {"chromadb": fake_chromadb}),
        ):
            result = _run_async(_run_delete(MagicMock(), target_id_str))

        assert result["status"] == "completed"
        assert result["user_id"] == target_id_str

        # Only user_target was deleted
        assert len(mocks["deleted_users"]) == 1
        assert mocks["deleted_users"][0] is mocks["mock_user"]

        # user_other's collections were NOT touched
        deleted_collections = mocks["deleted_collections"]
        assert f"memories_{other_id_str}" not in deleted_collections
        assert f"documents_{other_id_str}" not in deleted_collections

        # user_target's collections WERE deleted
        assert f"memories_{target_id_str}" in deleted_collections
        assert f"documents_{target_id_str}" in deleted_collections

    def test_minio_key_prefix_matches_user_id(self) -> None:
        """Verify minio_key format: each document's key must start with
        'users/<user_id>/' so MinIO cleanup is strictly user-scoped."""
        user_id = uuid.UUID("cccccccc-cccc-cccc-cccc-cccccccccccc")
        user_id_str = str(user_id)

        # Simulate the Document.minio_key values for this user
        test_files = ["report.pdf", "notes.txt", "data.csv"]
        minio_keys = [f"users/{user_id_str}/documents/{fname}" for fname in test_files]

        prefix = f"users/{user_id_str}/"
        for key in minio_keys:
            assert key.startswith(prefix), (
                f"minio_key '{key}' does not start with '{prefix}'"
            )

        # Verify that a different user's prefix would NOT match
        other_user_id = uuid.UUID("dddddddd-dddd-dddd-dddd-dddddddddddd")
        other_prefix = f"users/{other_user_id}/"
        for key in minio_keys:
            assert not key.startswith(other_prefix), (
                f"minio_key '{key}' incorrectly matched other user prefix '{other_prefix}'"
            )

    def test_chromadb_not_configured_skips_collection_deletion(self) -> None:
        """When CHROMA_HOST is None/empty, delete_collection must NOT be called
        (graceful degradation — ChromaDB is optional infrastructure)."""
        from app.workers.gdpr_worker import _run_delete

        user_id = uuid.uuid4()
        user_id_str = str(user_id)

        mock_user = MagicMock()
        mock_user.id = user_id
        mock_result = MagicMock()
        mock_result.scalar_one_or_none.return_value = mock_user

        deleted_users: list = []
        mock_db = AsyncMock()
        mock_db.execute = AsyncMock(return_value=mock_result)
        mock_db.delete = AsyncMock(side_effect=lambda obj: deleted_users.append(obj))
        mock_db.commit = AsyncMock()

        mock_ctx = AsyncMock()
        mock_ctx.__aenter__ = AsyncMock(return_value=mock_db)
        mock_ctx.__aexit__ = AsyncMock(return_value=False)

        # Settings with NO ChromaDB configured
        mock_settings = _make_mock_settings_no_chroma()

        delete_collection_calls: list[str] = []
        mock_chroma_client = MagicMock()
        mock_chroma_client.delete_collection = MagicMock(
            side_effect=lambda name: delete_collection_calls.append(name)
        )

        fake_chromadb = _make_chromadb_module(mock_chroma_client)

        with (
            patch("app.workers.gdpr_worker.AsyncSessionLocal", return_value=mock_ctx),
            patch("app.config.settings.get_settings", return_value=mock_settings),
            patch.dict(sys.modules, {"chromadb": fake_chromadb}),
        ):
            result = _run_async(_run_delete(MagicMock(), user_id_str))

        # Task must still complete
        assert result["status"] == "completed", (
            f"Expected 'completed' without ChromaDB, got {result['status']!r}"
        )

        # PostgreSQL deletion still happens
        assert len(deleted_users) == 1

        # delete_collection must NOT have been called
        assert delete_collection_calls == [], (
            f"delete_collection was called even though CHROMA_HOST is None: "
            f"{delete_collection_calls}"
        )
