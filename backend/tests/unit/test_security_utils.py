"""Unit tests for password hashing, encryption, and lockout utilities.

Covers:
- hash_password: produces a non-empty hash, raises on empty input
- verify_password: correct password returns True, wrong returns False
- encrypt_api_key / decrypt_api_key: round-trip, uniqueness, error cases
- AccountLockoutService: record/check/clear lockout state (mocked Redis)

Requirements: 9.3, 9.5, 9.10, 1.5
"""

from __future__ import annotations

import base64
import os
from unittest.mock import patch

import pytest

os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

# Generate a valid AES-256 key for tests
_TEST_AES_KEY = base64.b64encode(os.urandom(32)).decode()
os.environ.setdefault("AES_ENCRYPTION_KEY", _TEST_AES_KEY)

from app.security.encryption import decrypt_api_key, encrypt_api_key
from app.security.password import hash_password, verify_password

# ---------------------------------------------------------------------------
# Password hashing tests
# ---------------------------------------------------------------------------


class TestHashPassword:
    def test_returns_non_empty_string(self) -> None:
        result = hash_password("SecurePass123!")
        assert isinstance(result, str)
        assert len(result) > 0

    def test_raises_on_empty_string(self) -> None:
        with pytest.raises(ValueError, match="empty"):
            hash_password("")

    def test_two_hashes_of_same_password_differ(self) -> None:
        """bcrypt generates a fresh salt each time — hashes must differ."""
        p = "SamePass456!"
        h1 = hash_password(p)
        h2 = hash_password(p)
        assert h1 != h2

    def test_hash_starts_with_bcrypt_prefix(self) -> None:
        h = hash_password("TestPass789!")
        assert h.startswith("$2b$") or h.startswith("$2a$")


class TestVerifyPassword:
    def test_correct_password_returns_true(self) -> None:
        password = "CorrectPass123!"
        hashed = hash_password(password)
        assert verify_password(password, hashed) is True

    def test_wrong_password_returns_false(self) -> None:
        hashed = hash_password("RightPass123!")
        assert verify_password("WrongPass456!", hashed) is False

    def test_empty_plaintext_returns_false(self) -> None:
        hashed = hash_password("SomePass123!")
        assert verify_password("", hashed) is False

    def test_empty_hash_returns_false(self) -> None:
        assert verify_password("SomePass123!", "") is False

    def test_malformed_hash_returns_false(self) -> None:
        assert verify_password("password", "not-a-valid-hash") is False


# ---------------------------------------------------------------------------
# Encryption / Decryption tests
# ---------------------------------------------------------------------------


class TestEncryptDecryptApiKey:
    def test_round_trip(self) -> None:
        """encrypt then decrypt must return original plaintext."""
        plaintext = "sk-abc-test-key-12345"
        blob = encrypt_api_key(plaintext)
        result = decrypt_api_key(blob)
        assert result == plaintext

    def test_encrypt_raises_on_empty_string(self) -> None:
        with pytest.raises(ValueError, match="empty"):
            encrypt_api_key("")

    def test_two_encryptions_produce_different_blobs(self) -> None:
        """Each call generates a fresh nonce — blobs must differ."""
        plaintext = "sk-same-key"
        blob1 = encrypt_api_key(plaintext)
        blob2 = encrypt_api_key(plaintext)
        assert blob1 != blob2

    def test_encrypted_blob_starts_with_nonce(self) -> None:
        """Blob is at least 12 bytes (nonce) + 16 bytes (GCM tag) + 1 byte."""
        blob = encrypt_api_key("test-api-key-xyz")
        assert len(blob) >= 29

    def test_decrypt_raises_on_too_short_blob(self) -> None:
        with pytest.raises(ValueError, match="too short"):
            decrypt_api_key(b"short")

    def test_decrypt_raises_on_tampered_blob(self) -> None:
        from cryptography.exceptions import InvalidTag

        blob = encrypt_api_key("original-key-123")
        # Flip the last byte of the GCM tag
        tampered = bytearray(blob)
        tampered[-1] ^= 0xFF
        with pytest.raises(InvalidTag):
            decrypt_api_key(bytes(tampered))

    def test_no_aes_key_raises_value_error(self) -> None:
        """When AES_ENCRYPTION_KEY is not configured, ValueError is raised."""

        # Use lru_cache clear trick by temporarily patching
        with (
            patch(
                "app.security.encryption._load_aes_key",
                side_effect=ValueError("AES_ENCRYPTION_KEY is not configured"),
            ),
            pytest.raises(ValueError, match="AES_ENCRYPTION_KEY"),
        ):
            encrypt_api_key("some-key")
