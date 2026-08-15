# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/conversations
# File    : router.py
# Purpose : FastAPI router defining all HTTP endpoints for the conversations domain
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

"""Conversations and messages REST endpoints.

Provides full CRUD for conversations plus message listing, export, and
message regeneration.

Security: all endpoints are protected at router level via ``get_current_user``.
Every database query is scoped to ``current_user.sub`` so users cannot access
each other's conversations.

Requirements: 11.3, 11.4, 11.6, 2.6, 2.7
"""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import Response
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.message import MessageRole
from app.repositories.conversation_repository import ConversationRepository
from app.repositories.message_repository import MessageRepository
from app.schemas.conversations import (
    ConversationCreate,
    ConversationListResponse,
    ConversationResponse,
    ConversationUpdate,
    MessageListResponse,
    MessageResponse,
    RegenerateResponse,
)
from app.security.dependencies import TokenPayload, get_current_user

router = APIRouter(
    prefix="/conversations",
    tags=["conversations"],
    dependencies=[Depends(get_current_user)],
)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _current_user_id(current_user: TokenPayload) -> uuid.UUID:
    """Convert the string ``sub`` claim to a UUID."""
    return uuid.UUID(current_user.sub)


async def _get_owned_conversation(
    conversation_id: uuid.UUID,
    current_user: TokenPayload,
    db: AsyncSession,
):
    """Fetch a conversation and verify it belongs to the current user.

    Returns the :class:`~app.models.conversation.Conversation` on success.
    Raises HTTP 404 when the conversation is not found, soft-deleted, or owned
    by a different user.
    """
    repo = ConversationRepository(db)
    conversation = await repo.get_by_id(conversation_id)
    if (
        conversation is None
        or conversation.is_deleted
        or conversation.user_id != _current_user_id(current_user)
    ):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Conversation not found.",
        )
    return conversation


# ---------------------------------------------------------------------------
# Conversation endpoints
# ---------------------------------------------------------------------------


@router.get(
    "",
    response_model=ConversationListResponse,
    summary="List conversations (paginated)",
    description=(
        "Returns a paginated list of non-deleted conversations for the "
        "authenticated user, sorted by updated_at DESC."
    ),
)
async def list_conversations(
    page: int = Query(default=1, ge=1, description="1-indexed page number."),
    page_size: int = Query(default=20, ge=1, le=100, description="Items per page."),
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> ConversationListResponse:
    """Return paginated conversations for the current user.

    Requirements: 11.3
    """
    user_id = _current_user_id(current_user)
    repo = ConversationRepository(db)

    conversations = await repo.list_by_user(user_id, page=page, page_size=page_size)
    total = await repo.count_by_user(user_id)

    return ConversationListResponse(
        items=[ConversationResponse.model_validate(c) for c in conversations],
        total=total,
        page=page,
        page_size=page_size,
    )


@router.post(
    "",
    response_model=ConversationResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Create a conversation",
)
async def create_conversation(
    body: ConversationCreate,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> ConversationResponse:
    """Create a new conversation owned by the current user.

    Requirements: 11.4
    """
    user_id = _current_user_id(current_user)
    repo = ConversationRepository(db)

    conversation = await repo.create(
        user_id=user_id,
        title=body.title,
        provider=body.provider,
    )
    return ConversationResponse.model_validate(conversation)


@router.get(
    "/{conversation_id}",
    response_model=ConversationResponse,
    summary="Get a conversation by ID",
)
async def get_conversation(
    conversation_id: uuid.UUID,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> ConversationResponse:
    """Return a single conversation.

    Returns HTTP 404 if not found, soft-deleted, or owned by another user.

    Requirements: 11.3
    """
    conversation = await _get_owned_conversation(conversation_id, current_user, db)
    return ConversationResponse.model_validate(conversation)


@router.patch(
    "/{conversation_id}",
    response_model=ConversationResponse,
    summary="Update a conversation (rename / pin)",
)
async def update_conversation(
    conversation_id: uuid.UUID,
    body: ConversationUpdate,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> ConversationResponse:
    """Rename and/or pin/unpin a conversation.

    Requirements: 11.4
    """
    # Verify ownership first (raises 404 on failure)
    await _get_owned_conversation(conversation_id, current_user, db)

    repo = ConversationRepository(db)
    updates: dict = {}
    if body.title is not None:
        updates["title"] = body.title
    if body.is_pinned is not None:
        updates["is_pinned"] = body.is_pinned

    conversation = await repo.update(conversation_id, **updates)
    if conversation is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Conversation not found.",
        )
    return ConversationResponse.model_validate(conversation)


@router.delete(
    "/{conversation_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    response_model=None,
    summary="Soft-delete a conversation",
)
async def delete_conversation(
    conversation_id: uuid.UUID,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> None:
    """Soft-delete a conversation by setting ``is_deleted = True``.

    The row is retained in the database; the Android client is responsible for
    removing it from its local cache.

    Requirements: 11.6
    """
    # Verify ownership first (raises 404 on failure)
    await _get_owned_conversation(conversation_id, current_user, db)

    repo = ConversationRepository(db)
    await repo.soft_delete(conversation_id)


# ---------------------------------------------------------------------------
# Message endpoints
# ---------------------------------------------------------------------------


@router.get(
    "/{conversation_id}/messages",
    response_model=MessageListResponse,
    summary="List messages in a conversation (paginated, oldest-first)",
)
async def list_messages(
    conversation_id: uuid.UUID,
    page: int = Query(default=1, ge=1, description="1-indexed page number."),
    page_size: int = Query(default=20, ge=1, le=100, description="Items per page."),
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> MessageListResponse:
    """Return paginated messages for a conversation (oldest-first).

    Requirements: 2.6
    """
    # Verify ownership (raises 404 if not found / not owned)
    await _get_owned_conversation(conversation_id, current_user, db)

    msg_repo = MessageRepository(db)
    offset = (page - 1) * page_size
    messages = await msg_repo.get_paginated_by_conversation_id(
        conversation_id, offset=offset, limit=page_size
    )
    total = await msg_repo.count_by_conversation_id(conversation_id)

    return MessageListResponse(
        items=[MessageResponse.model_validate(m) for m in messages],
        total=total,
        page=page,
        page_size=page_size,
    )


# ---------------------------------------------------------------------------
# Export endpoint
# ---------------------------------------------------------------------------


@router.post(
    "/{conversation_id}/export",
    summary="Export a conversation as Markdown or PDF",
)
async def export_conversation(
    conversation_id: uuid.UUID,
    format: str = Query(
        default="markdown",
        description="Export format: 'markdown' or 'pdf'.",
    ),
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> Response:
    """Export a conversation.

    - ``format=markdown``: returns plain text with ``Content-Type: text/markdown``
      and ``Content-Disposition: attachment``.
    - ``format=pdf``: returns a stub plain-text response noting that PDF export
      requires backend task 36.

    Requirements: 2.7
    """
    if format not in ("markdown", "pdf"):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Invalid format. Must be 'markdown' or 'pdf'.",
        )

    conversation = await _get_owned_conversation(conversation_id, current_user, db)
    msg_repo = MessageRepository(db)
    # Fetch all messages (no pagination limit for export)
    messages = await msg_repo.get_by_conversation_id(conversation_id)

    if format == "pdf":
        content = (
            "PDF export requires backend task 36.\n"
            f"Conversation: {conversation.title}\n"
            f"Messages: {len(messages)}\n"
        )
        return Response(
            content=content.encode("utf-8"),
            media_type="application/pdf",
            headers={"Content-Disposition": (f'attachment; filename="{conversation_id}.pdf"')},
        )

    # Markdown export
    lines: list[str] = [f"# {conversation.title}\n"]
    for msg in messages:
        role = msg.role.value if hasattr(msg.role, "value") else str(msg.role)
        lines.append(f"**{role}**: {msg.content}\n")

    markdown_content = "\n".join(lines)
    return Response(
        content=markdown_content.encode("utf-8"),
        media_type="text/markdown",
        headers={"Content-Disposition": (f'attachment; filename="{conversation_id}.md"')},
    )


# ---------------------------------------------------------------------------
# Message regeneration endpoint
# ---------------------------------------------------------------------------


@router.post(
    "/{conversation_id}/messages/{message_id}/regenerate",
    response_model=RegenerateResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Regenerate an AI response",
)
async def regenerate_message(
    conversation_id: uuid.UUID,
    message_id: uuid.UUID,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> RegenerateResponse:
    """Produce a new AI response appended as an alternative to the existing message.

    The AI call is stubbed for this task; full orchestration is wired in task 29.
    A new ``Message`` row with ``role=assistant`` is created and returned.

    Requirements: 2.7
    """
    # Verify ownership (raises 404 if not found / not owned)
    await _get_owned_conversation(conversation_id, current_user, db)

    msg_repo = MessageRepository(db)
    new_message = await msg_repo.create(
        conversation_id=conversation_id,
        role=MessageRole.assistant,
        content="[Regenerated response - AI wiring pending task 29]",
        input_tokens=0,
        output_tokens=0,
        provider="",
    )

    return RegenerateResponse(
        message=MessageResponse.model_validate(new_message),
        note="AI response regeneration stubbed. Full wiring pending task 29.",
    )
