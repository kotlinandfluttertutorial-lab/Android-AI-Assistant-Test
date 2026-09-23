# ============================================================================
# local-check.ps1 — Local Development Validation Script (Windows)
# ============================================================================
#
# Purpose:
#   Validates the complete local development environment before pushing code.
#   Starts the Docker stack, waits for all services to be healthy, checks
#   health endpoints, runs backend tests, and runs Android unit tests.
#
# Usage (run from the repository root):
#   .\scripts\local-check.ps1                    # full suite
#   .\scripts\local-check.ps1 -SkipDocker        # skip Docker start/check
#   .\scripts\local-check.ps1 -SkipAndroid       # skip Android/Gradle tests
#   .\scripts\local-check.ps1 -SkipBackend       # skip Python/backend tests
#   .\scripts\local-check.ps1 -HealthOnly        # only check service health
#
# Prerequisites:
#   - Docker Desktop installed and running
#   - .env.local exists (copy .env.local.example → .env.local and fill values)
#   - Java 17+ on PATH (for Gradle)
#   - Python venv at backend/venv311 or backend/venv (for backend tests)
#
# Exit codes:
#   0  all checks passed
#   1  one or more checks failed
# ============================================================================

param(
    [switch]$SkipDocker,
    [switch]$SkipAndroid,
    [switch]$SkipBackend,
    [switch]$HealthOnly
)

Set-StrictMode -Off
$ErrorActionPreference = "Continue"

$ROOT        = Split-Path $PSScriptRoot -Parent
$COMPOSE_FILE = Join-Path $ROOT "docker-compose.local.yml"
$ENV_LOCAL    = Join-Path $ROOT ".env.local"
$BACKEND_DIR  = Join-Path $ROOT "backend"

# ── Python venv detection ─────────────────────────────────────────────────────
$VENV311 = Join-Path $BACKEND_DIR "venv311\Scripts\python.exe"
$VENV    = Join-Path $BACKEND_DIR "venv\Scripts\python.exe"
if     (Test-Path $VENV311) { $PYTHON = $VENV311 }
elseif (Test-Path $VENV)    { $PYTHON = $VENV    }
else                         { $PYTHON = "python" }

# ── Colour helpers ────────────────────────────────────────────────────────────
function Write-Header([string]$msg) {
    Write-Host ""
    Write-Host ("=" * 60) -ForegroundColor DarkCyan
    Write-Host "  $msg" -ForegroundColor Cyan
    Write-Host ("=" * 60) -ForegroundColor DarkCyan
}
function Write-Step([string]$msg)  { Write-Host "`n  >> $msg" -ForegroundColor Yellow }
function Write-Pass([string]$msg)  { Write-Host "  OK  $msg" -ForegroundColor Green  }
function Write-Fail([string]$msg)  { Write-Host "  FAIL  $msg" -ForegroundColor Red    }
function Write-Skip([string]$msg)  { Write-Host "  --  $msg" -ForegroundColor Gray   }
function Write-Info([string]$msg)  { Write-Host "     $msg"  -ForegroundColor Gray   }

$results = [ordered]@{}

function Record-Pass([string]$name) { $results[$name] = "PASS"; Write-Pass $name }
function Record-Fail([string]$name) { $results[$name] = "FAIL"; Write-Fail "$name FAILED" }
function Record-Skip([string]$name, [string]$reason) {
    $results[$name] = "SKIP"
    Write-Skip "$name — $reason"
}

# ── Preflight checks ──────────────────────────────────────────────────────────
Write-Header "Preflight"

# .env.local required
if (-not (Test-Path $ENV_LOCAL)) {
    Write-Fail ".env.local not found"
    Write-Info "Run:  cp .env.local.example .env.local"
    Write-Info "Then fill in SECRET_KEY, AES_ENCRYPTION_KEY, GEMINI_API_KEY."
    exit 1
}
Write-Pass ".env.local exists"

# Docker available
$dockerOk = $false
try {
    docker info *>$null 2>&1
    $dockerOk = ($LASTEXITCODE -eq 0)
} catch { }
if (-not $dockerOk) {
    if ($SkipDocker) {
        Write-Skip "Docker" "not running — -SkipDocker flag set"
    } else {
        Write-Fail "Docker is not running. Start Docker Desktop first."
        exit 1
    }
} else {
    Write-Pass "Docker is running"
}

# ── Docker stack ──────────────────────────────────────────────────────────────
Write-Header "Local Docker Stack"

if ($SkipDocker -or -not $dockerOk) {
    Record-Skip "Docker stack" "-SkipDocker"
} else {
    Write-Step "Starting services (docker compose -f docker-compose.local.yml up -d)"
    Push-Location $ROOT
    docker compose -f $COMPOSE_FILE up -d 2>&1 | Where-Object { $_ -notmatch "^#" }
    $upCode = $LASTEXITCODE
    Pop-Location

    if ($upCode -ne 0) {
        Record-Fail "Docker stack start"
        Write-Info "Check: docker compose -f docker-compose.local.yml logs"
        exit 1
    }
    Record-Pass "Docker stack start"

    # Wait for healthy services
    Write-Step "Waiting for services to become healthy (up to 120s)..."
    $services   = @("postgres", "redis", "chromadb", "minio", "backend", "nginx")
    $maxWait    = 120
    $elapsed    = 0
    $interval   = 5

    while ($elapsed -lt $maxWait) {
        $allHealthy = $true
        foreach ($svc in $services) {
            $state = docker inspect --format='{{.State.Health.Status}}' `
                "$( (Get-Item $ROOT).Name)_${svc}_1" 2>$null
            if (-not $state) {
                # Try alternate naming convention (Compose v2)
                $state = docker compose -f $COMPOSE_FILE ps --format json 2>$null |
                    ConvertFrom-Json | Where-Object { $_.Service -eq $svc } |
                    Select-Object -ExpandProperty Health -ErrorAction SilentlyContinue
            }
            if ($state -ne "healthy") {
                $allHealthy = $false
                break
            }
        }
        if ($allHealthy) { break }
        Start-Sleep $interval
        $elapsed += $interval
        Write-Info "  ...waiting ($elapsed/$maxWait s)"
    }

    if ($allHealthy) {
        Record-Pass "All services healthy"
    } else {
        Write-Info "Some services may not be fully healthy yet — continuing with health checks..."
    }
}

# ── Health endpoint checks ────────────────────────────────────────────────────
Write-Header "Service Health Checks"

if ($SkipDocker -or -not $dockerOk) {
    Record-Skip "Health checks" "-SkipDocker"
} else {
    $healthChecks = @(
        @{ Name = "Nginx gateway";  Url = "http://localhost:8080/health" },
        @{ Name = "FastAPI";        Url = "http://localhost:8000/health" },
        @{ Name = "ChromaDB";       Url = "http://127.0.0.1:8001/api/v1/heartbeat" },
        @{ Name = "MinIO";          Url = "http://localhost:9000/minio/health/live" },
        @{ Name = "Prometheus";     Url = "http://localhost:9090/-/healthy" },
        @{ Name = "Grafana";        Url = "http://localhost:3000/api/health" },
        @{ Name = "Loki";           Url = "http://localhost:3100/ready" }
    )

    foreach ($hc in $healthChecks) {
        Write-Step $hc.Name
        try {
            $resp = Invoke-WebRequest -Uri $hc.Url -TimeoutSec 10 -UseBasicParsing -ErrorAction Stop
            if ($resp.StatusCode -lt 400) {
                Record-Pass $hc.Name
            } else {
                Record-Fail "$($hc.Name) (HTTP $($resp.StatusCode))"
            }
        } catch {
            Record-Fail "$($hc.Name) — $($_.Exception.Message.Split([Environment]::NewLine)[0])"
        }
    }
}

if ($HealthOnly) {
    Write-Header "Health-Only Mode — skipping tests"
    $SkipBackend = $true
    $SkipAndroid = $true
}

# ── Backend tests ─────────────────────────────────────────────────────────────
Write-Header "Backend Tests"

if ($SkipBackend) {
    Record-Skip "Backend unit tests"        "-SkipBackend"
    Record-Skip "Backend integration tests" "-SkipBackend"
} else {
    $testEnv = @{
        SECRET_KEY            = "ci-local-check-secret-key-must-be-32-chars!"
        DATABASE_URL          = "postgresql+asyncpg://testuser:testpass@localhost:5432/testdb"
        REDIS_URL             = "redis://localhost:6379/0"
        OPENAI_API_KEY        = "sk-test-not-real"
        GEMINI_API_KEY        = "test-gemini-not-real"
        ANTHROPIC_API_KEY     = "sk-ant-test-not-real"
        OLLAMA_BASE_URL       = "http://localhost:11434"
        MINIO_ENDPOINT        = "localhost:9000"
        MINIO_ACCESS_KEY      = "minioadmin"
        MINIO_SECRET_KEY      = "minioadmin123"
        MINIO_BUCKET_NAME     = "test-bucket"
        LOKI_URL              = ""
        ENVIRONMENT           = "test"
        LOG_LEVEL             = "WARNING"
        AES_ENCRYPTION_KEY    = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
    $savedEnv = @{}
    foreach ($k in $testEnv.Keys) {
        $savedEnv[$k] = [System.Environment]::GetEnvironmentVariable($k)
        [System.Environment]::SetEnvironmentVariable($k, $testEnv[$k])
    }

    Write-Step "Backend unit tests"
    Push-Location $BACKEND_DIR
    & $PYTHON -m pytest tests/unit/ --tb=short -q --junit-xml=unit-test-results.xml
    if ($LASTEXITCODE -eq 0) { Record-Pass "Backend unit tests" }
    else                      { Record-Fail "Backend unit tests" }
    Pop-Location

    Write-Step "Backend integration tests (mocked)"
    Push-Location $BACKEND_DIR
    & $PYTHON -m pytest tests/integration/ --tb=short -q --timeout=30 --junit-xml=integration-test-results.xml
    if ($LASTEXITCODE -eq 0) { Record-Pass "Backend integration tests" }
    else                      { Record-Fail "Backend integration tests" }
    Pop-Location

    foreach ($k in $savedEnv.Keys) {
        [System.Environment]::SetEnvironmentVariable($k, $savedEnv[$k])
    }
}

# ── Android tests ─────────────────────────────────────────────────────────────
Write-Header "Android Tests (local flavor)"

if ($SkipAndroid) {
    Record-Skip "Android unit tests" "-SkipAndroid"
    Record-Skip "Android KSP gate"   "-SkipAndroid"
} else {
    Write-Step "Hilt KSP gate (kspLocalDebugKotlin)"
    Push-Location $ROOT
    .\gradlew.bat kspLocalDebugKotlin --no-daemon --quiet
    if ($LASTEXITCODE -eq 0) { Record-Pass "Android KSP gate" }
    else                      { Record-Fail "Android KSP gate" }
    Pop-Location

    Write-Step "Android unit tests (testLocalDebugUnitTest)"
    Push-Location $ROOT
    .\gradlew.bat :core-network:testLocalDebugUnitTest :core-ai:test --no-daemon --quiet --continue
    if ($LASTEXITCODE -eq 0) { Record-Pass "Android unit tests" }
    else                      { Record-Fail "Android unit tests" }
    Pop-Location
}

# ── Summary ───────────────────────────────────────────────────────────────────
Write-Header "Summary"

$passed = 0; $failed = 0; $skipped = 0
foreach ($k in $results.Keys) {
    switch ($results[$k]) {
        "PASS" { Write-Host "  OK   $k" -ForegroundColor Green; $passed++  }
        "FAIL" { Write-Host "  FAIL $k" -ForegroundColor Red;   $failed++  }
        "SKIP" { Write-Host "  --   $k" -ForegroundColor Gray;  $skipped++ }
    }
}
Write-Host ""
Write-Host "  Passed: $passed   Failed: $failed   Skipped: $skipped" -ForegroundColor White
Write-Host ""

if ($failed -gt 0) {
    Write-Host "  Local check FAILED — fix $failed issue(s) before pushing." -ForegroundColor Red
    exit 1
} else {
    Write-Host "  Local check PASSED — safe to push." -ForegroundColor Green
    exit 0
}
