# ============================================================================
# Android AI Assistant -- Local Lint & Type Check Script
# ============================================================================
# Mirrors the CI "Lint & Type Check" job exactly:
#   1. ruff linter       (ruff check .)
#   2. ruff formatter    (ruff format . --check)
#   3. black formatter   (black --check --diff .)
#   4. mypy type check   (mypy app --strict ...)
#
# Usage (from project root OR from backend\):
#   .\backend\lint-check.ps1           # check only
#   .\backend\lint-check.ps1 -Fix      # auto-fix ruff + black, then re-check
#   .\backend\lint-check.ps1 -SkipMypy # skip the slow mypy pass
# ============================================================================

param(
    [switch]$Fix,
    [switch]$SkipMypy
)

Set-StrictMode -Off
$ErrorActionPreference = "Stop"

$SCRIPT_DIR = $PSScriptRoot

# ---------------------------------------------------------------------------
# Use a dedicated lint venv so it never conflicts with the app venv.
# Located at backend\.venv-lint -- created automatically if missing.
# ---------------------------------------------------------------------------
$LINT_VENV = Join-Path $SCRIPT_DIR ".venv-lint"
$LINT_PY   = Join-Path $LINT_VENV "Scripts\python.exe"
$LINT_PIP  = Join-Path $LINT_VENV "Scripts\pip.exe"

# ---------------------------------------------------------------------------
# Ensure the lint venv exists and is Python 3.11 (required by asyncpg,
# tiktoken, pydantic-core which have no wheels for Python 3.14 yet).
# ---------------------------------------------------------------------------
function Find-Python311 {
    # Try explicit launchers first, then fall back to PATH entries
    $candidates = @("py -3.11", "python3.11", "python311")
    foreach ($cmd in $candidates) {
        $parts = $cmd -split " "
        try {
            $ver = & $parts[0] $parts[1..99] --version 2>&1
            if ($ver -match "3\.11") { return $parts }
        } catch {}
    }
    # Last resort: check if 'python' on PATH is 3.11
    try {
        $ver = & python --version 2>&1
        if ($ver -match "3\.11") { return @("python") }
    } catch {}
    return $null
}

# Check if existing venv is 3.11
$needCreate = $true
if (Test-Path $LINT_PY) {
    $existingVer = & $LINT_PY --version 2>&1
    if ($existingVer -match "3\.11") {
        $needCreate = $false
        Write-Host "Lint venv (Python 3.11): $LINT_VENV" -ForegroundColor Gray
    } else {
        Write-Host "Lint venv is $existingVer -- need 3.11. Recreating..." -ForegroundColor Yellow
        Remove-Item -Recurse -Force $LINT_VENV -ErrorAction SilentlyContinue
    }
}

if ($needCreate) {
    $py311 = Find-Python311
    if ($null -eq $py311) {
        Write-Host "Python 3.11 not found. Install it from https://www.python.org/downloads/ and ensure 'py -3.11' or 'python3.11' works." -ForegroundColor Red
        exit 1
    }
    Write-Host "Creating lint venv with $($py311 -join ' ') at $LINT_VENV ..." -ForegroundColor Yellow
    & $py311[0] $py311[1..99] -m venv $LINT_VENV
    if ($LASTEXITCODE -ne 0) {
        Write-Host "venv creation failed." -ForegroundColor Red
        exit 1
    }
    Write-Host "Lint venv created (Python 3.11)." -ForegroundColor Green
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
    $global:LASTEXITCODE = 0
    try {
        & $Block
        $code = $global:LASTEXITCODE
        if ($code -ne 0) {
            Write-Fail "$Name exited with code $code"
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
# Step 0 -- Install lint tools into the dedicated venv
# ---------------------------------------------------------------------------
Write-Step "Installing lint tools (ruff==0.6.9  black==24.10.0  mypy==1.11.2)..."
& $LINT_PIP install --quiet ruff==0.6.9 black==24.10.0 mypy==1.11.2 types-passlib types-python-jose
if ($LASTEXITCODE -ne 0) {
    Write-Host "pip install failed. Aborting." -ForegroundColor Red
    exit 1
}
Write-Ok "Lint tools ready"

$RUFF  = Join-Path $LINT_VENV "Scripts\ruff.exe"
$BLACK = Join-Path $LINT_VENV "Scripts\black.exe"
$MYPY  = Join-Path $LINT_VENV "Scripts\mypy.exe"

# ---------------------------------------------------------------------------
# Run all checks from within the backend directory
# ---------------------------------------------------------------------------
Push-Location $SCRIPT_DIR
try {

    # -------------------------------------------------------------------------
    # Step 1 -- ruff linter
    # -------------------------------------------------------------------------
    if ($Fix) {
        Invoke-Step "ruff linter (--fix)" {
            & $RUFF check . --fix
        }
    } else {
        Invoke-Step "ruff linter" {
            & $RUFF check . --output-format=github
        }
    }

    # -------------------------------------------------------------------------
    # Step 2 -- ruff formatter
    # -------------------------------------------------------------------------
    if ($Fix) {
        Invoke-Step "ruff formatter (auto-format)" {
            & $RUFF format .
        }
    } else {
        Invoke-Step "ruff formatter check" {
            & $RUFF format . --check
        }
    }

    # -------------------------------------------------------------------------
    # Step 3 -- black formatter
    # -------------------------------------------------------------------------
    if ($Fix) {
        Invoke-Step "black formatter (auto-format)" {
            & $BLACK .
        }
    } else {
        Invoke-Step "black formatter check" {
            & $BLACK --check --diff .
        }
    }

    # -------------------------------------------------------------------------
    # Step 4 -- mypy (needs app deps installed alongside mypy in the lint venv)
    # -------------------------------------------------------------------------
    if (-not $SkipMypy) {
        Write-Step "Installing app dependencies into lint venv for mypy..."
        & $LINT_PIP install --quiet -r "$SCRIPT_DIR\requirements.txt"
        if ($LASTEXITCODE -ne 0) {
            Write-Host "pip install -r requirements.txt failed. Aborting." -ForegroundColor Red
            exit 1
        }
        Write-Ok "App deps installed"

        Invoke-Step "mypy type check" {
            & $MYPY app `
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
