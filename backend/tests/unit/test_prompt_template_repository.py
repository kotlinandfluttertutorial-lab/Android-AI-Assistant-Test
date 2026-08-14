"""Unit tests for app.repositories.prompt_template_repository.PromptTemplateRepository.

Uses AsyncMock to validate all repository logic without a live database.
The SQLAlchemy AsyncSession is mocked throughout.

Covers:
- get_active: returns active row; raises TemplateNotFoundError when missing
- get_version: returns the exact version row; raises TemplateNotFoundError when missing
- list_versions: returns all rows for a name in version order; empty list when none
- list_names: returns sorted distinct names; empty list when no templates
- create_version: first version is 1; increments max+1; deactivates prior active;
  new row has is_active=True and correct content/author
- rollback: creates new version with historical content; raises when target version missing;
  uses provided author_id when supplied; falls back to target author_id when not supplied

Requirements: 25.1, 25.2
"""

from __future__ import annotations

import os
import uuid
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

from app.repositories.prompt_template_repository import (
    PromptTemplateRepository,
    TemplateNotFoundError,
)

# ---------------------------------------------------------------------------
# Helpers / fixtures
# ---------------------------------------------------------------------------

AUTHOR_ID = uuid.UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
OTHER_AUTHOR_ID = uuid.UUID("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
_NOW = datetime(2024, 6, 1, tzinfo=timezone.utc)


def _make_template(
    name: str = "my_template",
    version: int = 1,
    content: str = "Hello {{ name }}",
    is_active: bool = True,
    author_id: uuid.UUID = AUTHOR_ID,
) -> MagicMock:
    """Build a lightweight mock PromptTemplate ORM row."""
    row = MagicMock()
    row.id = uuid.uuid4()
    row.name = name
    row.version = version
    row.content = content
    row.is_active = is_active
    row.author_id = author_id
    row.created_at = _NOW
    row.updated_at = _NOW
    return row


def _db_returns_scalar(value) -> AsyncMock:
    """Mock db whose execute().scalar_one_or_none() returns *value*."""
    db = AsyncMock()
    result = MagicMock()
    result.scalar_one_or_none.return_value = value
    db.execute.return_value = result
    return db


def _db_returns_scalars(rows: list) -> AsyncMock:
    """Mock db whose execute().scalars().all() returns *rows*."""
    db = AsyncMock()
    result = MagicMock()
    scalars = MagicMock()
    scalars.all.return_value = rows
    result.scalars.return_value = scalars
    db.execute.return_value = result
    return db


def _make_db_for_create(
    existing_rows: list,
    new_template: MagicMock,
) -> AsyncMock:
    """Build a mock db suitable for create_version tests.

    - list_versions is patched separately (not a db-level concern here).
    - db.execute() handles the UPDATE deactivate call.
    - db.refresh() populates the new row with attributes from *new_template*.
    """
    db = AsyncMock()
    db.execute.return_value = MagicMock()  # UPDATE result — return value ignored

    async def _refresh(obj):
        obj.id = new_template.id
        obj.name = new_template.name
        obj.version = new_template.version
        obj.content = new_template.content
        obj.is_active = new_template.is_active
        obj.author_id = new_template.author_id
        obj.created_at = new_template.created_at
        obj.updated_at = new_template.updated_at

    db.refresh.side_effect = _refresh
    return db


# ---------------------------------------------------------------------------
# get_active
# ---------------------------------------------------------------------------


class TestGetActive:
    """Tests for PromptTemplateRepository.get_active."""

    @pytest.mark.asyncio
    async def test_returns_active_template(self) -> None:
        """Returns the active row when it exists."""
        template = _make_template(is_active=True)
        db = _db_returns_scalar(template)

        result = await PromptTemplateRepository(db).get_active("my_template")

        assert result is template

    @pytest.mark.asyncio
    async def test_raises_when_no_active_template(self) -> None:
        """Raises TemplateNotFoundError when no active row exists."""
        db = _db_returns_scalar(None)

        with pytest.raises(TemplateNotFoundError, match="my_template"):
            await PromptTemplateRepository(db).get_active("my_template")

    @pytest.mark.asyncio
    async def test_executes_one_query(self) -> None:
        """Only one database query is issued."""
        template = _make_template(is_active=True)
        db = _db_returns_scalar(template)

        await PromptTemplateRepository(db).get_active("my_template")

        db.execute.assert_called_once()


# ---------------------------------------------------------------------------
# get_version
# ---------------------------------------------------------------------------


class TestGetVersion:
    """Tests for PromptTemplateRepository.get_version."""

    @pytest.mark.asyncio
    async def test_returns_specific_version(self) -> None:
        """Returns the exact version row when it exists."""
        template = _make_template(version=3, is_active=False)
        db = _db_returns_scalar(template)

        result = await PromptTemplateRepository(db).get_version("my_template", 3)

        assert result is template

    @pytest.mark.asyncio
    async def test_raises_when_version_missing(self) -> None:
        """Raises TemplateNotFoundError when the requested version does not exist."""
        db = _db_returns_scalar(None)

        with pytest.raises(TemplateNotFoundError, match="version 42"):
            await PromptTemplateRepository(db).get_version("my_template", 42)

    @pytest.mark.asyncio
    async def test_raises_error_includes_template_name(self) -> None:
        """Error message includes the template name."""
        db = _db_returns_scalar(None)

        with pytest.raises(TemplateNotFoundError, match="my_template"):
            await PromptTemplateRepository(db).get_version("my_template", 5)


# ---------------------------------------------------------------------------
# list_versions
# ---------------------------------------------------------------------------


class TestListVersions:
    """Tests for PromptTemplateRepository.list_versions."""

    @pytest.mark.asyncio
    async def test_returns_all_versions(self) -> None:
        """Returns every row for the named template."""
        rows = [
            _make_template(version=1, is_active=False),
            _make_template(version=2, is_active=False),
            _make_template(version=3, is_active=True),
        ]
        db = _db_returns_scalars(rows)

        result = await PromptTemplateRepository(db).list_versions("my_template")

        assert result == rows
        assert len(result) == 3

    @pytest.mark.asyncio
    async def test_returns_empty_list_when_none_exist(self) -> None:
        """Returns an empty list when no versions exist for the name."""
        db = _db_returns_scalars([])

        result = await PromptTemplateRepository(db).list_versions("nonexistent")

        assert result == []

    @pytest.mark.asyncio
    async def test_executes_one_query(self) -> None:
        """Only one database query is issued."""
        db = _db_returns_scalars([])

        await PromptTemplateRepository(db).list_versions("my_template")

        db.execute.assert_called_once()


# ---------------------------------------------------------------------------
# list_names
# ---------------------------------------------------------------------------


class TestListNames:
    """Tests for PromptTemplateRepository.list_names."""

    @pytest.mark.asyncio
    async def test_returns_sorted_distinct_names(self) -> None:
        """Returns the distinct names provided by the database."""
        db = _db_returns_scalars(["alpha", "beta", "gamma"])

        result = await PromptTemplateRepository(db).list_names()

        assert result == ["alpha", "beta", "gamma"]

    @pytest.mark.asyncio
    async def test_returns_empty_when_no_templates(self) -> None:
        """Returns empty list when no templates are stored."""
        db = _db_returns_scalars([])

        result = await PromptTemplateRepository(db).list_names()

        assert result == []

    @pytest.mark.asyncio
    async def test_executes_one_query(self) -> None:
        """Only one database query is issued."""
        db = _db_returns_scalars([])

        await PromptTemplateRepository(db).list_names()

        db.execute.assert_called_once()


# ---------------------------------------------------------------------------
# create_version
# ---------------------------------------------------------------------------


class TestCreateVersion:
    """Tests for PromptTemplateRepository.create_version."""

    @pytest.mark.asyncio
    async def test_first_version_is_one(self) -> None:
        """When no previous versions exist, the new version number is 1."""
        new_template = _make_template(version=1, is_active=True)
        db = _make_db_for_create(existing_rows=[], new_template=new_template)
        repo = PromptTemplateRepository(db)

        with patch.object(repo, "list_versions", new=AsyncMock(return_value=[])):
            await repo.create_version("my_template", "Hello", AUTHOR_ID)

        db.add.assert_called_once()
        added = db.add.call_args[0][0]
        assert added.version == 1

    @pytest.mark.asyncio
    async def test_version_increments_max_plus_one(self) -> None:
        """New version is max(existing_versions) + 1."""
        existing = [
            _make_template(version=1, is_active=False),
            _make_template(version=2, is_active=False),
            _make_template(version=3, is_active=True),
        ]
        new_template = _make_template(version=4, is_active=True)
        db = _make_db_for_create(existing_rows=existing, new_template=new_template)
        repo = PromptTemplateRepository(db)

        with patch.object(repo, "list_versions", new=AsyncMock(return_value=existing)):
            await repo.create_version("my_template", "Updated content", AUTHOR_ID)

        added = db.add.call_args[0][0]
        assert added.version == 4

    @pytest.mark.asyncio
    async def test_new_version_is_active(self) -> None:
        """The newly created version row has is_active=True."""
        existing = [_make_template(version=1, is_active=True)]
        new_template = _make_template(version=2, is_active=True)
        db = _make_db_for_create(existing_rows=existing, new_template=new_template)
        repo = PromptTemplateRepository(db)

        with patch.object(repo, "list_versions", new=AsyncMock(return_value=existing)):
            await repo.create_version("my_template", "New content", AUTHOR_ID)

        added = db.add.call_args[0][0]
        assert added.is_active is True

    @pytest.mark.asyncio
    async def test_deactivate_update_is_executed(self) -> None:
        """db.execute is called once for the UPDATE that deactivates the prior version."""
        existing = [_make_template(version=1, is_active=True)]
        new_template = _make_template(version=2, is_active=True)
        db = _make_db_for_create(existing_rows=existing, new_template=new_template)
        repo = PromptTemplateRepository(db)

        with patch.object(repo, "list_versions", new=AsyncMock(return_value=existing)):
            await repo.create_version("my_template", "New content", AUTHOR_ID)

        # One UPDATE call (deactivate) is expected
        assert db.execute.call_count == 1

    @pytest.mark.asyncio
    async def test_content_is_stored_correctly(self) -> None:
        """The content argument is passed through to the new ORM row."""
        new_template = _make_template(
            version=1, content="Special content", is_active=True
        )
        db = _make_db_for_create(existing_rows=[], new_template=new_template)
        repo = PromptTemplateRepository(db)

        with patch.object(repo, "list_versions", new=AsyncMock(return_value=[])):
            await repo.create_version("my_template", "Special content", AUTHOR_ID)

        added = db.add.call_args[0][0]
        assert added.content == "Special content"

    @pytest.mark.asyncio
    async def test_author_id_is_stored_correctly(self) -> None:
        """The author_id argument is stored on the new version row."""
        new_template = _make_template(version=1, author_id=AUTHOR_ID, is_active=True)
        db = _make_db_for_create(existing_rows=[], new_template=new_template)
        repo = PromptTemplateRepository(db)

        with patch.object(repo, "list_versions", new=AsyncMock(return_value=[])):
            await repo.create_version("my_template", "Content", AUTHOR_ID)

        added = db.add.call_args[0][0]
        assert added.author_id == AUTHOR_ID

    @pytest.mark.asyncio
    async def test_flush_and_refresh_are_called(self) -> None:
        """db.flush and db.refresh are both called after db.add."""
        new_template = _make_template(version=1, is_active=True)
        db = _make_db_for_create(existing_rows=[], new_template=new_template)
        repo = PromptTemplateRepository(db)

        with patch.object(repo, "list_versions", new=AsyncMock(return_value=[])):
            await repo.create_version("my_template", "Content", AUTHOR_ID)

        db.flush.assert_called_once()
        db.refresh.assert_called_once()


# ---------------------------------------------------------------------------
# rollback
# ---------------------------------------------------------------------------


class TestRollback:
    """Tests for PromptTemplateRepository.rollback."""

    @pytest.mark.asyncio
    async def test_raises_when_target_version_not_found(self) -> None:
        """Raises TemplateNotFoundError when the target version does not exist."""
        db = _db_returns_scalar(None)
        repo = PromptTemplateRepository(db)

        with pytest.raises(TemplateNotFoundError):
            await repo.rollback("my_template", version_number=99)

    @pytest.mark.asyncio
    async def test_new_version_carries_historical_content(self) -> None:
        """The rollback row's content matches the historical version's content."""
        historic_content = "Original system prompt"
        target = _make_template(version=2, content=historic_content, is_active=False)
        new_row = _make_template(version=4, content=historic_content, is_active=True)

        db = AsyncMock()

        # First execute: SELECT for get_version
        get_result = MagicMock()
        get_result.scalar_one_or_none.return_value = target

        # Second execute: UPDATE for deactivate inside create_version
        update_result = MagicMock()
        db.execute.side_effect = [get_result, update_result]

        async def _refresh(obj):
            obj.id = new_row.id
            obj.name = new_row.name
            obj.version = new_row.version
            obj.content = new_row.content
            obj.is_active = new_row.is_active
            obj.author_id = new_row.author_id
            obj.created_at = _NOW
            obj.updated_at = _NOW

        db.refresh.side_effect = _refresh

        existing = [
            _make_template(version=1, is_active=False),
            _make_template(version=2, is_active=False),
            _make_template(version=3, is_active=True),
        ]

        repo = PromptTemplateRepository(db)
        with patch.object(repo, "list_versions", new=AsyncMock(return_value=existing)):
            await repo.rollback("my_template", version_number=2)

        added = db.add.call_args[0][0]
        assert added.content == historic_content

    @pytest.mark.asyncio
    async def test_rollback_uses_provided_author_id(self) -> None:
        """When author_id is supplied, the rollback row uses that author."""
        target = _make_template(version=1, author_id=AUTHOR_ID, is_active=False)
        new_row = _make_template(version=2, author_id=OTHER_AUTHOR_ID, is_active=True)

        db = AsyncMock()
        get_result = MagicMock()
        get_result.scalar_one_or_none.return_value = target
        update_result = MagicMock()
        db.execute.side_effect = [get_result, update_result]

        async def _refresh(obj):
            obj.id = new_row.id
            obj.name = new_row.name
            obj.version = new_row.version
            obj.content = new_row.content
            obj.is_active = new_row.is_active
            obj.author_id = new_row.author_id
            obj.created_at = _NOW
            obj.updated_at = _NOW

        db.refresh.side_effect = _refresh
        existing = [_make_template(version=1, is_active=True)]

        repo = PromptTemplateRepository(db)
        with patch.object(repo, "list_versions", new=AsyncMock(return_value=existing)):
            await repo.rollback(
                "my_template", version_number=1, author_id=OTHER_AUTHOR_ID
            )

        added = db.add.call_args[0][0]
        assert added.author_id == OTHER_AUTHOR_ID

    @pytest.mark.asyncio
    async def test_rollback_falls_back_to_target_author_id(self) -> None:
        """When no author_id is provided, the target version's author is reused."""
        target = _make_template(version=1, author_id=AUTHOR_ID, is_active=False)
        new_row = _make_template(version=2, author_id=AUTHOR_ID, is_active=True)

        db = AsyncMock()
        get_result = MagicMock()
        get_result.scalar_one_or_none.return_value = target
        update_result = MagicMock()
        db.execute.side_effect = [get_result, update_result]

        async def _refresh(obj):
            obj.id = new_row.id
            obj.name = new_row.name
            obj.version = new_row.version
            obj.content = new_row.content
            obj.is_active = new_row.is_active
            obj.author_id = new_row.author_id
            obj.created_at = _NOW
            obj.updated_at = _NOW

        db.refresh.side_effect = _refresh
        existing = [_make_template(version=1, is_active=True)]

        repo = PromptTemplateRepository(db)
        with patch.object(repo, "list_versions", new=AsyncMock(return_value=existing)):
            await repo.rollback("my_template", version_number=1)  # no author_id

        added = db.add.call_args[0][0]
        assert added.author_id == AUTHOR_ID

    @pytest.mark.asyncio
    async def test_rollback_does_not_delete_existing_rows(self) -> None:
        """No existing version rows are deleted — history is always preserved."""
        target = _make_template(version=2, content="v2 content", is_active=False)
        new_row = _make_template(version=4, content="v2 content", is_active=True)

        db = AsyncMock()
        get_result = MagicMock()
        get_result.scalar_one_or_none.return_value = target
        update_result = MagicMock()
        db.execute.side_effect = [get_result, update_result]

        async def _refresh(obj):
            obj.version = new_row.version
            obj.content = new_row.content
            obj.is_active = new_row.is_active
            obj.author_id = new_row.author_id
            obj.created_at = _NOW
            obj.updated_at = _NOW

        db.refresh.side_effect = _refresh
        existing = [
            _make_template(version=1, is_active=False),
            _make_template(version=2, is_active=False),
            _make_template(version=3, is_active=True),
        ]

        repo = PromptTemplateRepository(db)
        with patch.object(repo, "list_versions", new=AsyncMock(return_value=existing)):
            await repo.rollback("my_template", version_number=2)

        db.add.assert_called_once()
        db.delete.assert_not_called()
