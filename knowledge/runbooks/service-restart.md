# Runbook: Restart the Backend Service

**Service:** ai-assistant-backend (Cloud Run)
**Last updated:** 2026-08-26
**Owner:** DevOps
**Severity applicability:** All severities

---

## When to use this runbook

- The backend is returning 503 on `/health`
- Cloud Run reports the container is failing its health check
- A deployment left the service in an inconsistent state
- Memory leak requires a clean restart
- You need to force a new revision without a code change

---

## Prerequisites

- `gcloud` CLI authenticated: `gcloud auth login`
- Project set: `gcloud config set project android-ai-assistant-89cec`
- Region: `asia-south1`

---

## Step 1 — Verify the problem

```bash
# Check current service state
gcloud run services describe ai-assistant-backend \
  --region=asia-south1 \
  --format="value(status.conditions)"

# Check the last 50 log lines for errors
gcloud run services logs read ai-assistant-backend \
  --region=asia-south1 \
  --limit=50

# Hit the health endpoint directly
curl https://ai-assistant-backend-106071012091.asia-south1.run.app/health
# Expected: {"status":"ok"}

# Hit the readiness endpoint for dependency status
curl https://ai-assistant-backend-106071012091.asia-south1.run.app/ready
# Expected: {"status":"ready","dependencies":{"database":"ok","redis":"..."}}
```

---

## Step 2 — Force a new revision (restart)

Cloud Run has no "restart" command — a restart means deploying a new revision
with the same image. This forces Cloud Run to spin up fresh instances.

```bash
PROJECT=android-ai-assistant-89cec
REGION=asia-south1
SERVICE=ai-assistant-backend

# Get the currently deployed image
IMAGE=$(gcloud run services describe $SERVICE \
  --region=$REGION \
  --format="value(spec.template.spec.containers[0].image)")

echo "Current image: $IMAGE"

# Redeploy the same image to force new instances
gcloud run services update $SERVICE \
  --region=$REGION \
  --image=$IMAGE
```

This triggers a rolling deployment: Cloud Run starts new instances, waits for
them to pass the health check, then routes traffic and terminates the old ones.
Zero downtime.

---

## Step 3 — Verify recovery

```bash
# Watch for the new revision to become ready (usually < 60 seconds)
gcloud run revisions list \
  --service=ai-assistant-backend \
  --region=asia-south1 \
  --limit=3

# Confirm health
curl https://ai-assistant-backend-106071012091.asia-south1.run.app/health
curl https://ai-assistant-backend-106071012091.asia-south1.run.app/ready
```

---

## Step 4 — If the restart does not help

If the new revision also fails health checks, the problem is in the application
code or its dependencies (database, secrets). Do NOT keep cycling revisions.

1. Check startup logs: `gcloud run services logs read ai-assistant-backend --region=asia-south1 --limit=100`
2. Look for `STARTUP_VALIDATION_FAILED` — a required env var is missing or wrong
3. Look for `ChromaDB NOT reachable` — the ChromaDB service may be down
4. Look for `database` errors — the Neon PostgreSQL connection string may be expired
5. Check Secret Manager: `gcloud secrets versions list DATABASE_URL --project=android-ai-assistant-89cec`

---

## Escalation

If the service does not recover within 10 minutes:
1. Roll back to the last known-good revision (see rollback runbook)
2. Page the on-call engineer
3. Open an incident ticket

---

## Related runbooks

- `rollback.md` — revert to a previous revision
- `database-recovery.md` — fix database connectivity issues
- `scaling.md` — adjust instance count under load
