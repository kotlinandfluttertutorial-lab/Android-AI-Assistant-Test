# DevOps Guide
## Android AI Assistant — Enterprise Edition

---

## Docker Compose Services

### Full Stack (`docker-compose.yml`)

```yaml
services:
  postgres:
    image: postgres:15-alpine
    environment:
      POSTGRES_DB: aiassistant
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    ports: ["5432:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER}"]
      interval: 10s

  redis:
    image: redis:7-alpine
    command: redis-server --requirepass ${REDIS_PASSWORD}
    ports: ["6379:6379"]

  chromadb:
    image: chromadb/chroma:latest
    volumes:
      - chroma_data:/chroma/chroma
    ports: ["8000:8000"]

  minio:
    image: minio/minio:latest
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: ${MINIO_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_PASSWORD}
    volumes:
      - minio_data:/data
    ports: ["9000:9000", "9001:9001"]

  backend:
    build: ./backend
    depends_on: [postgres, redis, chromadb, minio]
    env_file: ./backend/.env
    ports: ["8080:8080"]
    command: uvicorn app.main:app --host 0.0.0.0 --port 8080 --workers 4

  celery:
    build: ./backend
    depends_on: [redis, postgres, chromadb]
    env_file: ./backend/.env
    command: celery -A app.workers.celery_app worker --loglevel=info --concurrency=4

  nginx:
    image: nginx:alpine
    volumes:
      - ./infrastructure/nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./infrastructure/certs:/etc/nginx/certs:ro
    ports: ["80:80", "443:443"]
    depends_on: [backend]

  prometheus:
    image: prom/prometheus:latest
    volumes:
      - ./infrastructure/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    ports: ["9090:9090"]

  grafana:
    image: grafana/grafana:latest
    environment:
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_PASSWORD}
    volumes:
      - grafana_data:/var/lib/grafana
      - ./infrastructure/grafana/dashboards:/etc/grafana/provisioning/dashboards:ro
    ports: ["3000:3000"]

  loki:
    image: grafana/loki:latest
    ports: ["3100:3100"]
    volumes:
      - loki_data:/loki
```

---

## Service Summary

| Service | Image | Port(s) | Purpose |
|---------|-------|---------|---------|
| `postgres` | postgres:15-alpine | 5432 | Primary relational database |
| `redis` | redis:7-alpine | 6379 | Cache + Celery message broker |
| `chromadb` | chromadb/chroma | 8000 | Vector store for RAG + memory |
| `minio` | minio/minio | 9000, 9001 | Document object storage |
| `backend` | (build) | 8080 | FastAPI application |
| `celery` | (build) | — | Background task workers |
| `nginx` | nginx:alpine | 80, 443 | Reverse proxy + TLS termination |
| `prometheus` | prom/prometheus | 9090 | Metrics scraping |
| `grafana` | grafana/grafana | 3000 | Metrics dashboards |
| `loki` | grafana/loki | 3100 | Log aggregation |

---

## GitHub Actions CI/CD Pipelines

### `android-ci.yml`

```yaml
name: Android CI
on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

jobs:
  build-and-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17

      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${{ hashFiles('**/*.gradle.kts') }}

      - name: Run ktlint
        run: ./gradlew ktlintCheck

      - name: Run Detekt
        run: ./gradlew detekt

      - name: Run unit tests
        run: ./gradlew testDebugUnitTest

      - name: Check coverage (≥70%)
        run: ./gradlew koverVerify

      - name: Upload test reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: android-test-reports
          path: '**/build/reports/tests/'
```

### `backend-ci.yml`

```yaml
name: Backend CI
on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

jobs:
  test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:15
        env:
          POSTGRES_PASSWORD: test
          POSTGRES_DB: test_db
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
      redis:
        image: redis:7
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: "3.11"

      - name: Install dependencies
        run: pip install -r backend/requirements.txt
        working-directory: .

      - name: Run ruff lint
        run: ruff check .
        working-directory: backend

      - name: Run mypy
        run: mypy app/
        working-directory: backend

      - name: Run pytest with coverage
        run: pytest --cov=app --cov-fail-under=70 --cov-report=xml
        working-directory: backend
        env:
          DATABASE_URL: postgresql+asyncpg://postgres:test@localhost:5432/test_db
          REDIS_URL: redis://localhost:6379/0
          SECRET_KEY: ci-test-secret-key-minimum-32-characters
          ENCRYPTION_KEY: ci-test-encryption-key-32-chars!

      - name: Upload coverage report
        uses: codecov/codecov-action@v4
        with:
          file: backend/coverage.xml
```

---

## Environment Variable Management

### Required Variables (`backend/.env.example`)

```env
# Database
DATABASE_URL=postgresql+asyncpg://user:pass@localhost:5432/aiassistant
REDIS_URL=redis://:password@localhost:6379/0

# Security
SECRET_KEY=your-256-bit-secret-key-here-minimum-32-chars
ENCRYPTION_KEY=your-256-bit-aes-key-for-api-keys-32c

# LLM Providers (all optional — only configure what you use)
OPENAI_API_KEY=sk-...
GEMINI_API_KEY=AIza...
ANTHROPIC_API_KEY=sk-ant-...
OLLAMA_BASE_URL=http://localhost:11434

# Storage
MINIO_ENDPOINT=localhost:9000
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin
MINIO_BUCKET=documents

# Firebase (for push notifications)
FIREBASE_CREDENTIALS_JSON=/path/to/serviceAccountKey.json

# Google OAuth2
GOOGLE_CLIENT_ID=...
GOOGLE_CLIENT_SECRET=...

# Observability
PROMETHEUS_ENABLED=true
LOKI_URL=http://loki:3100

# Celery
CELERY_BROKER_URL=redis://:password@localhost:6379/1
CELERY_RESULT_BACKEND=redis://:password@localhost:6379/2
```

**Never commit `.env` files.** The repository includes `.env.example` with placeholder values.

---

## Database Migrations (Alembic)

```bash
# Create a new migration (auto-generated from model changes)
cd backend
alembic revision --autogenerate -m "add_habit_entries_table"

# Apply all pending migrations
alembic upgrade head

# Roll back one migration
alembic downgrade -1

# Check current migration state
alembic current
```

Alembic migration files live in `backend/alembic/versions/`. Every migration must be reviewed
before merging to ensure it is reversible where possible.

---

## Local Development Quickstart

```bash
# 1. Start all infrastructure services (no backend)
docker compose up postgres redis chromadb minio prometheus grafana loki -d

# 2. Apply database migrations
cd backend
cp .env.example .env  # then fill in values
alembic upgrade head

# 3. Start the backend in development mode
uvicorn app.main:app --reload --host 0.0.0.0 --port 8080

# 4. Start Celery worker
celery -A app.workers.celery_app worker --loglevel=debug
```

---

## Production Deployment Checklist

See `deployment-guide.md` for the full production deployment checklist.

Key points:
- All environment variables set and validated
- TLS certificates configured and pinned in the Android app
- `alembic upgrade head` run before deploying new backend version
- Prometheus / Grafana / Loki accessible only on internal network
- MinIO and ChromaDB not exposed publicly
- Grafana admin password rotated from default
- Firebase `google-services.json` for production in Android app
