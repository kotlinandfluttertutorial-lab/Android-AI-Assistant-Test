# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : security
# File    : audit.py
# Purpose : audit — security module
#
# Architecture Layer : Security
# Pattern Used       : Audit Logging
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Audit log service — writes security events to the ``audit_logs`` table.

All authentication events (login, logout, token refresh, failed login) are
persisted here for compliance purposes.  Records are retained for a minimum of
90 days (enforced at the database / retention-policy level, not in code).

Event types
-----------
- ``login``         — successful password or OAuth2 authentication.
- ``logout``        — explicit session termination.
- ``token_refresh`` — successful refresh token rotation.
- ``failed_login``  — credential validation failure (wrong password, unknown
                      email, locked account).

The service is intentionally append-only: no update or delete operations are
provided here.  Row deletion must go through a dedicated data-retention job.

Usage::

    from app.security.audit import AuditService, AuditEventType

    audit = AuditService(db)
    await audit.log_login(user_id=user.id, ip_address=request.client.host,
                          user_agent=request.headers.get("user-agent", ""))

Requirements: 9.8
"""

from __future__ import annotations

import uuid
from enum import Enum
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.models.audit_log import AuditLog


class AuditEventType(str, Enum):
    """Canonical string identifiers for audit log event types.

    Requirements: 9.8
    """

    login = "login"
    logout = "logout"
    token_refresh = "token_refresh"
    failed_login = "failed_login"
    mcp_invoke = "mcp_invoke"


class AuditService:
    """Append-only service for writing audit log entries.

    All methods are ``async`` coroutines that flush a new :class:`~app.models.audit_log.AuditLog`
    row within the caller's active transaction.  Commit/rollback is controlled
    by the caller (typically a route handler's dependency session).

    Args:
        db: SQLAlchemy async session (transaction managed by the caller).

    Requirements: 9.8
    """

    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    # ------------------------------------------------------------------
    # Low-level write
    # ------------------------------------------------------------------

    async def _write(
        self,
        *,
        event_type: AuditEventType | str,
        ip_address: str,
        user_agent: str,
        user_id: uuid.UUID | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> AuditLog:
        """Persist a single audit log record and return it.

        Args:
            event_type:  The event category (use :class:`AuditEventType`
                values for forward compatibility).
            ip_address:  Client IP (IPv4 or IPv6).
            user_agent:  HTTP ``User-Agent`` header value.
            user_id:     UUID of the acting user, or ``None`` for
                pre-authentication events.
            metadata:    Optional JSONB payload with event-specific details.

        Returns:
            The flushed :class:`~app.models.audit_log.AuditLog` instance.
        """
        entry = AuditLog(
            user_id=user_id,
            event_type=event_type if isinstance(event_type, str) else event_type.value,
            ip_address=ip_address or "",
            user_agent=user_agent or "",
            metadata_=metadata or {},
        )
        self._db.add(entry)
        await self._db.flush()
        return entry

    # ------------------------------------------------------------------
    # Typed convenience methods
    # ------------------------------------------------------------------

    async def log_login(
        self,
        *,
        user_id: uuid.UUID,
        ip_address: str,
        user_agent: str,
        provider: str = "password",
    ) -> AuditLog:
        """Record a successful login event.

        Args:
            user_id:    UUID of the authenticated user.
            ip_address: Client IP address.
            user_agent: HTTP User-Agent header.
            provider:   Authentication provider, e.g. ``"password"`` or
                ``"google"``.

        Returns:
            The persisted :class:`~app.models.audit_log.AuditLog` row.

        Requirements: 9.8
        """
        return await self._write(
            event_type=AuditEventType.login,
            user_id=user_id,
            ip_address=ip_address,
            user_agent=user_agent,
            metadata={"provider": provider},
        )

    async def log_logout(
        self,
        *,
        user_id: uuid.UUID,
        ip_address: str,
        user_agent: str,
        tokens_revoked: int = 0,
    ) -> AuditLog:
        """Record an explicit logout event.

        Args:
            user_id:        UUID of the user who logged out.
            ip_address:     Client IP address.
            user_agent:     HTTP User-Agent header.
            tokens_revoked: Number of refresh tokens that were revoked.

        Returns:
            The persisted :class:`~app.models.audit_log.AuditLog` row.

        Requirements: 9.8
        """
        return await self._write(
            event_type=AuditEventType.logout,
            user_id=user_id,
            ip_address=ip_address,
            user_agent=user_agent,
            metadata={"tokens_revoked": tokens_revoked},
        )

    async def log_token_refresh(
        self,
        *,
        user_id: uuid.UUID,
        ip_address: str,
        user_agent: str,
    ) -> AuditLog:
        """Record a successful refresh token rotation event.

        Args:
            user_id:    UUID of the user whose tokens were rotated.
            ip_address: Client IP address.
            user_agent: HTTP User-Agent header.

        Returns:
            The persisted :class:`~app.models.audit_log.AuditLog` row.

        Requirements: 9.8
        """
        return await self._write(
            event_type=AuditEventType.token_refresh,
            user_id=user_id,
            ip_address=ip_address,
            user_agent=user_agent,
        )

    async def log_failed_login(
        self,
        *,
        ip_address: str,
        user_agent: str,
        email: str,
        reason: str = "",
        user_id: uuid.UUID | None = None,
    ) -> AuditLog:
        """Record a failed login attempt.

        ``user_id`` is optional because the email may not correspond to an
        existing account — in that case ``user_id`` should be left as ``None``.

        Args:
            ip_address: Client IP address.
            user_agent: HTTP User-Agent header.
            email:      The email submitted in the failed attempt (stored for
                forensic purposes; never store the attempted password).
            reason:     Short human-readable reason, e.g. ``"wrong_password"``,
                ``"account_locked"``, ``"user_not_found"``.
            user_id:    UUID of the matched user, if any.

        Returns:
            The persisted :class:`~app.models.audit_log.AuditLog` row.

        Requirements: 9.8
        """
        return await self._write(
            event_type=AuditEventType.failed_login,
            user_id=user_id,
            ip_address=ip_address,
            user_agent=user_agent,
            metadata={"email": email, "reason": reason},
        )

    async def log_mcp_invoke(
        self,
        *,
        user_id: uuid.UUID,
        ip_address: str,
        user_agent: str,
        tool: str,
        params_summary: dict[str, Any] | None = None,
    ) -> AuditLog:
        """Record an MCP tool invocation event.

        Args:
            user_id:       UUID of the user who triggered the invocation.
            ip_address:    Client IP address.
            user_agent:    HTTP User-Agent header.
            tool:          Tool name string, e.g. ``"github:create_issue"``.
            params_summary: Optional sanitised summary of tool parameters
                (never log secrets or personal data).

        Returns:
            The persisted :class:`~app.models.audit_log.AuditLog` row.

        Requirements: 9.8
        """
        return await self._write(
            event_type=AuditEventType.mcp_invoke,
            user_id=user_id,
            ip_address=ip_address,
            user_agent=user_agent,
            metadata={"tool": tool, "params": params_summary or {}},
        )
