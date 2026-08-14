# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services
# File    : prompt_service.py
# Purpose : Business logic for the prompt domain
#
# Architecture Layer : Service
# Pattern Used       : Service Layer (Business Logic)
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Prompt template versioning service.

Provides all business logic for managing versioned prompt templates:
creating new versions, fetching the active version, listing history,
and rolling back to a prior version.

Versioning contract
-------------------
- Every template is identified by a logical ``name``.
- Each update to a named template creates a new row with ``version = max + 1``
  and sets it as the sole active version (previous active version is
  deactivated within the same transaction).
- Rollback does **not** modify any historical row.  Instead it creates a *new*
  version whose content is copied from version V.  The full audit trail is
  therefore always intact.

Requirements: 25.1, 25.2
"""

from __future__ import annotations

import uuid

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.prompt_template import PromptTemplate


class TemplateNotFoundError(Exception):
    """Raised when the requested template name or version does not exist."""


class PromptService:
    """Service class encapsulating all versioned prompt-template operations.

    All public methods accept an ``AsyncSession`` as their first argument so
    that callers control the transaction boundary (commit / rollback is the
    caller's responsibility).

    Requirements: 25.1, 25.2
    """

    # ------------------------------------------------------------------
    # Read operations
    # ------------------------------------------------------------------

    @staticmethod
    async def get_current_template(
        db: AsyncSession,
        name: str,
    ) -> PromptTemplate:
        """Return the currently active version for ``name``.

        Args:
            db:   Async SQLAlchemy session.
            name: Logical template name.

        Returns:
            The :class:`PromptTemplate` row whose ``is_active`` flag is ``True``
            for the given ``name``.

        Raises:
            TemplateNotFoundError: When no active version exists for ``name``.

        Requirements: 25.1
        """
        result = await db.execute(
            select(PromptTemplate)
            .where(PromptTemplate.name == name, PromptTemplate.is_active.is_(True))
            .limit(1)
        )
        template = result.scalar_one_or_none()
        if template is None:
            raise TemplateNotFoundError(
                f"No active prompt template found with name={name!r}"
            )
        return template

    @staticmethod
    async def list_versions(
        db: AsyncSession,
        name: str,
    ) -> list[PromptTemplate]:
        """Return all versions of ``name``, ordered by version ascending.

        Args:
            db:   Async SQLAlchemy session.
            name: Logical template name.

        Returns:
            List of :class:`PromptTemplate` rows sorted by ``version`` ASC.
            Empty list when no versions exist for ``name``.

        Requirements: 25.1
        """
        result = await db.execute(
            select(PromptTemplate)
            .where(PromptTemplate.name == name)
            .order_by(PromptTemplate.version.asc())
        )
        return list(result.scalars().all())

    @staticmethod
    async def list_template_names(db: AsyncSession) -> list[str]:
        """Return a sorted list of distinct logical template names.

        Args:
            db: Async SQLAlchemy session.

        Returns:
            Alphabetically sorted list of unique template name strings.

        Requirements: 25.1
        """
        result = await db.execute(
            select(PromptTemplate.name).distinct().order_by(PromptTemplate.name.asc())
        )
        return list(result.scalars().all())

    # ------------------------------------------------------------------
    # Write operations
    # ------------------------------------------------------------------

    @staticmethod
    async def create_version(
        db: AsyncSession,
        name: str,
        content: str,
        author_id: uuid.UUID,
    ) -> PromptTemplate:
        """Create a new version of a named template and make it active.

        The new version number is ``max(existing_versions) + 1``, or ``1`` if
        no prior versions exist.  The previous active version (if any) is
        deactivated atomically within the same session.

        Args:
            db:        Async SQLAlchemy session.
            name:      Logical template name.
            content:   Jinja2-compatible template text.
            author_id: UUID of the admin user creating this version.

        Returns:
            The newly created :class:`PromptTemplate` row (``is_active=True``).

        Requirements: 25.1
        """
        # Determine next version number.
        existing = await PromptService.list_versions(db, name)
        next_version = (max(t.version for t in existing) + 1) if existing else 1

        # Deactivate the current active version if one exists.
        await db.execute(
            update(PromptTemplate)
            .where(PromptTemplate.name == name, PromptTemplate.is_active.is_(True))
            .values(is_active=False)
        )

        # Create the new version row.
        new_template = PromptTemplate(
            name=name,
            content=content,
            version=next_version,
            author_id=author_id,
            is_active=True,
        )
        db.add(new_template)
        await (
            db.flush()
        )  # populate server-side defaults (id, timestamps) within transaction
        await db.refresh(new_template)
        return new_template

    @staticmethod
    async def rollback(
        db: AsyncSession,
        name: str,
        version_number: int,
    ) -> PromptTemplate:
        """Restore a template to the content it had at ``version_number``.

        This operation is non-destructive: a **new** version row is created
        whose content is a copy of version ``version_number``.  The historical
        rows are never modified, preserving the complete audit trail.

        Args:
            db:             Async SQLAlchemy session.
            name:           Logical template name.
            version_number: The historical version whose content to restore.

        Returns:
            The newly created :class:`PromptTemplate` row (``is_active=True``).

        Raises:
            TemplateNotFoundError: When ``version_number`` does not exist for
                ``name``.

        Requirements: 25.2
        """
        # Fetch the target historical version.
        result = await db.execute(
            select(PromptTemplate)
            .where(
                PromptTemplate.name == name,
                PromptTemplate.version == version_number,
            )
            .limit(1)
        )
        target = result.scalar_one_or_none()
        if target is None:
            raise TemplateNotFoundError(
                f"No version {version_number} found for template name={name!r}"
            )

        # Create a new version with the historical content (history preserved).
        return await PromptService.create_version(
            db=db,
            name=name,
            content=target.content,
            author_id=target.author_id,
        )
