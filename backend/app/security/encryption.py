# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : security
# File    : encryption.py
# Purpose : encryption — security module
#
# Architecture Layer : Security
# Pattern Used       : AES-256 Encryption
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""AES-256-GCM encryption helpers for sensitive data at rest.

This module is the **single authoritative place** for symmetric encryption in
the application.  No other module should call ``cryptography.hazmat`` directly
for AES operations — always go through the functions defined here.

Encryption scheme
-----------------
AES-256-GCM (Authenticated Encryption with Associated Data) via
``cryptography.hazmat.primitives.ciphers.aead.AESGCM``.

- **Key**: 32 bytes (256 bits), Base64-encoded in ``settings.AES_ENCRYPTION_KEY``.
- **Nonce (IV)**: 12 bytes, generated fresh with ``os.urandom`` on every
  encryption call (NIST recommended size for GCM).
- **Authentication tag**: 16 bytes, appended to ciphertext by ``AESGCM.encrypt``.

Stored blob layout::

    [ nonce (12 bytes) | ciphertext + tag (n + 16 bytes) ]

AES-GCM provides both confidentiality and integrity — any tampering with the
ciphertext is detected via the authentication tag at decryption time.

Usage example::

    from app.security.encryption import encrypt_api_key, decrypt_api_key

    blob  = encrypt_api_key("sk-abc123")      # returns bytes
    plain = decrypt_api_key(blob)             # returns "sk-abc123"

Security notes
--------------
- The encryption key is loaded from ``settings.AES_ENCRYPTION_KEY`` and is
  never persisted to the database.
- Key rotation requires decrypting all existing rows with the old key and
  re-encrypting them with the new key.
- Never return plaintext key values in API responses (Requirement 9.10).

Requirements: 9.3, 9.10
"""

from __future__ import annotations

import base64
import os

from cryptography.exceptions import InvalidTag  # noqa: F401 — re-exported for callers
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

# NIST-recommended nonce size for AES-GCM (12 bytes = 96 bits)
_NONCE_SIZE: int = 12
# Minimum blob length: nonce (12) + GCM tag (16) + at least 1 byte of ciphertext
_MIN_BLOB_SIZE: int = _NONCE_SIZE + 16 + 1


def _load_aes_key() -> bytes:
    """Decode the AES-256 encryption key from application settings.

    Imported lazily to avoid circular imports at module load time and to
    allow tests to override the environment variable before the first call.

    Returns:
        32-byte key suitable for ``AESGCM``.

    Raises:
        ValueError: If ``AES_ENCRYPTION_KEY`` is not set or does not decode to
            exactly 32 bytes.
    """
    from app.config.settings import get_settings

    raw = get_settings().AES_ENCRYPTION_KEY
    if not raw:
        raise ValueError(
            "AES_ENCRYPTION_KEY is not configured.  "
            "Generate a key with: "
            'python -c "import base64, os; print(base64.b64encode(os.urandom(32)).decode())"'
        )
    key = base64.b64decode(raw)
    if len(key) != 32:
        raise ValueError(
            f"AES_ENCRYPTION_KEY must decode to exactly 32 bytes (AES-256); " f"got {len(key)} bytes."
        )
    return key


def encrypt_api_key(plaintext: str) -> bytes:
    """Encrypt an API key string with AES-256-GCM and return the ciphertext blob.

    A fresh 12-byte random nonce is generated on every call so that encrypting
    the same plaintext twice yields different ciphertext blobs.

    The returned bytes have the layout::

        [ nonce (12 bytes) | ciphertext+tag (n+16 bytes) ]

    Args:
        plaintext: The raw API key or secret string to encrypt.  Must not be
            empty.

    Returns:
        Encrypted byte string safe to store in the database.

    Raises:
        ValueError: If ``plaintext`` is empty or ``AES_ENCRYPTION_KEY`` is
            invalid.

    Requirements: 9.3, 9.10
    """
    if not plaintext:
        raise ValueError("plaintext must not be empty")

    key = _load_aes_key()
    aesgcm = AESGCM(key)
    nonce = os.urandom(_NONCE_SIZE)
    # AESGCM.encrypt returns ciphertext + 16-byte GCM authentication tag
    ciphertext_with_tag = aesgcm.encrypt(nonce, plaintext.encode("utf-8"), None)
    return nonce + ciphertext_with_tag


def decrypt_api_key(blob: bytes) -> str:
    """Decrypt a stored AES-256-GCM blob and return the plaintext API key.

    Args:
        blob: The raw bytes retrieved from the database (as produced by
            :func:`encrypt_api_key`).

    Returns:
        The decrypted plaintext string.

    Raises:
        ValueError: If ``blob`` is too short to be a valid encrypted payload.
        :class:`cryptography.exceptions.InvalidTag`: If the blob has been
            tampered with or the wrong encryption key is used.

    Requirements: 9.3, 9.10
    """
    if len(blob) < _MIN_BLOB_SIZE:
        raise ValueError(
            f"Encrypted blob is too short ({len(blob)} bytes); "
            f"expected at least {_MIN_BLOB_SIZE} bytes "
            f"(nonce={_NONCE_SIZE} + tag=16 + at least 1 byte of ciphertext)."
        )

    key = _load_aes_key()
    aesgcm = AESGCM(key)
    nonce = blob[:_NONCE_SIZE]
    ciphertext_with_tag = blob[_NONCE_SIZE:]
    plaintext_bytes = aesgcm.decrypt(nonce, ciphertext_with_tag, None)
    return plaintext_bytes.decode("utf-8")
