# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : schemas
# File    : auth.py
# Purpose : auth — schemas module
#
# Architecture Layer : Pydantic Schema
# Pattern Used       : Python Module
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Pydantic v2 request/response schemas for the /auth/* endpoints.

All schemas use Pydantic v2 syntax (``model_config``, ``field_validator``).
They are intentionally thin — validation lives here; business logic lives in
``app.services.auth_service``.

Requirements: 1.1, 1.2, 1.6, 1.10, 9.7
"""

from __future__ import annotations

import re
import uuid

from pydantic import BaseModel, ConfigDict, EmailStr, Field, field_validator

from app.security.input_sanitizer import sanitize_user_string

# ---------------------------------------------------------------------------
# Validators shared across schemas
# ---------------------------------------------------------------------------

_EMAIL_REGEX = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


def _validate_email_format(value: str) -> str:
    """Basic structural email validation (RFC-5321 rough check)."""
    stripped = value.strip().lower()
    if not _EMAIL_REGEX.match(stripped):
        raise ValueError("invalid email address format")
    return stripped


def _validate_password_length(value: str) -> str:
    """Password must be at least 12 characters (Requirement 1.1)."""
    if len(value) < 12:
        raise ValueError("password must be at least 12 characters")
    return value


# ---------------------------------------------------------------------------
# Registration
# ---------------------------------------------------------------------------


class RegisterRequest(BaseModel):
    """Request body for POST /auth/register.

    Requirements: 1.1, 9.7
    """

    model_config = ConfigDict(str_strip_whitespace=True)

    email: EmailStr = Field(
        description="Valid email address used as the account identifier.",
        examples=["user@example.com"],
    )
    password: str = Field(
        min_length=12,
        max_length=128,
        description="Password — minimum 12 characters, maximum 128 characters.",
        examples=["Str0ng!Password123"],
    )
    display_name: str = Field(
        default="",
        max_length=255,
        description="Optional display name shown in the UI.",
        examples=["Jane Doe"],
    )

    @field_validator("password")
    @classmethod
    def password_min_length(cls, v: str) -> str:
        return _validate_password_length(v)

    @field_validator("display_name")
    @classmethod
    def sanitize_display_name(cls, v: str) -> str:
        return sanitize_user_string(cls, v)


class RegisterResponse(BaseModel):
    """Response body for POST /auth/register (HTTP 201).

    Requirements: 1.1, 1.2
    """

    model_config = ConfigDict(from_attributes=True)

    user_id: uuid.UUID = Field(description="UUID of the newly created user account.")
    email: str = Field(description="Normalised email address.")
    access_token: str = Field(description="Signed JWT access token (15-minute expiry).")
    refresh_token: str = Field(description="Opaque refresh token (30-day expiry).")
    access_token_expires_at: int = Field(
        description="Epoch milliseconds when the access token expires."
    )
    refresh_token_expires_at: int = Field(
        description="Epoch milliseconds when the refresh token expires."
    )
    token_type: str = Field(default="bearer", description="OAuth2 token type literal.")


# ---------------------------------------------------------------------------
# Login
# ---------------------------------------------------------------------------


class LoginRequest(BaseModel):
    """Request body for POST /auth/login.

    Requirements: 1.2, 9.7
    """

    model_config = ConfigDict(str_strip_whitespace=True)

    email: EmailStr = Field(
        description="Registered email address.",
        examples=["user@example.com"],
    )
    password: str = Field(
        max_length=128,
        description="Account password (maximum 128 characters).",
        examples=["Str0ng!Password123"],
    )


class LoginResponse(BaseModel):
    """Response body for POST /auth/login (HTTP 200).

    Requirements: 1.2
    """

    model_config = ConfigDict(from_attributes=True)

    user_id: uuid.UUID = Field(description="UUID of the authenticated user.")
    email: str = Field(description="Normalised email address.")
    role: str = Field(description="User role: user | premium | admin.")
    access_token: str = Field(description="Signed JWT access token (15-minute expiry).")
    refresh_token: str = Field(description="Opaque refresh token (30-day expiry).")
    access_token_expires_at: int = Field(
        description="Epoch milliseconds when the access token expires."
    )
    refresh_token_expires_at: int = Field(
        description="Epoch milliseconds when the refresh token expires."
    )
    token_type: str = Field(default="bearer", description="OAuth2 token type literal.")


# ---------------------------------------------------------------------------
# Token refresh
# ---------------------------------------------------------------------------


class RefreshRequest(BaseModel):
    """Request body for POST /auth/refresh.

    Requirements: 1.3, 1.4
    """

    refresh_token: str = Field(
        description="The opaque refresh token previously issued by /auth/login or /auth/refresh.",
        examples=["dGhpcyBpcyBub3QgYSByZWFsIHRva2Vu"],
    )


class RefreshResponse(BaseModel):
    """Response body for POST /auth/refresh (HTTP 200).

    Requirements: 1.3, 1.4
    """

    access_token: str = Field(description="New signed JWT access token.")
    refresh_token: str = Field(
        description="New opaque refresh token (old token is invalidated)."
    )
    access_token_expires_at: int = Field(
        description="Epoch milliseconds when the new access token expires."
    )
    refresh_token_expires_at: int = Field(
        description="Epoch milliseconds when the new refresh token expires."
    )
    token_type: str = Field(default="bearer", description="OAuth2 token type literal.")


# ---------------------------------------------------------------------------
# Logout
# ---------------------------------------------------------------------------


class LogoutResponse(BaseModel):
    """Response body for POST /auth/logout (HTTP 200).

    Requirements: 1.10
    """

    message: str = Field(
        default="Successfully logged out.",
        description="Human-readable confirmation message.",
    )
    tokens_revoked: int = Field(
        description="Number of active refresh tokens that were revoked.",
    )


# ---------------------------------------------------------------------------
# Google OAuth2
# ---------------------------------------------------------------------------


class GoogleAuthRequest(BaseModel):
    """Request body for POST /auth/google.

    The client exchanges the Google OAuth2 authorisation code (returned by the
    Google Sign-In SDK on Android) for application-level JWT + refresh tokens.

    Requirements: 1.6
    """

    id_token: str = Field(
        description=(
            "Google ID token (``id_token``) obtained from the Google Sign-In SDK "
            "on the Android client.  The backend verifies this token with Google's "
            "public keys before trusting any claims."
        ),
        examples=["eyJhbGci..."],
    )


class GoogleAuthResponse(BaseModel):
    """Response body for POST /auth/google (HTTP 200 / 201).

    Requirements: 1.6
    """

    model_config = ConfigDict(from_attributes=True)

    user_id: uuid.UUID = Field(description="UUID of the linked local user account.")
    email: str = Field(description="Verified email address from the Google account.")
    display_name: str = Field(description="Display name from the Google profile.")
    role: str = Field(description="User role: user | premium | admin.")
    access_token: str = Field(description="Signed JWT access token (15-minute expiry).")
    refresh_token: str = Field(description="Opaque refresh token (30-day expiry).")
    access_token_expires_at: int = Field(
        description="Epoch milliseconds when the access token expires."
    )
    refresh_token_expires_at: int = Field(
        description="Epoch milliseconds when the refresh token expires."
    )
    token_type: str = Field(default="bearer", description="OAuth2 token type literal.")
    is_new_user: bool = Field(
        description="``True`` when a new local account was created on this sign-in.",
    )
