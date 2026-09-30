# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : agents
# File    : code_agent.py
# Purpose : CodeAgent — wraps the existing /code/analyze pipeline
#           (AIOrchestrator + InjectionDetector + prompt builders)
#           without modifying any existing code.
#
# Architecture Layer : Agent Core (Phase 4)
# Pattern Used       : Adapter (implements Agent)
#
# Key Concepts:
#   - DOES NOT modify code/router.py or any existing code
#   - Reuses the same prompt-building logic the router uses
#   - Supports all 6 CodeAgentAction values; new actions (generate,
#     refactor, review) map to the nearest existing backend action
#     until the backend is extended in a future phase
#   - Provider-agnostic: uses AIOrchestrator which selects provider
#     from settings (or request.provider hint)
#
# Request metadata keys:
#   "code_action"  — action string (default "explain")
#   "language_id"  — language string (default "kotlin")
# ============================================================

"""CodeAgent — wraps existing /code/analyze pipeline as an Agent."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncIterator

from app.agents.base import Agent
from app.agents.models import (
    AgentCapability,
    AgentCompletedEvent,
    AgentError,
    AgentEvent,
    AgentExecution,
    AgentFailedEvent,
    AgentRequest,
    AgentResult,
    AgentStartedEvent,
    AgentStatus,
    AgentStatusChangedEvent,
    AgentTokenEvent,
)

logger = logging.getLogger(__name__)

CODE_AGENT_NAME = "code-analysis"

# Timeouts per action (matches router.py values)
_ACTION_TIMEOUTS: dict[str, float] = {
    "explain": 30.0,
    "fix_bug": 30.0,
    "generate_tests": 45.0,
    "generate": 45.0,
    "refactor": 30.0,
    "review": 30.0,
}

_DEFAULT_MAX_TOKENS: dict[str, int] = {
    "explain": 2048,
    "fix_bug": 2048,
    "generate_tests": 3072,
    "generate": 3072,
    "refactor": 2048,
    "review": 2048,
}

# Map new Phase 4 actions to existing backend action strings
_ACTION_BACKEND_MAP: dict[str, str] = {
    "generate": "explain",  # uses AIOrchestrator.complete with custom prompt
    "refactor": "fix_bug",
    "review": "explain",
}

VALID_ACTIONS = frozenset(_ACTION_TIMEOUTS.keys())
VALID_LANGUAGES = frozenset({"kotlin", "java", "python", "javascript", "cpp", "sql"})

# Lazy imports — guarded to keep module importable without pgvector
try:
    from app.services.ai_orchestrator import AIOrchestrator as _AIOrchestrator  # type: ignore
except Exception:  # pragma: no cover
    _AIOrchestrator = None  # type: ignore

try:
    from app.services.safety_service import InjectionDetector as _InjectionDetector  # type: ignore
except Exception:  # pragma: no cover
    _InjectionDetector = None  # type: ignore

try:
    from app.database import AsyncSessionLocal as _AsyncSessionLocal  # type: ignore
except Exception:  # pragma: no cover
    _AsyncSessionLocal = None  # type: ignore

AIOrchestrator = _AIOrchestrator
InjectionDetector = _InjectionDetector
AsyncSessionLocal = _AsyncSessionLocal


class CodeAgent(Agent):
    """Agent that performs code analysis/generation via the existing AIOrchestrator.

    Supports all 6 CodeAgentAction values:
      explain, fix_bug, generate_tests, generate, refactor, review
    """

    @property
    def name(self) -> str:
        return CODE_AGENT_NAME

    @property
    def description(self) -> str:
        return "Code analysis, generation, debugging, refactoring, review, and test generation."

    @property
    def capabilities(self) -> frozenset[AgentCapability]:
        return frozenset(
            {
                AgentCapability.CODE_ANALYSIS,
                AgentCapability.TEXT_GENERATION,
                AgentCapability.STREAMING,
            }
        )

    async def execute(
        self,
        request: AgentRequest,
        execution: AgentExecution,
    ) -> AsyncIterator[AgentEvent]:
        yield AgentStartedEvent(execution_id=execution.execution_id, agent_name=self.name)
        yield AgentStatusChangedEvent(
            execution_id=execution.execution_id, status=AgentStatus.RUNNING
        )

        if AsyncSessionLocal is None or AIOrchestrator is None:
            yield self._failed(execution, request, "DB_UNAVAILABLE", "Database not available.")
            return

        metadata = request.metadata or {}
        action = (metadata.get("code_action") or "explain").strip().lower()
        language_id = (metadata.get("language_id") or "kotlin").strip().lower()

        if action not in VALID_ACTIONS:
            logger.warning("CodeAgent: unknown action %r — defaulting to explain", action)
            action = "explain"
        if language_id not in VALID_LANGUAGES:
            logger.warning("CodeAgent: unknown language %r — defaulting to kotlin", language_id)
            language_id = "kotlin"

        # Map new actions to backend-supported action for prompt selection (unused directly
        # but kept for future router.py integration)
        _backend_action = _ACTION_BACKEND_MAP.get(action, action)
        timeout = _ACTION_TIMEOUTS.get(action, 30.0)
        max_tokens = _DEFAULT_MAX_TOKENS.get(action, 2048)

        # Prompt injection detection
        if InjectionDetector is not None:
            try:
                async with AsyncSessionLocal() as db:
                    detector = InjectionDetector()
                    await detector.check_input(
                        text=request.input,
                        user_id=request.user_id,
                        db=db,
                    )
            except Exception as exc:
                exc_type = type(exc).__name__
                if "injection" in exc_type.lower() or "prompt" in str(exc).lower():
                    yield self._failed(execution, request, "PROMPT_INJECTION_DETECTED", str(exc))
                    return
                # Other errors from injection check → log and continue

        # Build prompt (same logic as the router)
        prompt = _build_prompt(request.input, language_id, action)

        try:
            async with AsyncSessionLocal() as db:
                orchestrator = AIOrchestrator(db=db)
                result_text = await asyncio.wait_for(
                    orchestrator.complete(
                        prompt=prompt,
                        provider=_resolve_provider(request),
                        max_tokens=max_tokens,
                        user_id=request.user_id,
                    ),
                    timeout=timeout,
                )
        except asyncio.TimeoutError:
            yield self._failed(
                execution, request, "TIMEOUT", f"Code {action} request timed out after {timeout}s."
            )
            return
        except Exception as exc:
            logger.exception("CodeAgent: unexpected error: %s", exc)
            yield self._failed(execution, request, "UNEXPECTED_ERROR", str(exc))
            return

        # Surface result as a single token + Completed event
        content: str = result_text if isinstance(result_text, str) else str(result_text)
        yield AgentTokenEvent(token=content)

        result = AgentResult(
            execution_id=execution.execution_id,
            request_id=request.request_id,
            agent_name=self.name,
            status=AgentStatus.COMPLETED,
            content=content,
            metadata={
                "language_id": language_id,
                "action": action,
                "original_code": request.input[:500],  # truncate for metadata
            },
        )
        yield AgentCompletedEvent(result=result)

    @staticmethod
    def _failed(
        execution: AgentExecution,
        request: AgentRequest,
        code: str,
        message: str,
    ) -> AgentFailedEvent:
        r = AgentResult(
            execution_id=execution.execution_id,
            request_id=request.request_id,
            agent_name=CODE_AGENT_NAME,
            status=AgentStatus.FAILED,
            error=AgentError(code=code, message=message),
        )
        return AgentFailedEvent(result=r)


def _resolve_provider(request: AgentRequest) -> str:
    provider = (request.provider or "").strip().lower()
    if not provider:
        provider = (request.metadata or {}).get("provider", "gemini")
    return provider or "gemini"


def _build_prompt(code: str, language_id: str, action: str) -> str:
    """Build the analysis prompt matching the existing router logic."""
    if action in ("explain", "generate", "review"):
        return (
            f"You are an expert {language_id} developer.\n\n"
            f"**Task:** {_action_instruction(action)}\n\n"
            f"```{language_id}\n{code}\n```\n\n"
            f"Respond in Markdown with clear sections."
        )
    elif action in ("fix_bug", "refactor"):
        return (
            f"You are an expert {language_id} developer.\n\n"
            f"**Task:** {_action_instruction(action)}\n\n"
            f"```{language_id}\n{code}\n```\n\n"
            "Return only the corrected/refactored code. "
            "Add inline comments on changed lines using `# FIX:` or `// FIX:`."
        )
    elif action == "generate_tests":
        return (
            f"You are an expert {language_id} developer specialising in testing.\n\n"
            f"**Task:** Generate a complete, runnable test file for the following code.\n\n"
            f"```{language_id}\n{code}\n```\n\n"
            "Return only the test file content. No explanations."
        )
    return f"Analyse the following {language_id} code:\n\n```{language_id}\n{code}\n```"


def _action_instruction(action: str) -> str:
    return {
        "explain": "Explain what this code does, how it works, and potential improvements.",
        "generate": "Generate code based on the description provided.",
        "review": "Review this code for issues, style, performance, and security.",
        "fix_bug": "Identify and fix bugs in this code.",
        "refactor": "Refactor this code for better readability and maintainability.",
    }.get(action, "Analyse this code.")
