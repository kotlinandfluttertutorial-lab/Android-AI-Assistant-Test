#!/bin/sh
# =============================================================================
# Android AI Assistant — Container Entrypoint
# =============================================================================
#
# Controls whether the container runs as the FastAPI API server or as a
# Celery background worker.  A single Docker image serves both roles; the
# Cloud Run service configuration sets APP_MODE to select the mode.
#
# APP_MODE values:
#   api     (default) — run the FastAPI server via uvicorn
#   worker             — run the Celery worker, consuming the ingestion queue
#
# Usage examples:
#   APP_MODE=api     → uvicorn app.main:app --host 0.0.0.0 --port 8000
#   APP_MODE=worker  → celery -A app.workers.celery_app worker ...
#
# Cloud Run notes:
#   - The API service sets APP_MODE=api (or omits it — default).
#   - The worker service sets APP_MODE=worker, min-instances=1 so it is
#     always running to drain the Redis queue.
#   - Both services use the same Docker image tag from Artifact Registry.
# =============================================================================

set -eu

APP_MODE="${APP_MODE:-api}"

case "$APP_MODE" in

  api)
    echo "[entrypoint] Starting FastAPI server (APP_MODE=api)"
    exec uvicorn app.main:app \
      --host 0.0.0.0 \
      --port "${PORT:-8000}"
    ;;

  worker)
    echo "[entrypoint] Starting Celery worker (APP_MODE=worker)"
    # --pool=solo: Cloud Run containers are single-process; the prefork pool
    #   would try to fork child processes which are unreliable in a container
    #   with a single vCPU and no /dev/shm.
    # --concurrency=1: one task at a time matches the single vCPU allocation.
    # -Q: subscribe to all queues so one worker handles every task type.
    exec python -m celery \
      -A app.workers.celery_app \
      worker \
      --loglevel=info \
      --pool=solo \
      --concurrency=1 \
      -Q celery,ingestion,notifications,gdpr,alerts
    ;;

  *)
    echo "[entrypoint] ERROR: Unknown APP_MODE='$APP_MODE'. Must be 'api' or 'worker'." >&2
    exit 1
    ;;

esac
