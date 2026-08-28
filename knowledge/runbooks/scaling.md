# Runbook: Scale the Backend Service

**Service:** ai-assistant-backend (Cloud Run)
**Last updated:** 2026-08-26
**Owner:** DevOps
**Severity applicability:** WARNING (sustained high traffic)

---

## When to use this runbook

- `CeleryQueueBacklog` alert fires — queue depth > 100 for 10 minutes
- `HighP95Latency` alert fires — P95 > 2s persistently
- Sustained traffic spike expected (product launch, demo, press coverage)
- Response times are slow but no errors — capacity issue, not a bug

---

## Current scaling configuration

| Parameter | Dev | Prod |
|-----------|-----|------|
| `min-instances` | 0 | 0 |
| `max-instances` | 1 | 2 |
| `concurrency` | 20 | 40 |
| `cpu` | 1 | 1 |
| `memory` | 512Mi | 1Gi |

Cloud Run scales to zero when idle — this keeps cost at ₹0 during off-hours.
Under load it scales up to `max-instances` automatically.

---

## Scaling up for a traffic event

### Option A — Increase max instances (handles more parallel users)

```bash
gcloud run services update ai-assistant-backend \
  --region=asia-south1 \
  --max-instances=5

# After the event, restore to 2
gcloud run services update ai-assistant-backend \
  --region=asia-south1 \
  --max-instances=2
```

### Option B — Keep a warm instance (eliminate cold starts)

Setting `min-instances=1` keeps one instance always running. This costs
approximately ₹150/month for 24/7 idle compute.

```bash
gcloud run services update ai-assistant-backend \
  --region=asia-south1 \
  --min-instances=1

# After the event, return to scale-to-zero
gcloud run services update ai-assistant-backend \
  --region=asia-south1 \
  --min-instances=0
```

### Option C — Increase CPU and memory for heavy LLM workloads

```bash
gcloud run services update ai-assistant-backend \
  --region=asia-south1 \
  --cpu=2 \
  --memory=2Gi

# After the event, restore
gcloud run services update ai-assistant-backend \
  --region=asia-south1 \
  --cpu=1 \
  --memory=1Gi
```

---

## Scaling the Celery worker (background tasks)

Celery runs as a separate Cloud Run service. If the document ingestion queue
is backing up, increase the Celery worker concurrency or replicas.

Current Celery configuration:
- Concurrency: 4 workers per process
- Deployed as Cloud Run Job (invoked on demand)

For sustained high ingestion volume, consider deploying Celery as a long-running
Cloud Run service rather than a job.

---

## Cost impact

| Change | Monthly cost impact |
|--------|-------------------|
| max-instances 2 → 5 | +₹300 at full utilisation |
| min-instances 0 → 1 | +₹150 always-on |
| CPU 1 → 2 | +₹200 at full utilisation |
| memory 1Gi → 2Gi | +₹100 at full utilisation |

Always revert temporary scaling changes after the traffic event.

---

## Budget protection

The budget alert at ₹800/month fires at 50%, 90%, and 100%.
Check current spend before scaling:

```bash
gcloud billing budgets list \
  --billing-account=$(gcloud billing projects describe android-ai-assistant-89cec \
    --format="value(billingAccountName)" | sed 's|billingAccounts/||')
```

---

## Monitoring scaling effectiveness

After scaling, verify in Grafana:
- `http_request_duration_seconds` P95 should drop below 2s
- `http_requests_total` rate should be stable (not dropping = no errors)
- Check Cloud Run metrics in GCP Console → Cloud Run → ai-assistant-backend → Metrics

---

## Related runbooks

- `service-restart.md` — restart without scaling changes
- `rollback.md` — if a recent deploy caused the performance regression
