# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/chat
# File    : router.py
# Purpose : FastAPI router defining all HTTP endpoints for the chat domain
#
# Architecture Layer : API Router
# Pattern Used       : FastAPI Router
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Chat router — /chat/* REST endpoints.

Provides the REST endpoint for sending chat messages.  Unlike the WebSocket
streaming endpoint (``/ws/chat/{conversation_id}``), this endpoint accepts a
single message and returns a complete (non-streamed) response via HTTP.

Security pipeline for every message request
-------------------------------------------
1. **JWT authentication** — enforced at router level via ``get_current_user``.
2. **Prompt injection detection** — ``InjectionDetector.check_input`` inspects
   the user message before it is forwarded to the LLM provider.  If an
   injection pattern is detected the request is blocked immediately:
   - An audit log entry is written with the user ID and the SHA-256 hash of
     the sanitised input (raw input is never stored).
   - HTTP 400 is returned with ``{"error": {"code": "PROMPT_INJECTION_DETECTED"}}``.
   - The LLM provider receives nothing.
3. **Safety filtering on output** — applied by ``AIOrchestrator._apply_safety_filters``
   on every token before delivery (Properties 13 and 14).

Requirements: 9.1, 9.6, 25.3, 25.4
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.security.dependencies import TokenPayload, get_current_user
from app.services.safety_service import InjectionDetector, PromptInjectionError

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/chat",
    tags=["chat"],
    dependencies=[Depends(get_current_user)],
)

# ---------------------------------------------------------------------------
# Request / Response schemas
# ---------------------------------------------------------------------------


class ChatMessageRequest(BaseModel):
    """Body schema for ``POST /chat/message``.

    Attributes:
        content: The user's message text.
        conversation_id: Optional UUID string of an existing conversation.
            If omitted a new conversation is created server-side.
    """

    content: str = Field(..., min_length=1, description="User message content.")
    conversation_id: str | None = Field(
        default=None,
        description="UUID of the target conversation. Omit to start a new one.",
    )


class ChatMessageResponse(BaseModel):
    """Response schema for ``POST /chat/message``."""

    message: str
    conversation_id: str | None = None


# ---------------------------------------------------------------------------
# Injection detector dependency
# ---------------------------------------------------------------------------

_injection_detector = InjectionDetector()


def get_injection_detector() -> InjectionDetector:
    """FastAPI dependency that returns the shared ``InjectionDetector`` instance."""
    return _injection_detector


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------


@router.get("/")
async def chat_root() -> dict:
    """Liveness stub — confirms the chat router is registered."""
    return {"message": "chat router"}


@router.post(
    "/message",
    response_model=ChatMessageResponse,
    summary="Send a chat message (REST)",
    description=(
        "Submit a user message for AI processing over REST (non-streaming). "
        "Prompt injection is detected and blocked before the LLM is called. "
        "The response is safety-filtered before delivery."
    ),
    status_code=status.HTTP_200_OK,
)
async def send_message(
    request: Request,
    body: ChatMessageRequest,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
    detector: InjectionDetector = Depends(get_injection_detector),
) -> ChatMessageResponse:
    """Process a chat message with injection detection and safety filtering.

    Workflow:
    1. Run ``InjectionDetector.check_input`` on the message content.
       - On detection: write audit log, raise HTTP 400 with
         ``PROMPT_INJECTION_DETECTED`` code.
    2. Forward the clean message to the AI backend for processing.
       (Full AI orchestration is wired in by subsequent tasks.)
    3. Apply safety filters to the response before returning.

    Args:
        request:      The raw Starlette request (used for IP / User-Agent enrichment
                      in future audit log iterations).
        body:         Validated request body containing ``content`` and optional
                      ``conversation_id``.
        current_user: JWT payload injected by ``get_current_user``.
        db:           Async database session injected by ``get_db``.
        detector:     Shared ``InjectionDetector`` instance.

    Returns:
        ``ChatMessageResponse`` with the processed reply.

    Raises:
        HTTPException (400): When a prompt injection pattern is detected.

    Requirements: 9.6, 25.4
    """
    try:
        await detector.check_input(
            text=body.content,
            user_id=current_user.sub,
            db=db,
        )
    except PromptInjectionError:
        logger.warning(
            "Prompt injection blocked for user %s (conversation_id=%s)",
            current_user.sub,
            body.conversation_id,
        )
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"error": {"code": "PROMPT_INJECTION_DETECTED"}},
        )

    # -----------------------------------------------------------------------
    # Placeholder AI processing — full wiring done by subsequent tasks.
    # The injection gate above is the security-critical part of this task.
    # -----------------------------------------------------------------------
    logger.debug(
        "Chat message accepted for user %s (conversation_id=%s)",
        current_user.sub,
        body.conversation_id,
    )

    return ChatMessageResponse(
        message="Message received. AI processing not yet wired (see task 28).",
        conversation_id=body.conversation_id,
    )
