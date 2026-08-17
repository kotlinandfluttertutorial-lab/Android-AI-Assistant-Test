# ============================================================================
# Android AI Assistant -- Windows Development Startup Script
# ============================================================================
#
# Starts all backend infrastructure services (PostgreSQL, Redis, MinIO,
# ChromaDB) using Docker Compose, then launches the FastAPI backend with
# hot-reload, a Celery worker, and a Cloudflare tunnel.
#
# Prerequisites:
#   - Docker Desktop running
#   - Python venv at backend\venv311 (run: python -m venv backend\venv311)
#   - backend\.env file configured (copy from backend\.env.example)
#   - cloudflared installed and tunnel configured
#
# Usage:
#   .\start-dev.ps1              # start infra + backend + celery + tunnel
#   .\start-dev.ps1 -InfraOnly  # start infra only (no uvicorn/celery/tunnel)
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
$CELERY  = Join-Path $VENV "Scripts\celery.exe"
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
Write-Step "Starting infrastructure services (postgres, redis, minio, chromadb)..."
docker compose -f "$ROOT\docker-compose.yml" up -d postgres redis minio chromadb

Write-Step "Waiting for services to be healthy..."
$maxWait = 60
$waited  = 0
while ($waited -lt $maxWait) {
    # Only check services that actually define a healthcheck (postgres, redis, minio).
    # chromadb has no healthcheck so Docker reports it as "" — exclude those.
    $unhealthy = docker compose -f "$ROOT\docker-compose.yml" ps --format json 2>$null |
        ForEach-Object { $_ | ConvertFrom-Json -ErrorAction SilentlyContinue } |
        Where-Object { $_.Health -ne $null -and $_.Health -ne "" -and $_.Health -ne "healthy" }
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
Write-Ok "Press Ctrl+C to stop the server, celery worker, and tunnel"

Push-Location $BACKEND
try {
    # Launch uvicorn in the background so we can start celery + cloudflared once it's ready
    $uvicornJob = Start-Job -ScriptBlock {
        param($uvicorn, $backend)
        Set-Location $backend
        & $uvicorn app.main:app --reload --host 0.0.0.0 --port 8000 --log-level info
    } -ArgumentList $UVICORN, $BACKEND

    # Launch Celery worker in the background
    Write-Step "Starting Celery worker (app.workers.celery_app)..."
    $celeryJob = Start-Job -ScriptBlock {
        param($celery, $backend)
        Set-Location $backend
        & $celery -A app.workers.celery_app worker --loglevel=info --concurrency=4
    } -ArgumentList $CELERY, $BACKEND
    Write-Ok "Celery worker started (background job)"

    # Wait until uvicorn is actually accepting connections (max 60 s)
    Write-Step "Waiting for uvicorn to be ready on port 8000..."
    $maxWait = 60
    $waited  = 0
    $ready   = $false
    while ($waited -lt $maxWait) {
        try {
            $tcp = New-Object System.Net.Sockets.TcpClient
            $tcp.Connect("127.0.0.1", 8000)
            $tcp.Close()
            $ready = $true
            break
        } catch {
            Start-Sleep -Seconds 1
            $waited += 1
        }
    }

    if (-not $ready) {
        Write-Error "uvicorn did not start within $maxWait seconds. Check logs above."
    }
    Write-Ok "uvicorn is up -- starting cloudflared tunnel..."

    # Start the cloudflared tunnel (blocks in foreground; Ctrl+C stops everything)
    cloudflared tunnel --config "C:\Users\admin\.cloudflared\config.yml" run mybackend

} finally {
    # Clean up background jobs when the script exits (Ctrl+C or error)
    if ($uvicornJob) {
        Stop-Job   $uvicornJob -ErrorAction SilentlyContinue
        Remove-Job $uvicornJob -ErrorAction SilentlyContinue
    }
    if ($celeryJob) {
        Stop-Job   $celeryJob -ErrorAction SilentlyContinue
        Remove-Job $celeryJob -ErrorAction SilentlyContinue
    }
    Pop-Location
}
