# security package — JWT, RBAC, prompt injection detection, encryption

from app.security.audit import AuditEventType, AuditService
from app.security.dependencies import get_current_user
from app.security.encryption import decrypt_api_key, encrypt_api_key
from app.security.exceptions import (
    AccountLockedError,
    AuthError,
    InvalidTokenError,
    SecurityViolationError,
    TokenFamilyRevokedError,
)
from app.security.jwt_handler import (
    RefreshTokenData,
    TokenPayload,
    create_access_token,
    create_refresh_token,
    hash_token,
    verify_access_token,
)
from app.security.lockout import AccountLockoutService
from app.security.password import hash_password, verify_password
from app.security.rbac import require_admin, require_premium_or_admin, require_roles

__all__ = [
    # Exceptions
    "AuthError",
    "InvalidTokenError",
    "SecurityViolationError",
    "TokenFamilyRevokedError",
    "AccountLockedError",
    # JWT
    "TokenPayload",
    "RefreshTokenData",
    "create_access_token",
    "create_refresh_token",
    "verify_access_token",
    "hash_token",
    # Password
    "hash_password",
    "verify_password",
    # Encryption
    "encrypt_api_key",
    "decrypt_api_key",
    # RBAC
    "require_roles",
    "require_admin",
    "require_premium_or_admin",
    # Auth dependency
    "get_current_user",
    # Lockout
    "AccountLockoutService",
    # Audit
    "AuditService",
    "AuditEventType",
]
