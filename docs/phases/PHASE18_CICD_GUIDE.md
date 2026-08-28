# Phase 18 — Production CI/CD Guide

> **Learning goal:** Understand the complete CI/CD pipeline — every workflow,
> every stage, and why each step exists.
>
> **Career connection:** "Walk me through your CI/CD pipeline" is asked in every
> DevOps and senior engineer interview. This project has a production-quality answer.

---

## 1. What CI/CD Does

```
Developer pushes code
        │
        ▼ (GitHub Actions triggered)
┌─────────────────────────────────────────────┐
│  CI — Continuous Integration                 │
│  "Does this code work correctly?"            │
│                                              │
│  Build → Test → Lint → Security Scan        │
│       → Docker Build → Push to Registry     │
└─────────────────────────────────────────────┘
        │ (if all passes)
        ▼
┌─────────────────────────────────────────────┐
│  CD — Continuous Deployment                  │
│  "Deploy the validated code to production"   │
│                                              │
│  Run Migrations → Deploy Cloud Run           │
│  → Smoke Test → Route Traffic                │
└─────────────────────────────────────────────┘
```

CI catches bugs before they reach production. CD makes deployment fast and
reliable — a 30-minute manual deploy becomes a 5-minute automated one.

---

## 2. Workflow Files

```
.github/workflows/
├── android-ci.yml          — Android build, test, lint, Detekt, security
├── backend-ci.yml          — pytest, Bandit, pip-audit, Docker build + push, deploy
├── cloud-run-deploy.yml    — standalone deploy workflow (manual trigger)
├── infrastructure-validation.yml — Terraform validate + Docker Compose test
├── release.yml             — tag-triggered: sign APK/AAB, Firebase distribution
└── security-scan.yml       — Trivy, Gitleaks, CodeQL, TLS pin check
```

---

## 3. Android CI Pipeline (`android-ci.yml`)

Triggered: every push to any branch + every PR to `main` or `develop`.

```
Checkout
    ↓
Setup JDK 17 + Gradle cache
    ↓
Build (assembleDebug, assembleRelease)
    ↓
Unit Tests (test) — JUnit 5 + Kotest
    ↓
Lint (lint) — blocks on errors, reports warnings
    ↓
Detekt — static analysis for Kotlin code quality
    ↓
Dependency-Check — OWASP CVE scan on Android dependencies
    ↓
Upload reports to GitHub Actions artifacts
```

**Key points:**
- Gradle wrapper is cached between runs — subsequent builds are 3× faster
- `lint-baseline.xml` captures existing violations so only NEW violations fail
- Detekt config in `config/detekt/detekt.yml` — team-agreed rules

---

## 4. Backend CI/CD Pipeline (`backend-ci.yml`)

Triggered: push to `main`/`develop`, PR to `main`.

```
Checkout
    ↓
Setup Python 3.11 + pip cache
    ↓
Install dependencies
    ↓
Bandit — Python security scanner (finds hardcoded secrets, SQL injection, etc.)
    ↓
pip-audit — check all dependencies against PyPI Advisory Database
    ↓
pytest (unit tests) — no Docker, no DB — fast
    ↓
pytest (integration tests) — with Postgres + Redis + ChromaDB Docker services
    ↓
Coverage check — fails if coverage drops below 80% on changed files
    ↓
Docker build (multi-stage, production target)
    ↓
Trivy image scan — scans built image for CRITICAL CVEs
    ↓
Push to Artifact Registry (sha-${{ github.sha }} tag)
    ↓
(on main only) Run Alembic migrations as Cloud Run Job
    ↓
(on main only) Deploy to Cloud Run
    ↓
Smoke test: GET /health → must return 200
```

**Pinned image tags:**
Every deploy uses the git SHA as the image tag (`sha-abc1234`). This means:
- Every revision is traceable to an exact commit
- Rolling back means deploying a previous SHA — no rebuilding needed
- `:latest` is never used in production

---

## 5. Security Scan Pipeline (`security-scan.yml`)

Triggered: push to `main`, weekly schedule (Sunday midnight).

```
Trivy filesystem scan — scans project code for secrets and vulnerabilities
    ↓
Gitleaks — scans git history for committed secrets
    ↓
CodeQL (JavaScript/Python/Kotlin) — semantic code analysis
    ↓
check-tls-pin.sh — verifies the certificate pin value is not default/test
    ↓
check-module-deps.sh — verifies feature modules don't import data layer directly
    ↓
Upload SARIF reports to GitHub Security tab
```

**Why weekly?** New CVEs are published daily. Even if no code changed, a dependency
might have a new vulnerability. The weekly scan catches this.

---

## 6. Release Pipeline (`release.yml`)

Triggered: git tag matching `v*.*.*` (e.g. `v1.2.3`).

```
Checkout tag
    ↓
Validate version code in build.gradle.kts matches tag
    ↓
Build release APK and AAB (signed with keystore from GitHub Secrets)
    ↓
Run full test suite on release build
    ↓
Create GitHub Release with APK attached
    ↓
Upload AAB to Firebase App Distribution (testers)
    ↓
(manual trigger) Upload AAB to Google Play internal track
```

**Code signing:**
The keystore is stored as a base64-encoded GitHub Secret. The CI decodes it,
creates a temporary keystore file, signs the release build, and immediately
deletes the file. The keystore password is a separate secret.

---

## 7. Branch Strategy

```
main         ← production-ready only; every push deploys automatically
develop      ← integration branch; CI runs but no deploy
feature/*    ← feature development; CI runs on PR
fix/*        ← bug fixes; CI runs on PR
release/*    ← release preparation; no auto-deploy until tagged
```

**Protection rules on `main`:**
- Require PR (no direct push)
- Require all CI checks to pass
- Require at least 1 review approval
- Dismiss stale approvals on new commits

---

## 8. Smoke Tests

After every Cloud Run deploy, the pipeline hits two endpoints:

```bash
# Health check — process is alive
curl $SERVICE_URL/health
# Expected: {"status":"ok"} — HTTP 200

# Readiness check — all dependencies connected
curl $SERVICE_URL/ready
# Expected: {"status":"ready"} — HTTP 200
```

If either returns non-200, the deploy is marked failed and the previous revision
continues receiving traffic. Cloud Run's traffic-splitting handles this automatically.

---

## 9. Interview Questions

**Q1: What is the difference between Continuous Integration, Continuous Delivery, and Continuous Deployment?**

**CI** — automated build and test on every commit. Ensures the code integrates
correctly with the rest of the codebase.

**Continuous Delivery** — the code is always in a deployable state. Every CI pass
produces an artifact that could be deployed to production. Deployment is manual.

**Continuous Deployment** — every CI pass automatically deploys to production.
No human step between commit and live traffic.

This project uses Continuous Deployment for the backend (every push to `main` deploys
automatically) and Continuous Delivery for the Android app (APK is produced but
must be manually promoted to Play Store).

---

**Q2: Why pin image tags to git SHAs instead of using `:latest`?**

`:latest` is a moving target. If you deploy `:latest` and it fails, you need to
know what was in `:latest` at the time of the failure. If `:latest` has since been
updated, you can't reproduce the issue.

With SHA tags (`sha-abc1234`), every running revision in Cloud Run has a tag that
directly maps to a git commit. You can reproduce the exact build, roll back to any
previous SHA, and audit exactly what code was running during an incident.

---

**Q3: What is a smoke test in a CI/CD pipeline?**

A smoke test is a minimal sanity check run immediately after deployment to verify
the service started correctly. It doesn't test business logic — it verifies the
service is reachable and its critical dependencies (DB, Redis) are connected.

In this project: `GET /health` (is the process alive?) and `GET /ready` (are DB and
Redis connected?). If either fails within 5 retries (with 8-second delays), the
deploy is marked failed and the previous revision stays live.

---

**Q4: How do you deploy a database migration safely alongside a code deploy?**

The migration runs as a Cloud Run Job **before** the new code revision goes live:

```
1. Alembic migration job runs (uses previous revision's image)
   → Adds new columns / creates new tables
2. New code revision deployed
   → Old code can still run (new columns have defaults / are nullable)
3. If migration fails → deploy aborted → old revision still live
```

This is the "expand-contract" pattern for zero-downtime migrations:
- **Expand:** Add new schema elements that old code ignores (Phase 1 deploy)
- **Contract:** Remove old schema elements once all code uses the new ones (Phase 2 deploy)

Destructive operations (DROP COLUMN) are always in a separate deploy from the code
change that stops using the column.

---

## Phase 18 Summary

The complete pipeline runs in ~8 minutes:

```
Push to main
  │
  ├── Android CI (~5 min): build, test, lint, Detekt, security scan
  │
  └── Backend CI (~8 min):
        Bandit + pip-audit (2 min)
        pytest unit (1 min)
        pytest integration (2 min)
        Docker build + Trivy scan (2 min)
        Push to Artifact Registry (30s)
        Alembic migration job (1 min)
        Cloud Run deploy (1 min)
        Smoke test (30s)
```

Total: ~8 minutes from push to live in production.

Say `NEXT` to continue to **Phase 19 — Jenkins**.
