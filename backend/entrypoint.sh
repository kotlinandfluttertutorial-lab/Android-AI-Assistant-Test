#!/bin/sh
# =============================================================================
# Android AI Assistant — Container Entrypoint
# =============================================================================
#
# APP_MODE=api     (default) — run FastAPI via uvicorn
# APP_MODE=worker            — run Celery worker + background HTTP health server
# APP_MODE=reingest          — one-shot: re-dispatch ingest tasks for stuck docs
#
# Cloud Run requires every container to listen on $PORT (default 8080/8000).
# The Celery worker has no HTTP server, so in worker mode we start a tiny
# Python health server in the background on $PORT before launching Celery.
# Cloud Run startup/liveness probes hit it and receive 200 OK.
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

    # -------------------------------------------------------------------------
    # Minimal HTTP health server — satisfies Cloud Run startup probe.
    # Runs in the background; exits cleanly when SIGTERM arrives.
    # -------------------------------------------------------------------------
    python3 -c "
import http.server, os, signal, sys, threading

class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b'ok')
    def log_message(self, *a):
        pass

port = int(os.environ.get('PORT', 8080))
srv = http.server.HTTPServer(('0.0.0.0', port), H)
signal.signal(signal.SIGTERM, lambda *_: (srv.shutdown(), sys.exit(0)))
t = threading.Thread(target=srv.serve_forever, daemon=True)
t.start()
print('[health] listening on port', port, flush=True)
t.join()
" &
    HEALTH_PID=$!
    echo "[entrypoint] Health server started on port ${PORT:-8080} (pid=$HEALTH_PID)"

    # -------------------------------------------------------------------------
    # Celery worker
    # --pool=solo      — no subprocess forking; required in single-vCPU Cloud Run
    # --concurrency=1  — one task at a time matches the 1 vCPU allocation
    # -Q               — subscribe to all queues
    # -------------------------------------------------------------------------
    exec python -m celery \
      -A app.workers.celery_app \
      worker \
      --loglevel=info \
      --pool=solo \
      --concurrency=1 \
      -Q celery,ingestion,notifications,gdpr,alerts
    ;;

  reingest)
    echo "[entrypoint] Running reingest-stuck script (APP_MODE=reingest)"
    exec python3 -c "
import asyncio, os, sys
os.environ.setdefault('ENVIRONMENT', 'production')

# Force settings to load before importing app modules
from app.config.settings import get_settings
settings = get_settings()

async def run():
    from app.database import AsyncSessionLocal
    from app.models.document import Document, IngestionStatus
    from sqlalchemy import select, or_
    from app.workers.rag_worker import ingest_document_task

    async with AsyncSessionLocal() as db:
        result = await db.execute(
            select(Document).where(
                or_(
                    Document.ingestion_status == IngestionStatus.processing,
                    Document.ingestion_status == IngestionStatus.pending,
                )
            )
        )
        docs = result.scalars().all()
        print(f'Found {len(docs)} stuck documents to reingest', flush=True)
        for doc in docs:
            doc.ingestion_status = IngestionStatus.pending
            await db.flush()
            ingest_document_task.delay(str(doc.id), str(doc.user_id))
            print(f'  dispatched {doc.id} ({doc.file_name})', flush=True)
        await db.commit()
        print('All reingest tasks dispatched successfully', flush=True)

asyncio.run(run())
"
    ;;

  *)
    echo "[entrypoint] ERROR: Unknown APP_MODE='$APP_MODE'. Must be 'api', 'worker', or 'reingest'." >&2
    exit 1
    ;;

esac
