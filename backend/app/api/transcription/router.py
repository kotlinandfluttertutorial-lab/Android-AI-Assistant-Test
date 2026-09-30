# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/transcription
# File    : router.py
# Purpose : FastAPI router for audio transcription endpoint
#
# Architecture Layer : API Router
# Pattern Used       : FastAPI Router
#
# Key Concepts:
#   - Multipart audio file upload (mp3, wav, m4a, ogg, webm), max 100 MB
#   - Stub/mock transcription returning timestamped speaker-attributed segments
#   - JWT Bearer authentication via get_current_user dependency
#
# Dependencies:
#   - fastapi
#   - app.security.dependencies
#
# Requirements: 5.6, 20.3, 22.4
# ============================================================

"""Transcription router — /transcription endpoint.

Endpoint
--------
POST /transcription
    - Accept multipart form: ``audio_file`` (meeting audio), optional
      ``language`` (default "en").
    - Validate: mp3, wav, m4a, ogg, webm formats only; max 100 MB.
    - Return: timestamped transcript with speaker attribution.

Requirements: 5.6, 20.3
"""

from __future__ import annotations

import logging
from typing import Annotated

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile, status

from app.schemas.transcription import TranscriptionResponse, TranscriptSegment
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

_MAX_AUDIO_BYTES = 100 * 1024 * 1024  # 100 MB

_ALLOWED_AUDIO_CONTENT_TYPES = {
    "audio/mpeg",
    "audio/mp3",
    "audio/wav",
    "audio/x-wav",
    "audio/wave",
    "audio/x-m4a",
    "audio/mp4",
    "audio/ogg",
    "audio/webm",
    "video/webm",
    "application/octet-stream",  # allow generic binary for testing
}

_ALLOWED_AUDIO_EXTENSIONS = {".mp3", ".wav", ".m4a", ".ogg", ".webm"}

# ---------------------------------------------------------------------------
# Router
# ---------------------------------------------------------------------------

router = APIRouter(
    prefix="/transcription",
    tags=["transcription"],
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _infer_extension(filename: str | None) -> str:
    """Extract the file extension from a filename, lower-cased."""
    if not filename:
        return ""
    parts = filename.rsplit(".", 1)
    if len(parts) == 2:
        return "." + parts[1].lower()
    return ""


def _estimate_duration(file_bytes: bytes, content_type: str) -> float:
    """Estimate audio duration from file size.

    This is a stub approximation used in the mock implementation.
    For mp3 at 128kbps: bytes / (128000 / 8) = seconds.
    Returns a minimum of 1.0 second.
    """
    if not file_bytes:
        return 1.0
    kbps = 128_000 / 8  # bytes per second at 128 kbps
    estimated = len(file_bytes) / kbps
    return max(1.0, round(estimated, 2))


def _generate_stub_transcript(language: str, duration_seconds: float) -> list[TranscriptSegment]:
    """Generate a stub transcript with realistic structure.

    This is a mock implementation. In production, this would delegate to a
    real speech-to-text service such as Whisper or Google Speech-to-Text.
    """
    segments: list[TranscriptSegment] = []
    # Generate one segment every ~30 seconds of audio, minimum 1 segment
    num_segments = max(1, int(duration_seconds // 30))

    for i in range(num_segments):
        seconds = i * 30
        hh = seconds // 3600
        mm = (seconds % 3600) // 60
        ss = seconds % 60
        timestamp = f"{hh:02d}:{mm:02d}:{ss:02d}"

        speaker_idx = (i % 2) + 1
        speaker = f"Speaker {speaker_idx}"

        texts = [
            "Good morning everyone, let's get started with today's agenda.",
            "Thank you for joining. I'd like to review the action items from last week.",
            "The project is progressing well, and we're on track for the deadline.",
            "I have a few concerns about the timeline that I'd like to address.",
        ]
        text = texts[i % len(texts)]

        segments.append(TranscriptSegment(timestamp=timestamp, speaker=speaker, text=text))

    return segments


# ---------------------------------------------------------------------------
# POST /transcription
# ---------------------------------------------------------------------------


@router.post(
    "",
    summary="Transcribe a meeting audio file",
    description=(
        "Upload an audio file (mp3, wav, m4a, ogg, webm, max 100 MB). "
        "Returns a timestamped transcript with speaker attribution. "
        "Requires JWT Bearer authentication."
    ),
    response_model=TranscriptionResponse,
)
async def transcribe_audio(
    audio_file: Annotated[UploadFile, File(description="Audio file to transcribe")],
    language: str = Form("en", description="Language code (default: en)"),
    current_user: TokenPayload = Depends(get_current_user),
) -> TranscriptionResponse:
    """Transcribe a meeting audio file into timestamped speaker-attributed segments.

    Validates format and size, then returns a stub transcript.

    Requirements: 5.6, 20.3
    """
    # -----------------------------------------------------------------------
    # Step 1 — Extension validation
    # -----------------------------------------------------------------------
    filename = audio_file.filename or ""
    ext = _infer_extension(filename)

    if ext and ext not in _ALLOWED_AUDIO_EXTENSIONS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(f"Unsupported audio format '{ext}'. Allowed: mp3, wav, m4a, ogg, webm."),
        )

    # Content-type check (secondary, as some clients may not set it correctly)
    content_type = (audio_file.content_type or "").lower().split(";")[0].strip()
    if ext == "" and content_type and content_type not in _ALLOWED_AUDIO_CONTENT_TYPES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"Unsupported audio content type '{content_type}'. "
                "Allowed formats: mp3, wav, m4a, ogg, webm."
            ),
        )

    # -----------------------------------------------------------------------
    # Step 2 — Read and size check
    # -----------------------------------------------------------------------
    audio_bytes = await audio_file.read()
    if len(audio_bytes) > _MAX_AUDIO_BYTES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"Audio file exceeds maximum allowed size of "
                f"{_MAX_AUDIO_BYTES // (1024 * 1024)} MB."
            ),
        )

    # -----------------------------------------------------------------------
    # Step 3 — Generate stub transcript (mock implementation)
    # -----------------------------------------------------------------------
    duration_seconds = _estimate_duration(audio_bytes, content_type)
    transcript = _generate_stub_transcript(language, duration_seconds)

    return TranscriptionResponse(
        transcript=transcript,
        language=language,
        duration_seconds=duration_seconds,
    )
