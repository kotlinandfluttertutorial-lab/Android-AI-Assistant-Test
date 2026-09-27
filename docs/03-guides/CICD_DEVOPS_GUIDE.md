# CI/CD & DevOps Foundation — Phase 4 Learning Guide

A thorough walkthrough of the CI/CD pipeline: every workflow, why it exists,
how jobs depend on each other, what blocks a merge, and how to extend it.

---

## Table of Contents

1. [What is CI/CD and why does it matter?](#1-what-is-cicd-and-why-does-it-matter)
2. [Repository Structure & Git Strategy](#2-repository-structure--git-strategy)
3. [Workflow Overview](#3-workflow-overview)
4. [android-ci.yml — Android Pipeline](#4-android-ciyml--android-pipeline)
5. [backend-ci.yml — Backend Pipeline](#5-backend-ciyml--backend-pipeline)
6. [flutter-ci.yml — Flutter Pipeline](#6-flutter-ciyml--flutter-pipeline)
7. [security-scan.yml — Security Pipeline](#7-security-scanyml--security-pipeline)
8. [infrastructure-validation.yml — IaC Pipeline](#8-infrastructure-validationyml--iac-pipeline)
9. [cloud-run-deploy.yml — Production Deploy](#9-cloud-run-deployyml--production-deploy)
10. [release.yml — Release Pipeline](#10-releaseyml--release-pipeline)
11. [GitHub Secrets & Variables](#11-github-secrets--variables)
12. [Branch Protection Rules](#12-branch-protection-rules)
13. [How to Add a New CI Stage](#13-how-to-add-a-new-ci-stage)
14. [Key Design Decisions & Trade-offs](#14-key-design-decisions--trade-offs)
15. [Interview Questions](#15-interview-questions)
16. [Exercises](#16-exercises)

---

## 1. What is CI/CD and why does it matter?

**CI (Continuous Integration)** means every push to the repository triggers an
automated pipeline that builds, lints, tests, and security-scans the code.
Problems are caught within minutes of introduction — before they reach another
developer's machine or a production server.

**CD (Continuous Delivery/Deployment)** means the artifact (APK, Docker image)
can be shipped to production at any time because the pipeline has already verified
it is safe. The pipeline does the boring work so humans do the interesting work.

### Why this project needs CI/CD

| Without CI/CD | With CI/CD |
|---|---|
| "It works on my machine" | Consistent, reproducible builds on every push |
| Lint errors reach code review | Lint blocks the PR before human time is spent |
| Security vulnerabilities shipped | Trivy/Gitleaks/pip-audit catch them before merge |
| Deployment is manual and error-prone | One push to main → automatic deploy + smoke test |
| Coverage erodes silently | 70% coverage gate fails the build immediately |
| Untested Alembic migrations | upgrade + downgrade verified on every migration change |

### The three principles

1. **Fail fast** — the quickest checks run first (validate gate before heavier jobs)
2. **Shift left** — find problems as early as possible (lint on PR, not after merge)
3. **Don't block humans with machines** — CI should take < 10 minutes for a PR

---

## 2. Repository Structure & Git Strategy

### Branch model

```
main          ← production-ready code; protected; no direct push
  │
  └── develop ← integration branch; feature PRs target here
        │
        └── feature/RAG-screen
        └── fix/token-rotation-replay
        └── release/1.2.0
```

| Branch | Purpose | Who pushes |
|---|---|---|
| `main` | Production-ready; triggers deploy | Only via PR + approval |
| `develop` | Integration; triggers staging deploy | Only via PR |
| `feature/*` | Day-to-day development | Developer |
| `fix/*` | Bug fixes | Developer |
| `release/*` | Release preparation | Release manager |

### Tag format

```
v1.2.3        — stable release
v1.2.3-rc.1  — release candidate (pre-release)
```

Tags trigger `release.yml`. The tag name is validated with a regex before any
expensive work runs — an invalid tag fails the `validate-tag` job in under 5 seconds.

---

## 3. Workflow Overview

```
Event                   Workflow triggered
────────────────────────────────────────────────────────────────────
PR → main/develop       android-ci.yml
                        backend-ci.yml
                        flutter-ci.yml (flutter/ path filter)
                        security-scan.yml
                        infrastructure-validation.yml (infra path filter)
────────────────────────────────────────────────────────────────────
Push → main             android-ci.yml (build-signed-apk job)
                        backend-ci.yml (build-and-push → deploy)
                        flutter-ci.yml (build-ios job)
                        security-scan.yml (trivy-image job)
                        cloud-run-deploy.yml (backend/** path filter)
────────────────────────────────────────────────────────────────────
Push tag v*.*.*         release.yml
────────────────────────────────────────────────────────────────────
Schedule (Sunday 02:00) security-scan.yml (full scan including OWASP)
────────────────────────────────────────────────────────────────────
workflow_dispatch       all workflows (manual trigger)
────────────────────────────────────────────────────────────────────
```

### Path filters

Most workflows use `paths:` filters so they only run when relevant files change:

```yaml
# backend-ci.yml only triggers when backend/ or the workflow file changes
paths:
  - "backend/**"
  - ".github/workflows/backend-ci.yml"
```

This prevents a documentation change from triggering a 10-minute Android build.

---

## 4. android-ci.yml — Android Pipeline

### Job dependency graph

```
validate
    ├── dependency-lint
    ├── hilt-ksp-gate     ← KSP code generation — checks Hilt bindings compile
    ├── hilt-di-gate      ← same, different variant
    ├── android-lint      ← all modules
    ├── android-unit-tests ← testDebugUnitTest
    ├── ktlint-detekt     ← only fails on CHANGED .kt files
    └── jacoco-gate       ← coverage ≥ 70%
         │
         └── build-signed-apk  (main branch only — needs ALL PR gates green)
```

### Why each job exists

**`validate`** — checks `gradlew` wrapper checksum in under 10 seconds. Prevents
the rare but dangerous case of a tampered Gradle wrapper (supply chain attack).

**`dependency-lint`** — runs `.github/scripts/check-module-deps.sh` which enforces
Clean Architecture dependency rules. Forbidden edges:
- `feature-chat` → `feature-rag` (features must not depend on each other)
- `domain` → `data` or any `feature-*`
- `data` → any `feature-*`

This catches accidental cross-module imports before they become entrenched technical debt.

**`hilt-ksp-gate`** — runs `./gradlew :app:kspStageDebugKotlin`. Hilt generates code
at compile time via KSP. If a `@Provides` function returns the wrong type or a module
is missing, the KSP step fails with a clear error — much better than an app crash at runtime.

**`ktlint-detekt`** — uses `git diff --name-only origin/$base...HEAD` to find which
`.kt` files changed in this PR, then only fails the job if THOSE files have violations.
Full-tree violations are reported as SARIF annotations (informational) but don't block
the merge if they're in files not touched by this PR.

This avoids the "one engineer cleans up the whole codebase" problem — pre-existing
violations don't block unrelated PRs.

**`jacoco-gate`** — generates JaCoCo coverage reports and fails if line coverage
drops below 70%. The `koverVerify` Gradle task enforces this.

### The `google-services.json` stub

Every job that runs Gradle writes a stub `google-services.json` if the secret isn't set:

```yaml
- name: Write google-services.json
  env:
    GOOGLE_SERVICES_JSON: ${{ secrets.GOOGLE_SERVICES_JSON }}
  run: |
    if [ -n "$GOOGLE_SERVICES_JSON" ]; then
      echo "$GOOGLE_SERVICES_JSON" | base64 --decode > app/google-services.json
    elif [ ! -f app/google-services.json ]; then
      cat > app/google-services.json << 'EOF'
      {"project_info":...}  # CI placeholder
      EOF
    fi
```

This means the pipeline works without Firebase secrets configured — contributors
can run CI without access to the Firebase project.

---

## 5. backend-ci.yml — Backend Pipeline

### Job dependency graph (PR)

```
lint-and-type-check ──┐
unit-tests            ├──→ (all required for merge)
integration-tests ────┘
```

### Job dependency graph (main branch push)

```
build-and-push
      │
      └── deploy-staging
              │
              └── smoke-test-staging
                        │
                        └── deploy-production  ← requires manual approval
```

### The lint-and-type-check job

Previously commented out — now active. Three tools run sequentially:

```bash
ruff check . --output-format=github   # linter — inline PR annotations
ruff format . --check --diff          # formatter — shows what would change
mypy app --ignore-missing-imports     # type checker — catches type errors
```

**Why `--output-format=github` for ruff?**

This emits GitHub Actions workflow commands:
```
::error file=app/api/auth/router.py,line=42,col=8::F401 'uuid' imported but unused
```
GitHub converts these into inline annotations on the PR diff, showing the error
next to the exact line in the changed file.

**Why not `--strict` for mypy?**

`--strict` enables every mypy check including `--disallow-untyped-defs`,
`--warn-return-any`, and `--disallow-any-generics`. Third-party libraries like
pydantic v2, SQLAlchemy 2.x, and chromadb ship incomplete stubs that produce
hundreds of false positives in strict mode on a large codebase. `--ignore-missing-imports`
is the pragmatic choice: catch real type errors in our code, ignore missing stubs.

### Integration tests with service containers

```yaml
services:
  postgres:
    image: postgres:16-alpine
    env:
      POSTGRES_DB: testdb
    ports:
      - 5432:5432
    options: >-
      --health-cmd "pg_isready -U testuser -d testdb"
      --health-interval 10s
```

GitHub Actions service containers run as Docker containers alongside the job.
The `options` block configures health checks — the test job doesn't start until
Postgres signals `pg_isready`. This prevents flaky "connection refused" errors in
the first few seconds after the container starts.

### Docker image signing with cosign

```yaml
- name: Sign image with cosign (keyless OIDC)
  env:
    COSIGN_EXPERIMENTAL: "1"
  run: |
    cosign sign --yes \
      "$IMAGE@$DIGEST"
```

Keyless signing uses the GitHub OIDC token as identity — no private key to store.
The signature is published to the Sigstore Rekor transparency log. Anyone can verify:

```bash
cosign verify \
  --certificate-identity-regexp "https://github.com/your-org/your-repo" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  ghcr.io/your-org/your-repo/backend:latest
```

This proves the image was built by this specific GitHub Actions workflow and not
tampered with after publication — a key supply chain security control.

### Concurrency control

```yaml
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true
```

If a second push arrives while the first is still running (common when force-pushing
a PR), the first run is cancelled. This saves runner minutes and ensures the latest
code is always what gets tested.

**Important exception:** `cloud-run-deploy.yml` uses `cancel-in-progress: false`
because cancelling a deploy mid-flight (after the new image is pushed but before
Cloud Run finishes the revision transition) could leave the service in an unknown state.

---

## 6. flutter-ci.yml — Flutter Pipeline

### Job dependency graph

```
validate
    ├── analyze
    ├── format-check
    └── unit-tests
             │
             └── build-android  ← ubuntu-latest
             └── build-ios      ← macos-latest (main push only)
                      │
                      └── summary
```

### Why `--reporter=github` for flutter test

```yaml
flutter test --coverage --reporter=github
```

The `github` reporter emits GitHub workflow commands for test failures, showing
them as inline annotations on the PR diff. Without this, test failures only appear
in the raw log output.

### Why iOS is main-push only

macOS runners cost approximately 10× more than Ubuntu runners on GitHub Actions.
Running `flutter build ios --no-codesign` on every PR would be prohibitively
expensive. The iOS build runs on pushes to main, catching compilation errors
quickly after merge without burning budget on every PR.

The PR still gets full coverage from:
- `flutter analyze` — catches syntax and type errors (same as iOS compile errors)
- `unit-tests` — logic coverage
- `build-android` — full compilation verification on Android

### The `ENV=local` dart-define

```yaml
DART_DEFINE: "--dart-define=ENV=local"
```

Every CI build passes `--dart-define=ENV=local` so `ApiConfig.baseUrl` resolves
to `http://10.0.2.2:8000` (the Android emulator's loopback alias). No real backend
is needed for unit/widget tests — they mock all network calls.

---

## 7. security-scan.yml — Security Pipeline

### Scan matrix

| Scanner | What it finds | Frequency |
|---|---|---|
| CodeQL (Kotlin) | SQL injection, intent redirection, insecure crypto | PR + main |
| CodeQL (Python) | SQL injection, SSRF, hardcoded secrets, path traversal | PR + main |
| Gitleaks | Credentials, API keys, tokens in Git history | PR + main |
| Bandit | Python SAST: HIGH+HIGH confidence only as CI gate | PR + main |
| Trivy (image) | CVEs in OS packages + pip dependencies in Docker image | main push |
| Trivy (FS/IaC) | CVEs in source files, docker-compose.yml misconfiguration | PR + main |
| pip-audit | OSV + PyPI advisory CVEs in requirements.txt | PR + main |
| OWASP Dep-Check | NVD CVE database for Gradle + Python dependencies | Weekly |
| TLS Pin Check | SHA-256 pin consistency across source files | PR + main |

### The Gitleaks configuration

`.gitleaks.toml` at the repo root allows certain patterns that would otherwise
trigger false positives (test placeholder API keys, CI stub credentials). Gitleaks
scans the full Git history, not just the current commit — it catches secrets that
were committed and later removed.

### The pip-audit retry logic

```yaml
for attempt in 1 2 3; do
    AUDIT_OUT=$(pip-audit --requirement requirements.txt ...)
    EXIT=$?
    if echo "$AUDIT_OUT" | grep -qE "ServiceError|503 Server Error"; then
        sleep 20  # transient PyPI outage — retry
    else
        exit $EXIT  # real finding or success — don't retry
    fi
done
```

PyPI's advisory database has occasional 503 outages. The retry logic distinguishes
"PyPI is down" (retry) from "genuine vulnerability found" (fail immediately without
retry). Without this, transient PyPI outages cause false CI failures.

### SARIF uploads

Most security tools produce SARIF (Static Analysis Results Interchange Format)
output and upload it to GitHub Code Scanning:

```yaml
- uses: github/codeql-action/upload-sarif@v4
  with:
    sarif_file: bandit-results.sarif
    category: bandit-python
```

SARIF results appear in the Security tab → Code Scanning. They accumulate over time,
letting you track whether the vulnerability count is trending up or down.

---

## 8. infrastructure-validation.yml — IaC Pipeline

### Why validate infrastructure as code?

A typo in `docker-compose.yml` discovered at 2 AM during an on-call incident is
much more expensive than one caught by a 30-second CI job. The infrastructure
validation pipeline catches:

- Docker Compose YAML syntax errors
- Unpinned image tags (a security and reproducibility risk)
- ChromaDB port conflicts with the backend API
- Dockerfile violations (hadolint checks best practices)
- Nginx config syntax errors
- Prometheus rule YAML validity
- Grafana dashboard JSON validity
- Alembic migrations that can't upgrade, can't downgrade, or create multiple heads

### The Alembic heads check

```bash
HEAD_COUNT=$(alembic heads 2>&1 | grep -c "head")
if [[ "$HEAD_COUNT" -gt 1 ]]; then
  echo "::error::Multiple Alembic heads detected"
  exit 1
fi
```

Multiple heads mean two developers created migration files starting from the same
parent, creating a fork in the migration chain. This must be resolved by creating
a merge migration (`alembic merge heads`) before the PR can merge.

### The env var documentation check

```python
REQUIRED_VARS = ["DATABASE_URL", "SECRET_KEY", "AES_ENCRYPTION_KEY", "BACKEND_TLS_PIN_SHA256"]
# Checks that all required vars appear in .env.example files
```

This ensures the README and `.env.example` files stay in sync with what the application
actually requires. Removing a required env var from the docs is caught on the next PR.

---

## 9. cloud-run-deploy.yml — Production Deploy

### Pipeline stages

```
Push → main (backend/** changed)
    │
    ▼
build ─────────────────────────────────────────────────────────────────
  Authenticate via WIF (no long-lived service account key)
  Compute image reference: asia-south1-docker.pkg.dev/$PROJECT/backend/api:sha-$SHA
  Build linux/amd64 Docker image (no cache for clean dependency resolution)
  Push to Artifact Registry
  Output: image reference + digest
    │
    ▼
migrate (parallel with deploy prep) ──────────────────────────────────
  Create/update Cloud Run Job: alembic-migrate
  Execute: python -m alembic upgrade head
  Logs shown on failure
    │
    ▼
deploy + deploy-worker (both depend on build + migrate) ───────────────
  API service:    min=0, max=2, cpu=1, memory=1Gi, concurrency=40
  Worker service: min=1, max=1, cpu=1, memory=2Gi, no-cpu-throttling
  Secrets from Secret Manager (never in env_vars block)
    │
    ▼
smoke-test ────────────────────────────────────────────────────────────
  Wait 30s for revision to stabilise (Cloud Run cold-start)
  GET /health → must return 200
  GET /ready  → checks DB + Redis + env vars (warning, not failure if Redis absent)
```

### Workload Identity Federation (WIF)

Traditional GCP service account keys are JSON files stored as secrets. If leaked,
they grant persistent access. WIF replaces them:

```yaml
- uses: google-github-actions/auth@v2
  with:
    workload_identity_provider: ${{ secrets.GCP_WIF_PROVIDER }}
    service_account: ${{ secrets.GCP_SERVICE_ACCOUNT }}
```

How WIF works:
1. GitHub generates a short-lived OIDC token for the workflow run
2. The token proves "this workflow ran from this repository at this time"
3. GCP's WIF endpoint exchanges the OIDC token for a short-lived Google access token
4. The access token expires in ~1 hour, automatically

No long-lived credentials. No key rotation. No leak risk from a secret file.

### Why min-instances=0 for the API

`min-instances=0` means the service scales to zero when idle — no idle cost.
The tradeoff is cold-start latency (~10-30 seconds for the first request after
idle). For this project's usage pattern, the cost saving outweighs the cold-start
penalty.

`min-instances=1` for the Celery worker keeps it always warm because:
- Celery workers need to receive tasks from Redis at any time
- A task arriving while the worker is cold-starting would be queued but not processed
  until the worker wakes up — adding unpredictable delays to background jobs

### Immutable deploys via image digest

```yaml
# release.yml pins to the exact digest, not the tag
export BACKEND_IMAGE="ghcr.io/.../backend@sha256:abc123..."
docker pull "$BACKEND_IMAGE"
```

Pinning to a digest (`sha256:...`) instead of a tag guarantees you're running exactly
the image that was tested and signed. A tag like `:latest` can be overwritten by
a subsequent push.

---

## 10. release.yml — Release Pipeline

### The 9-stage release process

```
validate-tag        ── enforce vMAJOR.MINOR.PATCH semver
      │
create-release ─────── GitHub Release with auto-generated changelog
      │
android-release ────── AAB + APK (signed), Firebase distribution, Play Store
backend-release ─────── multi-arch Docker image, Trivy CRITICAL scan, cosign sign
      │
deploy-staging ──────── SSH rolling update to staging server
      │
smoke-test-staging ───── /health check with 5 retries
      │
deploy-production ───── SSH rolling update (requires manual approval gate)
      │
post-release-smoke ──── /health check with retry logic
      │
notify ──────────────── Slack announcement
```

### Version code computation

Android requires a monotonically increasing integer `versionCode`:

```bash
VERSION="${{ needs.validate-tag.outputs.version }}"  # e.g. "1.2.3"
IFS='.' read -r MAJOR MINOR PATCH <<< "${VERSION%%-*}"
VERSION_CODE=$(( MAJOR * 10000 + MINOR * 100 + PATCH ))
# v1.2.3 → 10203
# v2.0.0 → 20000
```

This formula guarantees that `versionCode` increases with every semver bump in
any position (major, minor, or patch).

### The manual approval gate

```yaml
deploy-production:
  environment:
    name: production
```

The `environment: production` declaration triggers GitHub's environment protection
rules. If "Required reviewers" is configured on the `production` environment in
Settings → Environments, the job waits for a human to approve before running.

This implements **human approval before any production-changing action** — a core
principle from the master plan.

### Firebase App Distribution

```yaml
- uses: wzieba/Firebase-Distribution-Github-Action@v1
  with:
    appId: ${{ secrets.FIREBASE_APP_ID }}
    serviceCredentialsFileContent: ${{ secrets.FIREBASE_SERVICE_ACCOUNT }}
    groups: ${{ secrets.FIREBASE_TESTER_GROUPS }}
    file: android-ai-assistant-1.2.3.apk
    releaseNotesFile: firebase-release-notes.txt
```

The APK is uploaded to Firebase App Distribution where configured testers can
install it on their devices. This is separate from Google Play — it's used for
internal testing before the AAB goes to the Play Store internal track.

---

## 11. GitHub Secrets & Variables

### Secrets vs Variables

| Type | Storage | Use case |
|---|---|---|
| **Secret** | Encrypted, masked in logs | API keys, passwords, private keys, service account JSON |
| **Variable** | Plain text, visible in logs | Project IDs, region names, non-sensitive config |

A common mistake: storing a GCP project ID as a secret. Project IDs are not sensitive
(they appear in public URLs). Using secrets for non-sensitive values causes problems
because GitHub masks them in logs, making debugging harder.

### Required secrets by workflow

| Workflow | Key secrets |
|---|---|
| `android-ci.yml` | `KEYSTORE_BASE64`, `KEY_ALIAS`, `KEY_PASSWORD`, `KEYSTORE_PASSWORD`, `GOOGLE_SERVICES_JSON` |
| `backend-ci.yml` | `AES_ENCRYPTION_KEY_CI`, `STAGING_SSH_HOST/USER/KEY`, `PROD_SSH_HOST/USER/KEY` |
| `flutter-ci.yml` | `CODECOV_TOKEN` (optional) |
| `security-scan.yml` | `NVD_API_KEY`, `GITLEAKS_LICENSE` (optional), `SLACK_WEBHOOK_URL` (optional) |
| `cloud-run-deploy.yml` | `GCP_WIF_PROVIDER`, `GCP_SERVICE_ACCOUNT`, `CLOUD_RUN_SERVICE`, `SECRET_KEY` (in Secret Manager) |
| `release.yml` | All of the above + `FIREBASE_APP_ID`, `FIREBASE_SERVICE_ACCOUNT`, `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` |

### Generating `AES_ENCRYPTION_KEY_CI`

```bash
python -c "import base64, os; print(base64.b64encode(os.urandom(32)).decode())"
```

Store the output as the `AES_ENCRYPTION_KEY_CI` repository secret. The backend's
unit and integration tests require this key to test AES-256 encryption of stored
API keys. The CI key is separate from the production key (which lives in Secret Manager).

---

## 12. Branch Protection Rules

Configure these in Settings → Branches → `main`:

### Required status checks

```
android-ci / validate
android-ci / android-lint
android-ci / android-unit-tests
android-ci / ktlint + Detekt
android-ci / jacoco-gate

backend-ci / lint-and-type-check
backend-ci / unit-tests
backend-ci / integration-tests

flutter-ci / analyze
flutter-ci / unit-tests
flutter-ci / build-android

security-scanning / CodeQL Analysis (java-kotlin)
security-scanning / CodeQL Analysis (python)
security-scanning / gitleaks
security-scanning / bandit
security-scanning / Trivy Filesystem & IaC Scan
security-scanning / safety

cloud-run-deploy / Build & Push Image
```

### Other settings

- **Require a pull request before merging** — prevents direct pushes to `main`
- **Require at least 1 approving review** — human review before merge
- **Dismiss stale pull request approvals when new commits are pushed** — prevents
  approving an old version then sneaking in a commit
- **Require branches to be up to date before merging** — prevents merge conflicts
  from breaking `main` after a long-running PR lands
- **Do not allow bypassing the above settings** — applies to admins too

---

## 13. How to Add a New CI Stage

### Example: add a frontend bundle size check

**Step 1 — Write the check script**

```bash
# .github/scripts/check-flutter-size.sh
APK=$1
SIZE=$(du -b "$APK" | cut -f1)
LIMIT=20000000  # 20 MB
if [ "$SIZE" -gt "$LIMIT" ]; then
  echo "::error::APK size ${SIZE}B exceeds limit ${LIMIT}B"
  exit 1
fi
echo "APK size: ${SIZE}B (limit: ${LIMIT}B) ✅"
```

**Step 2 — Add a job to `flutter-ci.yml`**

```yaml
apk-size-check:
  name: APK size check
  runs-on: ubuntu-latest
  needs: build-android          # depends on the build output

  steps:
    - uses: actions/checkout@v4

    - name: Download debug APK artifact
      uses: actions/download-artifact@v4
      with:
        name: flutter-debug-apk-${{ github.run_number }}
        path: apk/

    - name: Check APK size
      run: |
        chmod +x .github/scripts/check-flutter-size.sh
        .github/scripts/check-flutter-size.sh apk/app-debug.apk
      working-directory: .
```

**Step 3 — Add to branch protection required checks**

In Settings → Branches → `main` → Required status checks, add:
`flutter-ci / APK size check`

### Rules for new stages

1. **Fast first** — put the check in the earliest possible job position. If it takes 2
   minutes and catches 80% of problems, run it before the 15-minute full build.

2. **Fail clearly** — use `::error::` workflow commands for inline annotations.
   A log message buried in 500 lines of output wastes reviewer time.

3. **Upload artifacts on failure** — reports, logs, and diffs as artifacts let you debug
   without re-running the pipeline.

4. **Use `if: always()`** for artifact upload steps — they must run even when the test step fails.

5. **Cache aggressively** — `actions/cache@v4` keyed on `hashFiles('**/requirements.txt')` or
   `hashFiles('**/pubspec.yaml')` dramatically reduces install times for repeat runs.

---

## 14. Key Design Decisions & Trade-offs

### 14.1 Path filters on workflows

```yaml
paths:
  - "flutter/**"
  - ".github/workflows/flutter-ci.yml"
```

**Benefit:** A backend-only change doesn't trigger a 15-minute Flutter build.

**Trade-off:** If a shared file (e.g. a root `.gitignore`) affects the build, it won't
trigger the relevant workflow unless you add it to the path filter. Add filters for
any shared file that could break individual pipelines.

### 14.2 `cancel-in-progress: true` everywhere except deploys

Cancelling a mid-deploy is dangerous. Cloud Run's rollout involves:
1. Push new image
2. Create new revision
3. Route % of traffic to new revision
4. Complete traffic migration

If step 3 is cancelled, traffic is partially migrated — some requests go to the old
revision, some to the new one. Keeping `cancel-in-progress: false` on deploy workflows
ensures the rollout either completes or fails cleanly.

### 14.3 Stub google-services.json vs real secret

Contributors without Firebase access can still run CI. The stub google-services.json
has placeholder package names and app IDs that pass the Google Services Plugin's
validation without being real Firebase credentials.

**Trade-off:** Firebase-dependent features (Crashlytics, Push Notifications) are never
actually tested in CI — only their absence is verified. Real Firebase integration tests
would require a dedicated test Firebase project and the corresponding credentials.

### 14.4 Separate lint job vs running lint inside test job

Running `ruff check` inside the `unit-tests` job would save 30-60 seconds of
setup time per PR. The separate `lint-and-type-check` job was chosen because:

1. Lint failures should produce a separate named status check in branch protection
2. The lint job output (inline annotations) is cleaner when it's the only thing running
3. Lint and tests have different failure semantics — a lint failure doesn't tell you
   anything about test results; a test failure doesn't tell you anything about style

### 14.5 60% threshold vs 70% threshold

The JaCoCo gate is set at 70%. This is a pragmatic choice — 100% coverage would
require testing Hilt DI bindings and other boilerplate that has very low ROI. 70%
ensures meaningful coverage of business logic while allowing some infrastructure
code to remain untested.

### 14.6 Weekly OWASP Dependency-Check

OWASP Dependency-Check downloads the NIST NVD (National Vulnerability Database) on
every run — 500+ MB. Running it on every PR would take 10+ minutes and consume
significant bandwidth. Weekly (Sunday 02:00 UTC) catches new CVEs without slowing
down daily development.

`pip-audit` (runs on every PR) provides fast CVE coverage for Python packages using
the OSV advisory database which is lighter to query.

---

## 15. Interview Questions

**1. What is the difference between Continuous Integration and Continuous Delivery?**

CI automatically builds, tests, and validates code on every commit. CD goes further
— it automatically deploys validated code to staging or production. The distinction
matters: CI catches problems; CD ensures the artifact is always in a deployable state
and, in CD/D (deployment), actually deploys it.

In this project, CI runs on every PR (all tests + security scans). CD runs on
push to main (staging deploy + smoke test) and on semver tags (full production release).

**2. Why use Workload Identity Federation instead of a service account JSON key?**

Service account JSON keys are long-lived credentials that must be rotated and stored
securely. If leaked, they grant access until manually revoked. WIF issues short-lived
tokens (1-hour TTL) tied to the GitHub OIDC identity of the specific workflow run.
There's no credential to leak, rotate, or manage. The tokens expire automatically
and cannot be reused outside the originating workflow context.

**3. What does `concurrency: cancel-in-progress: true` do and when should you NOT use it?**

`cancel-in-progress: true` cancels any in-progress run in the same `group` when a
new run starts. This saves runner minutes when a developer pushes multiple commits
quickly. You should NOT use it for deploy jobs — cancelling a deploy mid-flight can
leave infrastructure in an inconsistent state (partial traffic migration, half-applied
Alembic migration, etc.).

**4. Explain the Alembic multiple-heads check and why it matters.**

Alembic migrations form a linked list where each migration has exactly one parent.
If two developers simultaneously create migrations based on the same parent, two
independent "head" revisions exist. Running `alembic upgrade head` is now ambiguous
— which head should be applied? The CI check `alembic heads | wc -l` catches this
and blocks the merge until the developer creates a merge migration
(`alembic merge heads -m "merge_branches"`).

**5. Why run ktlint and Detekt on changed files only instead of the full tree?**

Running on the full tree would block PRs for pre-existing violations the developer
didn't introduce. This creates friction and incentivises workarounds (adding `// ktlint-disable`
comments, or worse — bypassing CI). Scoping to changed files implements the "boy scout rule"
(leave things better than you found them) without punishing developers for other people's code.
Pre-existing violations are still reported as SARIF Code Scanning findings.

---

## 16. Exercises

These exercises reinforce the Phase 4 concepts. Each should take 30–60 minutes.

**Exercise 1 — Add a new lint rule**

The `ruff.toml` currently ignores `ANN001` (missing type annotations for function arguments).
Enable it by removing `"ANN001"` from the `ignore` list, then:
1. Run `ruff check app --select ANN001` locally to see what fails
2. Fix 5 functions in `app/services/` to add parameter type annotations
3. Verify `ruff check . --output-format=github` passes
4. Submit a PR and observe the inline GitHub annotations

**Exercise 2 — Add a coverage badge**

1. Add `codecov/codecov-action@v4` to the `unit-tests` job in `flutter-ci.yml`
   (it's already in `backend-ci.yml` — use that as a reference)
2. Generate the Codecov badge URL for the Flutter project
3. Add the badge to `flutter/README.md`

**Exercise 3 — Simulate a Gitleaks false positive**

1. Add a comment in `backend/app/services/auth_service.py` containing a fake API key:
   ```python
   # Example key (not real): sk-test-abcdef123456789012345678901234
   ```
2. Run `gitleaks detect --source .` locally to see the false positive
3. Add an allow-list entry to `.gitleaks.toml` that suppresses this specific pattern
4. Verify `gitleaks detect` passes after the allow-list entry

**Exercise 4 — Add a Flutter integration test to the workflow**

The integration test skeleton exists at `flutter/integration_test/app_test.dart`.
Add a new job to `flutter-ci.yml` that runs the integration tests on an Android emulator:

```yaml
integration-tests:
  name: integration-tests
  runs-on: ubuntu-latest
  needs: build-android
  if: github.event_name == 'push'  # main branch only
  
  steps:
    - uses: actions/checkout@v4
    - uses: subosito/flutter-action@v2
      with:
        flutter-version: ${{ env.FLUTTER_VERSION }}
    - name: Enable KVM for hardware acceleration
      run: |
        echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | \
          sudo tee /etc/udev/rules.d/99-kvm4all.rules
        sudo udevadm control --reload-rules
        sudo udevadm trigger --name-match=kvm
    - name: Run integration tests on Android emulator
      uses: reactivecircus/android-emulator-runner@v2
      with:
        api-level: 33
        script: |
          flutter test integration_test/ \
            --dart-define=ENV=local \
            -d emulator-5554
```

**Exercise 5 — Write a job that fails the pipeline on TODO comments**

Add a job to `backend-ci.yml` that fails if any Python file in `app/` contains a
`TODO` comment with an unresolved issue number (i.e., `TODO` without `#123` or `#456`):

```yaml
todo-check:
  name: no-untracked-todos
  runs-on: ubuntu-latest
  if: github.event_name == 'pull_request'
  defaults:
    run:
      working-directory: backend
  steps:
    - uses: actions/checkout@v4
    - name: Check for untracked TODOs
      run: |
        UNTRACKED=$(grep -rn "TODO" app/ \
          | grep -v "TODO.*#[0-9]" \
          | grep -v "# noqa")
        if [ -n "$UNTRACKED" ]; then
          echo "::error::Untracked TODO comments found (add a GitHub issue number):"
          echo "$UNTRACKED"
          exit 1
        fi
        echo "✅ All TODO comments have issue numbers"
```
