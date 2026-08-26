# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : observability
# File    : logging_setup.py
# Purpose : Configure structured JSON logging to stdout so Cloud Logging
#           can parse individual fields from log entries.
# ============================================================

"""Structured JSON log formatter for stdout.

WHY JSON LOGS?
  Cloud Logging ingests container stdout. If the log line is plain text it
  is stored as a single opaque string. If it is JSON, Cloud Logging
  automatically promotes the standard fields (severity, message, timestamp,
  httpRequest, labels) into first-class indexed columns you can query with
  Log Explorer and create metrics/alerts from.

  The Loki handler already ships structured data to Loki; this module makes
  *stdout* equally structured so:
    - Local development: `docker logs api | jq .` shows readable JSON
    - Cloud Run: Cloud Logging auto-parses severity, trace, and labels
    - CI: pytest captures clean JSON lines in GitHub Actions logs

USAGE:
  Call `configure_logging()` once at application startup (main.py).
  All loggers in the process then emit JSON to stdout.
"""

from __future__ import annotations

import json
import logging
import os
import sys
import traceback
from datetime import UTC, datetime
from typing import Any


class JsonFormatter(logging.Formatter):
    """Format a LogRecord as a single-line JSON object.

    Output schema (compatible with Cloud Logging structured log format):
    {
        "timestamp":  "2026-08-26T14:32:01.123Z",   // ISO-8601 UTC
        "severity":   "ERROR",                        // Cloud Logging severity
        "message":    "request",                      // log message
        "logger":     "app.middleware.logging",       // logger name
        "module":     "logging_middleware",
        "function":   "__call__",
        "line":       198,
        // --- extra fields from logger.info("msg", extra={...}) ---
        "correlation_id": "uuid",
        "user_id":        "user-123",
        "path":           "/api/v1/chat",
        "method":         "POST",
        "status_code":    200,
        "response_time_ms": 145.3,
        // --- on exceptions ---
        "exc_info":   "Traceback (most recent call last): ..."
    }
    """

    # Map Python log level names to Cloud Logging severity strings.
    # Cloud Logging uses uppercase names but recognises its own set of values.
    _SEVERITY_MAP: dict[str, str] = {
        "DEBUG": "DEBUG",
        "INFO": "INFO",
        "WARNING": "WARNING",
        "ERROR": "ERROR",
        "CRITICAL": "CRITICAL",
    }

    # Fields that are part of the LogRecord base object — we handle them
    # explicitly. Any field NOT in this set came from extra={} and is
    # promoted to a top-level JSON key.
    _STDLIB_FIELDS = frozenset(
        {
            "args", "created", "exc_info", "exc_text", "filename",
            "funcName", "levelname", "levelno", "lineno", "message",
            "module", "msecs", "msg", "name", "pathname", "process",
            "processName", "relativeCreated", "stack_info", "thread",
            "threadName", "taskName",
        }
    )

    def format(self, record: logging.LogRecord) -> str:  # noqa: A003
        # Merge the message (handles %-style formatting of args)
        record.message = record.getMessage()

        entry: dict[str, Any] = {
            "timestamp": datetime.fromtimestamp(record.created, tz=UTC).isoformat(
                timespec="milliseconds"
            ),
            "severity": self._SEVERITY_MAP.get(record.levelname, record.levelname),
            "message": record.message,
            "logger": record.name,
            "module": record.module,
            "function": record.funcName,
            "line": record.lineno,
        }

        # Promote all extra={} fields to top-level keys
        for key, value in record.__dict__.items():
            if key not in self._STDLIB_FIELDS and not key.startswith("_"):
                entry[key] = value

        # Attach exception traceback as a string when present
        if record.exc_info:
            entry["exc_info"] = self.formatException(record.exc_info)
        elif record.exc_text:
            entry["exc_info"] = record.exc_text

        if record.stack_info:
            entry["stack_info"] = self.formatStack(record.stack_info)

        return json.dumps(entry, default=str, ensure_ascii=False)


def configure_logging(level: str = "INFO") -> None:
    """Configure the root logger to emit structured JSON to stdout.

    Args:
        level: Log level string — DEBUG | INFO | WARNING | ERROR | CRITICAL.
               Defaults to INFO; override via the LOG_LEVEL env var.

    Call this **once** at the very top of main.py, before any other import
    that might trigger logging.basicConfig().
    """
    # Respect LOG_LEVEL env var so Cloud Run / docker-compose can tune it
    # without code changes.
    effective_level_str = os.environ.get("LOG_LEVEL", level).upper()
    effective_level = getattr(logging, effective_level_str, logging.INFO)

    root_logger = logging.getLogger()

    # Remove any existing handlers added by libraries that called basicConfig
    # before us — we want exactly one handler.
    for handler in list(root_logger.handlers):
        root_logger.removeHandler(handler)

    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())
    handler.setLevel(effective_level)

    root_logger.addHandler(handler)
    root_logger.setLevel(effective_level)

    # Silence noisy third-party loggers that produce debug-level chatter
    # which is not useful in production.
    for noisy_logger in (
        "uvicorn.access",      # raw HTTP access log — we have our own middleware
        "httpx",               # HTTP client debug output
        "httpcore",
        "multipart",
        "passlib",
    ):
        logging.getLogger(noisy_logger).setLevel(logging.WARNING)
