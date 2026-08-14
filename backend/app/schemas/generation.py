# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : schemas
# File    : generation.py
# Purpose : Pydantic schemas for resume, cover letter, and email endpoints
#
# Architecture Layer : Schema / DTO
# Pattern Used       : Pydantic BaseModel
#
# Key Concepts:
#   - ATS-optimized resume generation
#   - Cover letter generation
#   - Email composition and grammar correction
#
# Dependencies:
#   - pydantic
#
# Requirements: 14.1, 14.2, 14.4, 14.5, 22.4
# ============================================================

"""Pydantic schemas for resume/cover letter/email generation endpoints."""

from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field

# ---------------------------------------------------------------------------
# Resume generation
# ---------------------------------------------------------------------------


class ResumeGenerateRequest(BaseModel):
    """Request body for POST /resumes/generate.

    Attributes:
        work_experience: List of work experience dicts (required, non-empty).
        contact_info: Contact information dict (required).
        job_description: Target job description (required).
        education: Optional list of education dicts.
        skills: Optional list of skill strings.
    """

    work_experience: list[dict[str, Any]] = Field(..., min_length=1)
    contact_info: dict[str, Any]
    job_description: str
    education: list[dict[str, Any]] | None = None
    skills: list[str] | None = None


class ResumeGenerateResponse(BaseModel):
    """Response from POST /resumes/generate.

    Attributes:
        resume_markdown: ATS-optimized resume in Markdown format.
        generated_at: ISO 8601 datetime of generation.
    """

    resume_markdown: str
    generated_at: datetime


# ---------------------------------------------------------------------------
# Cover letter generation
# ---------------------------------------------------------------------------


class CoverLetterGenerateRequest(BaseModel):
    """Request body for POST /covers/generate.

    Attributes:
        job_description: The target job description.
        resume_data: Resume data dict used to tailor the letter.
    """

    job_description: str
    resume_data: dict[str, Any]


class CoverLetterGenerateResponse(BaseModel):
    """Response from POST /covers/generate.

    Attributes:
        cover_letter: Generated cover letter text (≤400 words).
        word_count: Number of words in the generated letter.
        generated_at: ISO 8601 datetime of generation.
    """

    cover_letter: str
    word_count: int
    generated_at: datetime


# ---------------------------------------------------------------------------
# Email generation
# ---------------------------------------------------------------------------


class EmailGenerateRequest(BaseModel):
    """Request body for POST /emails/generate.

    Attributes:
        context: Background context for the email.
        intent: The purpose/intent of the email.
        recipient_name: Optional recipient name for personalization.
        tone: Desired tone (default "professional").
    """

    context: str
    intent: str
    recipient_name: str | None = None
    tone: str = "professional"


class EmailGenerateResponse(BaseModel):
    """Response from POST /emails/generate.

    Attributes:
        subject: Suggested email subject line.
        greeting: Opening greeting line.
        body: Main email body (max 300 words).
        closing: Closing line.
        generated_at: ISO 8601 datetime of generation.
    """

    subject: str
    greeting: str
    body: str
    closing: str
    generated_at: datetime


# ---------------------------------------------------------------------------
# Grammar correction
# ---------------------------------------------------------------------------


class GrammarDiffItem(BaseModel):
    """A single insert or delete operation in the grammar correction diff.

    Attributes:
        type: "insert" for added text, "delete" for removed text.
        text: The text being inserted or deleted.
    """

    type: str  # "insert" | "delete"
    text: str


class EmailGrammarRequest(BaseModel):
    """Request body for POST /emails/grammar.

    Attributes:
        text: The email text to grammar-check and correct.
    """

    text: str


class EmailGrammarResponse(BaseModel):
    """Response from POST /emails/grammar.

    Attributes:
        corrected_text: The grammar-corrected text.
        no_changes_needed: True when no corrections were made.
        diff: List of diff operations (empty when no changes needed).
    """

    corrected_text: str
    no_changes_needed: bool
    diff: list[GrammarDiffItem]
