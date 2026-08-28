# Deployment Guide
## Android AI Assistant — Enterprise Edition

---

## Production Deployment Checklist

### Pre-Deployment

- [ ] All CI pipelines green on the release branch
- [ ] Coverage ≥ 70% confirmed for Android and backend
- [ ] `alembic upgrade head` run and verified on staging
- [ ] All required environment variables set (see below)
- [ ] TLS certificates valid and not expiring within 60 days
- [ ] Certificate SHA-256 fingerprints match those pinned in the Android app
- [ ] Firebase `google-services.json` (production project) placed in `app/`
- [ ] Firebase project has production FCM project configured
- [ ] MinIO bucket `documents` created and accessible
- [ ] ChromaDB accessible and returning healthy status
- [ ] Grafana dashboards imported from `infrastructure/grafana/provisioning/`
- [ ] Prometheus alert rules configured

---

## Docker Compose — Full Stack

```bash
# Start all services
docker compose up -d

# Verify all services healthy
docker compose ps

# Apply database migrations
docker compose exec backend alembic upgrade head

# Scale API servers (if needed)
docker compose up --scale backend=3 -d

# Scale Celery workers (if needed)
docker compose up --scale celery=4 -d
```

---

## Nginx Configuration

The Nginx reverse proxy handles TLS termination and WebSocket proxying.

```nginx
# infrastructure/nginx/nginx.conf

server {
    listen 443 ssl;
    server_name api.aiassistant.example.com;

    ssl_certificate     /etc/nginx/certs/fullchain.pem;
    ssl_certificate_key /etc/nginx/certs/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;

    # REST API
    location /api/ {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_read_timeout 60s;
    }

    # WebSocket
    location /ws/ {
        proxy_pass http://backend:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;   # keep WS connections alive
    }

    # Health check (no auth required)
    location /health {
        proxy_pass http://backend:8080/api/v1/health;
    }
}

server {
    listen 80;
    server_name api.aiassistant.example.com;
    return 301 https://$host$request_uri;   # redirect all HTTP to HTTPS
}
```

---

## Environment Variables Reference

All variables loaded by `pydantic-settings` from `backend/.env`.

### Required

| Variable | Description |
|----------|-------------|
| `DATABASE_URL` | `postgresql+asyncpg://user:pass@host:5432/aiassistant` |
| `REDIS_URL` | `redis://:password@host:6379/0` |
| `SECRET_KEY` | JWT signing key — minimum 256 bits, random, never reused |
| `ENCRYPTION_KEY` | AES-256 key — exactly 32 bytes, random |
| `CELERY_BROKER_URL` | Redis URL for Celery broker |
| `CELERY_RESULT_BACKEND` | Redis URL for Celery results |
| `MINIO_ENDPOINT` | `host:9000` |
| `MINIO_ACCESS_KEY` | MinIO access key |
| `MINIO_SECRET_KEY` | MinIO secret key |
| `MINIO_BUCKET` | `documents` |
| `FIREBASE_CREDENTIALS_JSON` | Absolute path to Firebase Admin SDK JSON |

### LLM Providers (configure at least one)

| Variable | Provider |
|----------|---------|
| `OPENAI_API_KEY` | OpenAI GPT-4o |
| `GEMINI_API_KEY` | Google Gemini 1.5 Pro |
| `ANTHROPIC_API_KEY` | Anthropic Claude 3.5 Sonnet |
| `OLLAMA_BASE_URL` | Self-hosted Ollama endpoint |

### Optional

| Variable | Default | Description |
|----------|---------|-------------|
| `GRAFANA_PASSWORD` | — | Grafana admin password (required in production) |
| `GOOGLE_CLIENT_ID` | — | Google OAuth2 client ID |
| `GOOGLE_CLIENT_SECRET` | — | Google OAuth2 client secret |
| `LOG_LEVEL` | `INFO` | `DEBUG` / `INFO` / `WARNING` / `ERROR` |
| `PROMETHEUS_ENABLED` | `true` | Enables `/metrics` endpoint |
| `LOKI_URL` | — | Log aggregation endpoint |

---

## Database Migrations (Alembic)

```bash
# Apply all pending migrations (run before starting the backend)
cd backend
alembic upgrade head

# Verify current migration state
alembic current

# Create a new migration (development only)
alembic revision --autogenerate -m "describe_change"

# Emergency rollback (requires explicit approval)
alembic downgrade -1
```

**Rule:** All migrations must be backwards-compatible where possible. Add new columns before
removing old ones. Never run `alembic downgrade` in production without explicit approval and
a tested rollback plan.

---

## Certificate Pinning Update Procedure

When the backend TLS certificate is renewed:

1. Get the new certificate's SHA-256 fingerprint:
   ```bash
   openssl x509 -in new_cert.pem -noout -fingerprint -sha256
   ```

2. Update `CertificatePinningInterceptor.kt` with both old and new pins:
   ```kotlin
   val pinned = CertificatePinner.Builder()
       .add("api.aiassistant.example.com", "sha256/NEW_FINGERPRINT==")
       .add("api.aiassistant.example.com", "sha256/OLD_FINGERPRINT==")  // keep during rollout
       .build()
   ```

3. Build and distribute the new app version (internal → staging → production track)
4. Wait for ≥ 90% of active users to update before removing the old pin
5. Deploy the new TLS certificate on the backend **after** sufficient app adoption
6. Release a follow-up app version removing the old pin

**Critical:** Plan certificate renewals at least 60 days in advance. If the certificate expires
before the updated app is distributed, users on old builds will be unable to connect.

---

## Android Play Store Release

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`
2. Verify `app/google-services.json` is the production Firebase file
3. Build the release AAB:
   ```bash
   ./gradlew :app:bundleRelease
   ```
4. Sign with the production keystore:
   ```bash
   jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
     -keystore production.keystore \
     app/build/outputs/bundle/release/app-release.aab \
     release_key_alias
   ```
5. Upload to Play Console → Internal Testing → Closed Testing → Production
6. Roll out at 10% → 25% → 50% → 100% over 72 hours; monitor Crashlytics and reviews

---

## Horizontal Scaling

The backend API is stateless. Scale by adding instances.

```bash
# Docker Compose
docker compose up --scale backend=3 --scale celery=4 -d
```

For Kubernetes deployments, set `replicas: 3+` in the Deployment manifest. All instances
share the same PostgreSQL, Redis, ChromaDB, and MinIO backends.

---

## Monitoring

| Dashboard | URL | Access |
|-----------|-----|--------|
| Grafana | `http://grafana:3000` | Internal network only |
| Prometheus | `http://prometheus:9090` | Internal network only |
| Loki (via Grafana) | `http://grafana:3000/explore` | Internal network only |
| Admin Dashboard | `https://api/api/v1/admin/*` | `admin` role required |

### Recommended Alerts

| Condition | Threshold | Action |
|-----------|-----------|--------|
| HTTP error rate | > 1% over 5 min | PagerDuty |
| LLM first-token p95 | > 1 s | Slack notification |
| PostgreSQL pool exhaustion | pool_overflow > 0 | PagerDuty |
| Celery queue depth | > 100 tasks | Slack notification |
| Disk usage | > 80% | PagerDuty |

---

## Rollback Procedure

1. Roll back the Docker image to the previous tag:
   ```bash
   docker compose pull backend:previous-tag
   docker compose up -d backend celery
   ```
2. If database migration needs rollback:
   ```bash
   alembic downgrade -1   # requires explicit approval
   ```
3. Monitor Grafana error rate dashboard for 30 minutes
4. File a post-mortem issue with root cause analysis and fix plan
