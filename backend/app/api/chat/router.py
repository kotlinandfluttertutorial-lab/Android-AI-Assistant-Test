# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/chat
# File    : router.py
# Purpose : FastAPI router — POST /chat/message and POST /api/v1/chat
#
# Architecture Layer : API Router
# Pattern Used       : FastAPI Router + Dependency Injection
# ============================================================

"""Chat router — REST endpoints for non-streaming AI chat.

Endpoints
---------
- ``POST /chat/message``   — primary REST chat (existing clients).
- ``POST /api/v1/chat``    — versioned REST chat (spec-compliant route).

Both endpoints share the same handler logic.

Security pipeline
-----------------
1. JWT authentication — enforced at router level via ``get_current_user``.
2. Prompt injection detection — ``InjectionDetector.check_input`` blocks
   messages before the LLM is called.  On detection: HTTP 400 is returned
   with ``{"error": {"code": "PROMPT_INJECTION_DETECTED"}}``.
3. Prompt size limit — ``PromptBuilder`` enforces ``LLM_PROMPT_MAX_CHARS``.
4. LLM call via ``LLMService`` — routes to ``GeminiProvider`` by default.
5. Response delivered without internal error details exposed to the client.

Requirements: 9.1, 9.6, 25.3, 25.4
"""

from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.llm.base import LLMRequest
from app.llm.exceptions import (
    LLMConfigurationError,
    LLMError,
    LLMPromptTooLargeError,
    LLMRateLimitError,
)
from app.llm.prompt_builder import PromptBuilder
from app.llm.service import LLMService, get_llm_service
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload
from app.services.safety_service import InjectionDetector, PromptInjectionError

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Router (existing prefix — backward-compatible)
# ---------------------------------------------------------------------------

router = APIRouter(
    prefix="/chat",
    tags=["chat"],
    dependencies=[Depends(get_current_user)],
)

# ---------------------------------------------------------------------------
# Request / Response schemas
# ---------------------------------------------------------------------------


class ChatMessageRequest(BaseModel):
    """Body schema for ``POST /chat/message`` and ``POST /api/v1/chat``.

    Attributes:
        message:         The user's message text.  Also accepted as ``content``
                         for backward compatibility.
        conversation_id: Optional UUID string of an existing conversation.
        provider:        Optional provider hint (ignored — routing is handled
                         by LLMService; kept for API compatibility).
    """

    message: str = Field(
        default="",
        min_length=0,
        description="User message (preferred field name).",
    )
    content: str = Field(
        default="",
        min_length=0,
        description="User message — legacy alias for 'message'.",
    )
    conversation_id: str | None = Field(
        default=None,
        description="UUID of the target conversation. Omit to start a new one.",
    )
    provider: str | None = Field(
        default=None,
        description="Routing hint (reserved for future use). Ignored currently.",
    )

    @property
    def effective_message(self) -> str:
        """Return ``message`` if set, otherwise fall back to ``content``."""
        return self.message.strip() or self.content.strip()


class ChatUsage(BaseModel):
    """Token usage reported in the response."""

    input_tokens: int = 0
    output_tokens: int = 0
    total_tokens: int = 0


class ChatMessageResponse(BaseModel):
    """Response schema for both chat endpoints.

    Attributes:
        answer:          Generated AI response text.
        provider:        Provider that served the request (e.g. ``"gemini"``).
        model:           Exact model ID used (e.g. ``"gemini-3.6-flash"``).
        conversation_id: Echo of the request ``conversation_id``.
        usage:           Token counts.

    Legacy field:
        message: Echo of ``answer`` for backward compatibility with clients
                 that read the old stub ``"message"`` field.
    """

    answer: str
    provider: str
    model: str
    conversation_id: str | None = None
    usage: ChatUsage = Field(default_factory=ChatUsage)
    # Backward-compat alias.
    message: str = ""


# ---------------------------------------------------------------------------
# Shared dependencies
# ---------------------------------------------------------------------------

_injection_detector = InjectionDetector()
_prompt_builder = PromptBuilder()


def get_injection_detector() -> InjectionDetector:
    """FastAPI dependency returning the shared ``InjectionDetector``."""
    return _injection_detector


def get_prompt_builder() -> PromptBuilder:
    """FastAPI dependency returning the shared ``PromptBuilder``."""
    return _prompt_builder


# ---------------------------------------------------------------------------
# Shared handler logic
# ---------------------------------------------------------------------------


async def _handle_chat(
    body: ChatMessageRequest,
    current_user: TokenPayload,
    db: AsyncSession,
    detector: InjectionDetector,
    builder: PromptBuilder,
    llm_service: LLMService,
    request_id: str,
) -> ChatMessageResponse:
    """Core chat handler shared by both endpoint paths.

    Args:
        body:         Validated request body.
        current_user: JWT payload from authentication.
        db:           Async database session.
        detector:     Injection detection service.
        builder:      Prompt assembly service.
        llm_service:  LLM routing and generation service.
        request_id:   Unique correlation ID for this request.

    Returns:
        ``ChatMessageResponse`` with generated answer and usage stats.

    Raises:
        HTTPException 400: Prompt injection detected, or empty message.
        HTTPException 429: Application-level rate limit exceeded.
        HTTPException 413: Prompt exceeds configured size limit.
        HTTPException 503: LLM provider unavailable.
        HTTPException 500: Unexpected internal error.
    """
    user_text = body.effective_message

    if not user_text:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"error": {"code": "EMPTY_MESSAGE", "message": "Message cannot be empty."}},
        )

    # Step 1: Prompt injection detection.
    try:
        await detector.check_input(
            text=user_text,
            user_id=current_user.sub,
            db=db,
        )
    except PromptInjectionError:
        logger.warning(
            "Prompt injection blocked",
            extra={
                "request_id": request_id,
                "user_id": current_user.sub,
                "conversation_id": body.conversation_id,
            },
        )
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"error": {"code": "PROMPT_INJECTION_DETECTED"}},
        )

    # Step 2: Build the prompt.
    try:
        llm_request: LLMRequest = builder.build(
            user_message=user_text,
            conversation_id=body.conversation_id,
            user_id=current_user.sub,
            request_id=request_id,
        )
    except LLMPromptTooLargeError as exc:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail={
                "error": {
                    "code": "PROMPT_TOO_LARGE",
                    "message": "The assembled prompt exceeds the configured size limit.",
                }
            },
        ) from exc

    # Step 3: Generate response via LLMService.
    try:
        llm_response = await llm_service.generate(llm_request)
    except LLMRateLimitError as exc:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail={
                "error": {
                    "code": "RATE_LIMIT_EXCEEDED",
                    "message": "Too many requests. Please wait before retrying.",
                    "retry_after": exc.retry_after_seconds,
                }
            },
        ) from exc
    except LLMConfigurationError:
        logger.exception(
            "LLM configuration error",
            extra={"request_id": request_id, "user_id": current_user.sub},
        )
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"error": {"code": "LLM_UNAVAILABLE", "message": "AI service is currently unavailable."}},
        )
    except LLMError as exc:
        logger.error(
            "LLM request failed",
            extra={"request_id": request_id, "error": str(exc)},
        )
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"error": {"code": "LLM_ERROR", "message": "AI service encountered an error."}},
        ) from exc
    except Exception:
        logger.exception(
            "Unexpected error in chat handler",
            extra={"request_id": request_id},
        )
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"error": {"code": "INTERNAL_ERROR"}},
        )

    # Build the response.
    response = ChatMessageResponse(
        answer=llm_response.text,
        provider=llm_response.provider,
        model=llm_response.model,
        conversation_id=body.conversation_id,
        usage=ChatUsage(
            input_tokens=llm_response.usage.input_tokens,
            output_tokens=llm_response.usage.output_tokens,
            total_tokens=llm_response.usage.total_tokens,
        ),
        message=llm_response.text,  # backward-compat
    )

    logger.info(
        "Chat request complete",
        extra={
            "request_id": request_id,
            "user_id": current_user.sub,
            "provider": llm_response.provider,
            "model": llm_response.model,
            "input_tokens": llm_response.usage.input_tokens,
            "output_tokens": llm_response.usage.output_tokens,
            "fallback_used": llm_response.fallback_used,
        },
    )

    return response


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------


@router.get("/")
async def chat_root() -> dict[str, str]:
    """Liveness stub — confirms the chat router is registered."""
    return {"message": "chat router"}


@router.post(
    "/message",
    response_model=ChatMessageResponse,
    summary="Send a chat message (REST, non-streaming)",
    description=(
        "Submit a user message for AI processing. "
        "Prompt injection is detected and blocked before the LLM is called. "
        "Responses are served by GeminiProvider (default) via LLMService."
    ),
    status_code=status.HTTP_200_OK,
)
async def send_message(
    request: Request,
    body: ChatMessageRequest,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
    detector: InjectionDetector = Depends(get_injection_detector),
    builder: PromptBuilder = Depends(get_prompt_builder),
    llm_service: LLMService = Depends(get_llm_service),
) -> ChatMessageResponse:
    """Process a chat message via LLMService.

    Requirements: 9.6, 25.4
    """
    request_id = uuid.uuid4().hex[:12]
    return await _handle_chat(
        body=body,
        current_user=current_user,
        db=db,
        detector=detector,
        builder=builder,
        llm_service=llm_service,
        request_id=request_id,
    )


# ---------------------------------------------------------------------------
# /api/v1/chat — spec-compliant versioned endpoint
# ---------------------------------------------------------------------------

v1_router = APIRouter(
    prefix="/api/v1/chat",
    tags=["chat"],
    dependencies=[Depends(get_current_user)],
)


@v1_router.post(
    "",
    response_model=ChatMessageResponse,
    summary="Send a chat message — v1 API",
    description=(
        "POST /api/v1/chat — versioned REST chat endpoint.\n\n"
        "Request body: ``{message, conversation_id}``\n\n"
        "Response: ``{answer, provider, model, usage: {input_tokens, output_tokens, total_tokens}}``"
    ),
    status_code=status.HTTP_200_OK,
)
async def v1_chat(
    request: Request,
    body: ChatMessageRequest,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
    detector: InjectionDetector = Depends(get_injection_detector),
    builder: PromptBuilder = Depends(get_prompt_builder),
    llm_service: LLMService = Depends(get_llm_service),
) -> ChatMessageResponse:
    """Versioned chat endpoint (POST /api/v1/chat).

    Requirements: 9.6, 25.4
    """
    request_id = uuid.uuid4().hex[:12]
    return await _handle_chat(
        body=body,
        current_user=current_user,
        db=db,
        detector=detector,
        builder=builder,
        llm_service=llm_service,
        request_id=request_id,
    )
