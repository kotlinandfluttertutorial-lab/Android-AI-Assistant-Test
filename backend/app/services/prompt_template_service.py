# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services
# File    : prompt_template_service.py
# Purpose : Business logic for versioned prompt template management
#
# Architecture Layer : Service
# Pattern Used       : Service Layer (Business Logic)
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#   - Repository pattern — all DB access through PromptTemplateRepository
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Versioned prompt template service.

Thin business-logic layer that delegates persistence to
:class:`~app.repositories.prompt_template_repository.PromptTemplateRepository`.
Callers (e.g. the API router) interact only with this service; they never touch
the repository directly.

Versioning contract
-------------------
- ``update_template`` creates a new immutable version row (version = max + 1)
  and marks it as the sole active version for the given name.
- ``rollback_template`` validates that the target version exists, then creates a
  new version whose content is a copy of the historical row.  **No existing rows
  are ever deleted** — the full audit trail is preserved.
- ``get_template`` returns the currently active version's content as a plain
  string so callers need not handle ORM objects.

Requirements: 25.1, 25.2
"""

from __future__ import annotations

import uuid

from app.models.prompt_template import PromptTemplate
from app.repositories.prompt_template_repository import (
    PromptTemplateRepository,
    TemplateNotFoundError,
)

# Re-export so callers can:
#   ``from app.services.prompt_template_service import TemplateNotFoundError``
__all__ = ["PromptTemplateService", "TemplateNotFoundError"]


class PromptTemplateService:
    """Business-logic layer for versioned prompt template management.

    All methods are ``async`` and delegate database access to the injected
    :class:`~app.repositories.prompt_template_repository.PromptTemplateRepository`.
    Transaction boundaries (commit / rollback) are the caller's responsibility.

    Args:
        repository: Repository instance bound to the current request's async
                    session.  Injected at construction time to allow testing
                    with a mock/fake repository.

    Requirements: 25.1, 25.2
    """

    def __init__(self, repository: PromptTemplateRepository) -> None:
        self._repo = repository

    # ------------------------------------------------------------------
    # Read
    # ------------------------------------------------------------------

    async def get_template(self, name: str) -> str:
        """Return the content of the currently active version for ``name``.

        Args:
            name: Logical template name (e.g. ``"chat_system"``).

        Returns:
            The raw template content string for the active version.

        Raises:
            TemplateNotFoundError: When no active version exists for ``name``.

        Requirements: 25.1
        """
        template = await self._repo.get_active(name)
        return template.content

    async def get_active_template(self, name: str) -> PromptTemplate:
        """Return the full active :class:`PromptTemplate` row for ``name``.

        Useful when the caller needs version metadata (version number, author,
        timestamps) in addition to the content.

        Args:
            name: Logical template name.

        Returns:
            The :class:`PromptTemplate` row with ``is_active=True``.

        Raises:
            TemplateNotFoundError: When no active version exists for ``name``.

        Requirements: 25.1
        """
        return await self._repo.get_active(name)

    async def get_all_versions(self, name: str) -> list[PromptTemplate]:
        """Return all versions for ``name``, ordered by version ascending.

        Args:
            name: Logical template name.

        Returns:
            List of :class:`PromptTemplate` rows sorted by ``version`` ASC.
            Empty list when no versions exist.

        Requirements: 25.1
        """
        return await self._repo.list_versions(name)

    # ------------------------------------------------------------------
    # Write
    # ------------------------------------------------------------------

    async def update_template(
        self,
        name: str,
        content: str,
        author_id: uuid.UUID,
    ) -> PromptTemplate:
        """Create a new version of a named template and make it active.

        The new version's number is ``max(existing_versions) + 1``, or ``1``
        when this is the first version.  The previous active version (if any)
        is deactivated atomically within the same session before the new row is
        flushed.

        Args:
            name:      Logical template name.
            content:   New Jinja2-compatible template text.
            author_id: UUID of the admin user creating this version.

        Returns:
            The newly created :class:`PromptTemplate` row (``is_active=True``).

        Requirements: 25.1
        """
        return await self._repo.create_version(
            name=name,
            content=content,
            author_id=author_id,
        )

    async def rollback_template(
        self,
        name: str,
        version: int,
        author_id: uuid.UUID | None = None,
    ) -> PromptTemplate:
        """Restore a template to the content it had at ``version``.

        Validates that the target version exists before performing the rollback.
        The rollback is non-destructive: a **new** version row is created whose
        content is copied from the historical version.  All existing rows are
        preserved unchanged, keeping the full audit trail intact.

        Args:
            name:      Logical template name.
            version:   The historical version number whose content to restore.
            author_id: UUID of the user performing the rollback.  When omitted,
                       the original author of the target version is reused.

        Returns:
            The newly created :class:`PromptTemplate` row (``is_active=True``).

        Raises:
            TemplateNotFoundError: When ``version`` does not exist for ``name``.

        Requirements: 25.2
        """
        # Validate target version exists (raises TemplateNotFoundError if not)
        await self._repo.get_version(name, version)

        # Perform the non-destructive rollback
        return await self._repo.rollback(
            name=name,
            version_number=version,
            author_id=author_id,
        )
