# ============================================================================
# Android AI Assistant -- Windows Development Startup Script
# ============================================================================
#
# Starts the full stack via Docker Compose:
#   postgres . redis . minio . chromadb . backend (uvicorn) . celery_worker
# Then runs Alembic migrations inside the backend container and starts the
# Cloudflare tunnel once the API is reachable.
#
# Prerequisites:
#   - Docker Desktop running
#   - backend\.env file configured (copy from backend\.env.example)
#   - cloudflared installed and tunnel configured
#
# Usage:
#   .\start-dev.ps1             # build (if needed) + start all + tunnel
#   .\start-dev.ps1 -Build      # force rebuild backend image before starting
#   .\start-dev.ps1 -InfraOnly  # start infra only (no backend/celery/tunnel)
#   .\start-dev.ps1 -Stop       # stop all Docker services
# ============================================================================

param(
    [switch]$Build,
    [switch]$InfraOnly,
    [switch]$Stop
)

$ErrorActionPreference = 'Stop'
$ROOT     = $PSScriptRoot
$BACKEND  = Join-Path $ROOT 'backend'
$ENV_FILE = Join-Path $BACKEND '.env'

function Write-Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "    $msg"   -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "    WARNING: $msg" -ForegroundColor Yellow }

# Helper so we never have path-with-backslash issues inside expandable strings
function Compose {
    docker compose -f "$ROOT\docker-compose.yml" @args
}

# -- Stop mode ----------------------------------------------------------------
if ($Stop) {
    Write-Step 'Stopping all Docker Compose services...'
    Compose down
    Write-Ok 'All services stopped.'
    exit 0
}

# -- Pre-flight checks --------------------------------------------------------
Write-Step 'Checking prerequisites...'

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error 'Docker not found. Install Docker Desktop and ensure it is running.'
}

if (-not (Test-Path $ENV_FILE)) {
    Write-Warn '.env not found'
    Write-Warn 'Copying .env.example -> .env (edit it with real values before production use)'
    Copy-Item "$BACKEND\.env.example" $ENV_FILE
}

Write-Ok 'Prerequisites OK'

# -- Build (optional) ---------------------------------------------------------
if ($Build) {
    Write-Step 'Building backend and celery_worker images...'
    Compose build backend celery_worker
    Write-Ok 'Build complete'
}

# -- Start infrastructure services --------------------------------------------
Write-Step 'Starting infrastructure services (postgres, redis, minio, chromadb)...'
Compose up -d postgres redis minio chromadb

Write-Step 'Waiting for infrastructure to be healthy...'
$maxWait = 90
$waited  = 0
while ($waited -lt $maxWait) {
    # Only poll services that define a healthcheck; chromadb has none so skip blanks
    $unhealthy = Compose ps --format json 2>$null |
        ForEach-Object { $_ | ConvertFrom-Json -ErrorAction SilentlyContinue } |
        Where-Object { $_.Health -ne $null -and $_.Health -ne '' -and $_.Health -ne 'healthy' }
    if (-not $unhealthy) { break }
    Start-Sleep -Seconds 3
    $waited += 3
}
Write-Ok 'Infrastructure services are healthy'

if ($InfraOnly) {
    Write-Ok 'InfraOnly mode -- skipping backend startup.'
    exit 0
}

# -- Start backend + celery ---------------------------------------------------
Write-Step 'Starting backend and celery_worker containers...'
Compose up -d backend celery_worker
Write-Ok 'Containers started'

# -- Run migrations inside the backend container ------------------------------
Write-Step 'Running Alembic migrations...'
$migWait    = 30
$migElapsed = 0
$migDone    = $false
while ($migElapsed -lt $migWait) {
    $state = Compose ps --format json 2>$null |
        ForEach-Object { $_ | ConvertFrom-Json -ErrorAction SilentlyContinue } |
        Where-Object { $_.Service -eq 'backend' } |
        Select-Object -First 1
    if ($state -and $state.State -eq 'running') { $migDone = $true; break }
    Start-Sleep -Seconds 2
    $migElapsed += 2
}

if ($migDone) {
    Compose exec backend alembic upgrade head
    Write-Ok 'Migrations applied'
} else {
    Write-Warn "backend container did not reach running state in $migWait s -- skipping migrations."
    Write-Warn 'Run manually: docker compose exec backend alembic upgrade head'
}

# -- Wait for API to be reachable ---------------------------------------------
Write-Step 'Waiting for backend API to be ready on http://localhost:8000 ...'
Write-Ok 'API docs: http://localhost:8000/docs'

$maxWait = 60
$waited  = 0
$ready   = $false
while ($waited -lt $maxWait) {
    try {
        $tcp = New-Object System.Net.Sockets.TcpClient
        $tcp.Connect('127.0.0.1', 8000)
        $tcp.Close()
        $ready = $true
        break
    } catch {
        Start-Sleep -Seconds 2
        $waited += 2
    }
}

if (-not $ready) {
    Write-Warn "API did not respond within $maxWait s. Check logs: docker compose logs backend"
    Write-Warn 'Continuing to start tunnel anyway...'
} else {
    Write-Ok 'Backend is up and accepting connections'
}

# -- Start Cloudflare tunnel --------------------------------------------------
Write-Step 'Starting Cloudflare tunnel (mybackend)...'
Write-Ok 'Press Ctrl+C to stop the tunnel (containers keep running)'
Write-Ok 'To stop everything run: stop-dev.ps1'

$cloudflaredConfig = 'C:\Users\admin\.cloudflared\config.yml'
cloudflared tunnel --config $cloudflaredConfig run mybackend
