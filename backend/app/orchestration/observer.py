# ============================================================
# Android AI Assistant — Backend
# Module  : orchestration
# File    : observer.py
# Purpose : ObservabilityTracker — records spans and emits structured
#           log events for every step of the orchestration loop.
#
# Design rules:
#   - Zero infrastructure imports (no DB, Redis, HTTP).
#   - Emits structured log records that can be consumed by Prometheus,
#     Loki, or any structured-log pipeline.
#   - Sensitive data (PII, tokens, credentials) must never appear in
#     log output — summaries are truncated and redacted.
# ============================================================
"""ObservabilityTracker — records and emits structured orchestration events."""

from __future__ import annotations

import logging
import time
from typing import Any, Callable

from app.orchestration.state import ExecutionSpan, OrchestrationState, RunStatus

logger = logging.getLogger(__name__)

# Maximum characters of output summary written to structured logs
_MAX_SUMMARY_CHARS = 200

# Callback type: receives the span as soon as it is recorded
SpanCallback = Callable[[ExecutionSpan], None]


class ObservabilityTracker:
    """Records :class:`~app.orchestration.state.ExecutionSpan` objects and
    emits structured log events for every step of the orchestration loop.

    All methods are synchronous and non-blocking.  No infrastructure calls
    are made here — downstream systems consume the structured log stream
    (Loki, Prometheus, etc.).

    Usage::

        tracker = ObservabilityTracker()

        with tracker.track_step(state, action_type="respond") as recorder:
            output = await dispatcher.dispatch(decision, user_id)
            recorder.set_output(output.output, tokens=output.tokens_used)
            recorder.set_success(output.success, output.error_message)
    """

    def __init__(
        self,
        extra_callbacks: list[SpanCallback] | None = None,
    ) -> None:
        self._callbacks: list[SpanCallback] = extra_callbacks or []

    def add_callback(self, cb: SpanCallback) -> None:
        """Register a callback invoked after each span is recorded."""
        self._callbacks.append(cb)

    def on_run_start(self, state: OrchestrationState) -> None:
        """Emit a structured log event when a run begins."""
        logger.info(
            "orchestration.run_start",
            extra={
                "run_id": state.run_id,
                "request_id": state.request_id,
                "user_id": _redact_uid(state.user_id),
                "agent_name": state.agent_name,
            },
        )

    def on_run_end(self, state: OrchestrationState, elapsed_ms: int) -> None:
        """Emit a structured log event when a run terminates."""
        logger.info(
            "orchestration.run_end",
            extra={
                "run_id": state.run_id,
                "request_id": state.request_id,
                "agent_name": state.agent_name,
                "status": state.status.value,
                "step_count": state.step_count,
                "tool_call_count": state.tool_call_count,
                "total_tokens": state.total_tokens,
                "elapsed_ms": elapsed_ms,
                "error_msg": state.error_message or None,
            },
        )

    def record_span(
        self,
        state: OrchestrationState,
        *,
        step_index: int,
        action_type: str,
        input_summary: str,
        output_summary: str,
        duration_ms: int,
        tokens_used: int,
        success: bool,
        error_message: str = "",
        metadata: dict[str, Any] | None = None,
    ) -> ExecutionSpan:
        """Create, record, and return an :class:`ExecutionSpan`.

        Args:
            state:          The live run state to update.
            step_index:     Zero-based step index.
            action_type:    Decision type string.
            input_summary:  Short, safe description of the action input.
            output_summary: Short, safe description of the result.
            duration_ms:    Wall-clock time for this step.
            tokens_used:    LLM tokens consumed (0 for MCP/RAG).
            success:        False when the action raised an error.
            error_message:  Human-safe error; empty on success.
            metadata:       Extra diagnostic key-value pairs.

        Returns:
            The recorded :class:`ExecutionSpan`.
        """
        span = ExecutionSpan(
            step_index=step_index,
            action_type=action_type,
            input_summary=_truncate(input_summary),
            output_summary=_truncate(output_summary),
            duration_ms=duration_ms,
            tokens_used=tokens_used,
            success=success,
            error_message=error_message,
            metadata=metadata or {},
        )

        state.record_span(span)

        logger.debug(
            "orchestration.step",
            extra={
                "run_id": state.run_id,
                "step_index": step_index,
                "action_type": action_type,
                "success": success,
                "duration_ms": duration_ms,
                "tokens_used": tokens_used,
                "error_message": error_message or None,
            },
        )

        for cb in self._callbacks:
            try:
                cb(span)
            except Exception as exc:
                logger.warning(
                    "orchestration.observer_callback_error: %s", exc
                )

        return span

    def on_limit_exceeded(
        self,
        state: OrchestrationState,
        violation_kind: str,
        message: str,
    ) -> None:
        """Log a limit-exceeded event before the run is aborted."""
        logger.warning(
            "orchestration.limit_exceeded",
            extra={
                "run_id": state.run_id,
                "violation_kind": violation_kind,
                "limit_msg": message,
                "step_count": state.step_count,
                "tool_call_count": state.tool_call_count,
            },
        )

    def on_timeout(self, state: OrchestrationState) -> None:
        """Log a timeout event."""
        logger.warning(
            "orchestration.timeout",
            extra={
                "run_id": state.run_id,
                "elapsed_s": round(state.elapsed_s, 2),
                "step_count": state.step_count,
            },
        )

    def on_error(self, state: OrchestrationState, message: str) -> None:
        """Log an unexpected error event."""
        logger.error(
            "orchestration.error",
            extra={
                "run_id": state.run_id,
                "error_msg": message,
                "step_count": state.step_count,
            },
        )


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _truncate(text: str, max_chars: int = _MAX_SUMMARY_CHARS) -> str:
    """Truncate *text* to *max_chars*, appending '…' when cut."""
    if not text:
        return ""
    text = text.strip()
    if len(text) <= max_chars:
        return text
    return text[:max_chars] + "…"


def _redact_uid(uid: str) -> str:
    """Keep only the first 8 characters of a UUID to reduce PII in logs."""
    return uid[:8] + "…" if len(uid) > 8 else uid
