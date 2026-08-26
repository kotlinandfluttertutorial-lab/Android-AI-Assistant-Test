# ============================================================================
# pre-push-check.ps1 — Local CI/CD Mirror
# ============================================================================
#
# Runs every check that GitHub Actions enforces BEFORE you push, so you catch
# failures locally instead of in CI.
#
# Mirrors:
#   android-ci.yml    → Android Lint, ktlint, Detekt, Unit Tests
#   backend-ci.yml    → Python Unit Tests, Integration Tests
#   security-scan.yml → Bandit, pip-audit
#   check-module-deps.sh → Module dependency graph rules
#
# Usage (run from the repo root):
#   .\pre-push-check.ps1                  # full suite
#   .\pre-push-check.ps1 -SkipAndroid     # skip all Gradle/Android checks
#   .\pre-push-check.ps1 -SkipBackend     # skip all Python/backend checks
#   .\pre-push-check.ps1 -SkipSecurity    # skip Bandit + pip-audit
#   .\pre-push-check.ps1 -OnlyChanged     # only check files modified since last commit
#
# Exit code:
#   0 — all checks passed, safe to push
#   1 — one or more checks failed, do not push
# ============================================================================

param(
    [switch]$SkipAndroid,
    [switch]$SkipBackend,
    [switch]$SkipSecurity,
    [switch]$OnlyChanged
)

Set-StrictMode -Off
$ErrorActionPreference = "Continue"

$ROOT        = $PSScriptRoot
$BACKEND_DIR = Join-Path $ROOT "backend"

# ── Detect Python venv ──────────────────────────────────────────────────────
# Prefer venv311 (project standard), fall back to venv, then system Python.
$VENV311   = Join-Path $BACKEND_DIR "venv311\Scripts\python.exe"
$VENV      = Join-Path $BACKEND_DIR "venv\Scripts\python.exe"

if     (Test-Path $VENV311) { $PYTHON = $VENV311 ; $PIP = Join-Path $BACKEND_DIR "venv311\Scripts\pip.exe" }
elseif (Test-Path $VENV)    { $PYTHON = $VENV    ; $PIP = Join-Path $BACKEND_DIR "venv\Scripts\pip.exe"    }
else                         { $PYTHON = "python" ; $PIP = "pip" }

# ── Colour helpers ──────────────────────────────────────────────────────────
function Write-Header([string]$msg) {
    Write-Host ""
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkCyan
    Write-Host "  $msg" -ForegroundColor Cyan
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkCyan
}
function Write-Step([string]$msg)  { Write-Host "`n  ▶ $msg" -ForegroundColor Yellow }
function Write-Pass([string]$msg)  { Write-Host "  ✅ $msg" -ForegroundColor Green  }
function Write-Fail([string]$msg)  { Write-Host "  ❌ $msg" -ForegroundColor Red    }
function Write-Skip([string]$msg)  { Write-Host "  ⏭  $msg" -ForegroundColor Gray   }
function Write-Info([string]$msg)  { Write-Host "     $msg" -ForegroundColor Gray   }

# ── Result tracking ─────────────────────────────────────────────────────────
$results = [ordered]@{}   # name → "PASS" | "FAIL" | "SKIP"

function Run-Check {
    param(
        [string]   $Name,
        [string]   $Dir,
        [string]   $Cmd,
        [string[]] $Args,
        [hashtable]$Env = @{}
    )

    Write-Step $Name

    # Set env vars for this check
    $saved = @{}
    foreach ($k in $Env.Keys) {
        $saved[$k] = [System.Environment]::GetEnvironmentVariable($k)
        [System.Environment]::SetEnvironmentVariable($k, $Env[$k])
    }

    Push-Location $Dir
    try {
        & $Cmd @Args
        $code = $LASTEXITCODE
    } catch {
        Write-Info "Exception: $_"
        $code = 1
    } finally {
        Pop-Location
        foreach ($k in $saved.Keys) {
            [System.Environment]::SetEnvironmentVariable($k, $saved[$k])
        }
    }

    if ($code -eq 0) {
        Write-Pass $Name
        $results[$Name] = "PASS"
    } else {
        Write-Fail "$Name (exit $code)"
        $results[$Name] = "FAIL"
    }
}

function Skip-Check([string]$Name, [string]$Reason) {
    Write-Skip "$Name — $Reason"
    $results[$Name] = "SKIP"
}

# ============================================================================
# GIT STATUS SUMMARY
# ============================================================================
Write-Header "Git Status"

Push-Location $ROOT
$branch  = git rev-parse --abbrev-ref HEAD 2>$null
$ahead   = (git rev-list --count "@{u}..HEAD" 2>$null)
$modified = (git diff --name-only 2>$null) -join ", "
$untracked = (git ls-files --others --exclude-standard 2>$null) -join ", "
Pop-Location

Write-Info "Branch  : $branch"
if ($ahead)     { Write-Info "Ahead   : $ahead commit(s) not yet pushed" }
if ($modified)  { Write-Info "Modified: $modified" }
if ($untracked) { Write-Info "Untracked (not committed): $untracked" }

if ($untracked) {
    Write-Host "`n  ⚠  Untracked files above will NOT be checked by CI unless committed." -ForegroundColor Magenta
}

# ============================================================================
# ANDROID CHECKS  (mirrors android-ci.yml)
# ============================================================================
Write-Header "Android Checks"

if ($SkipAndroid) {
    Skip-Check "Android Lint"   "-SkipAndroid flag"
    Skip-Check "ktlint"         "-SkipAndroid flag"
    Skip-Check "Detekt"         "-SkipAndroid flag"
    Skip-Check "Android Unit Tests" "-SkipAndroid flag"
    Skip-Check "Module Dependency Lint" "-SkipAndroid flag"
} else {
    # ── 1. Module Dependency Graph Lint ─────────────────────────────────────
    # Pure text scan — runs in seconds, no Gradle needed.
    Write-Step "Module Dependency Graph Lint"
    $depViolations = 0
    $gradleFiles = Get-ChildItem -Path $ROOT -Recurse -Filter "build.gradle.kts" |
        Where-Object { $_.FullName -notmatch "\\build\\" -and $_.FullName -notmatch "\\.gradle\\" }

    $forbidden = @(
        @{ OwnerPattern = "^feature-"; DepPattern = "^feature-"; Rule = "feature → feature" },
        @{ OwnerPattern = "^domain$";  DepPattern = "^data$";    Rule = "domain → data"     },
        @{ OwnerPattern = "^domain$";  DepPattern = "^feature-"; Rule = "domain → feature"  },
        @{ OwnerPattern = "^data$";    DepPattern = "^feature-"; Rule = "data → feature"    }
    )

    foreach ($file in $gradleFiles) {
        $ownerName = $file.Directory.Name
        $content   = Get-Content $file.FullName -Raw
        $lineNum   = 0
        foreach ($line in (Get-Content $file.FullName)) {
            $lineNum++
            if ($line -match 'project\(":([\w-]+)"\)') {
                $dep = $Matches[1]
                foreach ($rule in $forbidden) {
                    if ($ownerName -match $rule.OwnerPattern -and $dep -match $rule.DepPattern) {
                        Write-Fail "$($rule.Rule) in $($file.FullName):$lineNum"
                        Write-Info "  → $line"
                        $depViolations++
                    }
                }
            }
        }
    }

    if ($depViolations -eq 0) {
        Write-Pass "Module Dependency Graph Lint"
        $results["Module Dependency Lint"] = "PASS"
    } else {
        Write-Fail "Module Dependency Graph Lint ($depViolations violation(s))"
        $results["Module Dependency Lint"] = "FAIL"
    }

    # ── 2. Android Lint ─────────────────────────────────────────────────────
    Run-Check -Name "Android Lint" `
              -Dir  $ROOT `
              -Cmd  ".\gradlew" `
              -Args @("lintDebug", "--continue", "--quiet")

    # ── 3. ktlint ───────────────────────────────────────────────────────────
    Run-Check -Name "ktlint" `
              -Dir  $ROOT `
              -Cmd  ".\gradlew" `
              -Args @("ktlintCheck", "--quiet")

    # ── 4. Detekt ───────────────────────────────────────────────────────────
    Run-Check -Name "Detekt" `
              -Dir  $ROOT `
              -Cmd  ".\gradlew" `
              -Args @("detekt", "--quiet")

    # ── 5. Android Unit Tests ───────────────────────────────────────────────
    Run-Check -Name "Android Unit Tests" `
              -Dir  $ROOT `
              -Cmd  ".\gradlew" `
              -Args @("testDebugUnitTest", "--continue", "--quiet",
                      "-Porg.gradle.jvmargs=-Xmx4g")
}

# ============================================================================
# BACKEND CHECKS  (mirrors backend-ci.yml)
# ============================================================================
Write-Header "Backend Checks"

if ($SkipBackend) {
    Skip-Check "Backend Unit Tests"        "-SkipBackend flag"
    Skip-Check "Backend Integration Tests" "-SkipBackend flag"
} else {
    # Env vars that mirror the CI environment (no real services required)
    $testEnv = @{
        SECRET_KEY       = "ci-test-secret-key-must-be-at-least-32-chars!"
        DATABASE_URL     = "postgresql+asyncpg://testuser:testpass@localhost:5432/testdb"
        REDIS_URL        = "redis://localhost:6379/0"
        OPENAI_API_KEY   = "sk-test-not-real"
        GEMINI_API_KEY   = "test-gemini-not-real"
        ANTHROPIC_API_KEY= "sk-ant-test-not-real"
        OLLAMA_BASE_URL  = "http://localhost:11434"
        MINIO_ENDPOINT   = "localhost:9000"
        MINIO_ACCESS_KEY = "minioadmin"
        MINIO_SECRET_KEY = "minioadmin123"
        MINIO_BUCKET     = "test-bucket"
        LOKI_URL         = ""
        ENVIRONMENT      = "test"
        LOG_LEVEL        = "WARNING"
        AES_ENCRYPTION_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }

    # ── 6. Backend Unit Tests ────────────────────────────────────────────────
    Run-Check -Name "Backend Unit Tests" `
              -Dir  $BACKEND_DIR `
              -Cmd  $PYTHON `
              -Args @("-m", "pytest", "tests/unit/",
                      "--tb=short", "-q",
                      "--junit-xml=unit-test-results.xml") `
              -Env  $testEnv

    # ── 7. Backend Integration Tests (mocked — no live DB/Redis required) ───
    # NOTE: If PostgreSQL + Redis are not running locally, these will be skipped
    #       automatically by the conftest fixture guards.
    Run-Check -Name "Backend Integration Tests" `
              -Dir  $BACKEND_DIR `
              -Cmd  $PYTHON `
              -Args @("-m", "pytest", "tests/integration/",
                      "--tb=short", "-q", "--timeout=30",
                      "--junit-xml=integration-test-results.xml") `
              -Env  $testEnv
}

# ============================================================================
# SECURITY CHECKS  (mirrors security-scan.yml)
# ============================================================================
Write-Header "Security Checks"

if ($SkipSecurity) {
    Skip-Check "Bandit"    "-SkipSecurity flag"
    Skip-Check "pip-audit" "-SkipSecurity flag"
} else {
    # ── 8. Bandit — Python SAST ──────────────────────────────────────────────
    Write-Step "Bandit (HIGH severity + HIGH confidence gate)"
    Push-Location $BACKEND_DIR
    try {
        # Check if bandit is available
        $banditPath = & $PYTHON -c "import shutil; print(shutil.which('bandit') or '')" 2>$null
        if (-not $banditPath) {
            $banditVenv = Join-Path $BACKEND_DIR "venv311\Scripts\bandit.exe"
            if (-not (Test-Path $banditVenv)) {
                Write-Info "bandit not found — installing..."
                & $PIP install --quiet "bandit[toml]==1.7.10"
            }
        }

        & $PYTHON -m bandit `
            -r app `
            --severity-level high `
            --confidence-level high `
            --format txt `
            --exclude app/tests `
            -q
        $code = $LASTEXITCODE
    } catch {
        $code = 1
    } finally {
        Pop-Location
    }

    if ($code -eq 0) {
        Write-Pass "Bandit"
        $results["Bandit"] = "PASS"
    } else {
        Write-Fail "Bandit — HIGH+HIGH severity findings detected"
        $results["Bandit"] = "FAIL"
    }

    # ── 9. pip-audit — dependency CVE check ──────────────────────────────────
    Write-Step "pip-audit (CVE check)"
    Push-Location $BACKEND_DIR
    try {
        $pipauditPath = & $PYTHON -c "import shutil; print(shutil.which('pip-audit') or '')" 2>$null
        if (-not $pipauditPath) {
            Write-Info "pip-audit not found — installing..."
            & $PIP install --quiet "pip-audit==2.9.0"
        }

        # Mirror the CI ignore list exactly (chromadb advisories with no upstream fix)
        & $PYTHON -m pip_audit `
            --requirement requirements.txt `
            --ignore-vuln GHSA-2wm9-hf6c-p5cr `
            --ignore-vuln GHSA-36p7-vc44-83pf `
            --ignore-vuln GHSA-xph7-9rjv-w5fr `
            --ignore-vuln CVE-2026-45829 `
            --strict
        $code = $LASTEXITCODE
    } catch {
        $code = 1
    } finally {
        Pop-Location
    }

    if ($code -eq 0) {
        Write-Pass "pip-audit"
        $results["pip-audit"] = "PASS"
    } else {
        Write-Fail "pip-audit — vulnerable dependencies detected"
        $results["pip-audit"] = "FAIL"
    }
}

# ============================================================================
# FINAL SUMMARY
# ============================================================================
Write-Host ""
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkCyan
Write-Host "  Pre-Push Check Summary" -ForegroundColor Cyan
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkCyan

$passed  = 0
$failed  = 0
$skipped = 0

foreach ($check in $results.Keys) {
    $status = $results[$check]
    switch ($status) {
        "PASS" { Write-Host "  ✅  $check" -ForegroundColor Green  ; $passed++  }
        "FAIL" { Write-Host "  ❌  $check" -ForegroundColor Red    ; $failed++  }
        "SKIP" { Write-Host "  ⏭   $check" -ForegroundColor Gray   ; $skipped++ }
    }
}

Write-Host ""
Write-Host "  Passed : $passed   Failed : $failed   Skipped : $skipped" -ForegroundColor White
Write-Host ""

if ($failed -gt 0) {
    Write-Host "  🚫 DO NOT PUSH — fix the $failed failing check(s) above first." -ForegroundColor Red
    Write-Host ""
    Write-Host "  Lint reports : **/build/reports/lint-results*.html"      -ForegroundColor Gray
    Write-Host "  ktlint       : **/build/reports/ktlint/"                 -ForegroundColor Gray
    Write-Host "  Detekt       : **/build/reports/detekt/"                 -ForegroundColor Gray
    Write-Host "  Test results : backend/unit-test-results.xml"            -ForegroundColor Gray
    Write-Host "                 backend/integration-test-results.xml"     -ForegroundColor Gray
    exit 1
} else {
    Write-Host "  ✅ All checks passed — safe to push." -ForegroundColor Green
    exit 0
}
