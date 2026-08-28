# Deployment Guide
## Android AI Assistant — Enterprise Edition

---

## Production Deployment Checklist

### Pre-Deployment

- [ ] All CI pipelines green on the release branch
- [ ] Coverage ≥ 70% confirmed for Android and backend
- [ ] `alembic upgrade head` run and verified on staging
- [ ] All required environment variables set (see Environment Variables section)
- [ ] TLS certificates valid and not expiring within 60 days
- [ ] Certificate fingerprints match those pinned in the Android app
- [ ] Firebase `google-services.json` (production) in `app/` directory
- [ ] Firebase project has production environment FCM configured
- [ ] MinIO buckets created (`documents`)
- [ ] ChromaDB accessible and healthy
- [ ] Grafana dashboards imported from `infrastructure/grafana/dashboards/`

---

## Environment Variables

All variables are loaded via `pydantic-settings` from the `.env` file in `backend/`.
Copy `backend/.env.example` and populate all values before deployment.

### Required Variables

| Variable | Description |
|----------|-------------|
| `DATABASE_URL` | PostgreSQL connection string (async: `postgresql+asyncpg://`) |
| `REDIS_URL` | Redis connection string with password |
| `SECRET_KEY` | JWT signing key — minimum 256 bits, random |
| `ENCRYPTION_KEY` | AES-256 key for API key encryption — exactly 32 bytes |
| `CELERY_BROKER_URL` | Redis URL for Celery broker |
| `CELERY_RESULT_BACKEND` | Redis URL for Celery results |
| `MINIO_ENDPOINT` | MinIO host:port |
| `MINIO_ACCESS_KEY` | MinIO access key |
| `MINIO_SECRET_KEY` | MinIO secret key |
| `MINIO_BUCKET` | Bucket name for documents |
| `FIREBASE_CREDENTIALS_JSON` | Path to Firebase service account JSON |

### LLM Provider Variables (configure as needed)

| Variable | Provider |
|----------|---------|
| `OPENAI_API_KEY` | OpenAI GPT-4o |
| `GEMINI_API_KEY` | Google Gemini 1.5 Pro |
| `ANTHROPIC_API_KEY` | Anthropic Claude 3.5 Sonnet |
| `OLLAMA_BASE_URL` | Self-hosted Ollama instance URL |

---

## Database Migrations (Alembic)

```bash
# Apply all pending migrations (run before starting the backend)
cd backend
alembic upgrade head

# Verify current state
alembic current

# Create a new migration (development)
alembic revision --autogenerate -m "describe_change"

# Roll back one step (emergency only)
alembic downgrade -1
```

**Rule:** Never run `alembic downgrade` in production without explicit approval.
All migrations should be backwards-compatible where possible (add columns before removing old ones).

---

## Certificate Pinning Update Procedure

When the backend TLS certificate is renewed:

1. Obtain the new certificate's SHA-256 fingerprint:
   ```bash
   openssl x509 -in new_cert.pem -noout -fingerprint -sha256
   ```

2. Update `core-network/src/main/kotlin/.../CertificatePinningInterceptor.kt`:
   ```kotlin
   val pinned = CertificatePinner.Builder()
       .add("api.aiassistant.example.com", "sha256/NEW_FINGERPRINT==")
       .add("api.aiassistant.example.com", "sha256/OLD_FINGERPRINT==")  // keep old during rollout
       .build()
   ```

3. Build and distribute the new app version through internal testing → staging → production track
4. Wait for adoption (at least 90% of active users) before removing the old fingerprint
5. Deploy the new certificate on the backend **after** the app update has sufficient adoption
6. Remove the old fingerprint pin in the next app release

**Critical:** If the certificate expires before the app update is distributed, users on old builds will be unable to connect. Plan certificate renewals at least 60 days in advance.

---

## Firebase Configuration

### Android App

1. Download `google-services.json` from Firebase Console (production project)
2. Place in `app/google-services.json`
3. Build the release APK/AAB

### Backend

1. Download the Firebase Admin SDK service account JSON from Firebase Console
2. Set `FIREBASE_CREDENTIALS_JSON=/path/to/serviceAccountKey.json` in `.env`
3. **Never** commit `serviceAccountKey.json` to version control

---

## Play Store Release Build Steps

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`
2. Ensure `google-services.json` is the production file
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
5. Upload to Play Console → Internal Testing → Closed Testing → Production track
6. Set rollout percentage (recommend 10% → 25% → 50% → 100% over 72 hours)

---

## Horizontal Scaling

The backend is stateless. Scale by adding API server instances behind a load balancer.

### Docker Compose Scale (development)

```bash
docker compose up --scale backend=3 -d
```

### Production (Kubernetes example)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ai-assistant-backend
spec:
  replicas: 3
  selector:
    matchLabels:
      app: ai-assistant-backend
  template:
    spec:
      containers:
        - name: backend
          image: ai-assistant-backend:latest
          env:
            - name: DATABASE_URL
              valueFrom:
                secretKeyRef:
                  name: backend-secrets
                  key: database-url
```

### Celery Workers (scale independently)

```bash
docker compose up --scale celery=4 -d
```

---

## Monitoring in Production

| Dashboard | URL | Purpose |
|-----------|-----|---------|
| Grafana | http://grafana:3000 | AI cost, request volume, error rates |
| Prometheus | http://prometheus:9090 | Raw metrics + alerts |
| Loki (via Grafana) | http://grafana:3000/explore | Log queries by correlation ID |
| Admin Dashboard | https://api/admin | User management, audit logs, platform metrics |

### Alert Rules (Prometheus)

Configure alerts for:
- `http_errors_total` rate > 1% over 5 minutes → PagerDuty
- `llm_first_token_duration_seconds` p95 > 1s → Slack notification
- PostgreSQL connection pool exhaustion → PagerDuty
- Celery queue depth > 100 tasks → Slack notification

---

## Rollback Procedure

1. Roll back the Docker image to the previous tag:
   ```bash
   docker compose pull backend:previous-tag
   docker compose up -d backend
   ```
2. If the migration needs to be rolled back:
   ```bash
   alembic downgrade -1  # requires explicit approval
   ```
3. Monitor error rates in Grafana for 30 minutes after rollback
4. Create a post-mortem issue with the root cause and fix plan
