# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/generation
# File    : router.py
# Purpose : FastAPI router for resume, cover letter, and email generation
#
# Architecture Layer : API Router
# Pattern Used       : FastAPI Router
#
# Key Concepts:
#   - ATS-optimized resume generation via AIOrchestrator
#   - Cover letter generation (≤400 words) via AIOrchestrator
#   - Email composition and grammar correction via AIOrchestrator
#   - Input validation and authentication
#   - JWT Bearer authentication via get_current_user dependency
#
# Dependencies:
#   - fastapi
#   - app.services.ai_orchestrator
#   - app.security.dependencies
#
# Requirements: 14.1, 14.2, 14.4, 14.5, 22.4
# ============================================================

"""Generation router — resume, cover letter, and email generation endpoints.

Endpoints
---------
POST /resumes/generate   — ATS-optimized resume in Markdown
POST /covers/generate    — Cover letter ≤ 400 words
POST /emails/generate    — Structured email with subject, greeting, body, closing
POST /emails/grammar     — Grammar correction with inline diff

Requirements: 14.1, 14.2, 14.4, 14.5
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime

from fastapi import APIRouter, Depends, HTTPException, status

from app.schemas.generation import (
    CoverLetterGenerateRequest,
    CoverLetterGenerateResponse,
    EmailGenerateRequest,
    EmailGenerateResponse,
    EmailGrammarRequest,
    EmailGrammarResponse,
    GrammarDiffItem,
    ResumeGenerateRequest,
    ResumeGenerateResponse,
)
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Routers
# ---------------------------------------------------------------------------

resumes_router = APIRouter(
    prefix="/resumes",
    tags=["generation"],
)

covers_router = APIRouter(
    prefix="/covers",
    tags=["generation"],
)

emails_router = APIRouter(
    prefix="/emails",
    tags=["generation"],
)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _now_utc() -> datetime:
    return datetime.now(tz=UTC)


def _word_count(text: str) -> int:
    """Count words in text."""
    return len(text.split())


def _build_resume_prompt(request: ResumeGenerateRequest) -> str:
    """Construct an ATS-optimized resume generation prompt."""
    contact_str = ", ".join(f"{k}: {v}" for k, v in request.contact_info.items())
    exp_str = "\n".join(
        f"- {e.get('title', '')} at {e.get('company', '')} ({e.get('dates', '')}): "
        f"{e.get('description', '')}"
        for e in request.work_experience
    )
    edu_str = ""
    if request.education:
        edu_str = "\n".join(
            f"- {e.get('degree', '')} from {e.get('institution', '')} ({e.get('year', '')})"
            for e in request.education
        )
    skills_str = ", ".join(request.skills) if request.skills else "Not specified"

    return (
        "Generate an ATS-optimized resume in Markdown format with the following sections: "
        "Summary, Experience, Education, Skills. "
        "Tailor the resume to the provided job description.\n\n"
        f"Contact Information: {contact_str}\n\n"
        f"Job Description:\n{request.job_description}\n\n"
        f"Work Experience:\n{exp_str}\n\n"
        f"Education:\n{edu_str or 'Not provided'}\n\n"
        f"Skills: {skills_str}\n\n"
        "Return ONLY the Markdown resume content, no additional commentary."
    )


def _build_cover_letter_prompt(request: CoverLetterGenerateRequest) -> str:
    """Construct a cover letter generation prompt."""
    return (
        "Write a professional cover letter of no more than 400 words. "
        "Tailor the letter to the job description and resume data provided.\n\n"
        f"Job Description:\n{request.job_description}\n\n"
        f"Resume Data:\n{request.resume_data}\n\n"
        "Return ONLY the cover letter text, no additional commentary."
    )


def _build_email_prompt(request: EmailGenerateRequest) -> str:
    """Construct an email generation prompt."""
    recipient_note = (
        f"The recipient's name is {request.recipient_name}. " if request.recipient_name else ""
    )
    return (
        f"Write a {request.tone} email. "
        f"{recipient_note}"
        f"Context: {request.context}\n"
        f"Intent: {request.intent}\n\n"
        "Return the email as a JSON object with these exact keys: "
        "subject, greeting, body, closing. "
        "Keep the body under 300 words. "
        "Return ONLY the JSON object, no markdown wrapping."
    )


def _build_grammar_prompt(text: str) -> str:
    """Construct a grammar correction prompt."""
    return (
        "Correct the grammar and spelling in the following text. "
        "Return a JSON object with these exact keys:\n"
        "- corrected_text: the grammar-corrected text\n"
        "- no_changes_needed: true if no changes were made, false otherwise\n"
        "- diff: a list of change objects, each with keys 'type' ('insert' or 'delete') "
        "and 'text' (the text being inserted or deleted). "
        "If no_changes_needed is true, diff should be an empty array.\n\n"
        f"Text to correct:\n{text}\n\n"
        "Return ONLY the JSON object, no markdown wrapping."
    )


def _parse_email_response(text: str) -> dict:
    """Parse a JSON email response from the LLM."""
    import json
    import re

    # Strip markdown code fences if present
    cleaned = re.sub(r"^```(?:json)?\s*", "", text.strip(), flags=re.MULTILINE)
    cleaned = re.sub(r"\s*```$", "", cleaned, flags=re.MULTILINE)

    try:
        data = json.loads(cleaned.strip())
        return {
            "subject": str(data.get("subject", "")),
            "greeting": str(data.get("greeting", "")),
            "body": str(data.get("body", "")),
            "closing": str(data.get("closing", "")),
        }
    except (json.JSONDecodeError, KeyError):
        # Fallback: treat entire response as body with placeholder fields
        return {
            "subject": "Email",
            "greeting": "Hello,",
            "body": text.strip(),
            "closing": "Best regards,",
        }


def _parse_grammar_response(text: str, original: str) -> dict:
    """Parse a JSON grammar correction response from the LLM."""
    import json
    import re

    # Strip markdown code fences if present
    cleaned = re.sub(r"^```(?:json)?\s*", "", text.strip(), flags=re.MULTILINE)
    cleaned = re.sub(r"\s*```$", "", cleaned, flags=re.MULTILINE)

    try:
        data = json.loads(cleaned.strip())
        corrected = str(data.get("corrected_text", original))
        no_changes = bool(data.get("no_changes_needed", corrected == original))
        raw_diff = data.get("diff", [])
        diff_items = [
            GrammarDiffItem(type=d["type"], text=d["text"])
            for d in raw_diff
            if isinstance(d, dict) and "type" in d and "text" in d
        ]
        return {
            "corrected_text": corrected,
            "no_changes_needed": no_changes,
            "diff": diff_items,
        }
    except (json.JSONDecodeError, KeyError):
        # Fallback: return original text unchanged
        return {
            "corrected_text": original,
            "no_changes_needed": True,
            "diff": [],
        }


async def _orchestrate(prompt: str, user_id: str, max_tokens: int = 2048) -> str:
    """Call AIOrchestrator.complete() with default provider from settings."""
    from app.config.settings import get_settings
    from app.services.ai_orchestrator import (
        AIOrchestrator,
        LLMProvider,
    )

    settings = get_settings()
    provider_str = settings.DEFAULT_LLM_PROVIDER.lower()
    try:
        provider_enum = LLMProvider(provider_str)
    except ValueError:
        provider_enum = LLMProvider.openai

    orchestrator = AIOrchestrator(db=None)  # type: ignore[arg-type]
    result = await orchestrator.complete(
        prompt=prompt,
        provider=provider_enum,
        max_tokens=max_tokens,
        user_id=user_id,
    )
    return result.text


# ---------------------------------------------------------------------------
# POST /resumes/generate
# ---------------------------------------------------------------------------


@resumes_router.post(
    "/generate",
    summary="Generate an ATS-optimized resume",
    description=(
        "Generate a Markdown-formatted ATS-optimized resume with Summary, Experience, "
        "Education, and Skills sections. Requires work_experience, contact_info, and "
        "job_description. Requires JWT Bearer authentication."
    ),
    response_model=ResumeGenerateResponse,
)
async def generate_resume(
    request: ResumeGenerateRequest,
    current_user: TokenPayload = Depends(get_current_user),
) -> ResumeGenerateResponse:
    """Generate an ATS-optimized resume in Markdown via AIOrchestrator.

    Requirements: 14.1
    """
    import asyncio

    prompt = _build_resume_prompt(request)

    try:
        resume_markdown = await asyncio.wait_for(
            _orchestrate(prompt, current_user.sub, max_tokens=2048),
            timeout=30.0,
        )
    except asyncio.TimeoutError:
        raise HTTPException(
            status_code=status.HTTP_504_GATEWAY_TIMEOUT,
            detail="Resume generation timed out. Please try again.",
        )
    except Exception as exc:
        logger.error("Resume generation failed: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=f"Resume generation service unavailable: {exc}",
        ) from exc

    return ResumeGenerateResponse(
        resume_markdown=resume_markdown,
        generated_at=_now_utc(),
    )


# ---------------------------------------------------------------------------
# POST /covers/generate
# ---------------------------------------------------------------------------


@covers_router.post(
    "/generate",
    summary="Generate a cover letter",
    description=(
        "Generate a professional cover letter of ≤ 400 words. "
        "Requires job_description and resume_data. "
        "Requires JWT Bearer authentication."
    ),
    response_model=CoverLetterGenerateResponse,
)
async def generate_cover_letter(
    request: CoverLetterGenerateRequest,
    current_user: TokenPayload = Depends(get_current_user),
) -> CoverLetterGenerateResponse:
    """Generate a tailored cover letter via AIOrchestrator.

    Requirements: 14.2
    """
    prompt = _build_cover_letter_prompt(request)

    try:
        cover_letter = await _orchestrate(prompt, current_user.sub, max_tokens=1024)
    except Exception as exc:
        logger.error("Cover letter generation failed: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=f"Cover letter generation service unavailable: {exc}",
        ) from exc

    wc = _word_count(cover_letter)

    return CoverLetterGenerateResponse(
        cover_letter=cover_letter,
        word_count=wc,
        generated_at=_now_utc(),
    )


# ---------------------------------------------------------------------------
# POST /emails/generate
# ---------------------------------------------------------------------------


@emails_router.post(
    "/generate",
    summary="Generate a professional email",
    description=(
        "Generate a structured email with subject, greeting, body (≤300 words), "
        "and closing. Requires context and intent. "
        "Requires JWT Bearer authentication."
    ),
    response_model=EmailGenerateResponse,
)
async def generate_email(
    request: EmailGenerateRequest,
    current_user: TokenPayload = Depends(get_current_user),
) -> EmailGenerateResponse:
    """Generate a professional email via AIOrchestrator.

    Requirements: 14.4
    """
    prompt = _build_email_prompt(request)

    try:
        raw_response = await _orchestrate(prompt, current_user.sub, max_tokens=1024)
    except Exception as exc:
        logger.error("Email generation failed: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=f"Email generation service unavailable: {exc}",
        ) from exc

    parsed = _parse_email_response(raw_response)

    return EmailGenerateResponse(
        subject=parsed["subject"],
        greeting=parsed["greeting"],
        body=parsed["body"],
        closing=parsed["closing"],
        generated_at=_now_utc(),
    )


# ---------------------------------------------------------------------------
# POST /emails/grammar
# ---------------------------------------------------------------------------


@emails_router.post(
    "/grammar",
    summary="Grammar check and correct email text",
    description=(
        "Correct grammar and spelling in the provided text. "
        "Returns corrected text with an inline diff of changes. "
        "Requires JWT Bearer authentication."
    ),
    response_model=EmailGrammarResponse,
)
async def correct_grammar(
    request: EmailGrammarRequest,
    current_user: TokenPayload = Depends(get_current_user),
) -> EmailGrammarResponse:
    """Grammar-correct text and return diff via AIOrchestrator.

    Requirements: 14.5
    """
    prompt = _build_grammar_prompt(request.text)

    try:
        raw_response = await _orchestrate(prompt, current_user.sub, max_tokens=1024)
    except Exception as exc:
        logger.error("Grammar correction failed: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=f"Grammar correction service unavailable: {exc}",
        ) from exc

    parsed = _parse_grammar_response(raw_response, request.text)

    return EmailGrammarResponse(
        corrected_text=parsed["corrected_text"],
        no_changes_needed=parsed["no_changes_needed"],
        diff=parsed["diff"],
    )
