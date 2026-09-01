# Skill: Pre-Push CI/CD Checks

## Purpose
Run the full local CI/CD mirror (`pre-push-check.ps1`) before pushing any code,
and fix every failure so the GitHub Actions pipeline (`android-ci.yml`,
`backend-ci.yml`, `security-scan.yml`) passes clean.

## When to Use
- Before every `git push` to `feature/*`, `fix/*`, `develop`, or `main`
- After writing or modifying any Kotlin source file
- After modifying `backend/app/` Python source
- After changing `build.gradle.kts`, `libs.versions.toml`, or `requirements.txt`
- After any CI pipeline failure — run locally first to reproduce and fix

---

## Quick Run

From the **repo root** in PowerShell:

```powershell
# Full suite (Android + Backend + Security)
.\pre-push-check.ps1

# Skip slow Gradle tasks when only changing Python
.\pre-push-check.ps1 -SkipAndroid

# Skip Python tasks when only changing Kotlin
.\pre-push-check.ps1 -SkipBackend

# Skip Bandit + pip-audit
.\pre-push-check.ps1 -SkipSecurity
```

Exit `0` = safe to push. Exit `1` = one or more checks failed — do not push.

---

## Checks Reference

The script mirrors these GitHub Actions jobs in order:

| # | Check Name | Mirrors CI Job | Gradle / Python Command |
|---|-----------|----------------|------------------------|
| 1 | Module Dependency Lint | `dependency-lint` | Static scan of `build.gradle.kts` files |
| 2 | Android Lint | `android-lint` | `./gradlew lintDebug --continue --quiet` |
| 3 | ktlint | `ktlint-detekt` | `./gradlew ktlintCheck --quiet` |
| 4 | Detekt | `ktlint-detekt` | `./gradlew detekt --quiet` |
| 5 | Android Unit Tests | `android-unit-tests` | `./gradlew testDebugUnitTest --continue --quiet` |
| 6 | Backend Unit Tests | `backend-unit-tests` | `pytest tests/unit/ --tb=short -q` |
| 7 | Backend Integration Tests | `backend-integration-tests` | `pytest tests/integration/ --tb=short -q --timeout=30` |
| 8 | Bandit | `bandit` | `python -m bandit -r app --severity-level high --confidence-level high` |
| 9 | pip-audit | `safety` | `python -m pip_audit --requirement requirements.txt --strict` |

---

## Check Details and Fix Guide

### 1. Module Dependency Lint

**What it checks:** No forbidden cross-module dependencies in `build.gradle.kts` files.

**Forbidden edges:**

| From | To | Rule violated |
|------|----|--------------|
| `feature-*` | `feature-*` | feature → feature |
| `domain` | `data` | domain → data |
| `domain` | `feature-*` | domain → feature |
| `data` | `feature-*` | data → feature |

**Fix:** Remove the forbidden `project(":...")` dependency. The correct flow is:
```
feature → domain (interfaces) ← data (implementations)
```

**Example violation:**
```kotlin
// feature-chat/build.gradle.kts — WRONG
implementation(project(":feature-settings"))   // feature→feature forbidden

// Fix: move shared logic to :core-common or :domain
```

---

### 2. Android Lint

**Command:** `./gradlew lintDebug --continue --quiet`

**Fix strategy:**
- Read the HTML report at `<module>/build/reports/lint-results-debug.html`
- For baseline suppressions (false positives), add to the module's `lint-baseline.xml`
- For real issues, fix the source

**Common issues and fixes:**

| Issue | Fix |
|-------|-----|
| `HardcodedText` | Move string to `res/values/strings.xml` |
| `UnusedResources` | Delete the unused resource or use it |
| `MissingPermission` | Add `uses-permission` to `AndroidManifest.xml` |
| `NewApi` | Wrap with `if (Build.VERSION.SDK_INT >= X)` or add `@RequiresApi` |
| `ContentDescription` | Add `contentDescription` to every non-decorative `Image` / `Icon` composable |

---

### 3. ktlint

**Command:** `./gradlew ktlintCheck --quiet`

**Auto-fix:** Run `./gradlew ktlintFormat` to auto-fix most violations.

**Common violations:**

```kotlin
// Wrong: no space before {
if(condition){

// Correct
if (condition) {
```

```kotlin
// Wrong: wildcard import
import com.example.*

// Correct: explicit import
import com.example.MyClass
```

**Config:** Rules are governed by `.editorconfig` at the repo root.

---

### 4. Detekt

**Command:** `./gradlew detekt --quiet`

**Common violations and fixes:**

| Rule | Fix |
|------|-----|
| `MagicNumber` | Extract number to a named constant |
| `LongFunction` | Break function into smaller private functions |
| `ComplexCondition` | Extract boolean expression to a named `val` |
| `TooManyFunctions` | Move functions to a separate class |
| `UnusedPrivateMember` | Remove the unused member |
| `MaxLineLength` | Break the line; max is 140 chars |

**Config:** `detekt.yml` at the repo root.

---

### 5. Android Unit Tests

**Command:** `./gradlew testDebugUnitTest --continue --quiet`

**Fix strategy:**
- Read the HTML report at `<module>/build/reports/tests/testDebugUnitTest/index.html`
- Run a single failing test: `./gradlew :module:testDebugUnitTest --tests "com.example.MyTest"`

**JaCoCo gate:** CI requires combined `domain` + `data` instruction coverage ≥ 70%.
Check coverage locally:
```powershell
./gradlew :domain:jacocoTestReport :data:jacocoTestReport
# Open domain/build/reports/jacoco/jacocoTestReport/html/index.html
```

---

### 6. Backend Unit Tests

**Command (from `backend/` directory):**
```bash
python -m pytest tests/unit/ --tb=short -q --junit-xml=unit-test-results.xml
```

**Required env vars (set automatically by the script):**

| Variable | Test Value |
|----------|-----------|
| `SECRET_KEY` | `ci-test-secret-key-must-be-at-least-32-chars!` |
| `DATABASE_URL` | `postgresql+asyncpg://testuser:testpass@localhost:5432/testdb` |
| `REDIS_URL` | `redis://localhost:6379/0` |
| `OPENAI_API_KEY` | `sk-test-not-real` |
| `GEMINI_API_KEY` | `test-gemini-not-real` |
| `ANTHROPIC_API_KEY` | `sk-ant-test-not-real` |
| `AES_ENCRYPTION_KEY` | `AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=` |
| `ENVIRONMENT` | `test` |
| `LOG_LEVEL` | `WARNING` |

**Note:** Unit tests are fully mocked — no live PostgreSQL, Redis, or MinIO required.

**Fix strategy:**
- Run a single failing test: `python -m pytest tests/unit/test_my_module.py::test_function -v`
- All external services (DB, Redis, LLM providers) must be mocked with `unittest.mock`

---

### 7. Backend Integration Tests

**Command (from `backend/` directory):**
```bash
python -m pytest tests/integration/ --tb=short -q --timeout=30 \
    --junit-xml=integration-test-results.xml
```

Same env vars as unit tests. Integration tests are also mocked — no live services needed.

**Timeout:** Each test has a 30-second timeout enforced by `pytest-timeout`.

---

### 8. Bandit (Python SAST)

**Command (from `backend/` directory):**
```bash
python -m bandit -r app --severity-level high --confidence-level high \
    --format txt --exclude app/tests -q
```

**Gate:** HIGH severity + HIGH confidence findings fail the check.

**Common issues and fixes:**

| Issue | Bandit ID | Fix |
|-------|-----------|-----|
| Hardcoded password | B105/B106 | Read from env var via `os.environ` |
| SQL injection | B608 | Use parameterized queries (SQLAlchemy ORM, never raw `f"SELECT ... {var}"`) |
| `subprocess` with shell=True | B602 | Use `shell=False`, pass args as list |
| Weak random | B311 | Use `secrets.token_urlsafe()` for security contexts |
| `pickle` deserialization | B301 | Replace with `json` or explicitly ignore with comment if intentional |
| Hardcoded `/tmp` | B108 | Use `tempfile.mkdtemp()` |

**Suppressing a false positive** (only for confirmed false positives):
```python
result = subprocess.run(cmd, shell=False)  # noqa: S603
```

---

### 9. pip-audit (Python CVE Check)

**Command (from `backend/` directory):**
```bash
python -m pip_audit \
    --requirement requirements.txt \
    --ignore-vuln GHSA-2wm9-hf6c-p5cr \
    --ignore-vuln GHSA-36p7-vc44-83pf \
    --ignore-vuln GHSA-xph7-9rjv-w5fr \
    --ignore-vuln CVE-2026-45829 \
    --strict
```

**Ignored advisories** (chromadb — no upstream fix available; mitigated by internal Docker network):

| Advisory | CVE | Package | Reason Ignored |
|----------|-----|---------|---------------|
| `GHSA-2wm9-hf6c-p5cr` | CVE-2026-45830 | chromadb | Cross-tenant auth bypass; port not exposed externally |
| `GHSA-36p7-vc44-83pf` | CVE-2026-45833 | chromadb | Authenticated RCE via trust_remote_code; no public access |
| `GHSA-xph7-9rjv-w5fr` | CVE-2026-45831 | chromadb | RBAC scope bug; internal only |
| `CVE-2026-45829` | — | chromadb | Pre-auth RCE; mitigated by network isolation |

**Fix a real vulnerability:**
1. Update the package: `pip install "package>=fixed_version"`
2. Update `requirements.txt` with the new pinned version
3. Re-run the check to confirm it passes

**Adding a new ignore** (requires documented justification):
- Add `--ignore-vuln <ID>` to the command in `pre-push-check.ps1`
- Add a matching entry to the table above with justification and expiry date

---

## Python Virtual Environment Setup

The script auto-detects the Python executable in this priority order:

1. `backend/venv311/Scripts/python.exe` (**preferred** — project standard)
2. `backend/venv/Scripts/python.exe`
3. System `python`

**Create the project-standard venv:**
```powershell
cd backend
python -m venv venv311
.\venv311\Scripts\activate
pip install -r requirements.txt
pip install bandit[toml]==1.7.10 pip-audit==2.9.0
```

---

## CI Environment Variable Reference

When the pre-push script runs backend checks, it injects these env vars to mirror
the GitHub Actions environment. Your code must handle them gracefully — never crash
on missing external services when `ENVIRONMENT=test`.

```python
# Good: test-safe startup check
if os.environ.get("ENVIRONMENT") != "test":
    await verify_db_connection()
```

---

## Checklist Before Pushing

- [ ] `.\pre-push-check.ps1` exits with code `0`
- [ ] No forbidden module dependencies (`feature→feature`, `domain→data/feature`, `data→feature`)
- [ ] Android Lint passes on all modules
- [ ] ktlint passes — or run `./gradlew ktlintFormat` to auto-fix
- [ ] Detekt passes — no `error`-level findings
- [ ] All Android unit tests green
- [ ] `domain` + `data` JaCoCo coverage ≥ 70%
- [ ] Backend unit tests green (fully mocked)
- [ ] Backend integration tests green and complete within 30 s per test
- [ ] Bandit finds zero HIGH severity + HIGH confidence issues
- [ ] pip-audit finds zero unignored vulnerabilities
- [ ] No secrets committed (Gitleaks scans full history on every push to `main`)
