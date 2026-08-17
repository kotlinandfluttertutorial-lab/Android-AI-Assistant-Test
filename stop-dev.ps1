# ============================================================================
# Android AI Assistant -- Windows Development Stop Script
# ============================================================================
#
# Stops the FastAPI backend (uvicorn) process and all Docker Compose
# infrastructure services (PostgreSQL, Redis, MinIO).
#
# Usage:
#   .\stop-dev.ps1              # stop uvicorn + all infra containers
#   .\stop-dev.ps1 -InfraOnly  # stop only infra containers (leave uvicorn)
#   .\stop-dev.ps1 -AppOnly    # stop only uvicorn process (leave containers)
#   .\stop-dev.ps1 -Volumes    # stop infra and remove volumes (full wipe)
# ============================================================================

param(
    [switch]$InfraOnly,
    [switch]$AppOnly,
    [switch]$Volumes
)

$ErrorActionPreference = "Stop"
$ROOT = $PSScriptRoot

function Write-Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "    $msg"   -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "    WARNING: $msg" -ForegroundColor Yellow }

# -- Stop FastAPI / uvicorn ---------------------------------------------------
if (-not $InfraOnly) {
    Write-Step "Stopping FastAPI backend (uvicorn)..."

    $uvicornProcs = Get-Process -Name "uvicorn" -ErrorAction SilentlyContinue
    if ($uvicornProcs) {
        $uvicornProcs | Stop-Process -Force
        Write-Ok "uvicorn process(es) stopped."
    } else {
        Write-Warn "No running uvicorn process found."
    }
}

# -- Stop Docker Compose services ---------------------------------------------
if (-not $AppOnly) {
    Write-Step "Stopping Docker Compose services (postgres, redis, minio)..."

    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Write-Warn "Docker not found - skipping container shutdown."
    } else {
        if ($Volumes) {
            Write-Warn "-Volumes flag set: removing containers AND volumes (data will be lost)."
            docker compose -f "$ROOT\docker-compose.yml" down --volumes
        } else {
            docker compose -f "$ROOT\docker-compose.yml" down
        }
        Write-Ok "Docker Compose services stopped."
    }
}

Write-Step "Done."
