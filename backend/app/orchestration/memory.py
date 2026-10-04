# ============================================================
# Android AI Assistant — Backend
# Module  : orchestration
# File    : memory.py
# Purpose : Short-term conversation memory for the agent loop.
#
# Design rules:
#   - ConversationMemoryBuffer is pure in-process (no I/O).
#   - AgentMemoryAdapter wraps MemoryService for long-term persistence
#     and context injection; caller supplies the MemoryService.
#   - No credentials or PII are hardcoded; user IDs are UUIDs passed by caller.
#   - All long-term operations are async; short-term buffer is sync.
# ============================================================
"""Conversation memory for the single-agent orchestration loop.

Two complementary layers are provided:

**Short-term** — :class:`ConversationMemoryBuffer`
    An in-process fixed-size ring buffer of recent conversation turns.
    No database or network calls.  Designed to keep the current session's
    dialogue context immediately available without the latency of a vector
    search.  Once the buffer is full, the oldest turn is silently evicted
    (FIFO).

**Long-term** — :class:`AgentMemoryAdapter`
    A thin async wrapper around the existing
    :class:`~app.services.memory_service.MemoryService`.  Persists important
    facts from the conversation to ChromaDB / PostgreSQL and retrieves
    semantically relevant memories when building the next LLM prompt.

Both classes are intentionally independent so callers can use either or both
depending on the deployment context.
"""

from __future__ import annotations

import logging
import uuid
from collections import deque
from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.services.memory_service import MemoryEntry, MemoryService

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# ConversationTurn — one unit stored in the short-term buffer
# ---------------------------------------------------------------------------

_DEFAULT_BUFFER_SIZE: int = 20  # keep the last 20 turns by default


@dataclass(frozen=True)
class ConversationTurn:
    """One turn of the conversation held in short-term memory.

    Attributes:
        role:    ``"user"``, ``"assistant"``, or ``"tool"``.
        content: Text of the turn (may be a tool call summary).
        step:    Execution-loop step index this turn originated from.
    """

    role: str
    content: str
    step: int = 0


# ---------------------------------------------------------------------------
# ConversationMemoryBuffer — in-process short-term ring buffer
# ---------------------------------------------------------------------------


class ConversationMemoryBuffer:
    """Fixed-size in-process ring buffer of recent conversation turns.

    When the buffer is full, the oldest turn is evicted automatically (FIFO).
    The buffer is intentionally not thread-safe; the agent execution loop runs
    in a single coroutine task per request so no locking is required.

    Args:
        max_turns: Maximum number of turns to retain.  Must be ≥ 1.
                   Defaults to :data:`_DEFAULT_BUFFER_SIZE` (20).

    Example::

        buf = ConversationMemoryBuffer(max_turns=10)
        buf.add_turn("user", "What is the capital of France?", step=0)
        buf.add_turn("assistant", "Paris.", step=1)
        context = buf.format_as_text()
    """

    def __init__(self, max_turns: int = _DEFAULT_BUFFER_SIZE) -> None:
        if max_turns < 1:
            raise ValueError(f"ConversationMemoryBuffer.max_turns must be ≥ 1, got {max_turns}.")
        self._max_turns = max_turns
        self._turns: deque[ConversationTurn] = deque(maxlen=max_turns)

    # ---- mutation --------------------------------------------------------

    def add_turn(self, role: str, content: str, step: int = 0) -> None:
        """Append a new turn; oldest turn is evicted when buffer is full.

        Args:
            role:    Speaker: ``"user"``, ``"assistant"``, or ``"tool"``.
            content: Text of the turn.
            step:    Execution-loop step index (informational).
        """
        self._turns.append(ConversationTurn(role=role, content=content, step=step))

    def clear(self) -> None:
        """Remove all turns from the buffer."""
        self._turns.clear()

    # ---- read-only -------------------------------------------------------

    @property
    def turns(self) -> list[ConversationTurn]:
        """Return a snapshot of all turns in insertion order (oldest first)."""
        return list(self._turns)

    @property
    def max_turns(self) -> int:
        """Maximum number of turns the buffer retains."""
        return self._max_turns

    def __len__(self) -> int:
        return len(self._turns)

    def is_empty(self) -> bool:
        """Return ``True`` when no turns have been recorded yet."""
        return len(self._turns) == 0

    # ---- formatting ------------------------------------------------------

    def format_as_text(self, separator: str = "\n") -> str:
        """Render the buffer as a plain-text conversation transcript.

        Each turn is formatted as ``"<ROLE>: <content>"``.  Turns are joined
        by *separator* (default newline).

        Args:
            separator: String inserted between turns.

        Returns:
            Multi-line string suitable for injecting into an LLM system prompt,
            or an empty string when the buffer is empty.
        """
        if self.is_empty():
            return ""
        return separator.join(f"{turn.role.upper()}: {turn.content}" for turn in self._turns)

    def format_as_messages(self) -> list[dict[str, str]]:
        """Render the buffer as a list of ``{"role": ..., "content": ...}`` dicts.

        This matches the OpenAI / Gemini chat message format so the list can be
        passed directly to an LLM client's ``messages`` parameter.

        Returns:
            List of message dicts in insertion order (oldest first).
        """
        return [{"role": turn.role, "content": turn.content} for turn in self._turns]


# ---------------------------------------------------------------------------
# AgentMemoryAdapter — long-term memory via MemoryService
# ---------------------------------------------------------------------------


class AgentMemoryAdapter:
    """Async adapter bridging loop and :class:`~app.services.memory_service.MemoryService`.

    Provides two operations the loop needs:

    * **store** — persist a conversation fact for future sessions.
    * **retrieve** — fetch semantically relevant memories to inject into the
      next LLM prompt.

    Both operations degrade gracefully: storage failures are logged but never
    re-raised (so a Redis/ChromaDB outage does not kill the agent run);
    retrieval failures return an empty list.

    Args:
        memory_service: Injected :class:`~app.services.memory_service.MemoryService`
                        instance (created per-request, holds the DB session).
        user_id:        UUID of the user whose memories are being managed.
        redis:          Optional async Redis client forwarded to
                        :meth:`~app.services.memory_service.MemoryService.store_memory`
                        for privacy-budget tracking.

    Example::

        adapter = AgentMemoryAdapter(memory_service=svc, user_id=uid)
        await adapter.store_fact("User prefers concise answers.", memory_type="preference")
        memories = await adapter.retrieve_relevant("summarise this document", top_k=3)
        for entry in memories:
            print(entry.content)
    """

    def __init__(
        self,
        memory_service: MemoryService,
        user_id: uuid.UUID,
        redis: object | None = None,
    ) -> None:
        self._svc = memory_service
        self._user_id = user_id
        self._redis = redis

    # ---- write -----------------------------------------------------------

    async def store_fact(
        self,
        content: str,
        memory_type: str = "fact",
    ) -> None:
        """Persist *content* as a long-term memory for the user.

        Failures are caught and logged; the caller is never interrupted by a
        storage outage.

        Args:
            content:     Text of the memory to store.
            memory_type: Classification string (``"fact"``, ``"preference"``,
                         ``"style"``).  Forwarded to
                         :meth:`~app.services.memory_service.MemoryService.store_memory`.
        """
        try:
            from app.models.memory import MemoryType  # local import avoids circular dep

            mem_type = MemoryType(memory_type)
        except (ImportError, ValueError):
            # Fall back to the default if the enum value is unrecognised
            try:
                from app.models.memory import MemoryType

                mem_type = MemoryType.fact
            except ImportError:
                logger.warning("AgentMemoryAdapter: could not import MemoryType; skipping store.")
                return

        try:
            await self._svc.store_memory(
                user_id=self._user_id,
                content=content,
                memory_type=mem_type,
                redis=self._redis,
            )
            logger.debug(
                "AgentMemoryAdapter: stored memory for user=%s type=%s len=%d",
                self._user_id,
                memory_type,
                len(content),
            )
        except Exception as exc:  # pylint: disable=broad-except
            logger.warning(
                "AgentMemoryAdapter: failed to store memory for user=%s: %s",
                self._user_id,
                exc,
            )

    # ---- read ------------------------------------------------------------

    async def retrieve_relevant(
        self,
        query: str,
        top_k: int = 3,
    ) -> list[MemoryEntry]:
        """Retrieve the most semantically relevant memories for *query*.

        Delegates to :meth:`~app.services.memory_service.MemoryService.get_relevant_memories`.
        Returns an empty list on any failure so prompt construction is never
        blocked by a ChromaDB outage.

        Args:
            query: The current user input or agent reasoning step to match against.
            top_k: Number of memories to return (default 3).

        Returns:
            List of :class:`~app.services.memory_service.MemoryEntry` objects
            sorted by descending relevance score.
        """
        try:
            entries = await self._svc.get_relevant_memories(
                user_id=self._user_id,
                query=query,
                top_k=top_k,
            )
            logger.debug(
                "AgentMemoryAdapter: retrieved %d memories for user=%s",
                len(entries),
                self._user_id,
            )
            return entries
        except Exception as exc:  # pylint: disable=broad-except
            logger.warning(
                "AgentMemoryAdapter: memory retrieval failed for user=%s: %s",
                self._user_id,
                exc,
            )
            return []

    def format_memories_as_text(
        self,
        entries: list[MemoryEntry],
        header: str = "Relevant memories:",
    ) -> str:
        """Format a list of :class:`~app.services.memory_service.MemoryEntry` for prompt injection.

        Args:
            entries: List returned by :meth:`retrieve_relevant`.
            header:  Optional prefix line.

        Returns:
            Multi-line string ready to inject into the LLM system prompt,
            or an empty string when *entries* is empty.
        """
        if not entries:
            return ""
        lines = [header]
        for entry in entries:
            lines.append(f"- [{entry.memory_type}] {entry.content}")
        return "\n".join(lines)
