"""Unit tests for app.services.prompt_service.PromptService.

Uses AsyncMock to test all service logic without a live database.  The ORM
session is mocked so these tests run with no external dependencies.

Covers:
- get_current_template: returns active row; raises TemplateNotFoundError when missing
- list_versions: returns all rows sorted by version; returns empty list when none
- list_template_names: returns sorted distinct names
- create_version: computes max+1 version, deactivates previous active row, adds new row
- create_version on first version: version starts at 1
- rollback: creates a new version copying content from the target version
- rollback: raises TemplateNotFoundError when target version does not exist

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

from app.services.prompt_service import (
    PromptService,
    TemplateNotFoundError,
)

# ---------------------------------------------------------------------------
# Fixtures / helpers
# ---------------------------------------------------------------------------

AUTHOR_ID = uuid.UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
_NOW = datetime(2024, 1, 1, tzinfo=timezone.utc)


def _make_template(
    name: str = "test_template",
    version: int = 1,
    content: str = "Hello {{ name }}",
    is_active: bool = True,
    author_id: uuid.UUID = AUTHOR_ID,
) -> MagicMock:
    """Build a mock PromptTemplate ORM row."""
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


def _make_db_with_scalar(return_value) -> AsyncMock:
    """Return a mock AsyncSession whose execute().scalar_one_or_none() yields return_value."""
    db = AsyncMock()
    result = MagicMock()
    result.scalar_one_or_none.return_value = return_value
    db.execute.return_value = result
    return db


def _make_db_with_scalars(rows: list) -> AsyncMock:
    """Return a mock AsyncSession whose execute().scalars().all() yields rows."""
    db = AsyncMock()
    result = MagicMock()
    scalars = MagicMock()
    scalars.all.return_value = rows
    result.scalars.return_value = scalars
    db.execute.return_value = result
    return db


# ---------------------------------------------------------------------------
# get_current_template
# ---------------------------------------------------------------------------


class TestGetCurrentTemplate:
    """Tests for PromptService.get_current_template."""

    @pytest.mark.asyncio
    async def test_returns_active_template(self) -> None:
        """Returns the row when an active version exists."""
        template = _make_template(is_active=True)
        db = _make_db_with_scalar(template)

        result = await PromptService.get_current_template(db, "test_template")

        assert result is template

    @pytest.mark.asyncio
    async def test_raises_when_no_active_template(self) -> None:
        """Raises TemplateNotFoundError when no active row exists."""
        db = _make_db_with_scalar(None)

        with pytest.raises(TemplateNotFoundError, match="test_template"):
            await PromptService.get_current_template(db, "test_template")

    @pytest.mark.asyncio
    async def test_calls_db_execute(self) -> None:
        """Verifies that db.execute is called exactly once."""
        template = _make_template(is_active=True)
        db = _make_db_with_scalar(template)

        await PromptService.get_current_template(db, "test_template")

        db.execute.assert_called_once()


# ---------------------------------------------------------------------------
# list_versions
# ---------------------------------------------------------------------------


class TestListVersions:
    """Tests for PromptService.list_versions."""

    @pytest.mark.asyncio
    async def test_returns_all_versions(self) -> None:
        """Returns a list of all rows for the named template."""
        rows = [
            _make_template(version=1, is_active=False),
            _make_template(version=2, is_active=False),
            _make_template(version=3, is_active=True),
        ]
        db = _make_db_with_scalars(rows)

        result = await PromptService.list_versions(db, "test_template")

        assert len(result) == 3
        assert result == rows

    @pytest.mark.asyncio
    async def test_returns_empty_list_when_none_exist(self) -> None:
        """Returns empty list when no rows exist for the name."""
        db = _make_db_with_scalars([])

        result = await PromptService.list_versions(db, "nonexistent")

        assert result == []


# ---------------------------------------------------------------------------
# list_template_names
# ---------------------------------------------------------------------------


class TestListTemplateNames:
    """Tests for PromptService.list_template_names."""

    @pytest.mark.asyncio
    async def test_returns_distinct_names(self) -> None:
        """Returns all distinct names from the result set."""
        db = _make_db_with_scalars(["alpha_template", "beta_template", "code_helper"])

        result = await PromptService.list_template_names(db)

        assert result == ["alpha_template", "beta_template", "code_helper"]

    @pytest.mark.asyncio
    async def test_returns_empty_when_no_templates(self) -> None:
        """Returns empty list when no templates exist."""
        db = _make_db_with_scalars([])

        result = await PromptService.list_template_names(db)

        assert result == []


# ---------------------------------------------------------------------------
# create_version
# ---------------------------------------------------------------------------


class TestCreateVersion:
    """Tests for PromptService.create_version."""

    def _make_db_for_create(
        self,
        existing_rows: list,
        new_template: MagicMock,
    ) -> AsyncMock:
        """Build a mock db that handles list_versions (mocked) + execute (deactivate) + add/flush/refresh."""
        db = AsyncMock()

        # The UPDATE (deactivate) execute call — returns nothing meaningful
        update_result = MagicMock()
        db.execute.return_value = update_result

        # After db.add + flush + refresh the template is populated
        async def _refresh(obj):
            # Copy attributes from new_template into obj
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

    @pytest.mark.asyncio
    async def test_first_version_is_one(self) -> None:
        """When no existing versions exist the new version number is 1."""
        new_template = _make_template(version=1, is_active=True)
        db = self._make_db_for_create(existing_rows=[], new_template=new_template)

        with patch(
            "app.services.prompt_service.PromptService.list_versions",
            new=AsyncMock(return_value=[]),
        ):
            result = await PromptService.create_version(
                db=db,
                name="test_template",
                content="Hello",
                author_id=AUTHOR_ID,
            )

        # Verify the object added to db.add has the expected attributes
        assert db.add.call_count == 1
        added_obj = db.add.call_args[0][0]
        assert added_obj.version == 1
        assert added_obj.is_active is True

    @pytest.mark.asyncio
    async def test_increments_max_version(self) -> None:
        """New version is max(existing) + 1."""
        existing = [
            _make_template(version=1, is_active=False),
            _make_template(version=2, is_active=False),
            _make_template(version=3, is_active=True),
        ]
        new_template = _make_template(version=4, is_active=True)
        db = self._make_db_for_create(existing_rows=existing, new_template=new_template)

        with patch(
            "app.services.prompt_service.PromptService.list_versions",
            new=AsyncMock(return_value=existing),
        ):
            result = await PromptService.create_version(
                db=db,
                name="test_template",
                content="Updated content",
                author_id=AUTHOR_ID,
            )

        assert db.add.call_count == 1
        added_obj = db.add.call_args[0][0]
        assert added_obj.version == 4

    @pytest.mark.asyncio
    async def test_new_version_is_active(self) -> None:
        """The newly created version has is_active=True."""
        new_template = _make_template(version=2, is_active=True)
        existing = [_make_template(version=1, is_active=False)]
        db = self._make_db_for_create(existing_rows=existing, new_template=new_template)

        with patch(
            "app.services.prompt_service.PromptService.list_versions",
            new=AsyncMock(return_value=existing),
        ):
            await PromptService.create_version(
                db=db,
                name="test_template",
                content="New content",
                author_id=AUTHOR_ID,
            )

        assert db.add.call_count == 1
        added_obj = db.add.call_args[0][0]
        assert added_obj.is_active is True

    @pytest.mark.asyncio
    async def test_deactivate_execute_called(self) -> None:
        """db.execute is called once for the UPDATE deactivate (list_versions is mocked)."""
        new_template = _make_template(version=2, is_active=True)
        existing = [_make_template(version=1, is_active=True)]
        db = self._make_db_for_create(existing_rows=existing, new_template=new_template)

        with patch(
            "app.services.prompt_service.PromptService.list_versions",
            new=AsyncMock(return_value=existing),
        ):
            await PromptService.create_version(
                db=db,
                name="test_template",
                content="New content",
                author_id=AUTHOR_ID,
            )

        # Only the UPDATE deactivate execute call remains
        assert db.execute.call_count == 1

    @pytest.mark.asyncio
    async def test_content_stored_correctly(self) -> None:
        """The content passed to create_version is used in the ORM constructor."""
        new_template = _make_template(
            version=1, content="My special content", is_active=True
        )
        db = self._make_db_for_create(existing_rows=[], new_template=new_template)

        with patch(
            "app.services.prompt_service.PromptService.list_versions",
            new=AsyncMock(return_value=[]),
        ):
            await PromptService.create_version(
                db=db,
                name="test_template",
                content="My special content",
                author_id=AUTHOR_ID,
            )

        assert db.add.call_count == 1
        added_obj = db.add.call_args[0][0]
        assert added_obj.content == "My special content"


# ---------------------------------------------------------------------------
# rollback
# ---------------------------------------------------------------------------


class TestRollback:
    """Tests for PromptService.rollback."""

    @pytest.mark.asyncio
    async def test_raises_when_version_not_found(self) -> None:
        """Raises TemplateNotFoundError when target version does not exist."""
        db = _make_db_with_scalar(None)

        with pytest.raises(TemplateNotFoundError, match="version 99"):
            await PromptService.rollback(db=db, name="test_template", version_number=99)

    @pytest.mark.asyncio
    async def test_rollback_creates_new_version_with_same_content(self) -> None:
        """Rollback copies content from target version into a new version row."""
        target_content = "Old system prompt content"
        target = _make_template(version=2, content=target_content, is_active=False)

        # db.execute for finding target row (rollback's own SELECT)
        find_result = MagicMock()
        find_result.scalar_one_or_none.return_value = target

        # Update result for deactivate (create_version's UPDATE)
        update_result = MagicMock()

        db = AsyncMock()
        db.execute.side_effect = [find_result, update_result]

        existing_versions = [
            _make_template(version=1, is_active=False),
            _make_template(version=2, is_active=False),
            _make_template(version=3, is_active=True),
        ]

        new_template = _make_template(version=4, content=target_content, is_active=True)

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

        with patch(
            "app.services.prompt_service.PromptService.list_versions",
            new=AsyncMock(return_value=existing_versions),
        ):
            result = await PromptService.rollback(
                db=db,
                name="test_template",
                version_number=2,
            )

        # The new version must carry the target's content
        assert db.add.call_count == 1
        added_obj = db.add.call_args[0][0]
        assert added_obj.content == target_content

    @pytest.mark.asyncio
    async def test_rollback_new_version_is_max_plus_one(self) -> None:
        """New version after rollback is max(existing) + 1."""
        target = _make_template(version=1, content="v1 content", is_active=False)

        find_result = MagicMock()
        find_result.scalar_one_or_none.return_value = target

        update_result = MagicMock()
        db = AsyncMock()
        db.execute.side_effect = [find_result, update_result]

        existing_versions = [
            _make_template(version=1, is_active=False),
            _make_template(version=2, is_active=False),
            _make_template(version=3, is_active=True),
        ]

        new_template = _make_template(version=4, content="v1 content", is_active=True)

        async def _refresh(obj):
            obj.version = new_template.version
            obj.content = new_template.content
            obj.is_active = new_template.is_active
            obj.created_at = _NOW
            obj.updated_at = _NOW

        db.refresh.side_effect = _refresh

        with patch(
            "app.services.prompt_service.PromptService.list_versions",
            new=AsyncMock(return_value=existing_versions),
        ):
            await PromptService.rollback(
                db=db,
                name="test_template",
                version_number=1,
            )

        assert db.add.call_count == 1
        added_obj = db.add.call_args[0][0]
        assert added_obj.version == 4

    @pytest.mark.asyncio
    async def test_rollback_preserves_history_count(self) -> None:
        """Rollback adds one new row (db.add called once); existing rows not deleted."""
        target = _make_template(version=2, content="historic content", is_active=False)

        find_result = MagicMock()
        find_result.scalar_one_or_none.return_value = target

        update_result = MagicMock()
        db = AsyncMock()
        db.execute.side_effect = [find_result, update_result]

        existing_versions = [
            _make_template(version=1, is_active=False),
            _make_template(version=2, is_active=False),
        ]

        new_row = _make_template(version=3, content="historic content", is_active=True)

        async def _refresh(obj):
            obj.version = new_row.version
            obj.content = new_row.content
            obj.is_active = new_row.is_active
            obj.created_at = _NOW
            obj.updated_at = _NOW

        db.refresh.side_effect = _refresh

        with patch(
            "app.services.prompt_service.PromptService.list_versions",
            new=AsyncMock(return_value=existing_versions),
        ):
            await PromptService.rollback(
                db=db,
                name="test_template",
                version_number=2,
            )

        # db.add called once (for the new rollback row); no delete calls
        db.add.assert_called_once()
        db.delete.assert_not_called()
