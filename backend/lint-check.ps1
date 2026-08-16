# ============================================================================
# Android AI Assistant -- Local Lint & Type Check Script
# ============================================================================
#
# Mirrors the CI "Lint & Type Check" job exactly:
#   1. ruff linter       (ruff check .)
#   2. ruff formatter    (ruff format . --check)
#   3. black formatter   (black --check --diff .)
#   4. mypy type check   (mypy app --strict ...)
#
# Run this before pushing to catch failures locally before CI does.
#
# Usage (from project root OR from backend\ directory):
#   .\backend\lint-check.ps1           # check only, no files modified
#   .\backend\lint-check.ps1 -Fix      # auto-fix ruff + black, then re-check
#   .\backend\lint-check.ps1 -SkipMypy # skip the slow mypy pass
# ============================================================================

param(
    [switch]$Fix,
    [switch]$SkipMypy
)

$ErrorActionPreference = "Stop"

# ---------------------------------------------------------------------------
# Resolve paths -- works whether called from root or from backend\
# ---------------------------------------------------------------------------
$SCRIPT_DIR = $PSScriptRoot
$VENV       = Join-Path $SCRIPT_DIR "venv311"
$PIP        = Join-Path $VENV "Scripts\pip.exe"
$PYTHON     = Join-Path $VENV "Scripts\python.exe"

# Fall back to system python if venv does not exist yet
if (-not (Test-Path $PYTHON)) {
    $PYTHON = "python"
    $PIP    = "pip"
}

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
function Write-Step([string]$msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "    OK: $msg" -ForegroundColor Green }
function Write-Fail([string]$msg) { Write-Host "    FAIL: $msg" -ForegroundColor Red }
function Write-Info([string]$msg) { Write-Host "    $msg" -ForegroundColor Gray }

$failures = [System.Collections.Generic.List[string]]::new()

function Invoke-Step([string]$Name, [scriptblock]$Block) {
    Write-Step $Name
    try {
        & $Block
        if ($LASTEXITCODE -ne 0) {
            Write-Fail "$Name exited with code $LASTEXITCODE"
            $failures.Add($Name)
        } else {
            Write-Ok $Name
        }
    } catch {
        Write-Fail "$Name threw: $_"
        $failures.Add($Name)
    }
}

# ---------------------------------------------------------------------------
# Step 0 -- Install lint dependencies (same versions as CI)
# ---------------------------------------------------------------------------
Write-Step "Installing lint dependencies (ruff==0.6.9  black==24.10.0  mypy==1.11.2)..."
& $PIP install --quiet ruff==0.6.9 black==24.10.0 mypy==1.11.2 types-passlib types-python-jose
if ($LASTEXITCODE -ne 0) {
    Write-Error "pip install failed. Aborting."
}
Write-Ok "Lint deps installed"

# ---------------------------------------------------------------------------
# Step 1 -- ruff linter
# ---------------------------------------------------------------------------
Push-Location $SCRIPT_DIR
try {
    if ($Fix) {
        Invoke-Step "ruff linter (--fix)" {
            & $PYTHON -m ruff check . --fix
        }
    } else {
        Invoke-Step "ruff linter" {
            & $PYTHON -m ruff check . --output-format=github
        }
    }

    # -------------------------------------------------------------------------
    # Step 2 -- ruff formatter
    # -------------------------------------------------------------------------
    if ($Fix) {
        Invoke-Step "ruff formatter (auto-format)" {
            & $PYTHON -m ruff format .
        }
    } else {
        Invoke-Step "ruff formatter check" {
            & $PYTHON -m ruff format . --check
        }
    }

    # -------------------------------------------------------------------------
    # Step 3 -- black formatter
    # -------------------------------------------------------------------------
    if ($Fix) {
        Invoke-Step "black formatter (auto-format)" {
            & $PYTHON -m black .
        }
    } else {
        Invoke-Step "black formatter check" {
            & $PYTHON -m black --check --diff .
        }
    }

    # -------------------------------------------------------------------------
    # Step 4 -- Install app dependencies for mypy
    # -------------------------------------------------------------------------
    if (-not $SkipMypy) {
        Write-Step "Installing app dependencies for mypy..."
        & $PIP install --quiet -r "$SCRIPT_DIR\requirements.txt"
        if ($LASTEXITCODE -ne 0) {
            Write-Error "pip install -r requirements.txt failed. Aborting."
        }
        Write-Ok "App deps installed"

        # ---------------------------------------------------------------------
        # Step 5 -- mypy type check
        # ---------------------------------------------------------------------
        Invoke-Step "mypy type check" {
            & $PYTHON -m mypy app `
                --ignore-missing-imports `
                --strict `
                --exclude "app/alembic" `
                --no-error-summary `
                2>&1 | Select-Object -First 100
        }
    } else {
        Write-Info "Skipping mypy (-SkipMypy flag set)"
    }
} finally {
    Pop-Location
}

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
Write-Host ""
if ($failures.Count -eq 0) {
    Write-Host "==============================" -ForegroundColor Green
    Write-Host "  All checks passed!" -ForegroundColor Green
    Write-Host "==============================" -ForegroundColor Green
    exit 0
} else {
    Write-Host "==============================" -ForegroundColor Red
    Write-Host "  Failed checks:" -ForegroundColor Red
    foreach ($f in $failures) {
        Write-Host "    - $f" -ForegroundColor Red
    }
    Write-Host "==============================" -ForegroundColor Red
    Write-Host ""
    Write-Host "  Tip: run with -Fix to auto-fix ruff + black issues." -ForegroundColor Yellow
    exit 1
}
