# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : workers
# File    : metrics.py
# Purpose : metrics — workers module
#
# Architecture Layer : Celery Worker
# Pattern Used       : Python Module
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Celery worker metrics for Prometheus.

Exposes the following metrics:
- ``celery_queue_depth``         Gauge  — pending tasks per queue
- ``celery_active_tasks``        Gauge  — active tasks per queue
- ``celery_failed_tasks_total``  Counter — permanently failed tasks per task_name
- ``celery_completed_tasks_total`` Counter — successfully completed tasks per task_name

Per-provider LLM token cost metrics:
- ``llm_token_cost_usd_total``   Counter — cumulative USD cost by provider
- ``llm_input_tokens_total``     Counter — cumulative input tokens by provider
- ``llm_output_tokens_total``    Counter — cumulative output tokens by provider

Celery signals connected:
- ``task_failure``  → increments ``celery_failed_tasks_total``
- ``task_success``  → increments ``celery_completed_tasks_total``
- ``task_retry``    → logs retry (no counter; retry path already covered by failure/success)

Usage::

    from app.workers.metrics import setup_celery_metrics, record_token_usage
    from app.workers.celery_app import celery_app

    setup_celery_metrics(celery_app)
    record_token_usage(provider="openai", input_tokens=100, output_tokens=50, cost_usd=0.001)

Requirements: 27.1, 27.2, 27.3, 27.4
"""

from __future__ import annotations

import logging

from prometheus_client import CollectorRegistry, Counter, Gauge

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Registry
# ---------------------------------------------------------------------------

celery_metrics_registry = CollectorRegistry(auto_describe=True)

# ---------------------------------------------------------------------------
# Gauges
# ---------------------------------------------------------------------------

celery_queue_depth: Gauge = Gauge(
    "celery_queue_depth",
    "Number of pending tasks waiting in the Celery queue.",
    labelnames=["queue"],
    registry=celery_metrics_registry,
)

celery_active_tasks: Gauge = Gauge(
    "celery_active_tasks",
    "Number of actively executing Celery tasks.",
    labelnames=["queue"],
    registry=celery_metrics_registry,
)

# ---------------------------------------------------------------------------
# Counters
# ---------------------------------------------------------------------------

celery_failed_tasks_total: Counter = Counter(
    "celery_failed_tasks_total",
    "Total number of permanently failed Celery tasks (after retries exhausted).",
    labelnames=["task_name"],
    registry=celery_metrics_registry,
)

celery_completed_tasks_total: Counter = Counter(
    "celery_completed_tasks_total",
    "Total number of successfully completed Celery tasks.",
    labelnames=["task_name"],
    registry=celery_metrics_registry,
)

# ---------------------------------------------------------------------------
# LLM token cost metrics (per-provider)
# ---------------------------------------------------------------------------

llm_token_cost_usd_total: Counter = Counter(
    "llm_token_cost_usd_total",
    "Cumulative USD cost of LLM calls, broken down by provider.",
    labelnames=["provider"],
    registry=celery_metrics_registry,
)

llm_input_tokens_total: Counter = Counter(
    "llm_input_tokens_total",
    "Cumulative number of input (prompt) tokens sent to LLM providers.",
    labelnames=["provider"],
    registry=celery_metrics_registry,
)

llm_output_tokens_total: Counter = Counter(
    "llm_output_tokens_total",
    "Cumulative number of output (completion) tokens received from LLM providers.",
    labelnames=["provider"],
    registry=celery_metrics_registry,
)


# ---------------------------------------------------------------------------
# LLM token usage helper
# ---------------------------------------------------------------------------


def record_token_usage(
    provider: str,
    input_tokens: int,
    output_tokens: int,
    cost_usd: float,
) -> None:
    """Increment all three LLM token cost / usage counters for a single call.

    Call this immediately after persisting a ``TokenUsage`` row so that
    Prometheus reflects up-to-date per-provider cost and token volumes.

    Args:
        provider:      The LLM provider name (e.g. ``"openai"``, ``"gemini"``).
        input_tokens:  Number of prompt tokens consumed.
        output_tokens: Number of completion tokens generated.
        cost_usd:      Estimated cost of the call in US dollars.

    Requirements: 27.1, 27.2, 27.3, 27.4
    """
    try:
        llm_token_cost_usd_total.labels(provider=provider).inc(cost_usd)
        llm_input_tokens_total.labels(provider=provider).inc(input_tokens)
        llm_output_tokens_total.labels(provider=provider).inc(output_tokens)
    except Exception as exc:
        logger.warning("record_token_usage: failed to increment metrics: %s", exc)


# ---------------------------------------------------------------------------
# Signal handlers
# ---------------------------------------------------------------------------


def _on_task_failure(sender=None, task_id=None, exception=None, **kwargs) -> None:
    """Celery ``task_failure`` signal handler — increments the failure counter."""
    task_name = (
        getattr(sender, "name", str(sender)) if sender is not None else "unknown"
    )
    logger.debug("celery metrics: task_failure signal for task=%s", task_name)
    try:
        celery_failed_tasks_total.labels(task_name=task_name).inc()
    except Exception as exc:
        logger.warning("celery metrics: failed to record task_failure metric: %s", exc)


def _on_task_success(sender=None, result=None, **kwargs) -> None:
    """Celery ``task_success`` signal handler — increments the completed counter."""
    task_name = (
        getattr(sender, "name", str(sender)) if sender is not None else "unknown"
    )
    logger.debug("celery metrics: task_success signal for task=%s", task_name)
    try:
        celery_completed_tasks_total.labels(task_name=task_name).inc()
    except Exception as exc:
        logger.warning("celery metrics: failed to record task_success metric: %s", exc)


def _on_task_retry(sender=None, reason=None, **kwargs) -> None:
    """Celery ``task_retry`` signal handler — logs the retry event."""
    task_name = (
        getattr(sender, "name", str(sender)) if sender is not None else "unknown"
    )
    logger.info(
        "celery metrics: task_retry signal for task=%s reason=%s",
        task_name,
        reason,
    )


# ---------------------------------------------------------------------------
# Setup helper
# ---------------------------------------------------------------------------


def setup_celery_metrics(app) -> None:
    """Connect Prometheus metric collectors to Celery signals.

    Call this once at application startup, after the Celery app is created.

    Args:
        app: The :class:`celery.Celery` application instance.

    Requirements: 27.1, 27.2, 27.3, 27.4
    """
    from celery.signals import task_failure, task_retry, task_success

    task_failure.connect(_on_task_failure, weak=False)
    task_success.connect(_on_task_success, weak=False)
    task_retry.connect(_on_task_retry, weak=False)

    logger.info("Celery Prometheus metrics signals connected.")
