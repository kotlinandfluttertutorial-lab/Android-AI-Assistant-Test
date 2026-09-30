# Docker — Phase 5 Learning Guide

A thorough walkthrough of the Docker infrastructure: how the images are built,
what each Docker Compose file is for, how containers communicate, and how to
extend the stack safely.

---

## Table of Contents

1. [Why Docker?](#1-why-docker)
2. [Key Concepts](#2-key-concepts)
3. [The Dockerfile — Multi-Stage Build](#3-the-dockerfile--multi-stage-build)
4. [entrypoint.sh — APP_MODE Switching](#4-entrypointsh--app_mode-switching)
5. [The Three Docker Compose Files](#5-the-three-docker-compose-files)
6. [Container Networking](#6-container-networking)
7. [Health Checks](#7-health-checks)
8. [Volumes and Persistent Data](#8-volumes-and-persistent-data)
9. [The .dockerignore File](#9-the-dockerignore-file)
10. [Security Hardening](#10-security-hardening)
11. [Common Developer Tasks](#11-common-developer-tasks)
12. [Key Design Decisions & Trade-offs](#12-key-design-decisions--trade-offs)
13. [Interview Questions](#13-interview-questions)
14. [Exercises](#14-exercises)

---

## 1. Why Docker?

Before Docker, deploying a Python application required:
1. Installing the right Python version on the server
2. Installing every library with the right version
3. Configuring system libraries (libpq, tesseract, etc.)
4. Managing environment variables
5. Making sure all of that is the same on every server

Docker solves this with a container — a self-contained, isolated unit that includes
the application, its dependencies, and its configuration. The same container image
runs identically on a developer's laptop, in GitHub Actions, and on Cloud Run.

> **"Works on my machine" is no longer a valid excuse when everyone runs the same image.**

---

## 2. Key Concepts

| Concept | Description |
|---|---|
| **Image** | Immutable snapshot of a filesystem + entry point. Built by `docker build`. |
| **Container** | Running instance of an image. Started by `docker run` or `docker compose up`. |
| **Layer** | Each `RUN`, `COPY`, `ADD` instruction in a Dockerfile creates a layer. Layers are cached — unchanged layers are reused on rebuild. |
| **Volume** | Persistent storage that lives outside the container and survives `docker stop`. |
| **Bind mount** | Host filesystem path mounted into a container (e.g., `./backend/app:/app/app` for hot-reload). |
| **Network** | Isolated virtual network. Containers on the same network reach each other by **service name** (not IP). |
| **Health check** | Command Docker runs periodically to verify a container is ready. `depends_on: condition: service_healthy` waits for this. |
| **Multi-stage build** | A Dockerfile with multiple `FROM` instructions. Earlier stages compile; the final stage copies only the result — keeping the image small. |

---

## 3. The Dockerfile — Multi-Stage Build

```
backend/Dockerfile
```

### Stage 1 — `builder`

```dockerfile
FROM python:3.11-slim AS builder

RUN apt-get install -y build-essential libpq-dev libffi-dev libssl-dev

COPY requirements.txt .
RUN pip install --prefix=/install -r requirements.txt
```

**What it does:**
- Installs build tools (`gcc`, `make`, header files) needed to compile native Python extensions
- Installs all Python packages into `/install` (an isolated prefix so they can be copied cleanly)

**Why a separate stage?**
Build tools add ~200 MB to an image. They are needed to compile packages (e.g. `cryptography` requires C headers) but are never needed at runtime. By using a separate builder stage, the final image contains only the compiled packages — not the compilers.

### Stage 2 — `production`

```dockerfile
FROM python:3.11-slim AS production

# Runtime-only system libraries
RUN apt-get install -y libpq5 tesseract-ocr poppler-utils

# Copy compiled packages from the builder stage
COPY --from=builder /install /usr/local

# Non-root user for security
RUN adduser --system appuser

# Pre-download the embedding model (~90 MB) so containers don't fetch it at runtime
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('all-MiniLM-L6-v2')"

WORKDIR /app
COPY app/ ./app/
COPY alembic/ ./alembic/
COPY entrypoint.sh .

USER appuser
EXPOSE 8000
CMD ["/app/entrypoint.sh"]
```

**Key decisions:**

**Pre-downloading the model:** Without this, every new Cloud Run instance downloads ~90 MB from HuggingFace Hub on first use — adding ~17 seconds of latency to the first RAG query. The model is baked into the image so it's always available immediately.

**Non-root user:** Running as `appuser` means that if an attacker exploits a vulnerability in the application, they get a restricted user with no `sudo` access. The principle of least privilege.

**No `pip` in production:** `pip` itself is not copied from the builder stage. This prevents `pip install` from being used inside a running container (an attack vector).

### Image size comparison

| Without multi-stage | With multi-stage |
|---|---|
| ~2.1 GB (with build tools) | ~1.2 GB (runtime only + model) |

### Build locally

```bash
cd backend
docker build -t ai-assistant-backend:local .

# Build a specific stage only (e.g. to debug the builder)
docker build --target builder -t ai-assistant-builder:local .
```

---

## 4. entrypoint.sh — APP_MODE Switching

```
backend/entrypoint.sh
```

The same Docker image serves two roles controlled by `APP_MODE`:

```
APP_MODE=api    → uvicorn app.main:app (FastAPI HTTP server)
APP_MODE=worker → Celery worker + background health HTTP server
APP_MODE=reingest → one-shot maintenance script
```

### Why one image, multiple roles?

Keeping one image for both the API and the Celery worker means:
- One CI build instead of two
- No drift between API and worker dependencies
- Simpler Dockerfile
- Smaller Artifact Registry storage

### The Celery worker health server problem

Cloud Run requires every container to respond to HTTP health probes. A Celery worker
doesn't have an HTTP server — it connects to Redis and processes tasks.

The solution: `entrypoint.sh` spawns a tiny Python HTTP server in a **background thread**
before launching Celery:

```python
import http.server, threading

class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b'ok')

srv = http.server.HTTPServer(('0.0.0.0', port), H)
t = threading.Thread(target=srv.serve_forever, daemon=True)
t.start()
```

This satisfies Cloud Run's probe without adding a dependency on any web framework.

### The Redis backoff probe

Before Celery starts, `entrypoint.sh` pings Redis. If Redis returns "max requests
limit exceeded" (Upstash daily quota), the script sleeps with exponential backoff
instead of crashing:

```
Attempt 1: sleep 15s
Attempt 2: sleep 30s
Attempt 3: sleep 60s
...up to 5 minutes max, 10 attempts total
```

**Why this matters:** Without the backoff, a quota-exceeded Redis causes Celery to
crash immediately. Cloud Run restarts the container within seconds. The restarted
container hits Redis again, burning through more quota. This becomes a crash loop
that exhausts the next day's quota too.

---

## 5. The Three Docker Compose Files

### When to use which

| File | Command | Use case |
|---|---|---|
| `docker-compose.yml` | `docker compose up -d` | Windows developer machine — core services only, no observability |
| `docker-compose.local.yml` | `docker compose -f docker-compose.local.yml up -d` | Linux/Mac full stack — hot-reload, Nginx on 8080, Prometheus + Grafana + Loki |
| `docker-compose.prod.yml` | `docker compose -f docker-compose.prod.yml up -d` | VM-based production — Nginx on 80/443, no host port for API |

### Service inventory

| Service | Windows dev | Local full | Production |
|---|---|---|---|
| PostgreSQL (pgvector) | ✅ | ✅ | ✅ |
| Redis | ✅ | ✅ | ✅ |
| ChromaDB | ✅ | ✅ | ✅ |
| MinIO | ✅ | ✅ | ✅ |
| FastAPI (backend) | ✅ | ✅ (hot-reload) | ✅ |
| Celery worker | ✅ | ✅ | ✅ |
| Nginx | ❌ | ✅ (port 8080) | ✅ (port 80/443) |
| Prometheus | ❌ | ✅ | ✅ |
| Grafana | ❌ | ✅ | ✅ |
| Loki | ❌ | ✅ | ✅ |

### Port map (local full stack)

```
http://localhost:8080  → Nginx (use this from Android Emulator: http://10.0.2.2:8080)
http://localhost:8000  → FastAPI direct (for Postman, curl)
localhost:5432         → PostgreSQL
localhost:6379         → Redis
localhost:8001         → ChromaDB (127.0.0.1 only — CVE-2026-45829)
localhost:9000         → MinIO S3 API
localhost:9001         → MinIO Console (admin UI)
http://localhost:9090  → Prometheus
http://localhost:3000  → Grafana (admin / local_grafana_password)
localhost:3100         → Loki
```

### The `docker-compose.yml` Windows difference

The default `docker-compose.yml` does not start Nginx because:
- Windows Docker Desktop uses a slightly different network bridge
- Port 80 conflicts with other local services are common on Windows
- ChromaDB runs **embedded** in-process on Windows (not as a separate HTTP service)

Android Studio emulator can reach the backend directly via `http://10.0.2.2:8000`.

### Hot-reload in local development

```yaml
# docker-compose.local.yml
backend:
  volumes:
    - ./backend/app:/app/app      # ← bind mount: host code into container
  command: >
    python -m uvicorn app.main:app
      --reload
      --reload-dir /app/app       # ← watch this directory for changes
```

When you save a `.py` file on the host, uvicorn detects the change and restarts
**inside the container** — no rebuild needed. This gives near-instant feedback
during development.

---

## 6. Container Networking

### The cardinal rule

> **Containers must never use `localhost` to reach sibling containers.**

`localhost` inside a container means *that container itself*. A container named
`backend` cannot reach `postgres` via `localhost:5432` — it must use `postgres:5432`.

This is why `docker-compose.local.yml` overrides env vars:

```yaml
# The .env.local file might contain: DATABASE_URL=postgresql://...@localhost:5432/...
# Docker Compose overrides it with the container service name:
environment:
  DATABASE_URL: "postgresql+asyncpg://...@postgres:5432/..."
  REDIS_URL: "redis://redis:6379/0"
```

### How Docker DNS works

Docker Compose creates a virtual bridge network (`ai_assistant_net`). Within this
network, Docker runs an internal DNS server that resolves service names to container
IPs. When the `backend` container connects to `postgres:5432`, Docker DNS resolves
`postgres` to the PostgreSQL container's internal IP.

This is why containers can communicate without knowing each other's IP addresses —
the IP changes on every restart, but the service name never does.

```
backend container                 Docker DNS (127.0.0.11)
    │  connect("postgres", 5432)  │
    ├─────────────────────────────►│
    │                              │
    │  ←  10.10.0.3 (postgres IP) ─┤
    │                              │
    ├─ tcp connect 10.10.0.3:5432 ─────────────────►  postgres container
```

### `expose` vs `ports`

| Directive | Meaning |
|---|---|
| `expose: ["8000"]` | Makes the port available on the internal Docker network only. Other containers can reach it; the host cannot. |
| `ports: ["8000:8000"]` | Maps a host port to a container port. The host machine (and your browser) can reach it. |

The `api` service in production uses `expose: ["8000"]` — Nginx proxies it internally
and the API is never directly reachable from outside the Docker network.

---

## 7. Health Checks

Docker Compose health checks serve two purposes:

1. **Tell Docker when a container is actually ready** (not just running)
2. **Enable `depends_on: condition: service_healthy`** (don't start the app until the DB is ready)

```yaml
# PostgreSQL is running, but is it accepting connections yet?
healthcheck:
  test: ["CMD-SHELL", "pg_isready -U aiassistant -d aiassistant"]
  interval: 10s      # check every 10s
  timeout: 5s        # fail if no response in 5s
  retries: 5         # after 5 failures, mark as unhealthy
  start_period: 15s  # give 15s to start before checking
```

### Health check commands by service

| Service | Health check command |
|---|---|
| PostgreSQL | `pg_isready -U $USER -d $DB` |
| Redis | `redis-cli ping` |
| ChromaDB | `urllib.request.urlopen('http://localhost:8000/api/v1/heartbeat')` |
| MinIO | `curl -f http://localhost:9000/minio/health/live` |
| Backend | `urllib.request.urlopen('http://localhost:8000/health')` |
| Nginx | `wget -qO- http://localhost:8080/health` |
| Prometheus | `wget -qO- http://localhost:9090/-/healthy` |

### Why `depends_on` alone is not enough

```yaml
# This is wrong — starts the app before postgres is ready:
depends_on:
  - postgres

# This is correct — waits for the health check to pass:
depends_on:
  postgres:
    condition: service_healthy
```

`depends_on` (without `condition`) only waits for the container to start, not for
the process inside it to be ready. PostgreSQL takes 5-15 seconds after the container
starts before it accepts connections. Without `condition: service_healthy`, the
backend starts, immediately fails to connect to Postgres, and crashes.

---

## 8. Volumes and Persistent Data

```yaml
volumes:
  postgres_data:         # PostgreSQL WAL + tables
  redis_data:            # Redis RDB snapshots
  local_chroma_data:     # ChromaDB vector store
  local_minio_data:      # Uploaded documents
  local_grafana_data:    # Grafana dashboards + settings
```

### Named volumes vs bind mounts

| Type | Example | Use case |
|---|---|---|
| Named volume | `postgres_data:/var/lib/postgresql/data` | Production data — managed by Docker, survives container restarts |
| Bind mount | `./backend/app:/app/app` | Development hot-reload — host filesystem mapped into container |

**Critical:** `docker compose down -v` deletes ALL named volumes. This is destructive
— use it only when you want to wipe the local database and start fresh.

```bash
docker compose down    # stops containers, KEEPS volumes
docker compose down -v # stops containers, DELETES volumes (⚠ destructive)
```

---

## 9. The .dockerignore File

```
backend/.dockerignore
```

`.dockerignore` tells Docker which files to exclude from the build context — the
set of files sent to the Docker daemon before the build starts.

### Why it matters for security

```
# Without .dockerignore, this directory is sent to the Docker daemon:
backend/
  app/              # ← needed
  requirements.txt  # ← needed
  .env              # ← DANGER: real secrets!
  .env.local        # ← DANGER: real secrets!
  tests/            # ← not needed in image, wastes space
  venv/             # ← 200 MB, completely unnecessary
  .git/             # ← 50 MB of git history, unnecessary
```

If `.env` is included in the build context, Docker may cache it in an image layer.
If that image is ever pushed to a registry (even accidentally), the secrets are exposed.

### Key patterns

```
# Exclude secrets — CRITICAL
.env
.env.*

# Allow the example file (documents config, no real secrets)
!.env.example

# Exclude Python bytecode and caches
__pycache__/
*.pyc

# Exclude test files (not needed at runtime)
tests/
pytest.ini

# Exclude the knowledge/ directory (100+ MB of markdown)
# It's seeded at startup from GCS, not baked into the image
knowledge/
```

---

## 10. Security Hardening

### Non-root user

```dockerfile
RUN addgroup --system appgroup \
    && adduser --system --ingroup appgroup appuser
USER appuser
```

Running as a non-root user limits the blast radius of a vulnerability. If the
application is compromised, the attacker has `appuser` privileges — no access to
system directories, no ability to install packages with `apt-get`, no `sudo`.

### Read-only filesystem (Nginx config)

```yaml
volumes:
  - ./infrastructure/nginx/nginx.local.conf:/etc/nginx/nginx.conf:ro
```

The `:ro` (read-only) flag prevents the container from modifying the config file —
even if an attacker gains code execution inside Nginx.

### ChromaDB bound to localhost only

```yaml
# docker-compose.yml
chromadb:
  ports:
    - "127.0.0.1:8001:8000"  # Only reachable from localhost
```

CVE-2026-45829 is an unauthenticated RCE in ChromaDB. No patch is available. The
mitigation is to bind the host port to `127.0.0.1` so it's only reachable from
the local machine — not from the LAN or the internet.

In `docker-compose.local.yml` the same pattern is used. In production (`docker-compose.prod.yml`),
ChromaDB has no host port at all — it's only reachable from other containers on
the internal Docker network.

### No build tools in production image

```dockerfile
# Stage 1 installs these — Stage 2 does NOT
build-essential libpq-dev libffi-dev libssl-dev
```

Build tools are common attack vectors. `gcc` can compile exploit code. `pip` can
install malware. The production image contains neither.

### Secrets via environment variables, not files

```yaml
env_file:
  - ./backend/.env  # injected at runtime, not baked into the image
```

Secrets are passed as environment variables at container startup. They are never
written to a file inside the container and never included in the image layer cache.

---

## 11. Common Developer Tasks

### Start the local development stack

```bash
# Copy env template
cp .env.local.example .env.local
# Fill in at least: SECRET_KEY, GEMINI_API_KEY (or OPENAI_API_KEY)

# Start the full stack
docker compose -f docker-compose.local.yml up -d

# Watch logs
docker compose -f docker-compose.local.yml logs -f backend

# Verify everything is healthy
docker compose -f docker-compose.local.yml ps
```

### Run Alembic migrations inside the container

```bash
docker compose -f docker-compose.local.yml exec backend \
  python -m alembic upgrade head
```

### Apply migrations after changing a model

```bash
# 1. Create the migration
docker compose -f docker-compose.local.yml exec backend \
  python -m alembic revision --autogenerate -m "add_last_seen_at_to_users"

# 2. Apply it
docker compose -f docker-compose.local.yml exec backend \
  python -m alembic upgrade head
```

### Open a Python shell in the running container

```bash
docker compose -f docker-compose.local.yml exec backend python3
```

### Rebuild after changing requirements.txt

```bash
docker compose -f docker-compose.local.yml up -d --build backend celery_worker
```

The `--build` flag forces a rebuild. Without it, the existing image is reused even
if `requirements.txt` changed.

### Inspect a specific layer (debugging)

```bash
# Build the builder stage only
docker build --target builder -t debug-builder backend/

# Open a shell in the builder
docker run --rm -it debug-builder /bin/bash

# Inside: check what pip installed
ls /install/lib/python3.11/site-packages/
```

### Check image size and layers

```bash
docker build -t ai-assistant-backend:local backend/
docker images ai-assistant-backend:local
docker history ai-assistant-backend:local --no-trunc
```

### Wipe everything and start fresh

```bash
docker compose -f docker-compose.local.yml down -v  # ⚠ deletes all data
docker compose -f docker-compose.local.yml up -d --build
docker compose -f docker-compose.local.yml exec backend \
  python -m alembic upgrade head
```

---

## 12. Key Design Decisions & Trade-offs

### 12.1 One image for API and Celery worker

**Benefit:** Identical dependencies guaranteed. One `docker build` step. Smaller
Artifact Registry bill.

**Trade-off:** The image is larger than necessary for each role (the API image
includes Celery; the worker image includes uvicorn). For this scale, the trade-off
favours simplicity.

### 12.2 Pre-baking the embedding model into the image

**Benefit:** Zero cold-start latency for RAG queries. No network dependency on
HuggingFace Hub at runtime.

**Trade-off:** Image is ~900 MB larger (the model is ~90 MB compressed but larger
unpacked). Rebuild is required when switching models. The model is fixed in the
image until the next build.

### 12.3 `pgvector/pgvector:pg16` everywhere

**Benefit:** The pgvector extension is available in all environments. No "works
locally but fails in production" for vector similarity queries.

**Trade-off:** Slightly larger image than plain `postgres:16-alpine`. The extension
is compiled in — even if pgvector is never used, it's present.

### 12.4 `restart: unless-stopped`

**Benefit:** Containers auto-restart after crashes or Docker daemon restarts (e.g.
after a server reboot). No manual intervention required.

**Trade-off:** A container in a crash loop (e.g. misconfigured env) keeps restarting
and consuming resources. Use `docker compose logs -f <service>` to diagnose before
the loop becomes expensive.

### 12.5 `knowledge/` excluded from Docker image

The knowledge base (runbooks, architectural docs, operational procedures) is excluded
from the image and seeded at startup from the local `knowledge/` directory (Docker
volume) or from GCS (Cloud Run). 

**Benefit:** Documents can be updated without rebuilding the image. Image stays smaller.

**Trade-off:** First startup is slower (120s max seed timeout). ChromaDB collection is
wiped and re-seeded on every Cloud Run revision due to ephemeral storage.

---

## 13. Interview Questions

**1. What is a multi-stage Docker build and why is it used here?**

A multi-stage build uses multiple `FROM` instructions in one Dockerfile. Earlier
stages compile and package; the final stage copies only the finished artifact.
Here the `builder` stage installs `gcc`, `make`, and Python headers to compile
native wheels (`cryptography`, `psycopg2`). The `production` stage copies the
compiled packages but not the compilers — keeping the production image ~900 MB
smaller and removing attack surface.

**2. Why does the production image run as a non-root user?**

The principle of least privilege. If the application has a vulnerability (e.g. a
path traversal exploit), an attacker who gains code execution as `root` can install
packages, read any file, and pivot to the host. As `appuser`, they have no `sudo`,
no write access to system directories, and limited ability to escalate. The blast
radius is contained.

**3. What is the difference between `expose` and `ports` in Docker Compose?**

`expose: ["8000"]` makes a port available on the internal Docker network. Other
containers on the same network can reach it, but the host machine cannot.
`ports: ["8000:8000"]` additionally binds a host port, making the service reachable
from `localhost` on the host machine (or from the network if bound to `0.0.0.0`).
The production API uses `expose` because Nginx proxies it internally — there's no
reason to expose it directly to the host.

**4. Why does `docker compose down -v` delete data? What do volumes actually store?**

Named volumes (`postgres_data`, `redis_data`) are managed by Docker and stored outside
the container filesystem. They survive `docker stop` and `docker restart`. The `-v` flag
deletes these volumes when stopping. This is useful for wiping a development database
but catastrophic in production — the `-v` flag should never be used on a production host
unless you have a verified backup.

**5. What problem does `.dockerignore` solve? Give a security example.**

Without `.dockerignore`, `docker build` sends the entire build context to the Docker
daemon — including `.env` files with real secrets, `.git/` history, and `venv/`
(200 MB of unnecessary files). Secrets can end up in image layers and be exposed if
the image is pushed to a registry. `.dockerignore` excludes `.env` and `.env.*` to
prevent this. It also excludes `tests/`, `venv/`, and `__pycache__/` to keep the
build context small, making builds faster.

---

## 14. Exercises

**Exercise 1 — Measure .dockerignore impact**

1. Temporarily delete or rename `backend/.dockerignore`
2. Run `docker build -t before-ignore backend/ 2>&1 | grep "Sending build context"`
3. Restore `.dockerignore`
4. Run `docker build -t after-ignore backend/ 2>&1 | grep "Sending build context"`
5. Compare the sizes — the difference shows how much was being sent unnecessarily

**Exercise 2 — Trace a request through the local stack**

1. Start `docker-compose.local.yml`
2. Send a request: `curl http://localhost:8000/health`
3. Check Nginx logs: `docker compose -f docker-compose.local.yml logs nginx`
4. Check backend logs: `docker compose -f docker-compose.local.yml logs backend`
5. Open Grafana (`http://localhost:3000`) and find the request in the dashboards
6. Map the full path: Android → Nginx → FastAPI → Middleware → Response

**Exercise 3 — Add a dev-only service**

Add a `mailhog` service to `docker-compose.local.yml` that captures outgoing SMTP
emails during local development:

```yaml
mailhog:
  image: mailhog/mailhog:v1.0.1
  ports:
    - "1025:1025"  # SMTP
    - "8025:8025"  # Web UI → http://localhost:8025
  networks:
    - local_ai_net
```

Then update the backend's `SMTP_HOST=mailhog` and `SMTP_PORT=1025` in `.env.local`.
Test that the backend can send emails by calling an endpoint that triggers an email
notification, then verify it appears in the MailHog web UI.

**Exercise 4 — Add a health check to the Celery worker**

The current `celery_worker` service in `docker-compose.local.yml` has no health check.
Add one that verifies the worker is connected to Redis and can process tasks:

```yaml
healthcheck:
  test: ["CMD-SHELL",
         "python -m celery -A app.workers.celery_app inspect ping -d celery@$$HOSTNAME --timeout 5 2>&1 | grep -q pong"]
  interval: 30s
  timeout: 10s
  retries: 3
  start_period: 30s
```

After adding it, verify it works: `docker compose -f docker-compose.local.yml ps`
should show the worker as healthy after ~30 seconds.

**Exercise 5 — Understand image layers**

1. Build the backend image: `docker build -t ai-assistant-backend:layer-test backend/`
2. Run `docker history ai-assistant-backend:layer-test` to see all layers
3. Identify which layer is largest (hint: it's the embedding model download)
4. Modify the Dockerfile to move the model download to the LAST possible layer
   (after all other steps). Build again and compare using `docker history`.
5. Explain: if you change only `app/main.py`, which layers are invalidated?
   Which layers are reused from cache?
