# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services
# File    : suggestions_service.py
# Purpose : Business logic for context-aware AI suggestions
#
# Architecture Layer : Service
# Pattern Used       : Service Layer (Business Logic)
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Suggestions service — context-aware AI suggestion generation.

This module provides the ``SuggestionsService`` class which:

1. Checks user privacy mode — returns an empty list when enabled (Req 33.7).
2. Builds a screen-type-specific prompt for the AI orchestrator.
3. Calls ``AIOrchestrator.complete()`` wrapped in a 3-second timeout (Req 33.6).
4. Parses the AI JSON response into up to 3 ``ContextSuggestionResponse`` objects.
5. Returns silently on any error (timeout, JSON parse failure, etc.) — always
   HTTP 200 to the client.

Requirements: 33.1, 33.2, 33.6, 33.7
"""

from __future__ import annotations

import asyncio
import json
import logging
import uuid

from sqlalchemy.ext.asyncio import AsyncSession

from app.repositories.user_repository import UserRepository
from app.schemas.suggestions import (
    ContextSuggestionResponse,
    ScreenContextPayload,
    ScreenType,
    SuggestionType,
)
from app.services.ai_orchestrator import AIOrchestrator, LLMProvider

logger = logging.getLogger(__name__)

# Maximum number of suggestions to return (Requirement 33.2).
_MAX_SUGGESTIONS = 3

# Timeout in seconds for the AI completion call (Requirement 33.6).
_AI_TIMEOUT_SECONDS = 3.0

# Default max tokens for suggestion completions — small since we expect a
# compact JSON array response.
_MAX_TOKENS = 512


# ---------------------------------------------------------------------------
# Prompt template helpers
# ---------------------------------------------------------------------------


def _build_notes_prompt(payload: ScreenContextPayload) -> str:
    """Build the prompt for a notes-screen context request."""
    note_content = payload.note_content or ""
    return (
        "You are helping a user with their note. Based on the following note content, "
        "suggest 1-3 actionable AI suggestions from this list: "
        "summarize, expand, add_action_items. "
        "Return ONLY a JSON array like:\n"
        '[{"type": "summarize", "display_text": "Summarize this note", '
        '"pre_fill_text": "Please summarize this note concisely."}]\n'
        f"Note content (first 500 chars): {note_content}"
    )


def _build_calendar_prompt(payload: ScreenContextPayload) -> str:
    """Build the prompt for a calendar-screen context request."""
    event_title = payload.event_title or "Untitled Event"
    event_datetime = (
        str(payload.event_datetime) if payload.event_datetime else "Unknown time"
    )
    attendees_str = (
        ", ".join(payload.attendees) if payload.attendees else "No attendees listed"
    )
    return (
        "You are helping a user prepare for a calendar event. "
        "Suggest 1-3 helpful AI actions from: draft_agenda, prep_questions, lookup_attendees. "
        "Return ONLY a JSON array like:\n"
        '[{"type": "draft_agenda", "display_text": "Draft agenda", '
        '"pre_fill_text": "Please draft an agenda for this meeting."}]\n'
        f"Event: {event_title} at {event_datetime}. Attendees: {attendees_str}"
    )


def _build_chat_prompt(payload: ScreenContextPayload) -> str:
    """Build the prompt for a chat-screen context request."""
    age = payload.last_message_age_seconds or 0
    last_message = payload.last_message_content or ""
    topic = payload.conversation_title or "our previous discussion"
    pre_fill = f"Let's continue our discussion about {topic}."
    example = (
        '[{"type": "continue_conversation", "display_text": "Continue this conversation", '
        '"pre_fill_text": "' + pre_fill + '"}]'
    )
    return (
        f"The user has a conversation that has been idle for {age} seconds. "
        "Suggest 1 helpful continuation from: continue_conversation. "
        "Return ONLY a JSON array like:\n"
        + example
        + f"\nLast message: {last_message}. Conversation: {topic}"
    )


def _build_prompt(payload: ScreenContextPayload) -> str:
    """Dispatch to the correct prompt template based on screen type."""
    if payload.screen_type == ScreenType.notes:
        return _build_notes_prompt(payload)
    if payload.screen_type == ScreenType.calendar:
        return _build_calendar_prompt(payload)
    # chat
    return _build_chat_prompt(payload)


# ---------------------------------------------------------------------------
# Response parsing
# ---------------------------------------------------------------------------


def _parse_suggestions(
    raw_text: str,
    screen_type: ScreenType,
) -> list[ContextSuggestionResponse]:
    """Parse a JSON array string returned by the AI into suggestion objects.

    Each JSON object must contain ``type``, ``display_text``, and
    ``pre_fill_text`` fields. Unknown types are skipped silently.

    Args:
        raw_text: The raw text from ``CompletionResult.text``.
        screen_type: The screen type to set on each suggestion.

    Returns:
        List of up to ``_MAX_SUGGESTIONS`` suggestions. Empty list on any
        parse error.
    """
    try:
        data = json.loads(raw_text.strip())
    except (json.JSONDecodeError, ValueError) as exc:
        logger.debug("Failed to parse AI suggestion response as JSON: %s", exc)
        return []

    if not isinstance(data, list):
        logger.debug(
            "AI suggestion response is not a JSON array; got type=%s",
            type(data).__name__,
        )
        return []

    suggestions: list[ContextSuggestionResponse] = []
    for item in data:
        if not isinstance(item, dict):
            continue
        try:
            suggestion_type = SuggestionType(item.get("type", ""))
        except ValueError:
            logger.debug("Skipping unknown suggestion type: %r", item.get("type"))
            continue

        display_text = str(item.get("display_text", "")).strip()
        pre_fill_text = str(item.get("pre_fill_text", "")).strip()

        if not display_text or not pre_fill_text:
            continue

        suggestions.append(
            ContextSuggestionResponse(
                id=str(uuid.uuid4()),
                type=suggestion_type,
                display_text=display_text,
                pre_fill_text=pre_fill_text,
                target_screen_type=screen_type.value,
            )
        )

        if len(suggestions) >= _MAX_SUGGESTIONS:
            break

    return suggestions


# ---------------------------------------------------------------------------
# Service class
# ---------------------------------------------------------------------------


class SuggestionsService:
    """Business logic layer for generating context-aware AI suggestions.

    This service is stateless and safe to share across requests as a
    module-level singleton.

    Usage::

        service = SuggestionsService()
        suggestions = await service.get_context_suggestions(user_id, payload, db)

    Requirements: 33.1, 33.2, 33.6, 33.7
    """

    async def get_context_suggestions(
        self,
        user_id: uuid.UUID,
        payload: ScreenContextPayload,
        db: AsyncSession,
    ) -> list[ContextSuggestionResponse]:
        """Generate context-aware suggestions for the given screen context.

        Workflow:
        1. Check the user's ``privacy_mode`` — return ``[]`` if enabled (Req 33.7).
        2. Build a screen-specific prompt from the payload.
        3. Call ``AIOrchestrator.complete()`` with a 3-second timeout (Req 33.6).
           On timeout, return ``[]`` silently.
        4. Parse the AI JSON response into up to 3 suggestion objects (Req 33.2).
        5. Return the suggestions (or ``[]`` on any error).

        Args:
            user_id: UUID of the authenticated user.
            payload: The screen context payload from the client.
            db: SQLAlchemy async session.

        Returns:
            List of 0–3 :class:`~app.schemas.suggestions.ContextSuggestionResponse`
            objects.

        Requirements: 33.1, 33.2, 33.6, 33.7
        """
        # Step 1 — Privacy mode check (Requirement 33.7)
        user_repo = UserRepository(db)
        user = await user_repo.get_by_id(user_id)
        if user is None:
            logger.debug(
                "SuggestionsService: user %s not found; returning empty suggestions",
                user_id,
            )
            return []

        if user.privacy_mode:
            logger.debug(
                "SuggestionsService: privacy_mode=True for user %s; returning empty suggestions",
                user_id,
            )
            return []

        # Step 2 — Resolve LLM provider with fallback to openai
        try:
            provider = LLMProvider(payload.provider)
        except ValueError:
            logger.debug(
                "SuggestionsService: unknown provider %r; falling back to openai",
                payload.provider,
            )
            provider = LLMProvider.openai

        # Step 3 — Build prompt
        prompt = _build_prompt(payload)

        # Step 4 — Call AI with timeout (Requirement 33.6)
        orchestrator = AIOrchestrator(db=db)
        try:
            result = await asyncio.wait_for(
                orchestrator.complete(
                    prompt=prompt,
                    provider=provider,
                    max_tokens=_MAX_TOKENS,
                    user_id=str(user_id),
                ),
                timeout=_AI_TIMEOUT_SECONDS,
            )
        except asyncio.TimeoutError:
            logger.debug(
                "SuggestionsService: AI completion timed out after %.1fs for user %s; "
                "returning empty suggestions",
                _AI_TIMEOUT_SECONDS,
                user_id,
            )
            return []
        except Exception as exc:
            logger.debug(
                "SuggestionsService: AI completion failed for user %s: %s; "
                "returning empty suggestions",
                user_id,
                exc,
            )
            return []

        # Step 5 — Parse and return suggestions (capped at _MAX_SUGGESTIONS)
        return _parse_suggestions(result.text, payload.screen_type)
