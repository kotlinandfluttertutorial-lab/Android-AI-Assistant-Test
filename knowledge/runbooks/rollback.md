# Runbook: Roll Back a Deployment

**Service:** ai-assistant-backend (Cloud Run)
**Last updated:** 2026-08-26
**Owner:** DevOps
**Severity applicability:** CRITICAL (bad deploy causing production errors)

---

## When to use this runbook

- Error rate spiked immediately after a deployment
- `/health` or `/ready` started failing after a deploy
- A new revision introduced a regression and the fix is not immediate
- Security vulnerability discovered in newly deployed code

---

## Key principle

Cloud Run keeps all previous revisions. A rollback means routing 100% of traffic
to a previous revision — the bad revision still exists but receives no traffic.

The previous revision is already running and warm. Rollback is instantaneous
and zero-downtime.

---

## Step 1 — Identify the bad revision

```bash
# List the last 5 revisions with traffic allocation and status
gcloud run revisions list \
  --service=ai-assistant-backend \
  --region=asia-south1 \
  --limit=5 \
  --format="table(metadata.name, status.conditions[0].status, spec.containers[0].image)"
```

The output shows revision names like `ai-assistant-backend-00042-xyz`.
The current revision receiving traffic will show `True` in the status column.

---

## Step 2 — Find the last known-good revision

```bash
# Check which revision was serving before the bad deploy
# The bad revision is the one with the most recent creation time
gcloud run revisions list \
  --service=ai-assistant-backend \
  --region=asia-south1 \
  --limit=5 \
  --sort-by="~metadata.creationTimestamp"
```

Identify the revision immediately before the problematic one. Note its name —
for example `ai-assistant-backend-00041-abc`.

---

## Step 3 — Route traffic back to the good revision

```bash
GOOD_REVISION="ai-assistant-backend-00041-abc"  # replace with actual name

gcloud run services update-traffic ai-assistant-backend \
  --region=asia-south1 \
  --to-revisions=$GOOD_REVISION=100
```

This immediately routes 100% of traffic to the previous revision. No restart
required — Cloud Run keeps previous revisions ready.

---

## Step 4 — Verify the rollback

```bash
# Confirm traffic is on the correct revision
gcloud run services describe ai-assistant-backend \
  --region=asia-south1 \
  --format="value(status.traffic)"

# Verify health
curl https://ai-assistant-backend-106071012091.asia-south1.run.app/health
curl https://ai-assistant-backend-106071012091.asia-south1.run.app/ready

# Check error rate dropped in Grafana
# Dashboard: error_rates_dashboard → HTTP Error Rate
```

---

## Step 5 — Investigate the bad revision

After traffic is stable on the good revision, investigate the bad revision:

```bash
BAD_REVISION="ai-assistant-backend-00042-xyz"  # the one you rolled back from

# View logs for the bad revision only
gcloud run services logs read ai-assistant-backend \
  --region=asia-south1 \
  --limit=100 | grep $BAD_REVISION
```

Common causes:
- `STARTUP_VALIDATION_FAILED` → missing env var in the new image
- `ImportError` / `ModuleNotFoundError` → dependency not in requirements.txt
- Alembic migration conflict → schema incompatibility between old and new code
- Config change in Secret Manager pointing at wrong version

---

## Step 6 — Return to latest-revision routing

After the fix is deployed, return to automatic latest-revision routing:

```bash
gcloud run services update-traffic ai-assistant-backend \
  --region=asia-south1 \
  --to-latest
```

---

## Canary deployment (future)

To reduce rollback risk, use gradual traffic splitting before committing 100%:

```bash
# Send 10% to the new revision, 90% stays on the previous one
NEW_REVISION="ai-assistant-backend-00043-new"
OLD_REVISION="ai-assistant-backend-00042-old"

gcloud run services update-traffic ai-assistant-backend \
  --region=asia-south1 \
  --to-revisions=$NEW_REVISION=10,$OLD_REVISION=90

# Monitor for 5 minutes, then promote if healthy
gcloud run services update-traffic ai-assistant-backend \
  --region=asia-south1 \
  --to-revisions=$NEW_REVISION=100
```

---

## Related runbooks

- `service-restart.md` — restart without rolling back
- `migrations.md` — if the rollback requires a schema downgrade
