# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/translation
# File    : router.py
# Purpose : FastAPI router for text translation endpoint
#
# Architecture Layer : API Router
# Pattern Used       : FastAPI Router
#
# Key Concepts:
#   - Online translation via AIOrchestrator.complete()
#   - Offline translation via stub on-device model response
#   - Input validation (max 10,000 chars)
#   - JWT Bearer authentication via get_current_user dependency
#
# Dependencies:
#   - fastapi
#   - app.services.ai_orchestrator
#   - app.security.dependencies
#
# Requirements: 10.5, 20.1, 20.2, 22.4
# ============================================================

"""Translation router — /translate endpoint.

Endpoint
--------
POST /translate
    - Accept JSON: text, source_language, target_language, offline (bool), provider.
    - text max 10,000 characters.
    - When offline=false: route to AIOrchestrator.complete() with translation prompt.
    - When offline=true: return stub offline translation response.
    - Return: translated_text, source_language, target_language, provider, offline_mode.

Requirements: 10.5, 20.1, 20.2
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, status

from app.schemas.translation import TranslateRequest, TranslateResponse
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload

try:
    from app.services.ai_orchestrator import AIOrchestrator, LLMProvider
except ImportError:  # pragma: no cover
    AIOrchestrator = None  # type: ignore[assignment,misc]
    LLMProvider = None  # type: ignore[assignment]

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Router
# ---------------------------------------------------------------------------

router = APIRouter(
    prefix="/translate",
    tags=["translation"],
)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_OFFLINE_PROVIDER_LABEL = "offline-on-device"


def _build_translation_prompt(text: str, source: str, target: str) -> str:
    """Build a translation prompt for the AI Orchestrator."""
    return (
        f"Translate the following text from {source} to {target}. "
        "Return ONLY the translated text with no explanation, preamble, or formatting.\n\n"
        f"Text to translate:\n{text}"
    )


# ---------------------------------------------------------------------------
# POST /translate
# ---------------------------------------------------------------------------


@router.post(
    "",
    summary="Translate text between languages",
    description=(
        "Translate text (max 10,000 characters) between any supported language pair. "
        "Use offline=true to route to the on-device model. "
        "Requires JWT Bearer authentication."
    ),
    response_model=TranslateResponse,
)
async def translate_text(
    request: TranslateRequest,
    current_user: TokenPayload = Depends(get_current_user),
) -> TranslateResponse:
    """Translate text online (via AIOrchestrator) or offline (stub model).

    Requirements: 10.5, 20.1, 20.2
    """
    # -----------------------------------------------------------------------
    # Offline mode — return stub on-device translation (Requirements 10.5, 20.1)
    # -----------------------------------------------------------------------
    if request.offline:
        stub_translation = (
            f"[Offline translation from {request.source_language} to "
            f"{request.target_language}]: {request.text}"
        )
        return TranslateResponse(
            translated_text=stub_translation,
            source_language=request.source_language,
            target_language=request.target_language,
            provider=_OFFLINE_PROVIDER_LABEL,
            offline_mode=True,
        )

    # -----------------------------------------------------------------------
    # Online mode — route to AIOrchestrator (Requirements 20.2)
    # -----------------------------------------------------------------------
    # Resolve provider
    provider_str = (request.provider or "openai").lower()
    try:
        provider_enum = LLMProvider(provider_str)
    except (ValueError, TypeError):
        provider_enum = LLMProvider.openai if LLMProvider else None
        provider_str = "openai"

    prompt = _build_translation_prompt(
        request.text, request.source_language, request.target_language
    )

    try:
        orchestrator = AIOrchestrator(db=None)  # type: ignore[arg-type]
        result = await orchestrator.complete(
            prompt=prompt,
            provider=provider_enum,
            max_tokens=2048,
            user_id=current_user.sub,
        )
        translated_text = result.text
    except Exception as exc:
        logger.error("Translation via AIOrchestrator failed: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=f"Translation service temporarily unavailable: {exc}",
        ) from exc

    return TranslateResponse(
        translated_text=translated_text,
        source_language=request.source_language,
        target_language=request.target_language,
        provider=provider_str,
        offline_mode=False,
    )
