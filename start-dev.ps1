# ============================================================================
# Android AI Assistant -- Windows Development Startup Script
# ============================================================================
#
# Starts all backend infrastructure services (PostgreSQL, Redis, MinIO) using
# Docker Compose, then launches the FastAPI backend with hot-reload.
#
# Prerequisites:
#   - Docker Desktop running
#   - Python venv at backend\venv311 (run: python -m venv backend\venv311)
#   - backend\.env file configured (copy from backend\.env.example)
#
# Usage:
#   .\start-dev.ps1              # start infra + backend
#   .\start-dev.ps1 -InfraOnly  # start infra only (no uvicorn)
#   .\start-dev.ps1 -Stop       # stop all Docker services
# ============================================================================

param(
    [switch]$InfraOnly,
    [switch]$Stop
)

$ErrorActionPreference = "Stop"
$ROOT    = $PSScriptRoot
$BACKEND = Join-Path $ROOT "backend"
$VENV    = Join-Path $BACKEND "venv311"
$UVICORN = Join-Path $VENV "Scripts\uvicorn.exe"
$ENV_FILE = Join-Path $BACKEND ".env"

# Prefer Python 3.11 — packages like asyncpg, tiktoken, pydantic-core require
# it (no pre-built wheels for Python 3.14 yet as of Aug 2026).
$PYTHON = "python3.11"
if (-not (Get-Command $PYTHON -ErrorAction SilentlyContinue)) {
    # Fall back to any py launcher alias
    $PYTHON = "py"
    $PYTHON_ARGS = @("-3.11")
} else {
    $PYTHON_ARGS = @()
}

function Write-Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "    $msg"   -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "    WARNING: $msg" -ForegroundColor Yellow }

# -- Stop mode ----------------------------------------------------------------
if ($Stop) {
    Write-Step "Stopping Docker Compose services..."
    docker compose -f "$ROOT\docker-compose.yml" down
    Write-Ok "All services stopped."
    exit 0
}

# -- Pre-flight checks --------------------------------------------------------
Write-Step "Checking prerequisites..."

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "Docker not found. Install Docker Desktop and ensure it is running."
}

if (-not (Test-Path $ENV_FILE)) {
    Write-Warn ".env not found at $ENV_FILE"
    Write-Warn "Copying .env.example -> .env (edit it with real values before production use)"
    Copy-Item "$BACKEND\.env.example" $ENV_FILE
}

if (-not (Test-Path $UVICORN)) {
    Write-Warn "venv311 not found. Creating virtual environment with Python 3.11..."
    if ($PYTHON_ARGS.Count -gt 0) {
        & $PYTHON @PYTHON_ARGS -m venv $VENV
    } else {
        & $PYTHON -m venv $VENV
    }
    & "$VENV\Scripts\pip.exe" install --upgrade pip
    & "$VENV\Scripts\pip.exe" install -r "$BACKEND\requirements.txt"
}

Write-Ok "Prerequisites OK"

# -- Start infrastructure -----------------------------------------------------
Write-Step "Starting infrastructure services (postgres, redis, minio)..."
docker compose -f "$ROOT\docker-compose.yml" up -d postgres redis minio

Write-Step "Waiting for services to be healthy..."
$maxWait = 60
$waited  = 0
while ($waited -lt $maxWait) {
    $unhealthy = docker compose -f "$ROOT\docker-compose.yml" ps --format json 2>$null |
        ConvertFrom-Json -ErrorAction SilentlyContinue |
        Where-Object { $_.Health -ne "healthy" -and $_.Health -ne "" }
    if (-not $unhealthy) { break }
    Start-Sleep -Seconds 3
    $waited += 3
}
Write-Ok "Infrastructure services are healthy"

if ($InfraOnly) {
    Write-Ok "InfraOnly mode -- skipping backend startup."
    exit 0
}

# -- Run database migrations --------------------------------------------------
Write-Step "Running Alembic migrations..."
Push-Location $BACKEND
try {
    & "$VENV\Scripts\alembic.exe" upgrade head
    Write-Ok "Migrations applied"
} finally {
    Pop-Location
}

# -- Start FastAPI backend ----------------------------------------------------
Write-Step "Starting FastAPI backend with hot-reload on http://localhost:8000 ..."
Write-Ok "API docs: http://localhost:8000/docs"
Write-Ok "Press Ctrl+C to stop the server"

Push-Location $BACKEND
try {
    & $UVICORN app.main:app --reload --host 0.0.0.0 --port 8000 --log-level info
} finally {
    Pop-Location
}
