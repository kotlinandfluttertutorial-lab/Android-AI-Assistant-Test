# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/code
# File    : router.py
# Purpose : FastAPI router for POST /code/analyze
#
# Architecture Layer : API Router
# Pattern Used       : FastAPI Router
#
# Key Concepts:
#   - JWT Bearer authentication via get_current_user dependency
#   - Action-specific prompt construction (explain / fix_bug / generate_tests)
#   - Delegates to AIOrchestrator.complete() — same pattern as generation/router.py
#   - Prompt injection detection before every LLM call
#   - 30-second timeout guard; maps SafetyFilterError → 503
#
# Dependencies:
#   - fastapi
#   - app.schemas.code
#   - app.services.ai_orchestrator
#   - app.services.safety_service
#   - app.security.dependencies
#
# Requirements: 12.1, 12.2, 12.3, 12.4, 12.6
# ============================================================

"""Code analysis router — POST /code/analyze.

Security pipeline
-----------------
1. **JWT authentication** — enforced at router level via ``get_current_user``.
2. **Prompt injection detection** — ``InjectionDetector.check_input`` inspects
   the submitted code before it reaches the LLM.  Detected injections are
   blocked immediately with HTTP 400 ``PROMPT_INJECTION_DETECTED``.
3. **Safety filtering** — applied by ``AIOrchestrator._apply_safety_filters``
   inside ``complete()`` on the generated response.

Actions
-------
- ``explain``        → Markdown explanation: what the code does, why, and
                       suggested improvements (Requirement 12.2).
- ``fix_bug``        → Corrected code with inline ``# FIX:`` comments
                       explaining every change (Requirement 12.3).
- ``generate_tests`` → Complete test suite in the same language, using the
                       Arrange/Act/Assert pattern (Requirement 12.4).

Requirements: 12.1, 12.2, 12.3, 12.4, 12.6
"""

from __future__ import annotations

import asyncio
import logging

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.code import CodeAnalyzeRequest, CodeAnalyzeResponse
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload
from app.services.safety_service import InjectionDetector, PromptInjectionError

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Router
# ---------------------------------------------------------------------------

router = APIRouter(
    prefix="/code",
    tags=["code"],
    dependencies=[Depends(get_current_user)],
)

# ---------------------------------------------------------------------------
# Injection detector (shared singleton — same pattern as chat/router.py)
# ---------------------------------------------------------------------------

_injection_detector = InjectionDetector()


def _get_injection_detector() -> InjectionDetector:
    return _injection_detector


# ---------------------------------------------------------------------------
# Prompt builders
# ---------------------------------------------------------------------------

_LANGUAGE_LABELS: dict[str, str] = {
    "kotlin": "Kotlin",
    "java": "Java",
    "python": "Python",
    "javascript": "JavaScript",
    "cpp": "C++",
    "sql": "SQL",
}


def _build_explain_prompt(code: str, language_id: str) -> str:
    """Build a prompt that asks the LLM to explain the submitted code.

    The response must be Markdown-formatted and cover: what the code does,
    why it works the way it does, and concrete improvement suggestions.

    Requirements: 12.2
    """
    lang = _LANGUAGE_LABELS.get(language_id, language_id)
    return (
        f"You are an expert {lang} developer. "
        "Analyse the following code and return a Markdown-formatted explanation that covers:\n"
        "1. **What it does** — a plain-English summary of the code's purpose.\n"
        "2. **How it works** — a step-by-step walkthrough of the key logic.\n"
        "3. **Potential improvements** — at least two concrete, actionable suggestions.\n\n"
        f"```{language_id}\n{code}\n```\n\n"
        "Return ONLY the Markdown explanation. Do not repeat the code."
    )


def _build_fix_bug_prompt(code: str, language_id: str) -> str:
    """Build a prompt that asks the LLM to find and fix bugs in the code.

    The response must be the corrected code only, with inline ``# FIX:``
    comments on every changed line explaining what was wrong.

    Requirements: 12.3
    """
    lang = _LANGUAGE_LABELS.get(language_id, language_id)
    return (
        f"You are an expert {lang} developer and debugger. "
        "Identify all bugs in the following code and return the corrected version.\n\n"
        "Rules:\n"
        "- Return ONLY the corrected code, no prose before or after it.\n"
        "- Add an inline comment starting with `# FIX:` (or `// FIX:` for "
        "C-style languages) on every line you changed, briefly explaining the fix.\n"
        "- If the code has no bugs, return it unchanged with a single comment "
        "`# No bugs found` at the top.\n\n"
        f"```{language_id}\n{code}\n```"
    )


def _build_generate_tests_prompt(code: str, language_id: str) -> str:
    """Build a prompt that asks the LLM to generate a test suite for the code.

    The response must be a complete, runnable test file using the standard
    test framework for the language, following the Arrange/Act/Assert pattern.

    Requirements: 12.4
    """
    lang = _LANGUAGE_LABELS.get(language_id, language_id)

    framework_hint = {
        "kotlin": "JUnit 5 + MockK",
        "java": "JUnit 5 + Mockito",
        "python": "pytest",
        "javascript": "Jest",
        "cpp": "Google Test (gtest)",
        "sql": "pgTAP",
    }.get(language_id, "the standard testing framework for the language")

    return (
        f"You are an expert {lang} developer specialising in test-driven development. "
        f"Generate a complete, runnable test suite using {framework_hint} for the "
        "following code.\n\n"
        "Rules:\n"
        "- Follow the Arrange / Act / Assert pattern in every test.\n"
        "- Cover: happy path, edge cases, and at least one failure/error path.\n"
        "- Return ONLY the test file code, no prose before or after it.\n"
        "- Include all necessary imports at the top of the file.\n\n"
        f"```{language_id}\n{code}\n```"
    )


_PROMPT_BUILDERS = {
    "explain": _build_explain_prompt,
    "fix_bug": _build_fix_bug_prompt,
    "generate_tests": _build_generate_tests_prompt,
}

# Max tokens per action — explain and tests can be verbose; fix_bug mirrors
# the input so 2048 is comfortable for most snippets.
_MAX_TOKENS: dict[str, int] = {
    "explain": 2048,
    "fix_bug": 2048,
    "generate_tests": 3072,
}

# Per-action timeout in seconds (LLM call + safety filter).
_TIMEOUT_SECONDS: dict[str, float] = {
    "explain": 30.0,
    "fix_bug": 30.0,
    "generate_tests": 45.0,
}


# ---------------------------------------------------------------------------
# Shared orchestration helper (mirrors generation/router.py _orchestrate)
# ---------------------------------------------------------------------------


async def _orchestrate(prompt: str, user_id: str, max_tokens: int) -> str:
    """Call ``AIOrchestrator.complete()`` with the default provider from settings."""
    from app.config.settings import get_settings
    from app.services.ai_orchestrator import AIOrchestrator, LLMProvider

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
# POST /code/analyze
# ---------------------------------------------------------------------------


@router.post(
    "/analyze",
    response_model=CodeAnalyzeResponse,
    status_code=status.HTTP_200_OK,
    summary="Analyse code with AI",
    description=(
        "Submit source code for AI analysis. "
        "Supported actions:\n"
        "- **explain** — Markdown explanation: what the code does, how it works, and improvement ideas.\n"
        "- **fix_bug** — Corrected code with inline `# FIX:` comments on every changed line.\n"
        "- **generate_tests** — Complete test suite using the standard framework for the language.\n\n"
        "Prompt injection is detected and blocked before the LLM is called. "
        "Requires JWT Bearer authentication."
    ),
)
async def analyze_code(
    request: Request,
    body: CodeAnalyzeRequest,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
    detector: InjectionDetector = Depends(_get_injection_detector),
) -> CodeAnalyzeResponse:
    """Run AI-powered analysis on submitted source code.

    Workflow:
    1. Run ``InjectionDetector.check_input`` on the submitted code.
       - Detected injection: write audit log, raise HTTP 400
         ``PROMPT_INJECTION_DETECTED``.
    2. Build an action-specific prompt.
    3. Call ``AIOrchestrator.complete()`` inside a per-action timeout guard.
       - Timeout  → HTTP 504
       - LLM / safety error → HTTP 503
    4. Return the AI-generated content alongside the echoed inputs so the
       Android client can perform syntax highlighting without an extra round
       trip (Requirement 12.6).

    Args:
        request:      Raw Starlette request (reserved for future audit enrichment).
        body:         Validated request body — code, language_id, action.
        current_user: JWT payload injected by ``get_current_user``.
        db:           Async database session for audit logging.
        detector:     Shared ``InjectionDetector`` instance.

    Returns:
        ``CodeAnalyzeResponse`` with the AI-generated content.

    Raises:
        HTTPException 400: Prompt injection detected in the submitted code.
        HTTPException 504: LLM call exceeded the per-action timeout.
        HTTPException 503: LLM or safety-filter service error.

    Requirements: 12.1, 12.2, 12.3, 12.4, 12.6
    """
    # ── Step 1: prompt injection guard ────────────────────────────────────
    try:
        await detector.check_input(
            text=body.code,
            user_id=current_user.sub,
            db=db,
        )
    except PromptInjectionError:
        logger.warning(
            "Prompt injection blocked in code analysis (user=%s, action=%s, language=%s)",
            current_user.sub,
            body.action,
            body.language_id,
        )
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"error": {"code": "PROMPT_INJECTION_DETECTED"}},
        )

    # ── Step 2: build prompt ───────────────────────────────────────────────
    prompt = _PROMPT_BUILDERS[body.action](body.code, body.language_id)
    max_tokens = _MAX_TOKENS[body.action]
    timeout = _TIMEOUT_SECONDS[body.action]

    logger.debug(
        "Code analysis request (user=%s, action=%s, language=%s, code_len=%d)",
        current_user.sub,
        body.action,
        body.language_id,
        len(body.code),
    )

    # ── Step 3: LLM call with timeout ─────────────────────────────────────
    try:
        content = await asyncio.wait_for(
            _orchestrate(prompt, current_user.sub, max_tokens),
            timeout=timeout,
        )
    except asyncio.TimeoutError:
        logger.warning(
            "Code analysis timed out (user=%s, action=%s, timeout=%.0fs)",
            current_user.sub,
            body.action,
            timeout,
        )
        raise HTTPException(
            status_code=status.HTTP_504_GATEWAY_TIMEOUT,
            detail=(
                f"Code analysis timed out after {int(timeout)} seconds. "
                "Please try again with a shorter snippet."
            ),
        )
    except Exception as exc:
        logger.error(
            "Code analysis failed (user=%s, action=%s): %s",
            current_user.sub,
            body.action,
            exc,
        )
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=f"Code analysis service unavailable: {exc}",
        ) from exc

    # ── Step 4: return response ────────────────────────────────────────────
    return CodeAnalyzeResponse(
        language_id=body.language_id,
        original_code=body.code,
        action=body.action,
        content=content,
    )
