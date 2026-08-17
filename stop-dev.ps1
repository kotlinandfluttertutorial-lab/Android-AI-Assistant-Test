# ============================================================================
# Android AI Assistant -- Windows Development Stop Script
# ============================================================================
#
# Stops all Docker Compose services (backend, celery_worker, postgres,
# redis, minio, chromadb).
#
# Usage:
#   .\stop-dev.ps1           # stop all containers, keep volumes
#   .\stop-dev.ps1 -Volumes  # stop all containers AND delete volumes (full wipe)
# ============================================================================

param(
    [switch]$Volumes
)

$ErrorActionPreference = "Stop"
$ROOT = $PSScriptRoot

function Write-Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "    $msg"   -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "    WARNING: $msg" -ForegroundColor Yellow }

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Warn "Docker not found - nothing to stop."
    exit 0
}

Write-Step "Stopping Docker Compose services..."

if ($Volumes) {
    Write-Warn "-Volumes flag set: removing containers AND volumes (all data will be lost)."
    docker compose -f "$ROOT\docker-compose.yml" down --volumes
} else {
    docker compose -f "$ROOT\docker-compose.yml" down
}

Write-Ok "All services stopped."
