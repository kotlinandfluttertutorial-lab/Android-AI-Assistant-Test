# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services
# File    : persona_service.py
# Purpose : Business logic for the persona domain
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

"""Persona service — CRUD business logic for AI personas.

This module provides the ``PersonaService`` class which encapsulates all
business rules for persona management:

1. **Persona listing** — returns own personas plus admin-locked personas the
   user is permitted to see based on their RBAC role (Requirement 32.6).

2. **Persona creation** — enforces the 20-persona limit per user (Requirement 32.3)
   and validates the ``system_prompt`` for prompt injection patterns using
   ``InjectionDetector`` (Requirement 32.8).

3. **Persona update** — prevents non-admin users from editing admin-locked personas
   (Requirement 32.5) and re-validates the ``system_prompt`` when changed.

4. **Persona deletion** — prevents non-admin users from deleting admin-locked
   personas (Requirement 32.5).

Requirements: 32.1, 32.3, 32.5, 32.6, 32.8
"""

from __future__ import annotations

import logging
import uuid

from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.persona import Persona
from app.repositories.persona_repository import (
    PersonaCreateData,
    PersonaRepository,
    PersonaUpdateData,
)
from app.schemas.personas import PersonaCreate, PersonaUpdate
from app.services.safety_service import InjectionDetector, PromptInjectionError

logger = logging.getLogger(__name__)

# Maximum number of own personas a user may hold simultaneously (Requirement 32.3).
_MAX_PERSONAS_PER_USER = 20

# Platform safety rules appended for non-admin users (Requirement 32.4).
_PLATFORM_SAFETY_RULES = (
    "\n\n--- Platform Safety Rules ---\n"
    "This AI assistant must not provide harmful, illegal, or unethical content. "
    "Responses must remain within the scope of the active persona and the platform's "
    "community standards.\n"
    "User-defined personas cannot override platform safety guardrails."
)


class PersonaService:
    """Business logic layer for AI persona management.

    All public methods raise :class:`fastapi.HTTPException` on validation
    failures so router handlers remain thin.

    Usage::

        service = PersonaService()
        personas = await service.list_personas(user_id, user_role, db)

    Requirements: 32.1, 32.3, 32.5, 32.6, 32.8
    """

    def __init__(self) -> None:
        self._injection_detector = InjectionDetector()

    # ------------------------------------------------------------------
    # List
    # ------------------------------------------------------------------

    async def list_personas(
        self,
        user_id: uuid.UUID,
        user_role: str,
        db: AsyncSession,
    ) -> list[Persona]:
        """Return all personas accessible to the requesting user.

        Includes the user's own personas plus any admin-locked personas
        permitted for the user's RBAC role.

        Args:
            user_id: UUID of the requesting user.
            user_role: RBAC role string of the requesting user.
            db: SQLAlchemy async session.

        Returns:
            List of :class:`~app.models.persona.Persona` ORM instances.

        Requirements: 32.6
        """
        repo = PersonaRepository(db)
        return await repo.get_personas_for_user(user_id, user_role)

    # ------------------------------------------------------------------
    # Create
    # ------------------------------------------------------------------

    async def create_persona(
        self,
        user_id: uuid.UUID,
        user_role: str,
        data: PersonaCreate,
        db: AsyncSession,
    ) -> Persona:
        """Create a new persona for the given user.

        Validation steps (in order):
        1. Check ``system_prompt`` for prompt injection patterns (Req 32.8).
        2. Enforce the 20-persona limit per user (Req 32.3).
        3. Persist the new persona row.

        Args:
            user_id: UUID of the persona owner.
            user_role: RBAC role string of the requesting user.
            data: Validated persona creation payload.
            db: SQLAlchemy async session.

        Returns:
            The newly created :class:`~app.models.persona.Persona`.

        Raises:
            HTTPException 422 PROMPT_INJECTION_DETECTED: When system_prompt
                contains an injection pattern.
            HTTPException 422 PERSONA_LIMIT_REACHED: When the user already
                holds 20 personas.

        Requirements: 32.1, 32.3, 32.8
        """
        # Step 1 — Validate system_prompt for injection patterns (Req 32.8)
        await self._check_injection(data.system_prompt, str(user_id), db)

        # Step 2 — Enforce 20-persona limit (Req 32.3)
        repo = PersonaRepository(db)
        current_count = await repo.count_user_personas(user_id)
        if current_count >= _MAX_PERSONAS_PER_USER:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail={
                    "error": {
                        "code": "PERSONA_LIMIT_REACHED",
                        "message": (
                            "You have reached the maximum of 20 personas. "
                            "Please delete an existing persona before creating a new one."
                        ),
                    }
                },
            )

        # Step 3 — Persist (Req 32.1)
        create_data = PersonaCreateData(
            name=data.name,
            system_prompt=data.system_prompt,
            tone=data.tone,
            scope_description=data.scope_description,
            allowed_roles=data.allowed_roles,
        )
        persona = await repo.create_persona(user_id, create_data)
        await db.commit()

        logger.info(
            "Persona created: id=%s name=%r user_id=%s",
            persona.id,
            persona.name,
            user_id,
        )
        return persona

    # ------------------------------------------------------------------
    # Update
    # ------------------------------------------------------------------

    async def update_persona(
        self,
        persona_id: uuid.UUID,
        user_id: uuid.UUID,
        user_role: str,
        data: PersonaUpdate,
        db: AsyncSession,
    ) -> Persona:
        """Update an existing persona.

        Validation steps (in order):
        1. Verify the persona exists (404 if not).
        2. For non-admin users, reject updates to admin-locked personas (403).
        3. If ``system_prompt`` is changing, validate for injection (422).
        4. Apply updates.

        Args:
            persona_id: UUID of the persona to update.
            user_id: UUID of the requesting user.
            user_role: RBAC role string of the requesting user.
            data: Partial update payload (only non-None fields are applied).
            db: SQLAlchemy async session.

        Returns:
            The updated :class:`~app.models.persona.Persona`.

        Raises:
            HTTPException 404: When the persona does not exist.
            HTTPException 403: When a non-admin attempts to edit an admin-locked persona.
            HTTPException 422 PROMPT_INJECTION_DETECTED: When the new system_prompt
                contains an injection pattern.

        Requirements: 32.5, 32.8
        """
        repo = PersonaRepository(db)
        persona = await repo.get_persona_by_id(persona_id)

        if persona is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Persona with id={persona_id!s} not found.",
            )

        # Req 32.5 — non-admin cannot edit admin-locked personas
        if persona.admin_locked and user_role != "admin":
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="This persona is locked by an administrator and cannot be edited.",
            )

        # Validate system_prompt if it is being changed (Req 32.8)
        if data.system_prompt is not None:
            await self._check_injection(data.system_prompt, str(user_id), db)

        update_data = PersonaUpdateData(
            name=data.name,
            system_prompt=data.system_prompt,
            tone=data.tone,
            scope_description=data.scope_description,
            allowed_roles=data.allowed_roles,
        )
        updated = await repo.update_persona(persona_id, update_data)
        await db.commit()

        logger.info("Persona updated: id=%s user_id=%s", persona_id, user_id)
        # update_persona returns the updated row; it cannot be None here since
        # we already confirmed it exists above.
        return updated  # type: ignore[return-value]

    # ------------------------------------------------------------------
    # Delete
    # ------------------------------------------------------------------

    async def delete_persona(
        self,
        persona_id: uuid.UUID,
        user_id: uuid.UUID,
        user_role: str,
        db: AsyncSession,
    ) -> None:
        """Delete a persona.

        Validation steps:
        1. Verify the persona exists (404 if not).
        2. For non-admin users, reject deletion of admin-locked personas (403).
        3. Delete the row.

        Args:
            persona_id: UUID of the persona to delete.
            user_id: UUID of the requesting user.
            user_role: RBAC role string of the requesting user.
            db: SQLAlchemy async session.

        Raises:
            HTTPException 404: When the persona does not exist.
            HTTPException 403: When a non-admin attempts to delete an admin-locked persona.

        Requirements: 32.5
        """
        repo = PersonaRepository(db)
        persona = await repo.get_persona_by_id(persona_id)

        if persona is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Persona with id={persona_id!s} not found.",
            )

        # Req 32.5 — non-admin cannot delete admin-locked personas
        if persona.admin_locked and user_role != "admin":
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="This persona is locked by an administrator and cannot be deleted.",
            )

        await repo.delete_persona(persona_id)
        await db.commit()

        logger.info("Persona deleted: id=%s user_id=%s", persona_id, user_id)

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    async def _check_injection(
        self,
        system_prompt: str,
        user_id: str,
        db: AsyncSession,
    ) -> None:
        """Check *system_prompt* for prompt injection patterns.

        On detection raises HTTP 422 with the ``PROMPT_INJECTION_DETECTED``
        error code, as required by Requirement 32.8.

        Args:
            system_prompt: The system prompt text to inspect.
            user_id: String UUID of the requesting user (for audit log).
            db: SQLAlchemy async session.

        Raises:
            HTTPException 422 PROMPT_INJECTION_DETECTED: On detection.

        Requirements: 32.8
        """
        try:
            await self._injection_detector.check_input(system_prompt, user_id, db)
        except PromptInjectionError as exc:
            logger.warning(
                "Prompt injection detected in persona system_prompt for user %s: %s",
                user_id,
                exc,
            )
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail={
                    "error": {
                        "code": "PROMPT_INJECTION_DETECTED",
                        "message": (
                            "The persona system prompt contains a prompt injection pattern "
                            "and cannot be saved."
                        ),
                    }
                },
            ) from exc
