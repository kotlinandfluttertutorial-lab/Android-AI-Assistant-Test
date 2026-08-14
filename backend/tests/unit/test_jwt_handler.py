"""Unit tests for app.security.jwt_handler.

Covers:
- create_access_token: correct sub, jti, exp (~15 min), decodable
- verify_access_token: happy path, expired token, tampered signature
- create_refresh_token: hash differs from raw, expiry ~30 days, family_id preserved
- hash_token: deterministic SHA-256 hex output

Requirements: 1.2, 1.3, 1.4
"""

from __future__ import annotations

import hashlib
import os
import uuid
from datetime import datetime, timedelta, timezone

import pytest
import jwt

# ---------------------------------------------------------------------------
# Provide required env vars before any app import so pydantic-settings doesn't
# complain about missing required fields in the test process.
# ---------------------------------------------------------------------------
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

from app.security.exceptions import InvalidTokenError
from app.security.jwt_handler import (
    create_access_token,
    create_refresh_token,
    hash_token,
    verify_access_token,
)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

SAMPLE_USER_ID = uuid.UUID("12345678-1234-5678-1234-567812345678")
SAMPLE_ROLE = "user"


# ---------------------------------------------------------------------------
# create_access_token
# ---------------------------------------------------------------------------


class TestCreateAccessToken:
    def test_returns_tuple(self) -> None:
        token, exp = create_access_token(SAMPLE_USER_ID, SAMPLE_ROLE)
        assert isinstance(token, str)
        assert isinstance(exp, datetime)
        assert len(token) > 0

    def test_sub_matches_user_id(self) -> None:
        token, _ = create_access_token(SAMPLE_USER_ID, SAMPLE_ROLE)
        # Decode without verification to inspect claims
        decoded = jwt.decode(
            token,
            os.environ["SECRET_KEY"],
            algorithms=["HS256"],
        )
        assert decoded["sub"] == str(SAMPLE_USER_ID)

    def test_role_claim_present(self) -> None:
        token, _ = create_access_token(SAMPLE_USER_ID, "admin")
        decoded = jwt.decode(token, os.environ["SECRET_KEY"], algorithms=["HS256"])
        assert decoded["role"] == "admin"

    def test_jti_is_uuid(self) -> None:
        token, _ = create_access_token(SAMPLE_USER_ID, SAMPLE_ROLE)
        decoded = jwt.decode(token, os.environ["SECRET_KEY"], algorithms=["HS256"])
        # Should be parseable as UUID
        jti = uuid.UUID(decoded["jti"])
        assert isinstance(jti, uuid.UUID)

    def test_two_tokens_have_different_jti(self) -> None:
        t1, _ = create_access_token(SAMPLE_USER_ID, SAMPLE_ROLE)
        t2, _ = create_access_token(SAMPLE_USER_ID, SAMPLE_ROLE)
        d1 = jwt.decode(t1, os.environ["SECRET_KEY"], algorithms=["HS256"])
        d2 = jwt.decode(t2, os.environ["SECRET_KEY"], algorithms=["HS256"])
        assert d1["jti"] != d2["jti"]

    def test_expiry_is_approximately_15_minutes(self) -> None:
        before = datetime.now(tz=timezone.utc)
        token, exp = create_access_token(SAMPLE_USER_ID, SAMPLE_ROLE)
        after = datetime.now(tz=timezone.utc)

        decoded = jwt.decode(token, os.environ["SECRET_KEY"], algorithms=["HS256"])
        decoded_exp = datetime.fromtimestamp(decoded["exp"], tz=timezone.utc)
        iat = datetime.fromtimestamp(decoded["iat"], tz=timezone.utc)

        lifetime_seconds = (decoded_exp - iat).total_seconds()
        # Should be 15 minutes ± 5 seconds
        assert 14 * 60 + 55 <= lifetime_seconds <= 15 * 60 + 5

        # Also verify exp is in the future
        assert decoded_exp > before
        # JWT timestamps lose microsecond precision
        assert exp.replace(microsecond=0) == decoded_exp

    def test_custom_expiry_override(self) -> None:
        delta = timedelta(minutes=5)
        token, exp = create_access_token(
            SAMPLE_USER_ID, SAMPLE_ROLE, expires_delta=delta
        )
        decoded = jwt.decode(token, os.environ["SECRET_KEY"], algorithms=["HS256"])
        decoded_exp = datetime.fromtimestamp(decoded["exp"], tz=timezone.utc)
        iat = datetime.fromtimestamp(decoded["iat"], tz=timezone.utc)
        lifetime = (decoded_exp - iat).total_seconds()
        assert 4 * 60 + 55 <= lifetime <= 5 * 60 + 5
        # JWT timestamps lose microsecond precision
        assert exp.replace(microsecond=0) == decoded_exp


# ---------------------------------------------------------------------------
# verify_access_token
# ---------------------------------------------------------------------------


class TestVerifyAccessToken:
    def test_happy_path(self) -> None:
        token, _ = create_access_token(SAMPLE_USER_ID, SAMPLE_ROLE)
        payload = verify_access_token(token)

        assert payload.sub == str(SAMPLE_USER_ID)
        assert payload.role == SAMPLE_ROLE
        assert isinstance(payload.jti, str)
        assert isinstance(payload.iat, datetime)
        assert isinstance(payload.exp, datetime)

    def test_raises_on_expired_token(self) -> None:
        # Create a token that is already expired
        token, _ = create_access_token(
            SAMPLE_USER_ID,
            SAMPLE_ROLE,
            expires_delta=timedelta(seconds=-1),
        )
        with pytest.raises(InvalidTokenError):
            verify_access_token(token)

    def test_raises_on_tampered_signature(self) -> None:
        token, _ = create_access_token(SAMPLE_USER_ID, SAMPLE_ROLE)
        # Corrupt the last few bytes of the signature
        parts = token.split(".")
        tampered = parts[0] + "." + parts[1] + "." + parts[2][:-4] + "XXXX"
        with pytest.raises(InvalidTokenError):
            verify_access_token(tampered)

    def test_raises_on_wrong_secret(self) -> None:
        # Build a token signed with a different secret
        now = datetime.now(tz=timezone.utc)
        payload = {
            "sub": str(SAMPLE_USER_ID),
            "role": SAMPLE_ROLE,
            "jti": str(uuid.uuid4()),
            "iat": now,
            "exp": now + timedelta(minutes=15),
        }
        wrong_token = jwt.encode(payload, "wrong-secret-key", algorithm="HS256")
        with pytest.raises(InvalidTokenError):
            verify_access_token(wrong_token)

    def test_raises_on_malformed_token(self) -> None:
        with pytest.raises(InvalidTokenError):
            verify_access_token("not.a.jwt")

    def test_raises_on_missing_required_claim(self) -> None:
        # Build a token without 'jti'
        now = datetime.now(tz=timezone.utc)
        payload = {
            "sub": str(SAMPLE_USER_ID),
            "role": SAMPLE_ROLE,
            "iat": now,
            "exp": now + timedelta(minutes=15),
        }
        token = jwt.encode(payload, os.environ["SECRET_KEY"], algorithm="HS256")
        with pytest.raises(InvalidTokenError):
            verify_access_token(token)


# ---------------------------------------------------------------------------
# create_refresh_token
# ---------------------------------------------------------------------------


class TestCreateRefreshToken:
    def test_returns_refresh_token_data(self) -> None:
        data = create_refresh_token()
        assert isinstance(data.raw_token, str)
        assert isinstance(data.token_hash, str)
        assert isinstance(data.expires_at, datetime)
        assert isinstance(data.family_id, uuid.UUID)

    def test_hash_is_sha256_of_raw(self) -> None:
        data = create_refresh_token()
        expected = hashlib.sha256(data.raw_token.encode()).hexdigest()
        assert data.token_hash == expected

    def test_hash_length_is_64_chars(self) -> None:
        data = create_refresh_token()
        assert len(data.token_hash) == 64

    def test_expiry_is_approximately_30_days(self) -> None:
        before = datetime.now(tz=timezone.utc)
        data = create_refresh_token()
        # expires_at should be ~30 days from now (within ±5 seconds)
        expected = before + timedelta(days=30)
        diff = abs((data.expires_at - expected).total_seconds())
        assert diff < 10, f"Expected ~30 days, got diff={diff}s"

    def test_custom_family_id_is_preserved(self) -> None:
        fid = uuid.uuid4()
        data = create_refresh_token(family_id=fid)
        assert data.family_id == fid

    def test_new_family_id_when_none_passed(self) -> None:
        d1 = create_refresh_token(family_id=None)
        d2 = create_refresh_token(family_id=None)
        # Each call with family_id=None should generate a fresh UUID
        assert d1.family_id != d2.family_id

    def test_two_tokens_have_different_raw_values(self) -> None:
        d1 = create_refresh_token()
        d2 = create_refresh_token()
        assert d1.raw_token != d2.raw_token

    def test_custom_expiry_override(self) -> None:
        delta = timedelta(days=7)
        data = create_refresh_token(expires_delta=delta)
        expected = datetime.now(tz=timezone.utc) + delta
        diff = abs((data.expires_at - expected).total_seconds())
        assert diff < 10


# ---------------------------------------------------------------------------
# hash_token
# ---------------------------------------------------------------------------


class TestHashToken:
    def test_deterministic(self) -> None:
        raw = "some-raw-token-value"
        assert hash_token(raw) == hash_token(raw)

    def test_matches_sha256(self) -> None:
        raw = "test-token-abc123"
        expected = hashlib.sha256(raw.encode()).hexdigest()
        assert hash_token(raw) == expected

    def test_length_64(self) -> None:
        assert len(hash_token("any-token")) == 64

    def test_different_inputs_produce_different_hashes(self) -> None:
        assert hash_token("token-a") != hash_token("token-b")
