# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : schemas
# File    : images.py
# Purpose : Pydantic schemas for the /images/* endpoints
#
# Architecture Layer : Schema / DTO
# Pattern Used       : Pydantic BaseModel
#
# Key Concepts:
#   - Request/response validation for image analysis
#   - OCR bounding box representation
#
# Dependencies:
#   - pydantic
#
# Requirements: 6.3, 6.4, 22.4
# ============================================================

"""Pydantic schemas for image analysis endpoints."""

from __future__ import annotations

from pydantic import BaseModel


class BoundingBox(BaseModel):
    """A bounding box for a detected text region.

    Attributes:
        text: The text content of the region.
        left: Left pixel coordinate.
        top: Top pixel coordinate.
        width: Width in pixels.
        height: Height in pixels.
        confidence: OCR confidence score (0–100).
    """

    text: str
    left: int
    top: int
    width: int
    height: int
    confidence: float


class ImageAnalyzeResponse(BaseModel):
    """Response from the /images/analyze endpoint.

    Attributes:
        extracted_text: Full concatenated OCR text.
        bounding_boxes: Per-word bounding box data.
        no_text_found: True when OCR detected no text.
        vision_analysis: Optional LLM description when prompt was provided.
    """

    extracted_text: str
    bounding_boxes: list[BoundingBox]
    no_text_found: bool
    vision_analysis: str | None = None
