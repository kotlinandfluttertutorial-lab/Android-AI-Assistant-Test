# ============================================================================
# pre-push-check.ps1 GÇö Local CI/CD Mirror
# ============================================================================
#
# Runs every check that GitHub Actions enforces BEFORE you push, so you catch
# failures locally instead of in CI.
#
# Mirrors:
#   android-ci.yml    GåÆ Android Lint, ktlint, Detekt, Unit Tests
#   backend-ci.yml    GåÆ Python Unit Tests, Integration Tests
#   security-scan.yml GåÆ Bandit, pip-audit
#   check-module-deps.sh GåÆ Module dependency graph rules
#
# Usage (run from the repo root):
#   .\pre-push-check.ps1                  # full suite
#   .\pre-push-check.ps1 -SkipAndroid     # skip all Gradle/Android checks
#   .\pre-push-check.ps1 -SkipBackend     # skip all Python/backend checks
#   .\pre-push-check.ps1 -SkipSecurity    # skip Bandit + pip-audit
#   .\pre-push-check.ps1 -OnlyChanged     # only check files modified since last commit
#
# Exit code:
#   0 GÇö all checks passed, safe to push
#   1 GÇö one or more checks failed, do not push
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

# GöÇGöÇ Detect Python venv GöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇ
# Prefer venv311 (project standard), fall back to venv, then system Python.
$VENV311   = Join-Path $BACKEND_DIR "venv311\Scripts\python.exe"
$VENV      = Join-Path $BACKEND_DIR "venv\Scripts\python.exe"

if     (Test-Path $VENV311) { $PYTHON = $VENV311 ; $PIP = Join-Path $BACKEND_DIR "venv311\Scripts\pip.exe" }
elseif (Test-Path $VENV)    { $PYTHON = $VENV    ; $PIP = Join-Path $BACKEND_DIR "venv\Scripts\pip.exe"    }
else                         { $PYTHON = "python" ; $PIP = "pip" }

# GöÇGöÇ Colour helpers GöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇ
function Write-Header([string]$msg) {
    Write-Host ""
    Write-Host "GöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöü" -ForegroundColor DarkCyan
    Write-Host "  $msg" -ForegroundColor Cyan
    Write-Host "GöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöü" -ForegroundColor DarkCyan
}
function Write-Step([string]$msg)  { Write-Host "`n  Gû¦ $msg" -ForegroundColor Yellow }
function Write-Pass([string]$msg)  { Write-Host "  G£à $msg" -ForegroundColor Green  }
function Write-Fail([string]$msg)  { Write-Host "  G¥î $msg" -ForegroundColor Red    }
function Write-Skip([string]$msg)  { Write-Host "  GÅ¡  $msg" -ForegroundColor Gray   }
function Write-Info([string]$msg)  { Write-Host "     $msg" -ForegroundColor Gray   }

# GöÇGöÇ Result tracking GöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇ
$results = [ordered]@{}   # name GåÆ "PASS" | "FAIL" | "SKIP"

function Run-Check {
    param(
        [string]   $Name,
        [string]   $Dir,
        [string]   $Cmd,
        [string[]] $CmdArgs,
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
        & $Cmd @CmdArgs
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
    Write-Skip "$Name GÇö $Reason"
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
    Write-Host "`n  GÜá  Untracked files above will NOT be checked by CI unless committed." -ForegroundColor Magenta
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
    # GöÇGöÇ 1. Module Dependency Graph Lint GöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇ
    # Pure text scan GÇö runs in seconds, no Gradle needed.
    Write-Step "Module Dependency Graph Lint"
    $depViolations = 0
    $gradleFiles = Get-ChildItem -Path $ROOT -Recurse -Filter "build.gradle.kts" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch "\\build\\" -and $_.FullName -notmatch "\\.gradle\\" }

    $forbidden = @(
        @{ OwnerPattern = "^feature-"; DepPattern = "^feature-"; Rule = "feature GåÆ feature" },
        @{ OwnerPattern = "^domain$";  DepPattern = "^data$";    Rule = "domain GåÆ data"     },
        @{ OwnerPattern = "^domain$";  DepPattern = "^feature-"; Rule = "domain GåÆ feature"  },
        @{ OwnerPattern = "^data$";    DepPattern = "^feature-"; Rule = "data GåÆ feature"    }
    )

    foreach ($file in $gradleFiles) {
        $ownerName = $file.Directory.Name
        foreach ($line in (Get-Content $file.FullName)) {
            if ($line -match 'project\(":([\w-]+)"\)') {
                $dep = $Matches[1]
                foreach ($rule in $forbidden) {
                    if ($ownerName -match $rule.OwnerPattern -and $dep -match $rule.DepPattern) {
                        Write-Fail "$($rule.Rule) in $($file.FullName)"
                        Write-Info "  GåÆ $line"
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

    # GöÇGöÇ 2. Android Lint GöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇ
    Run-Check -Name "Android Lint" `
              -Dir  $ROOT `
              -Cmd  ".\gradlew" `
              -CmdArgs @("lintDebug", "--continue", "--quiet")

    # GöÇGöÇ 3. ktlint GöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇ
    Run-Check -Name "ktlint" `
              -Dir  $ROOT `
              -Cmd  ".\gradlew" `
              -CmdArgs @("ktlintCheck", "--quiet")

    # GöÇGöÇ 4. Detekt GöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇ
    Run-Check -Name "Detekt" `
              -Dir  $ROOT `
              -Cmd  ".\gradlew" `
              -CmdArgs @("detekt", "--quiet")

    # GöÇGöÇ 5. Android Unit Tests GöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇ
    Run-Check -Name "Android Unit Tests" `
              -Dir  $ROOT `
              -Cmd  ".\gradlew" `
              -CmdArgs @("testDebugUnitTest", "--continue", "--quiet",
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

    # GöÇGöÇ 6. Backend Unit Tests GöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇ
    Run-Check -Name "Backend Unit Tests" `
              -Dir  $BACKEND_DIR `
              -Cmd  $PYTHON `
              -CmdArgs @("-m", "pytest", "tests/unit/",
                      "--tb=short", "-q",
                      "--junit-xml=unit-test-results.xml") `
              -Env  $testEnv

    # GöÇGöÇ 7. Backend Integration Tests (mocked GÇö no live DB/Redis required) GöÇGöÇGöÇ
    Run-Check -Name "Backend Integration Tests" `
              -Dir  $BACKEND_DIR `
              -Cmd  $PYTHON `
              -CmdArgs @("-m", "pytest", "tests/integration/",
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
    # GöÇGöÇ 8. Bandit GÇö Python SAST GöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇ
    Write-Step "Bandit (HIGH severity + HIGH confidence gate)"
    Push-Location $BACKEND_DIR
    try {
        # Check if bandit is available
        $banditPath = & $PYTHON -c "import shutil; print(shutil.which('bandit') or '')" 2>$null
        if (-not $banditPath) {
            $banditVenv = Join-Path $BACKEND_DIR "venv311\Scripts\bandit.exe"
            if (-not (Test-Path $banditVenv)) {
                Write-Info "bandit not found GÇö installing..."
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
        Write-Info "Exception in Bandit: $_"
        $code = 1
    } finally {
        Pop-Location
    }

    if ($code -eq 0) {
        Write-Pass "Bandit"
        $results["Bandit"] = "PASS"
    } else {
        Write-Fail "Bandit GÇö findings detected"
        $results["Bandit"] = "FAIL"
    }

    # GöÇGöÇ 9. pip-audit GÇö dependency CVE check GöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇGöÇ
    Write-Step "pip-audit (CVE check)"
    Push-Location $BACKEND_DIR
    try {
        $pipauditPath = & $PYTHON -c "import shutil; print(shutil.which('pip-audit') or '')" 2>$null
        if (-not $pipauditPath) {
            Write-Info "pip-audit not found GÇö installing..."
            & $PIP install --quiet "pip-audit==2.9.0"
        }

        # Mirror the CI ignore list exactly (chromadb advisories with no upstream fix)
        & $PYTHON -m pip_audit `
            --requirement requirements.txt `
            --ignore-vuln GHSA-2wm9-hf6c-p5cr `
            --ignore-vuln GHSA-36p7-vc44-83pf `
            --ignore-vuln GHSA-xph7-9rjv-w5fr `
            --ignore-vuln CVE-2026-45829 --ignore-vuln CVE-2026-45830 --ignore-vuln CVE-2026-45831 --ignore-vuln CVE-2026-45833 `
            --strict
        $code = $LASTEXITCODE
    } catch {
        Write-Info "Exception in pip-audit: $_"
        $code = 1
    } finally {
        Pop-Location
    }

    if ($code -eq 0) {
        Write-Pass "pip-audit"
        $results["pip-audit"] = "PASS"
    } else {
        Write-Fail "pip-audit GÇö vulnerable dependencies detected"
        $results["pip-audit"] = "FAIL"
    }
}

# ============================================================================
# FINAL SUMMARY
# ============================================================================
Write-Host ""
Write-Host "GöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöü" -ForegroundColor DarkCyan
Write-Host "  Pre-Push Check Summary" -ForegroundColor Cyan
Write-Host "GöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöüGöü" -ForegroundColor DarkCyan

$passed  = 0
$failed  = 0
$skipped = 0

foreach ($check in $results.Keys) {
    $status = $results[$check]
    switch ($status) {
        "PASS" { Write-Host "  G£à  $check" -ForegroundColor Green  ; $passed++  }
        "FAIL" { Write-Host "  G¥î  $check" -ForegroundColor Red    ; $failed++  }
        "SKIP" { Write-Host "  GÅ¡   $check" -ForegroundColor Gray   ; $skipped++ }
    }
}

Write-Host ""
Write-Host "  Passed : $passed   Failed : $failed   Skipped : $skipped" -ForegroundColor White
Write-Host ""

if ($failed -gt 0) {
    Write-Host "  =ƒÜ½ DO NOT PUSH GÇö fix the $failed failing check(s) first." -ForegroundColor Red
    exit 1
} else {
    Write-Host "  G£à All checks passed GÇö safe to push." -ForegroundColor Green
    exit 0
}
