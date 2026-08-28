# Phase 11 — Anomaly Detection Guide

> **Learning goal:** Understand the three-stage progression from simple threshold
> rules to statistical baselines to ML models, why each stage exists, and how to
> tune them in production.
>
> **Career connection:** Every SRE, DevOps, and AIOps role asks "how do you know
> something is wrong before the user tells you?" This is the answer.

---

## 1. Concept — What Is Anomaly Detection?

Anomaly detection is the process of identifying system behaviour that deviates
significantly from the expected baseline.

```
Normal behaviour:   error_rate = 0.8%   (fluctuates 0.5% – 1.2%)
Anomaly:            error_rate = 23%    (something is broken)

Normal behaviour:   error_count = 3/min (consistent overnight pattern)
Anomaly:            error_count = 85/min (sudden spike after deployment)
```

The challenge: normal behaviour is not constant. Traffic has daily patterns,
weekly cycles, and one-time events (product launches, press coverage). A good
anomaly detector distinguishes "traffic is high because it's Monday morning"
from "traffic is high because the service is misbehaving."

---

## 2. The Three-Stage Progression

### Stage 1 — Rule-Based Detection

Simple threshold comparisons. Fast, predictable, zero configuration overhead.

```python
# From anomaly_detection_service.py
if error_rate > 0.05:      # > 5% of events are errors
    create_incident(severity="HIGH")

if error_count > 50:       # absolute count in 5 minutes
    create_incident(severity="HIGH")
```

**Pros:** Immediately actionable. No history required. Zero false negatives for
extreme conditions. Easy to explain to stakeholders.

**Cons:** Fixed thresholds ignore context. A 5% error rate at 3am (10 errors)
means something different than 5% at peak noon (10,000 errors). Generates false
positives during planned traffic spikes.

**When to use:** Always as the first line of defence. Catches outages that would
be obvious to any human looking at a dashboard.

---

### Stage 2 — Statistical Detection

Instead of a fixed threshold, compare the current value to the *historical baseline*
for the same time of day or traffic pattern.

```python
# Rolling baseline over the last 60 minutes
mean   = average(error_count_per_5min_bucket, last_60_min)
std_dev = standard_deviation(error_count_per_5min_bucket, last_60_min)

# Alert when current value is far from the baseline
if current > mean + 2 * std_dev:
    create_incident(severity="MEDIUM")
```

**Why mean + 2σ?** In a normal distribution:
- 68% of values fall within 1σ of the mean
- 95% fall within 2σ
- 99.7% fall within 3σ

Alerting at mean + 2σ catches roughly the top 2.5% of values as anomalies —
a good balance between sensitivity and false positive rate.

```
Normal distribution of error counts per 5-minute bucket:
     │          ████
     │        ██████████
     │      ██████████████
     │    ██████████████████
     │  ██████████████████████
     │████████████████████████████
  ───┼──────────────────────────────
     0   1   2   3   4   5   6   7   8
         mean-2σ  mean  mean+2σ  ← alert
```

**Pros:** Adapts to traffic patterns. Lower false positive rate than fixed rules.
Does not require ML expertise.

**Cons:** Requires sufficient history (60 minutes in this project). Slow to detect
anomalies in the first hour after deployment. Struggles with sudden traffic pattern
changes (e.g. new feature launch).

**When to use:** As a complement to Stage 1 — catches gradual degradations that
stay below the fixed threshold but are still meaningfully unusual.

---

### Stage 3 — ML-Based Detection (future phases)

Time-series models that learn seasonal patterns automatically.

```python
# Prophet: learns daily + weekly seasonality
from prophet import Prophet
model = Prophet(yearly_seasonality=False)
model.fit(historical_error_rates_df)
forecast = model.predict(future_df)
# Alert when observed value falls outside the prediction interval
```

**Techniques:**
- **Prophet** (Facebook/Meta) — additive model, excellent for daily/weekly patterns
- **Isolation Forest** — unsupervised ML, identifies points that are "isolated"
  from the rest of the data (good for multivariate anomalies)
- **LSTM Autoencoders** — deep learning, learns complex non-linear patterns

**Pros:** Handles complex seasonality. Adapts over weeks and months. Very low
false positive rate once trained.

**Cons:** Requires weeks of clean history to train. Cold start problem. Harder
to explain to stakeholders ("the model said so" is not always acceptable).
Computationally expensive.

**When to use:** After Stage 1 and Stage 2 are working well and you want to
reduce false positives further, or when you have complex multi-dimensional
anomalies (latency + error rate + CPU together).

---

## 3. Architecture — How This Project's Detection Works

```
Every 60 seconds (Celery beat)
         │
         ▼
anomaly_worker.py
  run_anomaly_detection_task()
         │
         ▼
AnomalyDetectionService.run_detection_cycle()
         │
         ├── Stage 1: _run_stage1()
         │     ├── count_errors_in_window(minutes=5)  → error_count
         │     ├── count_all_in_window(minutes=5)     → total_count
         │     ├── error_rate = error_count / total_count
         │     ├── Rule: error_rate > 5%  → DetectionResult(triggered=True)
         │     └── Rule: error_count > 50 → DetectionResult(triggered=True)
         │
         ├── Stage 2: _run_stage2()
         │     ├── compute_event_rate_stats(level="ERROR", window=60min, bucket=5min)
         │     │     → buckets = [3, 2, 4, 3, 5, 2, 3, 47, ...]
         │     │     → mean=3.2, std_dev=1.1
         │     │     → current=47, threshold=3.2 + 2×1.1 = 5.4
         │     └── current > threshold → DetectionResult(triggered=True)
         │
         └── For each triggered result:
               ├── recent_trigger_exists()? → skip (dedup, 5-min window)
               └── _create_incident_with_analysis()
                     ├── IncidentRepository.create() → Incident (OPEN)
                     ├── ErrorAnalysisService.analyse() → Phase 10 pipeline
                     └── IncidentRepository.attach_analysis() → link to incident

Incident is now queryable via:
  GET /api/v1/incidents
  GET /api/v1/incidents/{id}
```

---

## 4. Implementation — Key Files

| File | Purpose |
|------|---------|
| `backend/app/services/anomaly_detection_service.py` | Stage 1 + Stage 2 detection logic |
| `backend/app/workers/anomaly_worker.py` | Celery beat task — runs every 60s |
| `backend/app/workers/celery_app.py` | Beat schedule registration |
| `backend/app/models/incident.py` | Incident ORM model |
| `backend/alembic/versions/0011_add_incidents_table.py` | Migration |
| `backend/app/repositories/incident_repository.py` | Incident CRUD + dedup check |
| `backend/app/repositories/observability_event_repository.py` | Aggregation queries |
| `backend/app/api/incidents/router.py` | Incident CRUD endpoints |

### Configurable thresholds (`anomaly_detection_service.py`)

```python
ERROR_RATE_THRESHOLD  = 0.05   # Stage 1: 5% error rate fires HIGH incident
ERROR_COUNT_THRESHOLD = 50     # Stage 1: 50 errors/5min fires HIGH incident
DETECTION_WINDOW_MIN  = 5      # Stage 1: time window for threshold check
DEDUP_WINDOW_MIN      = 5      # minutes before re-firing the same rule
STAT_WINDOW_MIN       = 60     # Stage 2: history for baseline calculation
STAT_BUCKET_MIN       = 5      # Stage 2: bucket size for rolling stats
STAT_STD_MULTIPLIER   = 2.0    # Stage 2: mean + N * std_dev threshold
```

These match the thresholds in `infrastructure/prometheus/alerting.rules.yml`
intentionally — both systems should fire at the same conditions.

---

## 5. Incident Lifecycle

```
Detection
    │
    ▼
  OPEN           ← created automatically by anomaly_worker OR manually via API
    │
    ▼ (developer starts investigating)
  INVESTIGATING
    │
    ├── RESOLVED    ← problem fixed and verified
    └── DISMISSED   ← false positive, not a real issue
```

Status transitions via: `PATCH /api/v1/incidents/{id}/status`

```bash
curl -X PATCH http://localhost:8000/api/v1/incidents/{id}/status \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"status": "INVESTIGATING"}'
```

---

## 6. False Positives and the Dedup Window

### The dedup problem

The anomaly worker runs every 60 seconds. If the error rate is high for 10 minutes,
it would create 10 identical incidents without dedup. That's noise, not signal.

**Solution:** `recent_trigger_exists(triggered_by, within_minutes=5)` checks whether
an OPEN incident for the same rule was created within the last 5 minutes. If yes,
skip — the existing incident covers this condition.

```python
# In _create_incident_with_analysis()
if await self._inc_repo.recent_trigger_exists("error_rate", within_minutes=5):
    summary.skipped_dedup.append("error_rate")
    continue  # don't create a duplicate
```

### Tuning for false positives

If you're getting too many false positives:

| Symptom | Fix |
|---------|-----|
| Stage 1 fires during normal traffic spikes | Raise `ERROR_RATE_THRESHOLD` or `ERROR_COUNT_THRESHOLD` |
| Stage 2 fires after a new feature launch | Increase `STAT_WINDOW_MIN` so the baseline adapts faster |
| Both stages fire on deploy noise | Increase `DEDUP_WINDOW_MIN` from 5 to 15 minutes |
| Too many MEDIUM alerts | Raise `STAT_STD_MULTIPLIER` from 2.0 to 2.5 |

If you're missing real incidents (false negatives):
- Lower `ERROR_RATE_THRESHOLD`
- Lower `STAT_STD_MULTIPLIER` to 1.5 (more sensitive, more false positives)
- Decrease `DEDUP_WINDOW_MIN`

**The fundamental trade-off:** sensitivity ↑ → false positives ↑. There is no
perfect threshold. The goal is minimising the cost of false positives (alert fatigue)
versus the cost of false negatives (missed outages).

---

## 7. How to Test the Detection Pipeline

### Step 1 — Start the Celery worker with beat

```bash
# From project root
docker-compose up -d celery_worker

# Or manually for local dev (needs Redis running)
celery -A app.workers.celery_app worker --beat --loglevel=info
# The --beat flag starts the scheduler in the same process
```

### Step 2 — Inject error events above the threshold

```bash
# Insert 60 ERROR events (above ERROR_COUNT_THRESHOLD=50)
for i in $(seq 1 60); do
  curl -s -X POST http://localhost:8000/api/v1/observability/events \
    -H "Content-Type: application/json" \
    -d '{
      "events": [{
        "timestamp": '"$(date +%s)000"',
        "level": "ERROR",
        "eventType": "http_error",
        "message": "POST /chat returned HTTP 500",
        "sessionId": "stress-test-'"$i"'",
        "metadata": {"http_status": "500"}
      }]
    }'
done
```

### Step 3 — Wait up to 60 seconds for the worker cycle

```bash
# Watch the Celery worker logs
docker logs -f android-ai-assistant-celery-1 | grep anomaly
# Expected: anomaly_detection: incident created id=... rule=error_count severity=HIGH
```

### Step 4 — Check the incidents API

```bash
JWT=$(curl -s -X POST http://localhost:8000/api/v1/auth/login \
  -d '{"email":"test@test.com","password":"pass"}' | jq -r .access_token)

curl http://localhost:8000/api/v1/incidents?status=OPEN \
  -H "Authorization: Bearer $JWT" | jq '.incidents[0] | {title, severity, ai_summary, ai_confidence}'
```

### Step 5 — Test Stage 2 statistical detection

Stage 2 needs history. Insert a stable baseline of ~3 errors/5min for the last
60 minutes (use timestamps in the past), then insert 50 errors in the current window:

```bash
# Insert historical baseline via direct SQL (for testing only)
# Then trigger the detection cycle manually:
curl -X POST http://localhost:8000/api/v1/admin/anomaly/run \
  -H "Authorization: Bearer ADMIN_JWT"
# (Add this endpoint if you want manual triggering during development)
```

---

## 8. Debug — Common Issues

### No incidents created despite high error rate

1. Check the Celery worker is running: `docker ps | grep celery`
2. Check the beat schedule is registered:
   ```bash
   celery -A app.workers.celery_app inspect scheduled
   # Should show: run-anomaly-detection-every-60s
   ```
3. Check dedup is not suppressing: look for `skipping duplicate for rule=` in logs
4. Check `DETECTION_WINDOW_MIN=5` — events must be recent (last 5 minutes)

### Incidents created but no AI analysis attached

The Phase 10 `ErrorAnalysisService` failed. Check:
1. ChromaDB is running: `curl http://localhost:8001/api/v1/heartbeat`
2. An LLM provider key is configured in Secret Manager
3. Look for `Phase 10 analysis failed for incident` in the worker logs

### Stage 2 never fires

Requires at least `STAT_WINDOW_MIN=60` minutes of history. On a fresh database,
Stage 2 will not trigger until enough events accumulate. This is expected.

### `recent_trigger_exists` always returns True

The `DEDUP_WINDOW_MIN` is too large, or you have leftover OPEN incidents from
previous tests. Resolve them:
```bash
curl -X PATCH http://localhost:8000/api/v1/incidents/{id}/status \
  -H "Authorization: Bearer $JWT" \
  -d '{"status": "DISMISSED"}'
```

---

## 9. Interview Questions

**Q1: What is the difference between Stage 1 and Stage 2 anomaly detection? When would you use each?**

Stage 1 is rule-based: a fixed threshold like "error_rate > 5%." It's fast to implement,
easy to explain, and has zero false negatives for extreme conditions. It fires on a binary
condition regardless of context.

Stage 2 is statistical: "error_rate is 2 standard deviations above its recent baseline."
It adapts to traffic patterns — a 5% error rate at 3am is more concerning than at peak
noon, and Stage 2 captures this. It requires history and has a slower cold start.

In production, use both: Stage 1 catches outages immediately, Stage 2 catches gradual
degradations that stay below the fixed threshold but are still meaningfully unusual.

---

**Q2: What is a false positive in anomaly detection? Why does it matter?**

A false positive is when the detector fires an alert but nothing is actually wrong —
for example, alerting during a planned load test or a product launch that temporarily
elevated error rates.

False positives matter because they cause alert fatigue: engineers start ignoring
alerts because most of them are noise. The most dangerous outcome of alert fatigue
is a true positive getting missed because it looked like another false positive.

Mitigations in this project: the dedup window prevents 60 identical alerts in an
hour; the `within_minutes` parameter controls how long a rule is silenced after
first firing; `STAT_STD_MULTIPLIER` controls Stage 2 sensitivity.

---

**Q3: What is the mean + N * std_dev formula and why is N=2 a common choice?**

The formula defines an "alert threshold" that is N standard deviations above the
historical mean. In a normal distribution:
- N=1 → 84% of values are below the threshold → 16% false positive rate (too noisy)
- N=2 → 97.5% of values are below → 2.5% false positive rate (reasonable)
- N=3 → 99.85% → 0.15% false positive rate (may miss gradual degradation)

N=2 is a practical default: sensitive enough to catch real anomalies without
overwhelming on-call engineers. In high-severity, low-traffic systems you might
use N=1.5; in high-traffic systems with noisy baselines you might use N=2.5.

---

**Q4: How does the dedup window prevent incident flooding?**

Without dedup, an anomaly detected every 60 seconds for 30 minutes would create
30 incidents — all about the same problem. This is noise.

`recent_trigger_exists(triggered_by="error_rate", within_minutes=5)` queries the
`incidents` table for any OPEN incident for the same rule created in the last 5
minutes. If one exists, it skips creation. The existing incident covers the ongoing
condition. When the incident is resolved or dismissed, new incidents can be created
again if the condition re-appears.

This is preferable to rate-limiting the alert sender because it means the incident
record accurately reflects "one thing went wrong" rather than "the same thing went
wrong 30 times."

---

**Q5: How would you extend Stage 2 to handle seasonality (Monday morning spikes)?**

The current Stage 2 compares the current bucket to the mean of the last 60 minutes.
If Monday morning always has high error rates, those high values would raise the
mean, which actually helps — the threshold adapts.

But for *daily* or *weekly* seasonality, a better approach is to compare the current
value to the "same time last week" or "same day-of-week average":

```python
# Instead of: current > mean(last_60_min) + 2σ
# Use:        current > mean(same_hour_last_7_days) + 2σ
```

This is the core idea behind Facebook's **Prophet** model (Stage 3). It fits
additive components for daily seasonality + weekly seasonality + trend, then
generates a confidence interval. Values outside the interval are anomalies.

---

**Q6: This project's Prometheus rules already alert on high error rates. Why do we need application-level detection too?**

Two different concerns:

**Prometheus rules** fire against backend HTTP metrics (`http_requests_total`).
They alert via Alertmanager to Slack/email/PagerDuty. They are excellent for
backend API health but they know nothing about:
- Android client-side errors (network timeouts, crashes, HTTP errors from the app perspective)
- User session context (which screen, which user flow was active)
- Correlation with specific deployments or feature flags

**Application-level detection** operates on `observability_events` — the Android app's
perspective. It creates structured `Incident` records in PostgreSQL that can be linked
to AI error analysis, shown in the dashboard, and tracked through a lifecycle (OPEN →
INVESTIGATING → RESOLVED). It feeds directly into Phase 12 (RCA) and Phase 13 (AI
DevOps Assistant).

Both systems should fire at the same thresholds (this project intentionally aligns
the `ERROR_RATE_THRESHOLD` with the Prometheus rule) but they serve different
consumers: Prometheus → ops team alerting; application-level → AI DevOps pipeline.

---

## 10. Exercise

1. **Verify Stage 1 fires** — insert 60 ERROR events and wait 60s. Check `GET /incidents`.
   Verify `triggered_by = "error_count"`, `severity = "HIGH"`, and `ai_summary` is populated.

2. **Test the dedup window** — after the incident is created, insert 60 more errors.
   Wait 60s. Confirm no new incident is created (check log for `skipping duplicate`).
   Resolve the incident, wait 60s, insert errors again — a new incident should now be created.

3. **Tune a threshold** — change `ERROR_COUNT_THRESHOLD` from 50 to 5 in
   `anomaly_detection_service.py`. Insert 10 errors. Verify an incident fires sooner.
   Change it back after testing.

4. **Add a new Stage 1 rule** — add a check for `event_type == "crash_handled"` with a
   threshold of 3 crashes in 5 minutes. Follow the `_run_stage1` pattern. Test it by
   inserting crash events.

5. **Observe Stage 2 baseline building** — watch the `compute_event_rate_stats` log output
   over time. After 60 minutes of steady traffic, check that `mean` and `std_dev` reflect
   the actual traffic pattern.

---

## Phase 11 Summary

**What was built:**

```
Incident ORM model (incidents table) + Alembic migration 0011
IncidentRepository
  create(), attach_analysis(), update_status()
  recent_trigger_exists() — dedup guard
  list_recent(), get_by_id(), get_open_count()

ObservabilityEventRepository extensions
  count_errors_in_window() — Stage 1 numerator
  count_all_in_window()    — Stage 1 denominator
  compute_event_rate_stats() — Stage 2 rolling mean + std_dev

AnomalyDetectionService.run_detection_cycle()
  Stage 1: error_rate > 5%, error_count > 50  → HIGH incident
  Stage 2: current > mean + 2σ               → MEDIUM incident
  → Creates Incident → Triggers Phase 10 analysis → Attaches results

anomaly_worker.py — Celery beat task every 60s on 'anomaly' queue
celery_app.py — include list updated

API:
  GET  /api/v1/incidents
  GET  /api/v1/incidents/{id}
  PATCH /api/v1/incidents/{id}/status
  POST /api/v1/incidents  (manual creation)
```

**What connects forward:**
- Phase 12 (Root Cause Analysis) enriches each incident with metrics + traces
- Phase 13 (AI DevOps Assistant) uses incidents as a data source for answers
- Phase 14 (Android Dashboard) shows the incident list and severity counts
- Phase 15 (AIOps) closes the loop: detection → analysis → notification → human approval

Say `NEXT` to continue to **Phase 12 — Root Cause Analysis**.
