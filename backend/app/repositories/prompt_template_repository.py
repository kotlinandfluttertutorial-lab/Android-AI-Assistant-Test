# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : repositories
# File    : prompt_template_repository.py
# Purpose : Database access layer for prompt_template entities
#
# Architecture Layer : Repository
# Pattern Used       : Repository Pattern
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Database access layer for versioned prompt templates.

Provides thin CRUD and query operations on the ``prompt_templates`` table
via the SQLAlchemy async session.  All business-level invariants (version
sequencing, active-flag management) are enforced here rather than scattered
across the service layer.

Versioning contract
-------------------
- A logical template is identified by its ``name``.
- Each call to :meth:`create_version` produces a new immutable row with
  ``version = max(existing) + 1``, or ``1`` for a brand-new name.
- The previous active row is deactivated atomically in the same session
  before the new one is flushed.
- Rollback is **non-destructive**: :meth:`rollback` creates a new version
  whose content is copied from a historical version.  No existing rows are
  ever modified or deleted, preserving a complete audit trail.

Requirements: 25.1, 25.2
"""

from __future__ import annotations

import uuid

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.prompt_template import PromptTemplate


class TemplateNotFoundError(Exception):
    """Raised when the requested template name or version does not exist."""


class PromptTemplateRepository:
    """CRUD and versioning operations for the ``prompt_templates`` table.

    All methods are ``async`` and use the ``AsyncSession`` injected at
    construction time.  Transaction boundaries (commit / rollback) are the
    caller's responsibility.

    Args:
        db: SQLAlchemy async session for the current request.

    Requirements: 25.1, 25.2
    """

    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    # ------------------------------------------------------------------
    # Read operations
    # ------------------------------------------------------------------

    async def get_active(self, name: str) -> PromptTemplate:
        """Return the currently active version for ``name``.

        Args:
            name: Logical template name.

        Returns:
            The :class:`PromptTemplate` row whose ``is_active`` flag is
            ``True`` for the given ``name``.

        Raises:
            TemplateNotFoundError: When no active version exists for ``name``.

        Requirements: 25.1
        """
        result = await self._db.execute(
            select(PromptTemplate).where(
                PromptTemplate.name == name, PromptTemplate.is_active.is_(True)
            )
            .limit(1)
        )
        template = result.scalar_one_or_none()
        if template is None:
            raise TemplateNotFoundError(f"No active prompt template found with name={name!r}")
        return template

    async def get_version(self, name: str, version: int) -> PromptTemplate:
        """Return a specific version of a named template.

        Args:
            name:    Logical template name.
            version: Exact version number to retrieve.

        Returns:
            The matching :class:`PromptTemplate` row.

        Raises:
            TemplateNotFoundError: When the requested version does not exist.

        Requirements: 25.2
        """
        result = await self._db.execute(
            select(PromptTemplate).where(
                PromptTemplate.name == name,
                PromptTemplate.version == version,
            )
            .limit(1)
        )
        template = result.scalar_one_or_none()
        if template is None:
            raise TemplateNotFoundError(f"No version {version} found for template name={name!r}")
        return template

    async def list_versions(self, name: str) -> list[PromptTemplate]:
        """Return all versions of ``name``, ordered by version ascending.

        Args:
            name: Logical template name.

        Returns:
            List of :class:`PromptTemplate` rows sorted by ``version`` ASC.
            Returns an empty list when no versions exist for ``name``.

        Requirements: 25.1
        """
        result = await self._db.execute(
            select(PromptTemplate)
            .where(PromptTemplate.name == name)
            .order_by(PromptTemplate.version.asc())
        )
        return list(result.scalars().all())

    async def list_names(self) -> list[str]:
        """Return a sorted list of all distinct logical template names.

        Returns:
            Alphabetically sorted list of unique template name strings.

        Requirements: 25.1
        """
        result = await self._db.execute(
            select(PromptTemplate.name)
            .distinct()
            .order_by(PromptTemplate.name.asc())
        )
        return list(result.scalars().all())

    # ------------------------------------------------------------------
    # Write operations
    # ------------------------------------------------------------------

    async def create_version(
        self,
        name: str,
        content: str,
        author_id: uuid.UUID,
    ) -> PromptTemplate:
        """Create a new version of a named template and make it active.

        The version number is ``max(existing) + 1``, or ``1`` when this is the
        first version.  The previous active row (if any) is deactivated within
        the same session before the new row is flushed.

        Args:
            name:      Logical template name.
            content:   Jinja2-compatible template text.
            author_id: UUID of the admin user creating this version.

        Returns:
            The newly created :class:`PromptTemplate` row with
            ``is_active=True``.

        Requirements: 25.1
        """
        # Determine next version number from existing history.
        existing = await self.list_versions(name)
        next_version = (max(t.version for t in existing) + 1) if existing else 1

        # Atomically deactivate the current active version.
        await self._db.execute(
            update(PromptTemplate)
            .where(PromptTemplate.name == name, PromptTemplate.is_active.is_(True))
            .values(is_active=False)
        )

        # Insert the new immutable version snapshot.
        new_template = PromptTemplate(
            name=name,
            content=content,
            version=next_version,
            author_id=author_id,
            is_active=True,
        )
        self._db.add(new_template)
        await self._db.flush()
        await self._db.refresh(new_template)
        return new_template

    async def rollback(
        self,
        name: str,
        version_number: int,
        author_id: uuid.UUID | None = None,
    ) -> PromptTemplate:
        """Restore a template to the content it had at ``version_number``.

        This is a non-destructive operation: a **new** version row is created
        whose content is copied from ``version_number``.  All prior rows are
        preserved unchanged, keeping the complete audit trail intact.

        If ``author_id`` is not supplied the original author of the target
        version is reused (the rollback is attributed to the same author).

        Args:
            name:           Logical template name.
            version_number: The historical version whose content to restore.
            author_id:      Optional UUID of the user performing the rollback.
                            Falls back to the target version's ``author_id``.

        Returns:
            The newly created :class:`PromptTemplate` row with
            ``is_active=True``.

        Raises:
            TemplateNotFoundError: When ``version_number`` does not exist for
                ``name``.

        Requirements: 25.2
        """
        target = await self.get_version(name, version_number)
        effective_author = author_id if author_id is not None else target.author_id
        return await self.create_version(
            name=name,
            content=target.content,
            author_id=effective_author,
        )
