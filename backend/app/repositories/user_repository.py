# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : repositories
# File    : user_repository.py
# Purpose : Database access layer for user entities
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

"""Database access layer for users.

All queries operate on the ``users`` table via the SQLAlchemy async session.
The repository layer is intentionally thin — it translates between the service
layer and the ORM without embedding business logic.

Password hashing and token operations are *not* performed here; callers must
pass pre-hashed values.

Requirements: 1.1, 1.6
"""

from __future__ import annotations

import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.user import User, UserRole


class UserRepository:
    """CRUD and lookup operations for the ``users`` table.

    All methods are ``async`` and accept an ``AsyncSession`` injected by the
    caller (typically a FastAPI dependency or service function).

    Args:
        db: SQLAlchemy async session for the current request.
    """

    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    # ------------------------------------------------------------------
    # Read
    # ------------------------------------------------------------------

    async def get_by_email(self, email: str) -> User | None:
        """Return the user with *email* (case-insensitive), or ``None``.

        Args:
            email: The email address to look up (normalised to lowercase).

        Returns:
            The matching :class:`~app.models.user.User` row, or ``None``.
        """
        result = await self._db.execute(
            select(User).where(User.email == email.strip().lower())
        )
        return result.scalar_one_or_none()

    async def get_by_id(self, user_id: uuid.UUID) -> User | None:
        """Return the user with the given *user_id*, or ``None``.

        Args:
            user_id: Primary key UUID to look up.

        Returns:
            The matching :class:`~app.models.user.User` row, or ``None``.
        """
        result = await self._db.execute(select(User).where(User.id == user_id))
        return result.scalar_one_or_none()

    async def get_by_google_id(self, google_id: str) -> User | None:
        """Return the user linked to the given Google account, or ``None``.

        Args:
            google_id: Google account ``sub`` claim value.

        Returns:
            The matching :class:`~app.models.user.User` row, or ``None``.
        """
        result = await self._db.execute(select(User).where(User.google_id == google_id))
        return result.scalar_one_or_none()

    # ------------------------------------------------------------------
    # Create
    # ------------------------------------------------------------------

    async def create(
        self,
        *,
        email: str,
        password_hash: str,
        display_name: str = "",
        role: UserRole = UserRole.user,
    ) -> User:
        """Create a new user with email/password credentials.

        Args:
            email:         Normalised email address (already lowercase).
            password_hash: bcrypt hash of the plaintext password.
            display_name:  Optional display name.
            role:          User role (defaults to ``UserRole.user``).

        Returns:
            The newly created and flushed :class:`~app.models.user.User`.

        Requirements: 1.1
        """
        user = User(
            email=email.strip().lower(),
            password_hash=password_hash,
            display_name=display_name or "",
            role=role,
            is_active=True,
        )
        self._db.add(user)
        await self._db.flush()
        return user

    async def create_google_user(
        self,
        *,
        email: str,
        google_id: str,
        display_name: str = "",
        avatar_url: str | None = None,
        role: UserRole = UserRole.user,
    ) -> User:
        """Create a new user authenticated via Google OAuth2.

        Google-created accounts do not have a password hash; an empty string
        placeholder is stored so the NOT NULL constraint is satisfied.  These
        accounts can never be accessed via password login.

        Args:
            email:        Verified email from the Google ID token.
            google_id:    Google account ``sub`` claim.
            display_name: Display name from the Google profile.
            avatar_url:   Profile picture URL from the Google profile.
            role:         User role (defaults to ``UserRole.user``).

        Returns:
            The newly created and flushed :class:`~app.models.user.User`.

        Requirements: 1.6
        """
        user = User(
            email=email.strip().lower(),
            password_hash="",  # Google users cannot log in with a password
            google_id=google_id,
            display_name=display_name or "",
            avatar_url=avatar_url,
            role=role,
            is_active=True,
        )
        self._db.add(user)
        await self._db.flush()
        return user

    # ------------------------------------------------------------------
    # Update
    # ------------------------------------------------------------------

    async def update_google_id(
        self,
        user_id: uuid.UUID,
        google_id: str,
    ) -> User | None:
        """Link a Google account to an existing local user.

        Called when a user who previously registered with email/password signs
        in with Google for the first time and their email matches an existing
        account.

        Args:
            user_id:   UUID of the existing local user.
            google_id: Google account ``sub`` claim to associate.

        Returns:
            The updated :class:`~app.models.user.User`, or ``None`` if not found.

        Requirements: 1.6
        """
        user = await self.get_by_id(user_id)
        if user is None:
            return None
        user.google_id = google_id
        await self._db.flush()
        return user
