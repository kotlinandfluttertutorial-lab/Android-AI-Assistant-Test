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
    # Redis availability check — runs BEFORE launching Celery.
    #
    # Problem: If Upstash Redis hits its daily request limit (or is transiently
    # unavailable), Celery crashes on the very first command it sends (a mutex
    # SET). Cloud Run immediately restarts the container (min-instances=1), which
    # fires more Redis commands, causing a crash-restart loop that burns through
    # the next day's quota before it resets.
    #
    # Solution: Probe Redis with a single PING before starting Celery.
    #   - PONG            → Redis is healthy, proceed.
    #   - ResponseError   → Quota exceeded (or ACL/auth error). Sleep with
    #                       exponential backoff (max 5 min), then retry.
    #                       Cloud Run will keep the health server alive so
    #                       the container stays up without crashing.
    #   - ConnectionError → Redis unreachable. Short retry (30 s) then exit so
    #                       Cloud Run can reschedule on a different instance.
    # -------------------------------------------------------------------------
    python3 - <<'PYEOF'
import os, sys, time, math

redis_url = os.environ.get("REDIS_URL") or os.environ.get("CELERY_BROKER_URL", "")
if not redis_url:
    print("[entrypoint] WARNING: REDIS_URL not set — skipping Redis probe", flush=True)
    sys.exit(0)

try:
    import redis as redis_lib
except ImportError:
    print("[entrypoint] redis package not available — skipping probe", flush=True)
    sys.exit(0)

# Strip Celery-specific transport prefix (e.g. "redis+sentinel://")
url = redis_lib.from_url(redis_url.split(";")[0])

MAX_ATTEMPTS = 10          # give up and let Cloud Run reschedule after this many tries
BASE_DELAY   = 15          # seconds — first sleep
MAX_DELAY    = 300         # seconds — cap at 5 minutes

for attempt in range(1, MAX_ATTEMPTS + 1):
    try:
        url.ping()
        print(f"[entrypoint] Redis probe OK (attempt {attempt})", flush=True)
        sys.exit(0)
    except redis_lib.exceptions.ResponseError as exc:
        msg = str(exc)
        if "max requests limit exceeded" in msg:
            delay = min(BASE_DELAY * (2 ** (attempt - 1)), MAX_DELAY)
            print(
                f"[entrypoint] Upstash rate limit exceeded (attempt {attempt}/{MAX_ATTEMPTS}). "
                f"Sleeping {delay}s before retrying. Error: {exc}",
                flush=True,
            )
            time.sleep(delay)
        else:
            # Auth error or other — crash fast, no point retrying
            print(f"[entrypoint] Redis ResponseError (non-quota): {exc}", flush=True)
            sys.exit(1)
    except (redis_lib.exceptions.ConnectionError, redis_lib.exceptions.TimeoutError) as exc:
        delay = min(BASE_DELAY * attempt, 60)
        print(
            f"[entrypoint] Redis unreachable (attempt {attempt}/{MAX_ATTEMPTS}): {exc}. "
            f"Sleeping {delay}s.",
            flush=True,
        )
        time.sleep(delay)

# Exhausted all attempts — exit so Cloud Run reschedules
print(
    f"[entrypoint] Redis unavailable after {MAX_ATTEMPTS} attempts. Exiting.",
    flush=True,
)
sys.exit(1)
PYEOF

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
    exec python3 /app/scripts/reingest_stuck.py
    ;;

  *)
    echo "[entrypoint] ERROR: Unknown APP_MODE='$APP_MODE'. Must be 'api', 'worker', or 'reingest'." >&2
    exit 1
    ;;

esac
