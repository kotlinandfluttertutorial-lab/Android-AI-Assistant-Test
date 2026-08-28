# Phase 15 — AIOps Guide

> **Learning goal:** Understand how to close the full AIOps loop — from automatic
> anomaly detection through AI analysis to human-approved remediation — and why
> the human-in-the-loop requirement is non-negotiable.
>
> **Career connection:** AIOps is the fastest-growing discipline in platform
> engineering. Every enterprise DevOps interview asks about it.

---

## 1. Concept — What Is AIOps?

AIOps (AI for IT Operations) applies machine learning and AI to automate the
detection, diagnosis, and remediation of production issues.

```
Traditional DevOps:               AIOps:
  Alert fires                       Alert fires automatically
    ↓                                 ↓ (Phase 11 anomaly detection)
  Engineer wakes up                 AI collects evidence
    ↓                                 ↓ (Phase 10/12 error analysis + RCA)
  Engineer reads logs               AI proposes ranked remediation actions
    ↓                                 ↓ (Phase 15 remediation service)
  Engineer decides fix              📱 Push notification to engineer
    ↓                                 ↓ (Firebase FCM → Android app)
  Engineer executes fix             Engineer reviews on phone
                                      ↓ (Phase 14 Android Dashboard)
                                    Engineer approves or rejects
                                      ↓ (Phase 15 approval API)
                                    Engineer executes manually
```

The key word: **assists**, not replaces. The AI prepares the decision; the human makes it.

---

## 2. The Full AIOps Loop Built in This Project

```
Observability Data (continuous)
  Android ObservabilityEvents → POST /api/v1/observability/events
         │
         ▼ (every 60 seconds — Celery beat)
Anomaly Detection (Phase 11)
  AnomalyDetectionService.run_detection_cycle()
  Stage 1: error_rate > 5% OR error_count > 50  → HIGH incident
  Stage 2: current > mean + 2σ                  → MEDIUM incident
         │
         ▼
Incident Auto-Created (Phase 11)
  IncidentRepository.create() → Incident (status: OPEN)
         │
         ├──► Phase 10: ErrorAnalysisService.analyse()
         │    → ai_summary, ai_confidence, ai_recommended_fix
         │
         ├──► Phase 15: RemediationService.recommend()
         │    → ranked RemediationAction list (RECOMMENDED status)
         │    → LOW: notify_slack, create_ticket
         │    → MEDIUM: restart_service, scale_up
         │    → HIGH: rollback, modify_config
         │
         └──► Phase 15: _notify_admins()
              → send_push_notification.delay() → FCM → Android
                         │
                         ▼
📱 Push Notification (Firebase FCM)
  Title: "🟠 HIGH Incident Detected"
  Body:  "High error rate (23% in 5 min)"
  Data:  {type: "incident_created", incident_id: "...", screen: "devops/incident/..."}
         │
         ▼
Human Reviews on Android Dashboard (Phase 14)
  DashboardScreen → IncidentListItem → (future) IncidentDetailScreen
  AiAnalysisCard → confidence bar, root cause, recommended fix
  RemediationCard → ranked actions with Approve / Reject buttons
         │
         ├──► Human Approves → POST /incidents/{id}/remediation/{action_id}/approve
         │    status: RECOMMENDED → APPROVED
         │    reviewed_by: user_id recorded
         │    ⚠️ NO AUTO-EXECUTION — engineer executes manually
         │
         └──► Human Rejects → POST /incidents/{id}/remediation/{action_id}/reject
              status: RECOMMENDED → REJECTED
              rejection_reason: recorded
```

---

## 3. Phase 15 Initial Delivery — Recommendation Only

The master plan states:

> Phase 15 initial delivery: **Recommendation only.**
> Automated actions introduced only after human-approval flow is tested.

This is intentional. Every serious AIOps deployment starts with:
1. **Observe** — detect anomalies, collect data
2. **Diagnose** — AI analyzes and proposes fixes
3. **Recommend** — show ranked actions to the human
4. **Approve** — human approves (records intent, not execution)
5. *Later:* **Execute** — automated execution after approval flow is validated

What was built in Phase 15:
- ✅ `RemediationAction` model + migration 0013
- ✅ `RemediationService.recommend()` — generates ranked actions
- ✅ `RemediationService.approve()` + `reject()` — records decisions
- ✅ `POST /incidents/{id}/remediation/recommend` — trigger recommendations
- ✅ `POST /incidents/{id}/remediation/{action_id}/approve` — approve
- ✅ `POST /incidents/{id}/remediation/{action_id}/reject` — reject
- ✅ `_notify_admins()` in `AnomalyDetectionService` — sends FCM on incident creation
- ✅ `RemediationCard` Android component — approve/reject UI

What is NOT yet built (future):
- ❌ Automated execution of `restart_service` (Cloud Run API call after approval)
- ❌ Automated execution of `rollback` (Cloud Run traffic split after approval)
- ❌ Alertmanager configuration (routes Prometheus alerts to Slack/PagerDuty)

---

## 4. Risk Tiers — Why They Matter

```
LOW risk (safe to execute with minimal review):
  notify_slack    — sends a Slack message; no infrastructure change
  create_ticket   — creates a Jira/GitHub issue; no infrastructure change

MEDIUM risk (requires brief pause to verify):
  restart_service — Cloud Run new revision; zero-downtime but restarts all connections
  scale_up        — increases max-instances; costs more money
  scale_down      — reduces capacity; dangerous if traffic is high

HIGH risk (requires careful review — may affect production data):
  rollback        — routes traffic to previous revision; correct change may be lost
  modify_config   — changes an env var or secret; may break other features
```

The `RemediationCard` on Android makes risk tiers visually obvious:
- LOW → blue `primaryContainer` badge
- MEDIUM → amber `Warning90` badge
- HIGH → red `error` badge + extra warning text before the Approve button

---

## 5. How the Push Notification Works

### Backend side

When `AnomalyDetectionService._create_incident_with_analysis()` commits the incident,
it immediately calls `_notify_admins()`:

```python
# From anomaly_detection_service.py
result = await self._db.execute(
    select(User.id, User.fcm_token).where(
        User.role == UserRole.admin,
        User.fcm_token.isnot(None),
        User.is_active.is_(True),
    )
)

for user_id, _fcm_token in admin_users:
    send_push_notification.delay(
        user_id = str(user_id),
        title   = f"{severity_emoji} {severity} Incident Detected",
        body    = incident_title,
        data    = {
            "type":        "incident_created",
            "incident_id": str(incident_id),
            "screen":      f"devops/incident/{incident_id}",
        },
    )
```

`send_push_notification` is an existing Celery task in `notification_worker.py`
that reads `user.fcm_token` from PostgreSQL and calls Firebase Admin SDK.

### Android side

The `fcm_token` is registered by calling `POST /notifications/device-token`
after Firebase token refresh. The token is stored in the `users.fcm_token` column.

When the FCM notification arrives with `data.type = "incident_created"`, the
Android app should deep-link to `DashboardRoute.SCREEN` (already registered in
MainActivity). The `screen` field in the data payload provides the deep-link path.

---

## 6. The Remediation Recommendation Engine

`RemediationService._build_recommendations()` uses a simple rule-based mapping:

| Condition | Action added |
|-----------|-------------|
| Always | notify_slack (LOW, confidence 0.95) |
| Always | create_ticket (LOW, confidence 0.90) |
| severity in HIGH/CRITICAL | restart_service (MEDIUM, confidence = ai_confidence × 0.8) |
| triggered_by contains "error_rate" or "error_count" | scale_up (MEDIUM, confidence 0.6) |
| RCA or AI summary mentions "deploy"/"release" | rollback (HIGH, confidence = ai_confidence × 0.7) |
| RCA or AI summary mentions "config"/"timeout"/"pool_size" | modify_config (HIGH, confidence = ai_confidence × 0.6) |

Actions are sorted by confidence (highest first) and ranked 1–N.

This is Stage 1 of the remediation recommendation engine. Future stages:
- **Stage 2**: Use the Phase 12 RCA `chain_of_thought` to generate custom actions
- **Stage 3**: Learn from historical approval/rejection patterns (ML)

---

## 7. Interview Questions

**Q1: What is AIOps? How does it differ from traditional DevOps?**

Traditional DevOps automates build, test, and deploy pipelines but leaves
incident response to humans. AIOps applies AI to operations — automatically
detecting anomalies, diagnosing root causes, and proposing remediation.

The key addition is the intelligence layer between "alert fired" and "engineer
acts": instead of the engineer reading raw logs and guessing a fix, the AI
presents a ranked list of options with confidence scores and evidence.

---

**Q2: Why is human approval required before automated remediation?**

Four reasons:

1. **Confidence is not certainty.** Even an 87% confidence score means 1 in 8
   diagnoses is wrong. Automatically executing a rollback on a wrong diagnosis
   could cause more damage than the original incident.

2. **Context the AI doesn't have.** The engineer knows: "We have a conference demo
   in 30 minutes — do NOT restart the service right now." No model knows this.

3. **Risk asymmetry.** The cost of a false negative (delayed fix) is usually lower
   than the cost of a false positive (automated action breaks production further).

4. **Audit trail.** "The AI did it" is never an acceptable root cause in a post-
   mortem. Human approval creates accountability.

---

**Q3: What is the `requires_confirmation` pattern in the MCP broker?**

`MCPToolConnector.requires_confirmation = True` on `CreateIncidentConnector` and
`create_incident` tool causes `MCPBroker.invoke()` to return
`result_status="confirmation_required"` without executing the tool. The DevOps
assistant (Phase 13) will then tell the user "I need your confirmation to do this."

This is the same pattern used in Phase 15 but at the tool-call level rather than
the remediation-action level. Both implement the same principle: write operations
require explicit human intent, not just a question that happens to imply one.

---

**Q4: How would you add automated execution of `restart_service`?**

After the human-approval flow has been validated in production:

1. Add an `execute` endpoint: `POST /incidents/{id}/remediation/{action_id}/execute`
2. This endpoint checks `status == "APPROVED"` before proceeding
3. It reads `params_json` for the service name and region
4. It calls the Cloud Run API:
   ```python
   from googleapiclient.discovery import build
   service = build("run", "v2")
   service.projects().locations().services().patch(
       name=f"projects/{project}/locations/{region}/services/{service_name}",
       body={"template": {"revision": ...}},
   ).execute()
   ```
5. Sets `status = "EXECUTING"` → `"COMPLETED"` or `"FAILED"`
6. Logs the execution to the audit trail

The `params_json` field on `RemediationAction` was designed to carry exactly
the parameters the execution function needs.

---

**Q5: What is the difference between Prometheus alerting rules and the application-level anomaly detection built here?**

Prometheus alerting rules (in `alerting.rules.yml`) fire against backend HTTP
metrics (`http_requests_total`, `http_request_duration_seconds`) and route to
Alertmanager → Slack/PagerDuty. They are infrastructure-level.

The application-level detection (Phase 11 `AnomalyDetectionService`) operates on
`observability_events` — the Android app's perspective. It creates structured
`Incident` records in PostgreSQL, triggers AI analysis, generates remediation
recommendations, and sends FCM push notifications. It's application-level and
feeds directly into the Phase 15 approval workflow.

Both fire at the same thresholds intentionally — they serve different consumers:
- Prometheus → on-call Slack channel
- Application detector → developer's Android phone with actionable AI context

---

## 8. Exercise

1. **Verify push notification** — become an admin user, insert your FCM token via
   `POST /notifications/device-token`, trigger an incident, and verify the push
   notification arrives on your Android device within 60 seconds.

2. **Test recommendation generation** — call
   `POST /api/v1/incidents/{id}/remediation/recommend` for a HIGH severity incident.
   Verify `notify_slack`, `create_ticket`, and `restart_service` appear in the
   response. Verify `restart_service` has lower confidence than `notify_slack`.

3. **Test the approval flow** — approve `notify_slack` and verify:
   - `status = "APPROVED"`
   - `reviewed_by = your_user_id`
   - `reviewed_at` is set
   - No actual Slack message was sent (recommendation only)

4. **Test the rejection flow** — reject `rollback` with reason
   "We have a demo in 30 minutes". Verify `rejection_reason` is stored.

5. **Add a new action type** — add `notify_email` to the `ACTION_CATALOGUE` in
   `RemediationService` and add a rule that always recommends it alongside
   `notify_slack`. Verify it appears in the recommendation list.

---

## Phase 15 Summary

**What was built:**

```
Backend:
  RemediationAction ORM model (remediation_actions table) + migration 0013
  RemediationService
    recommend(incident_id) → ranked RemediationPlan
    approve(action_id, reviewer)  → status APPROVED (no execution)
    reject(action_id, reviewer)   → status REJECTED
    list_actions(incident_id)
  AnomalyDetectionService._notify_admins()
    → queries admin users with FCM tokens
    → queues send_push_notification.delay() for each

API (appended to incidents router):
  POST /incidents/{id}/remediation/recommend
  GET  /incidents/{id}/remediation
  POST /incidents/{id}/remediation/{action_id}/approve
  POST /incidents/{id}/remediation/{action_id}/reject

Android:
  RemediationActionDto + RemediationApiService (Retrofit)
  RemediationCard Compose component (risk tier badges, Approve/Reject buttons)
  DashboardUiState extended with RemediationUiState
  DashboardViewModel extended with recommendRemediation/approveAction/rejectAction
```

**What comes next (post Phase 15):**
- Automated execution endpoints (after approval flow validated in production)
- Alertmanager configuration (routes Prometheus alerts to Slack/PagerDuty)
- Celery task for scheduled RCA on OPEN incidents

Say `NEXT` to continue to **Phase 16 — Security**.
