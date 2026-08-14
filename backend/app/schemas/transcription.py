# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : schemas
# File    : transcription.py
# Purpose : Pydantic schemas for the /transcription endpoint
#
# Architecture Layer : Schema / DTO
# Pattern Used       : Pydantic BaseModel
#
# Key Concepts:
#   - Request/response validation for audio transcription
#   - Timestamped speaker-attributed transcript segments
#
# Dependencies:
#   - pydantic
#
# Requirements: 5.6, 20.3, 22.4
# ============================================================

"""Pydantic schemas for the transcription endpoint."""

from __future__ import annotations

from pydantic import BaseModel


class TranscriptSegment(BaseModel):
    """A single timestamped utterance in a meeting transcript.

    Attributes:
        timestamp: HH:MM:SS formatted timestamp.
        speaker: Speaker label (e.g. "Speaker 1").
        text: The spoken text for this segment.
    """

    timestamp: str
    speaker: str
    text: str


class TranscriptionResponse(BaseModel):
    """Response from the /transcription endpoint.

    Attributes:
        transcript: Ordered list of transcript segments.
        language: Detected or specified language code.
        duration_seconds: Total audio duration in seconds.
    """

    transcript: list[TranscriptSegment]
    language: str
    duration_seconds: float
