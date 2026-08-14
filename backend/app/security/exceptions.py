# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : security
# File    : exceptions.py
# Purpose : exceptions — security module
#
# Architecture Layer : Security
# Pattern Used       : Python Module
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Typed exceptions for the security / auth layer.

Every exception maps to a specific HTTP status code so that API route handlers
can catch a known type and return a well-formed error response without
inspecting exception messages.

Usage::

    from app.security.exceptions import InvalidTokenError, SecurityViolationError

    raise InvalidTokenError("token has expired")
    raise SecurityViolationError("replay detected — all family tokens revoked")

Requirements: 1.2, 1.3, 1.4, 1.5
"""

from __future__ import annotations


class AuthError(Exception):
    """Base class for all authentication / authorisation errors."""


class InvalidTokenError(AuthError):
    """Raised when a JWT or refresh token is missing, malformed, expired, or
    revoked.  Maps to HTTP 401 Unauthorized.
    """


class SecurityViolationError(AuthError):
    """Raised when a security-relevant attack is detected, e.g. refresh token
    replay.  Maps to HTTP 401 Unauthorized (the response body should be vague
    to avoid leaking information to an attacker).
    """


class TokenFamilyRevokedError(SecurityViolationError):
    """Raised specifically when an entire token family has been revoked due to
    replay detection.  Subclass of SecurityViolationError so callers that only
    catch the parent still handle it correctly.
    """


class AccountLockedError(AuthError):
    """Raised when a login attempt is rejected because the account is currently
    locked due to too many consecutive failed attempts within the rolling window.

    Carries ``retry_after_seconds`` so the HTTP layer can return a
    ``Retry-After`` header.

    Maps to HTTP 429 Too Many Requests.

    Requirements: 1.5
    """

    def __init__(self, message: str, retry_after_seconds: int = 0) -> None:
        super().__init__(message)
        self.retry_after_seconds = retry_after_seconds
