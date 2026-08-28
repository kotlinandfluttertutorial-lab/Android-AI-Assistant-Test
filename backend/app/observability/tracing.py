# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : observability
# File    : tracing.py
# Purpose : OpenTelemetry distributed tracing setup.
#           Instruments FastAPI, SQLAlchemy, httpx, and Redis.
#           Exports spans to Cloud Trace (or local Jaeger) via OTLP.
# ============================================================

"""OpenTelemetry tracing initialisation.

WHY DISTRIBUTED TRACING?
  Logs and metrics tell you *what* happened and *how often*.
  Traces tell you *where time was spent* across a single request:

      POST /chat
        └── 345ms total
            ├── SQLAlchemy: SELECT conversations  12ms
            ├── Redis: GET rate_limit_key          2ms
            ├── httpx: POST api.openai.com        310ms   ← bottleneck
            └── SQLAlchemy: INSERT message          8ms

  Without tracing, a "POST /chat is slow" alert has no path forward.
  With tracing, you click the trace in Cloud Trace / Jaeger and immediately
  see the OpenAI call is consuming 90% of latency.

HOW IT WORKS:
  1. setup_tracing() is called once at startup.
  2. It creates a TracerProvider with an OTLP/gRPC exporter.
  3. Auto-instrumentation patches FastAPI (adds spans per route),
     SQLAlchemy (adds spans per query), httpx (adds spans per outbound call),
     and Redis (adds spans per command).
  4. Each span carries the trace_id from the incoming request headers
     (W3C traceparent) so the full chain is reconstructed in Cloud Trace.

CONFIGURATION:
  OTEL_ENABLED=true               — toggle tracing on/off (default: true)
  OTEL_SERVICE_NAME=ai-assistant  — service name shown in Cloud Trace
  OTEL_EXPORTER_OTLP_ENDPOINT=   — gRPC endpoint (default: Cloud Trace via ADC)
                                    Local Jaeger: http://jaeger:4317

  When OTEL_EXPORTER_OTLP_ENDPOINT is empty, spans are exported to
  Cloud Trace using Google's ADC (Application Default Credentials).
  No explicit credentials file is needed on Cloud Run.
"""

from __future__ import annotations

import logging
import os

logger = logging.getLogger(__name__)


def setup_tracing(service_name: str | None = None) -> None:
    """Initialise OpenTelemetry tracing and instrument all supported libraries.

    This is a no-op when OTEL_ENABLED=false so local dev and unit tests are
    not affected by missing OTLP endpoints.

    Args:
        service_name: Override the service name in spans. Defaults to the
                      OTEL_SERVICE_NAME env var or "ai-assistant-backend".
    """
    enabled = os.environ.get("OTEL_ENABLED", "true").lower() in ("true", "1", "yes")
    if not enabled:
        logger.info("OTEL: tracing disabled (OTEL_ENABLED=false)")
        return

    try:
        _configure_tracing(service_name)
    except ImportError as exc:
        logger.warning(
            "OTEL: opentelemetry packages not installed — tracing unavailable. "
            "Install with: pip install opentelemetry-sdk opentelemetry-exporter-otlp-proto-grpc "
            "opentelemetry-instrumentation-fastapi opentelemetry-instrumentation-sqlalchemy "
            "opentelemetry-instrumentation-httpx opentelemetry-instrumentation-redis. "
            "Error: %s",
            exc,
        )
    except Exception as exc:
        # Tracing is non-critical — never crash the application over it.
        logger.warning("OTEL: tracing setup failed (non-fatal): %s", exc)


def _configure_tracing(service_name: str | None) -> None:
    """Internal — assumes opentelemetry packages are available."""
    from opentelemetry import trace
    from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
    from opentelemetry.instrumentation.fastapi import FastAPIInstrumentation
    from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentation
    from opentelemetry.instrumentation.redis import RedisInstrumentation
    from opentelemetry.instrumentation.sqlalchemy import SQLAlchemyInstrumentation
    from opentelemetry.sdk.resources import SERVICE_NAME, Resource
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import BatchSpanProcessor

    resolved_service_name = (
        service_name
        or os.environ.get("OTEL_SERVICE_NAME", "ai-assistant-backend")
    )

    resource = Resource.create({SERVICE_NAME: resolved_service_name})
    provider = TracerProvider(resource=resource)

    # ── Exporter ──────────────────────────────────────────────────────────────
    otlp_endpoint = os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT", "")

    if otlp_endpoint:
        # Explicit endpoint — local Jaeger or a managed collector
        exporter = OTLPSpanExporter(endpoint=otlp_endpoint, insecure=True)
        logger.info("OTEL: exporting spans to %s", otlp_endpoint)
    else:
        # No endpoint set — try Cloud Trace via ADC (works on Cloud Run natively)
        try:
            from opentelemetry.exporter.cloud_trace import CloudTraceSpanExporter  # type: ignore[import]
            exporter = CloudTraceSpanExporter()  # type: ignore[assignment]
            logger.info("OTEL: exporting spans to Google Cloud Trace via ADC")
        except ImportError:
            # opentelemetry-exporter-cloud-trace not installed — use OTLP console
            from opentelemetry.sdk.trace.export import ConsoleSpanExporter
            exporter = ConsoleSpanExporter()  # type: ignore[assignment]
            logger.info(
                "OTEL: opentelemetry-exporter-cloud-trace not installed — "
                "printing spans to console. Install for Cloud Trace export."
            )

    provider.add_span_processor(BatchSpanProcessor(exporter))
    trace.set_tracer_provider(provider)

    # ── Auto-instrumentations ─────────────────────────────────────────────────
    # Each instrument() call patches the library at import time.
    # Uninstrument() is safe to call multiple times — idempotent.

    FastAPIInstrumentation().instrument()
    SQLAlchemyInstrumentation().instrument()
    HTTPXClientInstrumentation().instrument()
    RedisInstrumentation().instrument()

    logger.info(
        "OTEL: tracing configured — service=%s, provider=%s",
        resolved_service_name,
        type(provider).__name__,
    )
