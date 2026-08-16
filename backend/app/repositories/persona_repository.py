# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : repositories
# File    : persona_repository.py
# Purpose : Database access layer for persona entities
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

"""Database access layer for personas.

All queries operate on the ``personas`` table via the SQLAlchemy async session.
This repository provides CRUD operations for user-created AI personas.

A user can access:
- Their own personas (where ``user_id`` matches).
- Admin-locked personas where the user's role is in ``allowed_roles`` or
  ``allowed_roles`` is empty (visible to everyone).

Requirements: 32.1, 32.3, 32.5, 32.6
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from typing import Any

from sqlalchemy import and_, func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.persona import Persona, PersonaTone


@dataclass
class PersonaCreateData:
    """Data transfer object for creating a new persona.

    Attributes:
        name: Persona display name (1-80 characters).
        system_prompt: Custom system prompt (1-4,000 characters).
        tone: Tone/style of the AI response.
        scope_description: Optional description of when to use this persona.
        allowed_roles: RBAC roles permitted to view this persona.
    """

    name: str
    system_prompt: str
    tone: PersonaTone
    scope_description: str = ""
    allowed_roles: list[str] = None  # type: ignore[assignment]

    def __post_init__(self) -> None:
        if self.allowed_roles is None:
            self.allowed_roles = []


@dataclass
class PersonaUpdateData:
    """Data transfer object for updating an existing persona.

    All fields are optional — only non-None fields are applied.

    Attributes:
        name: New persona display name.
        system_prompt: New system prompt.
        tone: New tone enum value.
        scope_description: New scope description.
        allowed_roles: New allowed roles list.
    """

    name: str | None = None
    system_prompt: str | None = None
    tone: PersonaTone | None = None
    scope_description: str | None = None
    allowed_roles: list[str] | None = None


class PersonaRepository:
    """CRUD and lookup operations for the ``personas`` table.

    Args:
        db: SQLAlchemy async session for the current request.
    """

    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    async def get_personas_for_user(
        self,
        user_id: uuid.UUID,
        user_role: str,
    ) -> list[Persona]:
        """Return all personas accessible to the given user.

        Includes:
        - The user's own personas (any ``admin_locked`` state).
        - Admin-locked personas where the user's role is in ``allowed_roles``
          OR where ``allowed_roles`` is empty (shared with everyone).

        Args:
            user_id: UUID of the requesting user.
            user_role: RBAC role string of the requesting user (e.g. "user",
                "premium", "admin").

        Returns:
            List of :class:`~app.models.persona.Persona` rows ordered by
            creation time ascending.

        Requirements: 32.6
        """
        # Own personas
        own_condition = Persona.user_id == user_id

        # Admin-locked personas the user is allowed to see:
        # admin_locked=True AND (allowed_roles is empty OR user_role is in allowed_roles)
        admin_locked_condition = and_(
            Persona.admin_locked.is_(True),
            Persona.user_id != user_id,
            or_(
                # PostgreSQL: array length 0 means open to all roles
                func.array_length(Persona.allowed_roles, 1).is_(None),
                # user_role is contained in the allowed_roles array
                Persona.allowed_roles.contains([user_role]),
            ),
        )

        result = await self._db.execute(
            select(Persona)
            .where(or_(own_condition, admin_locked_condition))
            .order_by(Persona.created_at)
        )
        return list(result.scalars().all())

    async def get_persona_by_id(
        self,
        persona_id: uuid.UUID,
    ) -> Persona | None:
        """Retrieve a single persona by its primary key.

        Args:
            persona_id: UUID of the persona to retrieve.

        Returns:
            The :class:`~app.models.persona.Persona` row, or ``None`` if not found.

        Requirements: 32.1
        """
        result = await self._db.execute(select(Persona).where(Persona.id == persona_id))
        return result.scalar_one_or_none()

    async def create_persona(
        self,
        user_id: uuid.UUID,
        data: PersonaCreateData,
    ) -> Persona:
        """Create a new persona owned by the given user.

        Args:
            user_id: UUID of the persona owner.
            data: Field values for the new persona.

        Returns:
            The newly created and flushed :class:`~app.models.persona.Persona`.

        Requirements: 32.1
        """
        persona = Persona(
            user_id=user_id,
            name=data.name,
            system_prompt=data.system_prompt,
            tone=data.tone,
            scope_description=data.scope_description,
            allowed_roles=data.allowed_roles,
        )
        self._db.add(persona)
        await self._db.flush()
        return persona

    async def count_user_personas(self, user_id: uuid.UUID) -> int:
        """Count the personas owned by a user (excluding admin-locked ones from others).

        This count is used to enforce the 20-persona limit. Only the user's own
        personas are counted — admin-locked personas from other users do not
        contribute to the quota.

        Args:
            user_id: UUID of the user whose quota is being checked.

        Returns:
            Number of personas owned by the user.

        Requirements: 32.3
        """
        result = await self._db.execute(
            select(func.count()).where(Persona.user_id == user_id)
        )
        return result.scalar_one()

    async def update_persona(
        self,
        persona_id: uuid.UUID,
        data: PersonaUpdateData,
    ) -> Persona | None:
        """Update fields on an existing persona.

        Only non-None fields in *data* are applied; fields that are ``None``
        are left unchanged.

        Args:
            persona_id: UUID of the persona to update.
            data: Partial field updates.

        Returns:
            The updated :class:`~app.models.persona.Persona` row, or ``None``
            if the persona does not exist.

        Requirements: 32.1, 32.5
        """
        persona = await self.get_persona_by_id(persona_id)
        if persona is None:
            return None

        updates: dict[str, Any] = {}
        if data.name is not None:
            updates["name"] = data.name
        if data.system_prompt is not None:
            updates["system_prompt"] = data.system_prompt
        if data.tone is not None:
            updates["tone"] = data.tone
        if data.scope_description is not None:
            updates["scope_description"] = data.scope_description
        if data.allowed_roles is not None:
            updates["allowed_roles"] = data.allowed_roles

        for field_name, value in updates.items():
            setattr(persona, field_name, value)

        await self._db.flush()
        return persona

    async def delete_persona(self, persona_id: uuid.UUID) -> bool:
        """Delete a persona by its primary key.

        Args:
            persona_id: UUID of the persona to delete.

        Returns:
            ``True`` if a row was deleted, ``False`` if the persona was not found.

        Requirements: 32.1, 32.5
        """
        persona = await self.get_persona_by_id(persona_id)
        if persona is None:
            return False

        await self._db.delete(persona)
        await self._db.flush()
        return True
