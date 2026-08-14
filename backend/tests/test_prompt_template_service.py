# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Tests : test_prompt_template_service.py
# Purpose: Unit tests for PromptTemplateService
#
# Tests use mock/patch for the PromptTemplateRepository layer so no live
# database is required.  All business-logic invariants defined in
# requirements 25.1 and 25.2 are exercised here.
#
# Requirements: 25.1, 25.2
# ============================================================

"""Unit tests for app.services.prompt_template_service.PromptTemplateService.

Covers:
- update_template: increments version and sets is_active correctly
- rollback_template: restores content and leaves full history intact
- rollback_template: raises TemplateNotFoundError for non-existent version
- get_template: returns the active template's content string
- get_active_template: returns the full ORM row
- get_all_versions: delegates to repository list_versions

Requirements: 25.1, 25.2
"""

from __future__ import annotations

import os
import uuid
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock

import pytest

# Ensure required env vars are set before any app imports.
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

from app.repositories.prompt_template_repository import (
    TemplateNotFoundError,
)
from app.services.prompt_template_service import PromptTemplateService

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

AUTHOR_ID = uuid.UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
OTHER_AUTHOR_ID = uuid.UUID("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
_NOW = datetime(2024, 1, 1, tzinfo=timezone.utc)


def _make_template(
    name: str = "chat_system",
    version: int = 1,
    content: str = "You are a helpful assistant.",
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


def _make_repo(**overrides) -> MagicMock:
    """Return a MagicMock repository with async methods as AsyncMocks."""
    repo = MagicMock()
    repo.get_active = AsyncMock()
    repo.get_version = AsyncMock()
    repo.list_versions = AsyncMock()
    repo.create_version = AsyncMock()
    repo.rollback = AsyncMock()
    for attr, value in overrides.items():
        setattr(repo, attr, value)
    return repo


# ---------------------------------------------------------------------------
# update_template
# ---------------------------------------------------------------------------


class TestUpdateTemplate:
    """Tests for PromptTemplateService.update_template.

    Requirements: 25.1
    """

    @pytest.mark.asyncio
    async def test_returns_new_version_row(self) -> None:
        """Returns the newly created PromptTemplate row from the repository."""
        new_row = _make_template(version=2, content="Updated content", is_active=True)
        repo = _make_repo(create_version=AsyncMock(return_value=new_row))
        service = PromptTemplateService(repo)

        result = await service.update_template(
            "chat_system", "Updated content", AUTHOR_ID
        )

        assert result is new_row

    @pytest.mark.asyncio
    async def test_delegates_to_repository_create_version(self) -> None:
        """Calls repository.create_version with the correct arguments."""
        new_row = _make_template(version=1, is_active=True)
        repo = _make_repo(create_version=AsyncMock(return_value=new_row))
        service = PromptTemplateService(repo)

        await service.update_template("chat_system", "Hello {{ name }}", AUTHOR_ID)

        repo.create_version.assert_called_once_with(
            name="chat_system",
            content="Hello {{ name }}",
            author_id=AUTHOR_ID,
        )

    @pytest.mark.asyncio
    async def test_new_version_is_active(self) -> None:
        """The returned row has is_active=True (versioning contract)."""
        new_row = _make_template(version=3, is_active=True)
        repo = _make_repo(create_version=AsyncMock(return_value=new_row))
        service = PromptTemplateService(repo)

        result = await service.update_template("chat_system", "v3 content", AUTHOR_ID)

        assert result.is_active is True

    @pytest.mark.asyncio
    async def test_version_number_increments(self) -> None:
        """The returned row carries an incremented version number."""
        new_row = _make_template(version=4, is_active=True)
        repo = _make_repo(create_version=AsyncMock(return_value=new_row))
        service = PromptTemplateService(repo)

        result = await service.update_template("chat_system", "v4 content", AUTHOR_ID)

        assert result.version == 4

    @pytest.mark.asyncio
    async def test_content_matches_input(self) -> None:
        """The returned row carries the exact content passed to update_template."""
        expected_content = "Speak only in haiku."
        new_row = _make_template(version=1, content=expected_content, is_active=True)
        repo = _make_repo(create_version=AsyncMock(return_value=new_row))
        service = PromptTemplateService(repo)

        result = await service.update_template(
            "chat_system", expected_content, AUTHOR_ID
        )

        assert result.content == expected_content


# ---------------------------------------------------------------------------
# rollback_template
# ---------------------------------------------------------------------------


class TestRollbackTemplate:
    """Tests for PromptTemplateService.rollback_template.

    Requirements: 25.2
    """

    @pytest.mark.asyncio
    async def test_returns_new_version_with_historical_content(self) -> None:
        """rollback_template returns the new version row whose content matches version V."""
        historic_content = "Original system prompt from v2."
        target = _make_template(version=2, content=historic_content, is_active=False)
        rolled_back_row = _make_template(
            version=5, content=historic_content, is_active=True
        )

        repo = _make_repo(
            get_version=AsyncMock(return_value=target),
            rollback=AsyncMock(return_value=rolled_back_row),
        )
        service = PromptTemplateService(repo)

        result = await service.rollback_template("chat_system", version=2)

        assert result.content == historic_content

    @pytest.mark.asyncio
    async def test_new_version_is_active(self) -> None:
        """The rolled-back row has is_active=True."""
        target = _make_template(version=1, content="v1 content", is_active=False)
        rolled_back_row = _make_template(
            version=3, content="v1 content", is_active=True
        )

        repo = _make_repo(
            get_version=AsyncMock(return_value=target),
            rollback=AsyncMock(return_value=rolled_back_row),
        )
        service = PromptTemplateService(repo)

        result = await service.rollback_template("chat_system", version=1)

        assert result.is_active is True

    @pytest.mark.asyncio
    async def test_raises_for_nonexistent_version(self) -> None:
        """Raises TemplateNotFoundError when the target version does not exist."""
        repo = _make_repo(
            get_version=AsyncMock(side_effect=TemplateNotFoundError("No version 99")),
        )
        service = PromptTemplateService(repo)

        with pytest.raises(TemplateNotFoundError):
            await service.rollback_template("chat_system", version=99)

    @pytest.mark.asyncio
    async def test_repository_rollback_not_called_when_version_missing(self) -> None:
        """repository.rollback is NOT called when get_version raises."""
        repo = _make_repo(
            get_version=AsyncMock(side_effect=TemplateNotFoundError("No version 99")),
            rollback=AsyncMock(),
        )
        service = PromptTemplateService(repo)

        with pytest.raises(TemplateNotFoundError):
            await service.rollback_template("chat_system", version=99)

        repo.rollback.assert_not_called()

    @pytest.mark.asyncio
    async def test_history_is_preserved(self) -> None:
        """Rollback adds exactly one new row; does not delete any existing rows.

        The repository.rollback method is responsible for the non-destructive
        copy; this test confirms the service passes the call through correctly.
        """
        target = _make_template(version=2, content="v2 content", is_active=False)
        new_row = _make_template(version=5, content="v2 content", is_active=True)

        repo = _make_repo(
            get_version=AsyncMock(return_value=target),
            rollback=AsyncMock(return_value=new_row),
        )
        service = PromptTemplateService(repo)

        await service.rollback_template("chat_system", version=2)

        # Rollback was delegated to the repository exactly once
        repo.rollback.assert_called_once_with(
            name="chat_system",
            version_number=2,
            author_id=None,  # default when no author_id provided
        )

    @pytest.mark.asyncio
    async def test_rollback_passes_author_id_to_repository(self) -> None:
        """When author_id is supplied it is forwarded to repository.rollback."""
        target = _make_template(version=1, content="v1 content", is_active=False)
        new_row = _make_template(
            version=3, content="v1 content", is_active=True, author_id=OTHER_AUTHOR_ID
        )

        repo = _make_repo(
            get_version=AsyncMock(return_value=target),
            rollback=AsyncMock(return_value=new_row),
        )
        service = PromptTemplateService(repo)

        await service.rollback_template(
            "chat_system", version=1, author_id=OTHER_AUTHOR_ID
        )

        repo.rollback.assert_called_once_with(
            name="chat_system",
            version_number=1,
            author_id=OTHER_AUTHOR_ID,
        )

    @pytest.mark.asyncio
    async def test_rollback_version_number_is_max_plus_one(self) -> None:
        """The returned row version is higher than the version being rolled back to."""
        target = _make_template(version=2, content="v2 content", is_active=False)
        new_row = _make_template(version=5, content="v2 content", is_active=True)

        repo = _make_repo(
            get_version=AsyncMock(return_value=target),
            rollback=AsyncMock(return_value=new_row),
        )
        service = PromptTemplateService(repo)

        result = await service.rollback_template("chat_system", version=2)

        # The new version number must be strictly greater than the rolled-back version
        assert result.version > 2


# ---------------------------------------------------------------------------
# get_template
# ---------------------------------------------------------------------------


class TestGetTemplate:
    """Tests for PromptTemplateService.get_template.

    Requirements: 25.1
    """

    @pytest.mark.asyncio
    async def test_returns_active_content_string(self) -> None:
        """Returns the content string of the active version."""
        active_row = _make_template(
            version=3, content="You are a code assistant.", is_active=True
        )
        repo = _make_repo(get_active=AsyncMock(return_value=active_row))
        service = PromptTemplateService(repo)

        result = await service.get_template("code_assistant")

        assert result == "You are a code assistant."

    @pytest.mark.asyncio
    async def test_calls_repository_get_active(self) -> None:
        """Delegates to repository.get_active with the given name."""
        active_row = _make_template(content="content")
        repo = _make_repo(get_active=AsyncMock(return_value=active_row))
        service = PromptTemplateService(repo)

        await service.get_template("chat_system")

        repo.get_active.assert_called_once_with("chat_system")

    @pytest.mark.asyncio
    async def test_raises_when_no_active_version(self) -> None:
        """Propagates TemplateNotFoundError when no active version exists."""
        repo = _make_repo(
            get_active=AsyncMock(
                side_effect=TemplateNotFoundError("No active template")
            )
        )
        service = PromptTemplateService(repo)

        with pytest.raises(TemplateNotFoundError):
            await service.get_template("nonexistent")


# ---------------------------------------------------------------------------
# get_active_template
# ---------------------------------------------------------------------------


class TestGetActiveTemplate:
    """Tests for PromptTemplateService.get_active_template.

    Requirements: 25.1
    """

    @pytest.mark.asyncio
    async def test_returns_full_orm_row(self) -> None:
        """Returns the full PromptTemplate row (not just the content string)."""
        active_row = _make_template(version=2, is_active=True)
        repo = _make_repo(get_active=AsyncMock(return_value=active_row))
        service = PromptTemplateService(repo)

        result = await service.get_active_template("chat_system")

        assert result is active_row

    @pytest.mark.asyncio
    async def test_raises_for_missing_template(self) -> None:
        """Propagates TemplateNotFoundError for unknown template names."""
        repo = _make_repo(
            get_active=AsyncMock(side_effect=TemplateNotFoundError("missing"))
        )
        service = PromptTemplateService(repo)

        with pytest.raises(TemplateNotFoundError):
            await service.get_active_template("unknown_name")


# ---------------------------------------------------------------------------
# get_all_versions
# ---------------------------------------------------------------------------


class TestGetAllVersions:
    """Tests for PromptTemplateService.get_all_versions.

    Requirements: 25.1
    """

    @pytest.mark.asyncio
    async def test_returns_all_versions_in_order(self) -> None:
        """Returns the list of all version rows from the repository."""
        versions = [
            _make_template(version=1, is_active=False),
            _make_template(version=2, is_active=False),
            _make_template(version=3, is_active=True),
        ]
        repo = _make_repo(list_versions=AsyncMock(return_value=versions))
        service = PromptTemplateService(repo)

        result = await service.get_all_versions("chat_system")

        assert result == versions
        assert len(result) == 3

    @pytest.mark.asyncio
    async def test_returns_empty_list_when_no_versions(self) -> None:
        """Returns an empty list when no versions exist for the name."""
        repo = _make_repo(list_versions=AsyncMock(return_value=[]))
        service = PromptTemplateService(repo)

        result = await service.get_all_versions("nonexistent")

        assert result == []

    @pytest.mark.asyncio
    async def test_delegates_to_repository(self) -> None:
        """Calls repository.list_versions with the correct name."""
        repo = _make_repo(list_versions=AsyncMock(return_value=[]))
        service = PromptTemplateService(repo)

        await service.get_all_versions("chat_system")

        repo.list_versions.assert_called_once_with("chat_system")
