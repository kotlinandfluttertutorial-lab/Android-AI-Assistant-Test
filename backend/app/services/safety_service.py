# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services
# File    : safety_service.py
# Purpose : Business logic for the safety domain
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

"""Safety service — centralised input/output safety enforcement.

This module provides two complementary classes:

1. ``SafetyService`` — applies safety filters to every LLM response before
   it is delivered to the user.  Harmful patterns are redacted in-place; if
   a pattern cannot be redacted, ``SafetyFilterError`` is raised so the caller
   can block the entire response (Requirement 25.3, Property 14).

2. ``InjectionDetector`` — inspects every user input for known prompt injection
   patterns before the request is forwarded to the LLM provider.  On detection
   it writes an audit log entry (user ID + SHA-256 hash of the sanitised input)
   and raises ``PromptInjectionError`` so the caller can return HTTP 400 with
   ``PROMPT_INJECTION_DETECTED`` (Requirement 9.6, 25.4, Property 13).

Both classes delegate pattern matching to the module-level helpers that are
already used by ``AIOrchestrator`` (``_detect_prompt_injection_static`` and
``_apply_safety_filters_static``), keeping the matching logic in one place.

Requirements: 9.6, 25.3, 25.4
"""

from __future__ import annotations

import hashlib
import logging
import re
import uuid

from sqlalchemy.ext.asyncio import AsyncSession

from app.models.audit_log import AuditLog

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Custom exception classes
# ---------------------------------------------------------------------------


class SafetyFilterError(Exception):
    """Raised when the safety filter cannot redact harmful content.

    The caller must block the entire LLM response when this exception is
    raised — delivering the unredacted content is not acceptable.

    Requirements: 25.3
    """


class PromptInjectionError(Exception):
    """Raised when a prompt injection attempt is detected in user input.

    The caller must reject the request with HTTP 400 and the error code
    ``PROMPT_INJECTION_DETECTED``.

    Requirements: 9.6, 25.4
    """


# ---------------------------------------------------------------------------
# Pattern definitions (single source of truth shared with AIOrchestrator)
# ---------------------------------------------------------------------------

# Known harmful output patterns to strip from LLM responses.
# Mirrors ``_HARMFUL_OUTPUT_PATTERNS`` in ``ai_orchestrator.py``; both must
# be kept in sync.  A production deployment should replace these with a
# dedicated content-moderation API call.
_HARMFUL_OUTPUT_PATTERNS: list[re.Pattern[str]] = [
    re.compile(r"<script\b[^>]*>.*?</script>", re.IGNORECASE | re.DOTALL),
    re.compile(r"javascript\s*:", re.IGNORECASE),
]

# Prompt injection patterns (mirrors ``_INJECTION_PATTERNS`` in ai_orchestrator.py).
_INJECTION_PATTERNS: list[re.Pattern[str]] = [
    re.compile(pattern, re.IGNORECASE)
    for pattern in [
        r"ignore\s+(all\s+)?previous\s+instructions?",
        r"disregard\s+(all\s+)?previous",
        r"forget\s+(all\s+)?previous\s+instructions?",
        r"forget\s+your\s+(instructions?|training|rules?)",
        r"system\s*:\s*",
        r"you\s+are\s+now\s+",
        r"new\s+system\s+prompt",
        r"override\s+(the\s+)?(system|prompt|instructions?)",
        r"your\s+(new\s+)?(true|real|actual)\s+(identity|self|persona|role)",
        r"pretend\s+(to\s+be|you\s+are)\s+",
        r"act\s+as\s+(if\s+you\s+(are|were)\s+)?(?:a\s+)?(?!helpful|an?\s+AI)",
        r"\[SYSTEM\]",
        r"<system>",
        r"</?(inst|s|INST)>",
    ]
]


# ---------------------------------------------------------------------------
# SafetyService
# ---------------------------------------------------------------------------


class SafetyService:
    """Apply safety filters to LLM provider responses.

    The ``filter_response`` method redacts known harmful patterns from the
    provided text.  If redaction succeeds, the sanitised text is returned.
    If any pattern still matches after redaction (i.e. the redaction itself
    failed), ``SafetyFilterError`` is raised so the caller can block the
    entire response.

    Usage example::

        safety = SafetyService()
        try:
            safe_text = safety.filter_response(raw_llm_output)
        except SafetyFilterError:
            # block entire response — do not deliver to user
            raise

    Requirements: 25.3
    """

    def filter_response(self, text: str) -> str:
        """Apply safety redactions to *text* and return the cleaned version.

        Each known harmful pattern is replaced with ``[content removed]``.
        After all substitutions the text is re-scanned; if any harmful pattern
        still matches, redaction has failed and ``SafetyFilterError`` is raised.

        Args:
            text: The raw LLM provider response to sanitise.

        Returns:
            The sanitised text with all harmful patterns replaced.

        Raises:
            SafetyFilterError: When any harmful pattern remains after
                redaction, indicating a failure to fully sanitise the content.

        Requirements: 25.3
        """
        sanitised = text
        for pattern in _HARMFUL_OUTPUT_PATTERNS:
            sanitised = pattern.sub("[content removed]", sanitised)

        # Verify that all harmful patterns have been fully removed.
        for pattern in _HARMFUL_OUTPUT_PATTERNS:
            if pattern.search(sanitised):
                logger.error(
                    "Safety filter failed to redact harmful content. "
                    "Pattern %r still matches after substitution.",
                    pattern.pattern,
                )
                raise SafetyFilterError(
                    "Safety filter failed to redact harmful content; "
                    "blocking entire response."
                )

        return sanitised


# ---------------------------------------------------------------------------
# InjectionDetector
# ---------------------------------------------------------------------------


class InjectionDetector:
    """Detect prompt injection patterns in user input.

    The ``check_input`` method inspects *text* against the known injection
    pattern list.  When a match is found it:

    1. Computes the SHA-256 hash of the sanitised input (all injection-matching
       spans replaced with ``[redacted]``) so that audit log entries never
       contain raw injection payloads.
    2. Writes an ``AuditLog`` row with the event type ``prompt_injection``,
       the user ID, and the hash.
    3. Raises ``PromptInjectionError`` so the caller can return HTTP 400 with
       the error code ``PROMPT_INJECTION_DETECTED``.

    Usage example (in a FastAPI route handler)::

        detector = InjectionDetector()
        try:
            await detector.check_input(body.content, user_id=current_user.sub, db=db)
        except PromptInjectionError:
            raise HTTPException(
                status_code=400,
                detail={"error": {"code": "PROMPT_INJECTION_DETECTED"}},
            )

    Requirements: 9.6, 25.4
    """

    async def check_input(
        self,
        text: str,
        user_id: str,
        db: AsyncSession,
    ) -> None:
        """Check *text* for prompt injection patterns.

        If no pattern matches, this method returns ``None`` and the caller
        may proceed normally.

        If a pattern matches:
        - A SHA-256 hash of the sanitised input is computed.
        - An ``AuditLog`` row is written to *db*.
        - ``PromptInjectionError`` is raised.

        Args:
            text: The raw user input to inspect.
            user_id: The UUID string of the requesting user (used in the
                audit log and for log messages).
            db: SQLAlchemy async session.  The caller is responsible for
                committing the session after this method raises (or use a
                savepoint).

        Raises:
            PromptInjectionError: When any injection pattern is detected.

        Requirements: 9.6, 25.4
        """
        matched_pattern: re.Pattern[str] | None = None
        for pattern in _INJECTION_PATTERNS:
            if pattern.search(text):
                matched_pattern = pattern
                break

        if matched_pattern is None:
            return  # clean input — nothing to do

        logger.warning(
            "Prompt injection detected for user %s. Pattern: %r",
            user_id,
            matched_pattern.pattern,
        )

        # Build the sanitised input: replace all injection-matching spans so
        # the raw payload is never stored in the audit log.
        sanitised_text = text
        for pattern in _INJECTION_PATTERNS:
            sanitised_text = pattern.sub("[redacted]", sanitised_text)

        # Compute SHA-256 hash of the sanitised text (hex digest).
        input_hash = hashlib.sha256(sanitised_text.encode("utf-8")).hexdigest()

        # Parse user_id to UUID; fall back to None on failure so the audit
        # record is still written even when the ID is malformed.
        user_uuid: uuid.UUID | None = None
        try:
            user_uuid = uuid.UUID(user_id)
        except (ValueError, AttributeError):
            logger.warning(
                "Could not parse user_id %r as UUID for audit log entry.", user_id
            )

        # Write the audit log entry.
        audit_entry = AuditLog(
            user_id=user_uuid,
            event_type="prompt_injection",
            ip_address="",  # IP address is not available at service layer;
            # the API layer may enrich this if needed.
            user_agent="",
            metadata_={
                "input_hash": input_hash,
                "matched_pattern": matched_pattern.pattern,
            },
        )
        db.add(audit_entry)
        await db.flush()

        raise PromptInjectionError(
            f"Prompt injection pattern detected for user {user_id}. Request blocked."
        )
