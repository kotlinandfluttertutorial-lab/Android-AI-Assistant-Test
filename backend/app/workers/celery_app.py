"""Celery application setup for the Android AI Assistant backend.

The Celery app is configured from application settings and serves as the
central broker/worker connection for all background tasks.

Usage::

    from app.workers.celery_app import celery_app

    @celery_app.task
    def my_task():
        ...

Requirements: 4.2, 4.5
"""

from __future__ import annotations

import ssl
import sys

from celery import Celery


def _ensure_redis_tls_params(url: str) -> str:
    """Append ssl_cert_reqs=required to a rediss:// URL if not already set.

    Celery's Redis result backend URL parser accepts: required, optional, none
    (lowercase).  The ssl module constant ssl.CERT_REQUIRED is passed separately
    via redis_backend_use_ssl for the result backend connection layer.

    Upstash uses a valid public CA certificate, so CERT_REQUIRED is correct.
    """
    if not url.startswith("rediss://"):
        return url
    if "ssl_cert_reqs" in url:
        return url  # already present — don't duplicate
    separator = "&" if "?" in url else "?"
    return f"{url}{separator}ssl_cert_reqs=required"


def _create_celery_app() -> Celery:
    """Construct the Celery application from settings."""
    from app.config.settings import get_settings

    settings = get_settings()

    broker = _ensure_redis_tls_params(settings.celery_broker)
    backend = _ensure_redis_tls_params(settings.celery_backend)

    app = Celery(
        "android_ai_assistant",
        broker=broker,
        backend=backend,
    )

    # For rediss:// URLs, also set ssl config via conf so the result backend
    # connection layer gets it explicitly — belt and suspenders approach.
    if broker.startswith("rediss://"):
        app.conf.broker_use_ssl = {"ssl_cert_reqs": ssl.CERT_REQUIRED}
    if backend.startswith("rediss://"):
        app.conf.redis_backend_use_ssl = {"ssl_cert_reqs": ssl.CERT_REQUIRED}

    app.conf.update(
        task_serializer="json",
        accept_content=["json"],
        result_serializer="json",
        timezone="UTC",
        enable_utc=True,
        task_track_started=True,
        # On Windows, prefork uses shared-memory semaphores that are blocked by
        # default security policy (WinError 5 / Access Denied).  Use the
        # "solo" pool so tasks run in-process without subprocess spawning.
        # On Linux/macOS the default prefork pool is used instead.
        worker_pool="solo" if sys.platform == "win32" else "prefork",
        # Hard kill after 10 minutes; soft warning at 8 minutes so the task can
        # clean up before being terminated.  Large PDFs (> ~50 pages) can take
        # several minutes to extract and embed; these limits prevent a runaway
        # task from blocking the worker indefinitely.
        task_time_limit=600,
        task_soft_time_limit=480,
        # Auto-discover tasks from the workers package
        include=[
            "app.workers.rag_worker",
            "app.workers.notification_worker",
            "app.workers.gdpr_worker",
            "app.workers.anomaly_worker",   # Phase 11 — anomaly detection beat task
        ],
        # Route tasks to dedicated queues for better isolation and scaling.
        # Workers can subscribe to specific queues:
        #   celery worker -Q ingestion      — processes RAG ingestion tasks
        #   celery worker -Q notifications  — processes notification tasks
        # A plain `celery worker` command (no -Q flag) processes all queues.
        task_routes={
            "app.workers.rag_worker.*": {"queue": "ingestion"},
            "app.workers.notification_worker.*": {"queue": "notifications"},
            "app.workers.gdpr_worker.*": {"queue": "gdpr"},
        },
    )

    return app


celery_app: Celery = _create_celery_app()
