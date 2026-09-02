#!/bin/sh
# =============================================================================
# Android AI Assistant — Container Entrypoint
# =============================================================================
#
# APP_MODE=api    (default) — run FastAPI via uvicorn
# APP_MODE=worker           — run Celery worker + background HTTP health server
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

  *)
    echo "[entrypoint] ERROR: Unknown APP_MODE='$APP_MODE'. Must be 'api' or 'worker'." >&2
    exit 1
    ;;

esac
