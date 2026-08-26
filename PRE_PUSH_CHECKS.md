# Pre-Push CI/CD Checks

Local script that mirrors every GitHub Actions gate before you push.
Catches failures on your machine instead of in CI.

---

## Script Location

```
pre-push-check.ps1   ← repo root
```

---

## Quick Start

Open PowerShell at the repo root and run:

```powershell
.\pre-push-check.ps1
```

Exit code `0` = safe to push. Exit code `1` = fix required.

---

## Flags

| Flag | Effect |
|------|--------|
| `-SkipAndroid` | Skip all Gradle/Android checks (lint, ktlint, Detekt, unit tests, module deps) |
| `-SkipBackend` | Skip all Python/backend checks (unit tests, integration tests) |
| `-SkipSecurity` | Skip Bandit and pip-audit |

Examples:

```powershell
# Backend + security only (skip the slow Gradle build)
.\pre-push-check.ps1 -SkipAndroid

# Android only (no Python environment needed)
.\pre-push-check.ps1 -SkipBackend -SkipSecurity

# Fast check — Android lint + ktlint + Detekt only, no tests
.\pre-push-check.ps1 -SkipBackend -SkipSecurity
# (then manually skip tests inside the script if needed)
```

---

## What Each Check Does

### 1 — Git Status Summary
Prints the current branch, how many commits are ahead of origin, modified files,
and **warns about untracked files** that will not be included in the push unless
they are committed first.

### 2 — Module Dependency Graph Lint
Mirrors: `android-ci.yml / dependency-lint`

Scans every `build.gradle.kts` for forbidden Clean Architecture dependency edges:

| Forbidden edge | Why |
|----------------|-----|
| `feature → feature` | Feature modules must be independent |
| `domain → data` | Domain must not know the data layer exists |
| `domain → feature` | Domain must not depend on any feature |
| `data → feature` | Data layer must not depend on any feature |

Pure text scan — runs in under a second, no Gradle needed.

### 3 — Android Lint
Mirrors: `android-ci.yml / android-lint`

```
./gradlew lintDebug --continue
```

Runs Android Lint across all modules. Reports saved to:
```
**/build/reports/lint-results*.html
**/build/reports/lint-results*.xml
```

### 4 — ktlint
Mirrors: `android-ci.yml / ktlint-detekt`

```
./gradlew ktlintCheck
```

Checks Kotlin code style. To auto-fix violations:
```powershell
.\gradlew ktlintFormat
```

Reports saved to:
```
**/build/reports/ktlint/
```

### 5 — Detekt
Mirrors: `android-ci.yml / ktlint-detekt`

```
./gradlew detekt
```

Static analysis for Kotlin code quality and complexity. Reports saved to:
```
**/build/reports/detekt/
```

### 6 — Android Unit Tests
Mirrors: `android-ci.yml / android-unit-tests`

```
./gradlew testDebugUnitTest --continue
```

Runs JUnit + MockK unit tests across all modules. Reports saved to:
```
**/build/reports/tests/
**/build/test-results/
```

### 7 — Backend Unit Tests
Mirrors: `backend-ci.yml / unit-tests`

```
pytest tests/unit/ --tb=short -q
```

Runs against mocked services — no PostgreSQL, Redis, or MinIO required.
Results saved to:
```
backend/unit-test-results.xml
```

### 8 — Backend Integration Tests
Mirrors: `backend-ci.yml / integration-tests`

```
pytest tests/integration/ --tb=short -q --timeout=30
```

Also runs with mocked services locally (CI uses real PostgreSQL + Redis containers).
If you have Docker running, you can spin up the real services first:
```powershell
docker compose up -d postgres redis
```
Results saved to:
```
backend/integration-test-results.xml
```

### 9 — Bandit
Mirrors: `security-scan.yml / bandit`

```
python -m bandit -r app --severity-level high --confidence-level high
```

Python SAST scanner. The CI gate blocks on HIGH severity + HIGH confidence findings.
MEDIUM findings are reported to SARIF but do not block the push.

### 10 — pip-audit
Mirrors: `security-scan.yml / safety`

```
python -m pip_audit --requirement requirements.txt --strict
```

Checks all Python dependencies against the OSV + PyPI advisory databases.

Ignored CVEs (chromadb — no upstream patch, port-binding mitigation in place):

| Advisory | CVE | Reason ignored |
|----------|-----|----------------|
| `GHSA-2wm9-hf6c-p5cr` | CVE-2026-45830 | cross-tenant authorization bypass — internal network only |
| `GHSA-36p7-vc44-83pf` | CVE-2026-45833 | authenticated RCE — internal network only |
| `GHSA-xph7-9rjv-w5fr` | CVE-2026-45831 | RBAC scope bug — internal network only |
| `CVE-2026-45829` | — | pre-auth RCE — internal network only |

When chromadb ships a patched release, remove the corresponding `--ignore-vuln` lines
from both `pre-push-check.ps1` and `.github/workflows/security-scan.yml`.

---

## Python Environment

The script automatically detects the correct Python interpreter in this order:

1. `backend/venv311/Scripts/python.exe` — project standard (Python 3.11)
2. `backend/venv/Scripts/python.exe` — fallback venv
3. `python` on system PATH — last resort

If neither venv exists, create it:

```powershell
py -3.11 -m venv backend\venv311
backend\venv311\Scripts\pip install -r backend\requirements.txt
```

---

## CI Parity Table

Every check here maps 1-to-1 to a required GitHub Actions status check.

| Local check | GitHub Actions job | Workflow file |
|-------------|-------------------|---------------|
| Module Dependency Lint | `android-ci / dependency-lint` | `android-ci.yml` |
| Android Lint | `android-ci / android-lint` | `android-ci.yml` |
| ktlint | `android-ci / ktlint-detekt` | `android-ci.yml` |
| Detekt | `android-ci / ktlint-detekt` | `android-ci.yml` |
| Android Unit Tests | `android-ci / android-unit-tests` | `android-ci.yml` |
| Backend Unit Tests | `android-ci / backend-unit-tests` | `android-ci.yml` |
| Backend Integration Tests | `android-ci / backend-integration-tests` | `android-ci.yml` |
| Bandit | `security-scanning / bandit` | `security-scan.yml` |
| pip-audit | `security-scanning / safety` | `security-scan.yml` |

Checks NOT covered locally (require cloud infrastructure):

| CI job | Why not local |
|--------|--------------|
| `hilt-ksp-gate` / `hilt-di-gate` | KSP code generation — run `./gradlew :app:kspLocalDebugKotlin` manually if needed |
| `jacoco-gate` | Coverage threshold — run `./gradlew :domain:jacocoTestReport :data:jacocoTestReport` manually |
| `CodeQL` | Requires GitHub infrastructure |
| `Trivy image scan` | Requires Docker + image build |
| `Gitleaks` | Run `gitleaks detect --source . -v` if gitleaks is installed |
| `build-signed-apk` | Requires release keystore secrets |
| `instrumented-tests` | Requires Android emulator |

---

## Troubleshooting

**Gradle wrapper permission error on first run**
```powershell
# gradlew.bat is used automatically on Windows — no chmod needed
.\gradlew tasks
```

**`pip-audit` or `bandit` not found**
The script installs them automatically into the active venv on first run.
If it fails, install manually:
```powershell
backend\venv311\Scripts\pip install "bandit[toml]==1.7.10" "pip-audit==2.9.0"
```

**Backend tests fail with `AES_ENCRYPTION_KEY` error**
The script injects a dummy base64 key for test runs. If you see this error,
confirm `ENVIRONMENT=test` is being picked up — the app skips key validation
in test mode.

**Android Lint reports false positives already in baseline**
The project uses `app/lint-baseline.xml` to suppress known issues.
If lint fails on something already baselined, regenerate the baseline:
```powershell
.\gradlew lintDebug -PupdateLintBaseline
```

**Integration tests hang**
Individual tests time out after 30 seconds (pytest-timeout).
If the whole suite hangs, check for a stuck Docker container or port conflict
on 5432 / 6379.

---

## Related Files

| File | Purpose |
|------|---------|
| `pre-push-check.ps1` | This script |
| `.github/workflows/android-ci.yml` | Android CI pipeline |
| `.github/workflows/backend-ci.yml` | Backend CI pipeline |
| `.github/workflows/security-scan.yml` | Security scanning pipeline |
| `.github/scripts/check-module-deps.sh` | Module dependency rules (bash version used in CI) |
| `.github/scripts/check-coverage.sh` | JaCoCo coverage gate (CI only) |
| `.github/scripts/check-tls-pin.sh` | TLS pin consistency check (CI only) |
| `backend/lint-check.ps1` | Backend-only ruff + black + mypy lint script |
| `backend/pytest.ini` | pytest configuration (asyncio_mode, timeout) |
| `android-lint-check.ps1` | Android-only lint runner |
