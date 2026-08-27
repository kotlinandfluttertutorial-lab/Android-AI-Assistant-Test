# Runbook: Database Connectivity Recovery

**Service:** ai-assistant-backend → Neon PostgreSQL
**Last updated:** 2026-08-26
**Owner:** DevOps
**Severity applicability:** HIGH / CRITICAL

---

## When to use this runbook

- `/ready` returns `{"database": "unreachable"}`
- Logs show `asyncpg.exceptions.ConnectionDoesNotExistError`
- Logs show `sqlalchemy.exc.OperationalError: connection refused`
- Error rate spike coincides with database errors in logs
- Alembic migration job failed and left the schema in a partial state

---

## Background

The backend connects to **Neon PostgreSQL** (serverless). Neon suspends the
compute endpoint after 5 minutes of inactivity and resumes it on the next
connection. The asyncpg driver handles this transparently via `pool_pre_ping=True`.

Connection pool settings (in `backend/app/database/__init__.py`):
- `pool_size=5` — max persistent connections per process
- `max_overflow=10` — additional connections allowed under burst
- `pool_pre_ping=True` — validates each connection before use
- `pool_recycle=3600` — recycle connections after 1 hour

---

## Diagnosis

### Check 1 — Is the database responding?

```bash
# From the Cloud Run service URL
curl https://ai-assistant-backend-106071012091.asia-south1.run.app/ready
# Look at: "dependencies": {"database": "ok" | "unreachable"}
```

### Check 2 — Is the DATABASE_URL secret correct?

```bash
# List versions of the DATABASE_URL secret
gcloud secrets versions list DATABASE_URL \
  --project=android-ai-assistant-89cec

# Access the current value (be careful — this contains credentials)
gcloud secrets versions access latest \
  --secret=DATABASE_URL \
  --project=android-ai-assistant-89cec
# Verify it matches: postgresql+asyncpg://user:pass@ep-xxx.neon.tech/neondb?sslmode=require
```

### Check 3 — Is Neon compute suspended?

Log in to Neon console at https://console.neon.tech and check if the compute
endpoint for project `android-ai-assistant` is suspended. It resumes automatically
on the next connection attempt — the first request will be slow (cold start ~2s).

### Check 4 — Are there too many open connections?

Neon free tier allows 100 concurrent connections. If the pool is exhausted:

```bash
# Check logs for connection pool errors
gcloud run services logs read ai-assistant-backend \
  --region=asia-south1 \
  --limit=50 | grep -i "connection pool\|too many clients\|pool exhausted"
```

---

## Recovery procedures

### Recovery A — Neon compute wakeup (most common)

Neon auto-resumes. Simply retry the failed request. If the `/ready` endpoint
still shows `database: unreachable` after 30 seconds:

1. Open the Neon console and manually wake the compute endpoint
2. Restart the Cloud Run service (see service-restart runbook)

### Recovery B — Incorrect DATABASE_URL

```bash
# Update the secret with the correct connection string
echo -n "postgresql+asyncpg://user:newpass@ep-xxx.neon.tech/neondb?sslmode=require" | \
  gcloud secrets versions add DATABASE_URL \
    --data-file=- \
    --project=android-ai-assistant-89cec

# Force a new Cloud Run revision to pick up the new secret version
gcloud run services update ai-assistant-backend \
  --region=asia-south1 \
  --project=android-ai-assistant-89cec
```

### Recovery C — Schema migration failure

If a migration ran partially and left the schema inconsistent:

```bash
PROJECT=android-ai-assistant-89cec
REGION=asia-south1
IMAGE=$(gcloud run services describe ai-assistant-backend \
  --region=$REGION --format="value(spec.template.spec.containers[0].image)")

# Run a migration status check
gcloud run jobs create alembic-check \
  --image=$IMAGE \
  --region=$REGION \
  --service-account=ai-assistant-backend@$PROJECT.iam.gserviceaccount.com \
  --set-secrets="DATABASE_URL=DATABASE_URL:latest,SECRET_KEY=SECRET_KEY:latest,REDIS_URL=REDIS_URL:latest" \
  --set-env-vars="ENVIRONMENT=production" \
  --command="python" \
  --args="-m,alembic,current" \
  --max-retries=0

gcloud run jobs execute alembic-check --region=$REGION --wait

# If current != head, run upgrade
gcloud run jobs update alembic-migrate \
  --image=$IMAGE --region=$REGION
gcloud run jobs execute alembic-migrate --region=$REGION --wait
```

### Recovery D — Connection pool exhaustion

Reduce `max_overflow` temporarily to limit connections, or restart the service
to reset the pool:

```bash
# Restart to reset connection pool
gcloud run services update ai-assistant-backend \
  --region=asia-south1 \
  --set-env-vars="ENVIRONMENT=production,RESTART_REASON=pool_reset_$(date +%s)"
```

---

## Prevention

- Set Neon auto-suspend delay to 10 minutes (not 5) in the Neon console
- Monitor `http_request_duration_seconds_bucket` for latency spikes that
  correlate with Neon cold starts
- Alert on `HighP95Latency` in Prometheus alerting rules

---

## Related runbooks

- `service-restart.md` — restart the Cloud Run service
- `migrations.md` — run Alembic database migrations
