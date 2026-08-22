# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : schemas
# File    : code.py
# Purpose : Pydantic request/response models for POST /code/analyze
#
# Architecture Layer : Schema
# Pattern Used       : Pydantic BaseModel
#
# Contract alignment: mirrors the Android-side DTOs exactly:
#   CodeAnalysisRequestDto  → CodeAnalyszeRequest
#   CodeAnalysisResponseDto → CodeAnalyzeResponse
#
# Requirements: 12.1, 12.2, 12.3, 12.4, 12.6
# ============================================================

"""Pydantic schemas for the code analysis endpoint.

These models are the canonical contract between the Android client and the
backend.  Any change here must be reflected in:
  - data/src/main/kotlin/com/aiassistant/data/remote/code/CodeApiService.kt
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

# ---------------------------------------------------------------------------
# Supported values — kept in sync with the Android CodeRemoteDataSource
# ---------------------------------------------------------------------------

LanguageId = Literal["kotlin", "java", "python", "javascript", "cpp", "sql"]

ActionId = Literal["explain", "fix_bug", "generate_tests"]


# ---------------------------------------------------------------------------
# Request
# ---------------------------------------------------------------------------


class CodeAnalyzeRequest(BaseModel):
    """Body schema for ``POST /code/analyze``.

    Attributes:
        code:        The source code submitted by the user.  Must be non-empty.
        language_id: Lowercase language identifier.  One of the values defined
                     in ``LanguageId`` (kotlin, java, python, javascript, cpp, sql).
        action:      The analysis action to perform.  One of ``explain``,
                     ``fix_bug``, or ``generate_tests``.

    Requirements: 12.1
    """

    code: str = Field(
        ...,
        min_length=1,
        max_length=100_000,
        description="Source code to analyse.  Max 100 000 characters.",
    )
    language_id: LanguageId = Field(
        ...,
        description="Language identifier: kotlin | java | python | javascript | cpp | sql",
    )
    action: ActionId = Field(
        ...,
        description="Analysis action: explain | fix_bug | generate_tests",
    )


# ---------------------------------------------------------------------------
# Response
# ---------------------------------------------------------------------------


class CodeAnalyzeResponse(BaseModel):
    """Response schema for ``POST /code/analyze``.

    Attributes:
        language_id:   Language identifier echoed back (used by the Android
                       side for syntax-highlighting — Requirement 12.6).
        original_code: The submitted code, echoed for client reference.
        action:        The analysis action that was performed.
        content:       AI-generated result — Markdown explanation, corrected
                       code with inline comments, or a full test suite.

    Requirements: 12.2, 12.3, 12.4, 12.6
    """

    language_id: LanguageId = Field(..., description="Echoed language identifier.")
    original_code: str = Field(..., description="The original code submitted.")
    action: ActionId = Field(..., description="The action that was performed.")
    content: str = Field(..., description="AI-generated analysis result.")
