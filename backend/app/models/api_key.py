# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : models
# File    : api_key.py
# Purpose : api_key — models module
#
# Architecture Layer : ORM Model
# Pattern Used       : Python Module
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""ORM model for the ``api_keys`` table, with AES-256 encryption helpers.

Users can store their own LLM provider API keys so the backend can forward
requests on their behalf.  Keys are never stored in plaintext.

Encryption scheme
-----------------
``encrypted_key`` is stored as raw bytes produced by AES-256-GCM (via
``cryptography.hazmat``).  The 16-byte IV and the 16-byte authentication tag
are prepended to the ciphertext so that the ``decrypt_key`` helper can
self-contained round-trip without out-of-band metadata.

Layout of stored bytes::

    [ IV (16 bytes) | TAG (16 bytes) | CIPHERTEXT (n bytes) ]

The encryption key is read from ``settings.AES_ENCRYPTION_KEY`` which must be
a Base64-encoded 32-byte value (256-bit key).  Generate one with::

    python -c "import base64, os; print(base64.b64encode(os.urandom(32)).decode())"

Security notes
--------------
- AES-GCM provides authenticated encryption; tampering with the ciphertext is
  detected at decryption time via the authentication tag.
- The encryption key itself is loaded from environment variables / secrets
  manager and is never persisted to the database.
- Key rotation requires decrypting all rows with the old key and re-encrypting
  with the new key.

Requirements: 9.3, 9.10
"""

from __future__ import annotations

import base64
import os
import uuid

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from sqlalchemy import ForeignKey, LargeBinary, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, TimestampMixin, uuid_pk

# GCM nonce size (12 bytes is the NIST recommended default for AESGCM)
_NONCE_SIZE = 12


def _load_aes_key() -> bytes:
    """Decode the AES-256 encryption key from application settings.

    Imported lazily to avoid circular imports at module load time and to
    allow tests to override the environment variable before the first call.
    """
    from app.config.settings import get_settings

    raw = get_settings().AES_ENCRYPTION_KEY
    if not raw:
        raise ValueError(
            "AES_ENCRYPTION_KEY is not set.  "
            "Generate one with: "
            'python -c "import base64, os; print(base64.b64encode(os.urandom(32)).decode())"'
        )
    key = base64.b64decode(raw)
    if len(key) != 32:
        raise ValueError(
            f"AES_ENCRYPTION_KEY must decode to exactly 32 bytes (AES-256); "
            f"got {len(key)} bytes."
        )
    return key


def encrypt_api_key(plaintext: str) -> bytes:
    """Encrypt an API key string and return the opaque ciphertext blob.

    The returned bytes are safe to store in the ``encrypted_key`` column.
    The format is: ``[ nonce (12 bytes) | ciphertext+tag (n+16 bytes) ]``.

    Args:
        plaintext: The raw API key string (e.g. ``"sk-..."``).

    Returns:
        Encrypted bytes suitable for storage in the database.
    """
    key = _load_aes_key()
    aesgcm = AESGCM(key)
    nonce = os.urandom(_NONCE_SIZE)
    # AESGCM.encrypt returns ciphertext + 16-byte tag concatenated
    ciphertext_with_tag = aesgcm.encrypt(nonce, plaintext.encode("utf-8"), None)
    return nonce + ciphertext_with_tag


def decrypt_api_key(blob: bytes) -> str:
    """Decrypt a stored API key blob and return the plaintext string.

    Args:
        blob: The raw bytes retrieved from the ``encrypted_key`` column.

    Returns:
        The decrypted API key string.

    Raises:
        cryptography.exceptions.InvalidTag: If the blob has been tampered with
            or the wrong key is used.
        ValueError: If the blob is too short to contain a valid nonce.
    """
    if len(blob) <= _NONCE_SIZE:
        raise ValueError(
            f"Encrypted blob is too short ({len(blob)} bytes); "
            f"expected at least {_NONCE_SIZE + 1} bytes."
        )
    key = _load_aes_key()
    aesgcm = AESGCM(key)
    nonce = blob[:_NONCE_SIZE]
    ciphertext_with_tag = blob[_NONCE_SIZE:]
    plaintext_bytes = aesgcm.decrypt(nonce, ciphertext_with_tag, None)
    return plaintext_bytes.decode("utf-8")


class APIKey(Base, TimestampMixin):
    """SQLAlchemy ORM model representing an encrypted LLM provider API key.

    The ``encrypted_key`` column stores AES-256-GCM ciphertext.  Use the
    module-level ``encrypt_api_key`` / ``decrypt_api_key`` helpers to convert
    between plaintext strings and the stored bytes.
    """

    __tablename__ = "api_keys"

    id: Mapped[uuid.UUID] = uuid_pk()
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    provider: Mapped[str] = mapped_column(
        String(64),
        nullable=False,
        comment="LLM provider identifier, e.g. 'openai', 'anthropic', 'gemini'",
    )
    encrypted_key: Mapped[bytes] = mapped_column(
        LargeBinary,
        nullable=False,
        comment=(
            "AES-256-GCM encrypted API key. "
            "Format: [nonce (12 B) | ciphertext+tag (n+16 B)]. "
            "Use encrypt_api_key() / decrypt_api_key() helpers."
        ),
    )

    # ------------------------------------------------------------------
    # Relationships
    # ------------------------------------------------------------------
    user: Mapped[User] = relationship("User", back_populates="api_keys")  # noqa: F821

    # ------------------------------------------------------------------
    # Convenience properties
    # ------------------------------------------------------------------
    @property
    def plaintext_key(self) -> str:
        """Decrypt and return the raw API key string.

        This property performs decryption on every access; callers should
        cache the result rather than accessing this property repeatedly in a
        hot loop.
        """
        return decrypt_api_key(self.encrypted_key)

    @plaintext_key.setter
    def plaintext_key(self, value: str) -> None:
        """Encrypt ``value`` and store it in ``encrypted_key``."""
        self.encrypted_key = encrypt_api_key(value)

    def __repr__(self) -> str:
        return (
            f"<APIKey id={self.id!s} user_id={self.user_id!s} "
            f"provider={self.provider!r}>"
        )
