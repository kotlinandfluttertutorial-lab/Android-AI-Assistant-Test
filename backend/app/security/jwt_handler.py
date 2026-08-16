"""JWT issuance and verification for the Android AI Assistant backend.

This module is the **single authoritative place** for all JWT operations.
No other module should call ``PyJWT`` directly — always go through the
functions defined here.

Access token (JWT)
------------------
- Algorithm: HS256 (configurable via ``settings.JWT_ALGORITHM``)
- Claims:
    - ``sub``  — string representation of the user UUID
    - ``role`` — user role string ("user" | "premium" | "admin")
    - ``jti``  — unique token ID (UUID4) for revocation
    - ``iat``  — issued-at timestamp (UTC)
    - ``exp``  — expiry timestamp (UTC, ``now + ACCESS_TOKEN_EXPIRE_MINUTES``)

Refresh token
-------------
- Opaque random token (32 bytes of URL-safe base64)
- Never stored in plaintext — only the SHA-256 hex-digest is persisted in the
  ``refresh_tokens`` table
- Expires in ``settings.REFRESH_TOKEN_EXPIRE_DAYS`` days

Requirements: 1.2, 1.3, 1.4
"""

from __future__ import annotations

import hashlib
import secrets
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

import jwt
from jwt.exceptions import PyJWTError as JWTError
from pydantic import BaseModel

from app.security.exceptions import InvalidTokenError

# ---------------------------------------------------------------------------
# Token payload schema
# ---------------------------------------------------------------------------


class TokenPayload(BaseModel):
    """Parsed, validated claims from a decoded JWT access token."""

    sub: str  # user UUID as string
    role: str  # user role
    jti: str  # unique token ID
    iat: datetime  # issued at
    exp: datetime  # expiry


# ---------------------------------------------------------------------------
# Public helpers
# ---------------------------------------------------------------------------


def _get_settings():
    from app.config.settings import get_settings

    return get_settings()


def create_access_token(
    user_id: uuid.UUID,
    role: str,
    *,
    expires_delta: timedelta | None = None,
) -> tuple[str, datetime]:
    """Sign and return a JWT access token and its expiry.

    Args:
        user_id: The UUID of the authenticated user.
        role: The user's role string (e.g. ``"user"``, ``"premium"``,
            ``"admin"``).
        expires_delta: Optional override for the token lifetime.  Defaults to
            ``settings.ACCESS_TOKEN_EXPIRE_MINUTES`` minutes.

    Returns:
        A tuple of ``(signed_jwt_string, expires_at_datetime)``.

    Requirements: 1.2
    """
    settings = _get_settings()
    now = datetime.now(tz=UTC)

    if expires_delta is None:
        expires_delta = timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES)

    expire = now + expires_delta
    jti = str(uuid.uuid4())

    payload: dict[str, Any] = {
        "sub": str(user_id),
        "role": role,
        "jti": jti,
        "iat": now,
        "exp": expire,
    }

    token = jwt.encode(
        payload,
        settings.SECRET_KEY,
        algorithm=settings.JWT_ALGORITHM,
    )
    return token, expire


def verify_access_token(token: str) -> TokenPayload:
    """Decode and validate a JWT access token.

    Validates:
    - Signature
    - Expiry (``exp`` claim)
    - Presence of required claims (``sub``, ``role``, ``jti``)

    Note: JTI revocation (Redis check) is performed by the middleware layer,
    not here, to keep this function pure and easily testable without I/O.

    Args:
        token: The compact JWT string from the ``Authorization: Bearer …`` header.

    Returns:
        A :class:`TokenPayload` instance with the decoded claims.

    Raises:
        :class:`~app.security.exceptions.InvalidTokenError`: When the token is
            expired, has an invalid signature, is malformed, or is missing
            required claims.

    Requirements: 1.3
    """
    settings = _get_settings()

    try:
        raw_payload: dict[str, Any] = jwt.decode(
            token,
            settings.SECRET_KEY,
            algorithms=[settings.JWT_ALGORITHM],
            options={"require": ["sub", "role", "jti", "iat", "exp"]},
        )
    except JWTError as exc:
        raise InvalidTokenError(f"JWT validation failed: {exc}") from exc

    # Manually verify required claims (PyJWT's 'require' option may
    # not enforce missing claims in all versions).
    required_claims = {"sub", "role", "jti", "iat", "exp"}
    missing = required_claims - set(raw_payload.keys())
    if missing:
        raise InvalidTokenError(
            f"JWT is missing required claims: {', '.join(sorted(missing))}"
        )

    # Convert numeric timestamps to aware datetime objects.
    try:
        iat = datetime.fromtimestamp(raw_payload["iat"], tz=UTC)
        exp = datetime.fromtimestamp(raw_payload["exp"], tz=UTC)
    except (TypeError, ValueError, OSError) as exc:
        raise InvalidTokenError(f"JWT timestamp conversion failed: {exc}") from exc

    return TokenPayload(
        sub=raw_payload["sub"],
        role=raw_payload["role"],
        jti=raw_payload["jti"],
        iat=iat,
        exp=exp,
    )


# ---------------------------------------------------------------------------
# Refresh token helpers
# ---------------------------------------------------------------------------


class RefreshTokenData:
    """Value object returned by :func:`create_refresh_token`.

    Contains the raw token string (to return to the client once) and all
    fields needed to persist a ``RefreshToken`` DB record.

    Attributes:
        raw_token:   URL-safe random token string.  Return to the client; do
                     NOT persist this value.
        token_hash:  SHA-256 hex-digest of ``raw_token``.  Persist this.
        expires_at:  UTC datetime when the token expires.
        family_id:   UUID shared by the entire rotation chain.
    """

    __slots__ = ("expires_at", "family_id", "raw_token", "token_hash")

    def __init__(
        self,
        raw_token: str,
        token_hash: str,
        expires_at: datetime,
        family_id: uuid.UUID,
    ) -> None:
        self.raw_token = raw_token
        self.token_hash = token_hash
        self.expires_at = expires_at
        self.family_id = family_id


def create_refresh_token(
    *,
    family_id: uuid.UUID | None = None,
    expires_delta: timedelta | None = None,
) -> RefreshTokenData:
    """Generate a new opaque refresh token and return its data.

    The raw token is generated with ``secrets.token_urlsafe(32)`` (256 bits of
    entropy).  Only the SHA-256 hash is intended for database persistence.

    Args:
        family_id: The rotation-chain family UUID.  Pass ``None`` to start a
            new family (first token after login); pass the existing family UUID
            when rotating a token.
        expires_delta: Optional override for token lifetime.  Defaults to
            ``settings.REFRESH_TOKEN_EXPIRE_DAYS`` days.

    Returns:
        A :class:`RefreshTokenData` instance.

    Requirements: 1.2
    """
    settings = _get_settings()
    now = datetime.now(tz=UTC)

    if expires_delta is None:
        expires_delta = timedelta(days=settings.REFRESH_TOKEN_EXPIRE_DAYS)

    raw_token = secrets.token_urlsafe(32)
    token_hash = hashlib.sha256(raw_token.encode()).hexdigest()
    expires_at = now + expires_delta
    resolved_family_id = family_id if family_id is not None else uuid.uuid4()

    return RefreshTokenData(
        raw_token=raw_token,
        token_hash=token_hash,
        expires_at=expires_at,
        family_id=resolved_family_id,
    )


def hash_token(raw_token: str) -> str:
    """Return the SHA-256 hex-digest of a raw refresh token string.

    Use this when looking up a submitted token in the database.

    Args:
        raw_token: The raw token string submitted by the client.

    Returns:
        64-character lowercase hex string (SHA-256 digest).
    """
    return hashlib.sha256(raw_token.encode()).hexdigest()
