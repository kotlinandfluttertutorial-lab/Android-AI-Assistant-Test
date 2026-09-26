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


def _strip_ssl_cert_reqs_param(url: str) -> str:
    """Remove any ssl_cert_reqs query parameter from a Redis URL.

    Kombu's Redis transport does NOT accept ssl_cert_reqs as a URL query
    parameter — it raises:
        "A rediss:// URL must have parameter ssl_cert_reqs and this must be
         set to CERT_REQUIRED, CERT_OPTIONAL, or CERT_NONE"
    even when the value looks correct, because it expects the raw ssl module
    constant name (CERT_REQUIRED), not a URL-encoded string.

    The correct way to configure TLS for rediss:// in Celery is exclusively
    via broker_use_ssl / redis_backend_use_ssl dicts (see _create_celery_app).
    This helper ensures no stale ssl_cert_reqs param remains in the URL.
    """
    if not url.startswith("rediss://"):
        return url

    from urllib.parse import parse_qs, urlencode, urlparse, urlunparse

    parsed = urlparse(url)
    params = parse_qs(parsed.query, keep_blank_values=True)
    params.pop("ssl_cert_reqs", None)
    clean_query = urlencode(params, doseq=True)
    return urlunparse(parsed._replace(query=clean_query))


def _create_celery_app() -> Celery:
    """Construct the Celery application from settings."""
    from app.config.settings import get_settings

    settings = get_settings()

    # Strip any ssl_cert_reqs query param — TLS is configured via conf dicts below.
    broker = _strip_ssl_cert_reqs_param(settings.celery_broker)
    backend = _strip_ssl_cert_reqs_param(settings.celery_backend)

    # Build SSL conf dicts upfront so they are available before Celery
    # establishes its first broker connection. Passing them through
    # app.conf.update() *after* construction is too late — Kombu reads the
    # broker URL at Celery() instantiation time and warns (then falls back to
    # insecure SSL) if broker_use_ssl is absent at that point.
    ssl_conf: dict = {}
    if broker.startswith("rediss://"):
        ssl_conf["broker_use_ssl"] = {"ssl_cert_reqs": ssl.CERT_REQUIRED}
    if backend.startswith("rediss://"):
        ssl_conf["redis_backend_use_ssl"] = {"ssl_cert_reqs": ssl.CERT_REQUIRED}

    app = Celery(
        "android_ai_assistant",
        broker=broker,
        backend=backend,
    )

    # Apply SSL conf immediately after construction, before any other conf
    # update, so Kombu sees it on the first connection attempt.
    if ssl_conf:
        app.conf.update(ssl_conf)

    app.conf.update(
        task_serializer="json",
        accept_content=["json"],
        result_serializer="json",
        timezone="UTC",
        enable_utc=True,
        task_track_started=True,
        # Retry broker connection on startup — required for Upstash Redis which
        # may reset idle connections. Without this Celery 5.x logs a deprecation
        # warning and will default to False in Celery 6.0.
        broker_connection_retry_on_startup=True,
        # Cap the number of connection retries so the worker exits cleanly
        # instead of spinning forever when Redis is over quota or unreachable.
        # entrypoint.sh probes Redis before launching Celery, so by the time we
        # reach here Redis is known-good; these limits handle post-startup drops.
        broker_connection_retry=True,
        broker_connection_max_retries=5,
        # Heartbeat keeps the broker connection alive on Upstash Redis which
        # resets idle TCP connections after ~60s. Set to 10s so we detect and
        # recover dropped connections well within that window.
        broker_heartbeat=10,
        # Transport options for the Redis broker — reconnect on connection loss
        # rather than crashing the worker process (Errno 104 Connection reset).
        broker_transport_options={
            "visibility_timeout": 3600,
            "socket_keepalive": True,
            "retry_on_timeout": True,
        },
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
