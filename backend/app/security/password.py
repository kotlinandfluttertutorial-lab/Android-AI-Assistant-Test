# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : security
# File    : password.py
# Purpose : password — security module
#
# Architecture Layer : Security
# Pattern Used       : Password Hashing (bcrypt)
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Password hashing and verification using bcrypt.

This module is the single authoritative place for all password hashing in the
application.  No other module should call passlib or bcrypt directly — always
go through the functions defined here.

Algorithm
---------
bcrypt via ``passlib.context.CryptContext`` with a work factor (rounds) of 12,
which is the OWASP-recommended minimum.  The work factor is read from
``settings.BCRYPT_WORK_FACTOR`` so it can be increased without code changes
(see ``app.config.settings.Settings.BCRYPT_WORK_FACTOR``).

Usage example::

    from app.security.password import hash_password, verify_password

    hashed = hash_password("super-secret-password")
    is_ok  = verify_password("super-secret-password", hashed)   # True
    is_bad = verify_password("wrong-password", hashed)           # False

Security notes
--------------
- ``hash_password`` generates a fresh random salt on every call.  Two calls
  with the same plaintext produce different hashes.
- ``verify_password`` is timing-safe; passlib's constant-time comparison
  prevents timing attacks on the comparison step.
- The work factor is configurable via ``settings.BCRYPT_WORK_FACTOR`` (default
  12).  Increase it in high-security environments (14–16 recommended for future
  deployments).
- Never log plaintext passwords.  Never include them in exception messages.
- This module does NOT interact with the database.  Callers are responsible for
  persisting the returned hash.

Requirements: 9.3
"""

from __future__ import annotations

from passlib.context import CryptContext

# ---------------------------------------------------------------------------
# Lazy initialisation — the CryptContext is built once on first use so that
# tests can override BCRYPT_WORK_FACTOR via the environment before the first
# call without running into lru_cache ordering issues.
# ---------------------------------------------------------------------------

_pwd_context: CryptContext | None = None


def _get_pwd_context() -> CryptContext:
    """Return the (lazily initialised) passlib CryptContext.

    The context is cached in a module-level variable.  Settings are read once
    on the first call; subsequent calls return the cached context.
    """
    global _pwd_context

    if _pwd_context is None:
        from app.config.settings import get_settings

        work_factor: int = get_settings().BCRYPT_WORK_FACTOR
        _pwd_context = CryptContext(
            schemes=["bcrypt"],
            deprecated="auto",
            bcrypt__rounds=work_factor,
        )

    return _pwd_context


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------


def hash_password(plaintext: str) -> str:
    """Hash ``plaintext`` with bcrypt and return the hash string.

    A fresh random salt is generated for every call, so two invocations with
    the same ``plaintext`` will return different hashes — both of which will
    satisfy ``verify_password(plaintext, hash)``.

    Args:
        plaintext: The raw password string supplied by the user.  Must not be
            empty.

    Returns:
        A bcrypt hash string (e.g. ``"$2b$12$..."``), safe to store in the
        ``users.password_hash`` column.

    Raises:
        ValueError: If ``plaintext`` is an empty string.  Accepting empty
            passwords would allow silent login bypass if validation is skipped
            upstream.
    """
    if not plaintext:
        raise ValueError("plaintext password must not be empty")

    return _get_pwd_context().hash(plaintext)


def verify_password(plaintext: str, hashed: str) -> bool:
    """Verify ``plaintext`` against a stored bcrypt hash.

    The comparison is performed in constant time to prevent timing attacks.

    Args:
        plaintext: The raw password string submitted by the user.
        hashed:    The bcrypt hash string retrieved from the database
                   (``users.password_hash``).

    Returns:
        ``True`` if ``plaintext`` matches the hash, ``False`` otherwise.

    Notes:
        - A return value of ``False`` must never be treated as an exceptional
          condition — it simply means authentication failed.
        - This function does not raise on hash format errors; it returns
          ``False`` so that callers always see a boolean result.
    """
    if not plaintext or not hashed:
        return False

    try:
        return _get_pwd_context().verify(plaintext, hashed)
    except Exception:  # noqa: BLE001 — passlib may raise on malformed hashes
        return False
