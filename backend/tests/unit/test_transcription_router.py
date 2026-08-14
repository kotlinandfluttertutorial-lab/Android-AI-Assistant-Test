# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : tests/unit
# File    : test_transcription_router.py
# Purpose : Unit tests for POST /transcription endpoint
#
# Architecture Layer : Test
# Pattern Used       : pytest + FastAPI TestClient with dependency overrides
#
# Key Concepts:
#   - Audio format validation (mp3, wav, m4a, ogg, webm)
#   - File size limit (100 MB)
#   - Timestamped transcript segments with speaker attribution
#   - JWT authentication bypass for tests
#
# Requirements: 5.6, 20.3
# ============================================================

"""Unit tests for POST /transcription endpoint.

Requirements: 5.6, 20.3
"""

from __future__ import annotations

import os
from datetime import datetime, timezone

import pytest

os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.transcription.router import router as transcription_router
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload

# ---------------------------------------------------------------------------
# Test helpers
# ---------------------------------------------------------------------------


def _fake_user() -> TokenPayload:
    return TokenPayload(
        sub="user-456",
        role="user",
        jti="jti-def",
        iat=datetime.now(tz=timezone.utc),
        exp=datetime(2099, 1, 1, tzinfo=timezone.utc),
    )


def _build_app() -> FastAPI:
    app = FastAPI()
    app.dependency_overrides[get_current_user] = lambda: _fake_user()
    app.include_router(transcription_router)
    return app


@pytest.fixture()
def client() -> TestClient:
    return TestClient(_build_app(), raise_server_exceptions=False)


def _small_audio() -> bytes:
    """Return minimal non-empty audio bytes."""
    return b"\x00" * 1024  # 1 KB placeholder


# ---------------------------------------------------------------------------
# Format validation
# ---------------------------------------------------------------------------


class TestTranscriptionFormatValidation:
    """Only mp3, wav, m4a, ogg, webm are accepted."""

    @pytest.mark.parametrize(
        "filename,content_type",
        [
            ("meeting.mp3", "audio/mpeg"),
            ("meeting.wav", "audio/wav"),
            ("meeting.m4a", "audio/x-m4a"),
            ("meeting.ogg", "audio/ogg"),
            ("meeting.webm", "audio/webm"),
        ],
    )
    def test_accepts_valid_audio_formats(
        self, client: TestClient, filename: str, content_type: str
    ) -> None:
        """All supported audio formats should be accepted (200)."""
        response = client.post(
            "/transcription",
            files={"audio_file": (filename, _small_audio(), content_type)},
        )
        assert response.status_code == 200

    @pytest.mark.parametrize(
        "filename,content_type",
        [
            ("video.mp4", "video/mp4"),
            ("doc.pdf", "application/pdf"),
            ("image.jpg", "image/jpeg"),
            ("data.txt", "text/plain"),
        ],
    )
    def test_rejects_unsupported_formats(
        self, client: TestClient, filename: str, content_type: str
    ) -> None:
        """Unsupported formats should return 422."""
        response = client.post(
            "/transcription",
            files={"audio_file": (filename, _small_audio(), content_type)},
        )
        assert response.status_code == 422

    def test_rejects_avi_by_extension(self, client: TestClient) -> None:
        """AVI extension should be rejected."""
        response = client.post(
            "/transcription",
            files={
                "audio_file": ("clip.avi", _small_audio(), "application/octet-stream")
            },
        )
        assert response.status_code == 422
        detail = response.json()["detail"]
        assert "avi" in detail.lower() or "Unsupported" in detail


# ---------------------------------------------------------------------------
# File size validation
# ---------------------------------------------------------------------------


class TestTranscriptionSizeValidation:
    """Files exceeding 100 MB must be rejected."""

    def test_rejects_file_over_100mb(self, client: TestClient) -> None:
        """File exceeding 100 MB should return 422."""
        oversized = b"\x00" * (100 * 1024 * 1024 + 1)
        response = client.post(
            "/transcription",
            files={"audio_file": ("big.mp3", oversized, "audio/mpeg")},
        )
        assert response.status_code == 422
        assert "100" in response.json()["detail"]

    def test_accepts_small_file(self, client: TestClient) -> None:
        """A small file well within the size limit should succeed."""
        response = client.post(
            "/transcription",
            files={"audio_file": ("small.wav", _small_audio(), "audio/wav")},
        )
        assert response.status_code == 200


# ---------------------------------------------------------------------------
# Transcript response structure (Requirement 20.3)
# ---------------------------------------------------------------------------


class TestTranscriptionResponseStructure:
    """Verify the response contains required fields and correct structure."""

    def test_response_contains_transcript_list(self, client: TestClient) -> None:
        """Response must include a 'transcript' list."""
        response = client.post(
            "/transcription",
            files={"audio_file": ("meeting.mp3", _small_audio(), "audio/mpeg")},
        )
        assert response.status_code == 200
        body = response.json()
        assert "transcript" in body
        assert isinstance(body["transcript"], list)

    def test_response_contains_language(self, client: TestClient) -> None:
        """Response must include the 'language' field."""
        response = client.post(
            "/transcription",
            files={"audio_file": ("meeting.mp3", _small_audio(), "audio/mpeg")},
        )
        assert response.status_code == 200
        assert "language" in response.json()

    def test_response_contains_duration_seconds(self, client: TestClient) -> None:
        """Response must include 'duration_seconds' as a number."""
        response = client.post(
            "/transcription",
            files={"audio_file": ("meeting.mp3", _small_audio(), "audio/mpeg")},
        )
        assert response.status_code == 200
        body = response.json()
        assert "duration_seconds" in body
        assert isinstance(body["duration_seconds"], float | int)
        assert body["duration_seconds"] > 0

    def test_transcript_segments_have_timestamp_speaker_text(
        self, client: TestClient
    ) -> None:
        """Each transcript segment must have timestamp, speaker, and text fields."""
        # Use a larger file to guarantee at least one segment
        audio = b"\x00" * (128_000 * 10)  # ~10 s of audio at 128kbps
        response = client.post(
            "/transcription",
            files={"audio_file": ("session.mp3", audio, "audio/mpeg")},
        )
        assert response.status_code == 200
        segments = response.json()["transcript"]
        assert len(segments) >= 1
        for seg in segments:
            assert "timestamp" in seg
            assert "speaker" in seg
            assert "text" in seg
            # Timestamp must be HH:MM:SS format
            assert len(seg["timestamp"]) == 8
            assert seg["timestamp"][2] == ":"
            assert seg["timestamp"][5] == ":"

    def test_speaker_attribution_present(self, client: TestClient) -> None:
        """Segments must have non-empty speaker labels."""
        audio = b"\x00" * (128_000 * 10)
        response = client.post(
            "/transcription",
            files={"audio_file": ("meeting.wav", audio, "audio/wav")},
        )
        assert response.status_code == 200
        segments = response.json()["transcript"]
        for seg in segments:
            assert seg["speaker"]  # Non-empty string

    def test_language_echoed_from_request(self, client: TestClient) -> None:
        """Response language should match the requested language."""
        response = client.post(
            "/transcription",
            files={"audio_file": ("meeting.mp3", _small_audio(), "audio/mpeg")},
            data={"language": "fr"},
        )
        assert response.status_code == 200
        assert response.json()["language"] == "fr"

    def test_default_language_is_en(self, client: TestClient) -> None:
        """Default language when not specified should be 'en'."""
        response = client.post(
            "/transcription",
            files={"audio_file": ("meeting.mp3", _small_audio(), "audio/mpeg")},
        )
        assert response.status_code == 200
        assert response.json()["language"] == "en"


# ---------------------------------------------------------------------------
# Authentication check
# ---------------------------------------------------------------------------


class TestTranscriptionAuth:
    """Endpoint requires JWT authentication."""

    def test_unauthenticated_request_rejected(self) -> None:
        """Requests without JWT should be rejected with 401/403."""
        app = FastAPI()
        app.include_router(transcription_router)
        client = TestClient(app, raise_server_exceptions=False)
        response = client.post(
            "/transcription",
            files={"audio_file": ("meeting.mp3", _small_audio(), "audio/mpeg")},
        )
        assert response.status_code in (401, 403)
