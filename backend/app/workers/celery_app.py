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

import sys

from celery import Celery


def _create_celery_app() -> Celery:
    """Construct the Celery application from settings.

    Settings are read lazily so that the Celery app object can be imported
    before the .env file is fully initialised (e.g., during testing with
    env-var overrides).
    """
    from app.config.settings import get_settings

    settings = get_settings()

    broker = settings.celery_broker
    backend = settings.celery_backend

    app = Celery(
        "android_ai_assistant",
        broker=broker,
        backend=backend,
    )

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
        # Auto-discover tasks from the workers package
        include=[
            "app.workers.rag_worker",
            "app.workers.notification_worker",
            "app.workers.gdpr_worker",
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
