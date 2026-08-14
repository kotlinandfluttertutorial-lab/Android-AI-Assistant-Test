# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : schemas
# File    : translation.py
# Purpose : Pydantic schemas for the /translate endpoint
#
# Architecture Layer : Schema / DTO
# Pattern Used       : Pydantic BaseModel
#
# Key Concepts:
#   - Request/response validation for text translation
#   - Online vs. offline translation routing
#
# Dependencies:
#   - pydantic
#
# Requirements: 10.5, 20.1, 20.2, 22.4
# ============================================================

"""Pydantic schemas for the translation endpoint."""

from __future__ import annotations

from pydantic import BaseModel, Field


class TranslateRequest(BaseModel):
    """Request body for POST /translate.

    Attributes:
        text: Source text to translate (max 10,000 characters).
        source_language: BCP-47 language code of the input text.
        target_language: BCP-47 language code of the desired output.
        offline: When True, use on-device model without network call.
        provider: Optional LLM provider override.
    """

    text: str = Field(..., max_length=10_000)
    source_language: str
    target_language: str
    offline: bool = False
    provider: str | None = None


class TranslateResponse(BaseModel):
    """Response from POST /translate.

    Attributes:
        translated_text: The translated text.
        source_language: Source language code echoed from request.
        target_language: Target language code echoed from request.
        provider: Provider that performed the translation.
        offline_mode: Whether the offline on-device model was used.
    """

    translated_text: str
    source_language: str
    target_language: str
    provider: str
    offline_mode: bool
