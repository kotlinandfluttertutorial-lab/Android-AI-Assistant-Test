# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/auth
# File    : router.py
# Purpose : FastAPI router defining all HTTP endpoints for the auth domain
#
# Architecture Layer : API Router
# Pattern Used       : FastAPI Router
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Auth router — /auth/* endpoints.

Implements user registration, login, logout, token refresh, and Google OAuth2
sign-in.

Requirements: 1.1, 1.6, 1.10
"""

from __future__ import annotations

import asyncio
import logging
import uuid

from fastapi import APIRouter, Depends, HTTPException, Request, status
from redis.asyncio import Redis
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.database.redis import get_redis
from app.repositories.user_repository import UserRepository
from app.schemas.auth import (
    GoogleAuthRequest,
    GoogleAuthResponse,
    LoginRequest,
    LoginResponse,
    LogoutResponse,
    RefreshRequest,
    RefreshResponse,
    RegisterRequest,
    RegisterResponse,
)
from app.security.audit import AuditService
from app.security.dependencies import get_current_user
from app.security.exceptions import (
    AccountLockedError,
    InvalidTokenError,
    TokenFamilyRevokedError,
)
from app.security.jwt_handler import TokenPayload
from app.security.lockout import AccountLockoutService
from app.security.password import hash_password, verify_password
from app.services.auth_service import issue_tokens_for_user, logout_user, refresh_tokens

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/auth", tags=["auth"])


# ---------------------------------------------------------------------------
# POST /auth/register
# ---------------------------------------------------------------------------


@router.post(
    "/register",
    response_model=RegisterResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Register a new user account",
)
async def register(
    body: RegisterRequest,
    request: Request,
    db: AsyncSession = Depends(get_db),
) -> RegisterResponse:
    """Create a new user account with email/password credentials.

    - Validates email format and password ≥12 chars (enforced by the schema).
    - Checks for duplicate email (HTTP 409).
    - Hashes the password with bcrypt (work factor from settings).
    - Issues a JWT + refresh token pair.
    - Writes an audit log entry.

    Requirements: 1.1
    """
    user_repo = UserRepository(db)
    audit = AuditService(db)
    ip = request.client.host if request.client else ""
    ua = request.headers.get("user-agent", "")

    # Normalise email (schema already strips whitespace; belt-and-suspenders)
    email = body.email.lower().strip()

    # Check for existing account
    existing = await user_repo.get_by_email(email)
    if existing is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="An account with this email address already exists.",
        )

    # Hash password and create user
    password_hash = hash_password(body.password)
    user = await user_repo.create(
        email=email,
        password_hash=password_hash,
        display_name=body.display_name,
    )

    # Issue tokens
    access_token, access_exp, refresh_token, refresh_exp = await issue_tokens_for_user(
        db, user.id, user.role.value
    )

    # Audit log
    await audit.log_login(
        user_id=user.id,
        ip_address=ip,
        user_agent=ua,
        provider="registration",
    )

    return RegisterResponse(
        user_id=user.id,
        email=user.email,
        access_token=access_token,
        refresh_token=refresh_token,
        access_token_expires_at=int(access_exp.timestamp() * 1000),
        refresh_token_expires_at=int(refresh_exp.timestamp() * 1000),
    )


# ---------------------------------------------------------------------------
# POST /auth/login
# ---------------------------------------------------------------------------


@router.post(
    "/login",
    response_model=LoginResponse,
    status_code=status.HTTP_200_OK,
    summary="Authenticate with email and password",
)
async def login(
    body: LoginRequest,
    request: Request,
    db: AsyncSession = Depends(get_db),
    redis: Redis = Depends(get_redis),
) -> LoginResponse:
    """Authenticate a user with email and password.

    - Returns HTTP 401 for unknown email (without revealing user existence).
    - Returns HTTP 429 with ``Retry-After`` header when the account is locked.
    - Returns HTTP 401 for wrong password (records failed attempt).
    - On success clears lockout state, issues JWT + refresh token, writes audit log.

    Requirements: 1.2, 1.5
    """
    user_repo = UserRepository(db)
    audit = AuditService(db)
    lockout = AccountLockoutService(redis)
    ip = request.client.host if request.client else ""
    ua = request.headers.get("user-agent", "")
    email = body.email.lower().strip()

    _generic_401 = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Invalid email or password.",
        headers={"WWW-Authenticate": "Bearer"},
    )

    # Look up user — return generic 401 if not found to avoid user enumeration
    user = await user_repo.get_by_email(email)
    if user is None:
        await audit.log_failed_login(
            ip_address=ip,
            user_agent=ua,
            email=email,
            reason="user_not_found",
        )
        raise _generic_401

    # Check lockout BEFORE verifying password (avoids timing-based enumeration)
    try:
        await lockout.check_locked(email)
    except AccountLockedError as exc:
        await audit.log_failed_login(
            ip_address=ip,
            user_agent=ua,
            email=email,
            reason="account_locked",
            user_id=user.id,
        )
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=str(exc),
            headers={"Retry-After": str(exc.retry_after_seconds)},
        ) from exc

    # Verify password
    if not verify_password(body.password, user.password_hash):
        await lockout.record_failed_attempt(email, display_name=user.display_name)
        await audit.log_failed_login(
            ip_address=ip,
            user_agent=ua,
            email=email,
            reason="wrong_password",
            user_id=user.id,
        )
        raise _generic_401

    # Check account is active
    if not user.is_active:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Account is disabled.",
            headers={"WWW-Authenticate": "Bearer"},
        )

    # Clear failed attempts on success
    await lockout.clear_on_success(email)

    # Issue tokens
    access_token, access_exp, refresh_token, refresh_exp = await issue_tokens_for_user(
        db, user.id, user.role.value
    )

    # Audit log
    await audit.log_login(
        user_id=user.id,
        ip_address=ip,
        user_agent=ua,
        provider="password",
    )

    return LoginResponse(
        user_id=user.id,
        email=user.email,
        role=user.role.value,
        access_token=access_token,
        refresh_token=refresh_token,
        access_token_expires_at=int(access_exp.timestamp() * 1000),
        refresh_token_expires_at=int(refresh_exp.timestamp() * 1000),
    )


# ---------------------------------------------------------------------------
# POST /auth/refresh
# ---------------------------------------------------------------------------


@router.post(
    "/refresh",
    response_model=RefreshResponse,
    status_code=status.HTTP_200_OK,
    summary="Rotate a refresh token and get a new access token",
)
async def refresh(
    body: RefreshRequest,
    request: Request,
    db: AsyncSession = Depends(get_db),
) -> RefreshResponse:
    """Rotate a refresh token and issue a new JWT + refresh token pair.

    - HTTP 401 on invalid, expired, or revoked token.
    - HTTP 401 on replay detection (vague message to avoid leaking details).
    - Writes audit log on success.

    Requirements: 1.3, 1.4
    """
    audit = AuditService(db)
    ip = request.client.host if request.client else ""
    ua = request.headers.get("user-agent", "")

    try:
        (
            new_access,
            access_exp,
            new_refresh,
            refresh_exp,
            role,
            user_id,
        ) = await refresh_tokens(db, body.refresh_token)
    except TokenFamilyRevokedError:
        # Deliberately vague — don't leak replay detection details
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token is no longer valid.",
            headers={"WWW-Authenticate": "Bearer"},
        )
    except InvalidTokenError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=str(exc),
            headers={"WWW-Authenticate": "Bearer"},
        ) from exc

    # Audit log
    await audit.log_token_refresh(
        user_id=user_id,
        ip_address=ip,
        user_agent=ua,
    )

    return RefreshResponse(
        access_token=new_access,
        refresh_token=new_refresh,
        access_token_expires_at=int(access_exp.timestamp() * 1000),
        refresh_token_expires_at=int(refresh_exp.timestamp() * 1000),
    )


# ---------------------------------------------------------------------------
# POST /auth/logout
# ---------------------------------------------------------------------------


@router.post(
    "/logout",
    response_model=LogoutResponse,
    status_code=status.HTTP_200_OK,
    summary="Revoke all active refresh tokens for the authenticated user",
)
async def logout(
    request: Request,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> LogoutResponse:
    """Revoke all active refresh tokens for the currently authenticated user.

    Requires a valid JWT in the ``Authorization: Bearer`` header.

    Requirements: 1.10
    """
    audit = AuditService(db)
    ip = request.client.host if request.client else ""
    ua = request.headers.get("user-agent", "")
    user_id = uuid.UUID(current_user.sub)

    tokens_revoked = await logout_user(db, user_id)

    await audit.log_logout(
        user_id=user_id,
        ip_address=ip,
        user_agent=ua,
        tokens_revoked=tokens_revoked,
    )

    return LogoutResponse(tokens_revoked=tokens_revoked)


# ---------------------------------------------------------------------------
# POST /auth/google
# ---------------------------------------------------------------------------


@router.post(
    "/google",
    response_model=GoogleAuthResponse,
    summary="Sign in or register with a Google ID token",
)
async def google_auth(
    body: GoogleAuthRequest,
    request: Request,
    db: AsyncSession = Depends(get_db),
    redis: Redis = Depends(get_redis),
) -> GoogleAuthResponse:
    """Authenticate (or register) a user via Google OAuth2 ID token."""
    try:
        return await _google_auth_impl(body, request, db, redis)
    except HTTPException:
        raise
    except Exception as exc:
        logger.exception("Unhandled error in google_auth")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Internal server error during Google authentication.",
        ) from exc


async def _google_auth_impl(
    body: GoogleAuthRequest,
    request: Request,
    db: AsyncSession,
    redis: Redis,
) -> GoogleAuthResponse:
    """Implementation of Google OAuth2 authentication workflow."""
    from app.config.settings import get_settings

    user_repo = UserRepository(db)
    audit = AuditService(db)
    ip = request.client.host if request.client else ""
    ua = request.headers.get("user-agent", "")
    settings = get_settings()

    # ------------------------------------------------------------------
    # Verify the Google ID token in a thread pool (sync library call)
    # ------------------------------------------------------------------
    try:
        import google.auth.transport.requests as g_requests
        import google.oauth2.id_token as g_id_token

        loop = asyncio.get_running_loop()
        http_request = g_requests.Request()

        def _verify() -> dict:
            # Accept both the Web client ID and the Android client ID as valid
            # audiences. Google Credential Manager on Android sets aud to the
            # Web client ID, but we also allow the Android client ID in case
            # the token was obtained via a different OAuth flow.
            # If GOOGLE_CLIENT_ID is empty we skip audience verification —
            # the token is still signature-verified against Google's public keys.
            audience = settings.GOOGLE_CLIENT_ID or None
            id_info = g_id_token.verify_oauth2_token(
                body.id_token,
                http_request,
                audience,
            )
            # Manually validate audience if we have a client ID configured
            if settings.GOOGLE_CLIENT_ID:
                valid_audiences = {
                    settings.GOOGLE_CLIENT_ID,
                    settings.GOOGLE_ANDROID_CLIENT_ID,
                }
                valid_audiences.discard("")
                token_aud = id_info.get("aud", "")
                if token_aud not in valid_audiences:
                    raise ValueError(
                        f"Token audience '{token_aud}' is not in the allowed set."
                    )
            return id_info

        id_info: dict = await loop.run_in_executor(None, _verify)
    except ValueError as exc:
        logger.warning("Google ID token verification failed: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid Google ID token.",
        ) from exc
    except Exception as exc:
        logger.exception("Unexpected error verifying Google ID token")
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Could not verify Google ID token.",
        ) from exc

    google_sub: str = str(id_info.get("sub") or "")
    email: str = str(id_info.get("email") or "").lower().strip()
    display_name: str = str(id_info.get("name") or "")
    avatar_url: str | None = id_info.get("picture") or None

    if not google_sub or not email:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Google token is missing required claims.",
        )

    is_new_user = False

    # 1. Look up by google_id
    user = await user_repo.get_by_google_id(google_sub)

    if user is None:
        # 2. Look up by email — link google_id to existing account
        user = await user_repo.get_by_email(email)
        if user is not None:
            user = await user_repo.update_google_id(user.id, google_sub)

    if user is None:
        # 3. Create a brand-new user
        user = await user_repo.create_google_user(
            email=email,
            google_id=google_sub,
            display_name=display_name,
            avatar_url=avatar_url,
        )
        is_new_user = True

    if not user.is_active:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Account is disabled.",
        )

    # Issue tokens
    access_token, access_exp, refresh_token, refresh_exp = await issue_tokens_for_user(
        db, user.id, user.role.value
    )

    # Audit log
    await audit.log_login(
        user_id=user.id,
        ip_address=ip,
        user_agent=ua,
        provider="google",
    )

    return GoogleAuthResponse(
        user_id=user.id,
        email=user.email,
        display_name=user.display_name,
        role=user.role.value,
        access_token=access_token,
        refresh_token=refresh_token,
        access_token_expires_at=int(access_exp.timestamp() * 1000),
        refresh_token_expires_at=int(refresh_exp.timestamp() * 1000),
        is_new_user=is_new_user,
    )
