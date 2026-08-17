# ============================================================================
# Android AI Assistant — Local Android Lint Check Script
# ============================================================================
# Runs Android Lint (lintDebug) across all modules and surfaces results.
#
# For ktlint + Detekt, use the dedicated script:
#   .\ktlint-detekt-check.ps1
#
# Usage (from project root):
#   .\android-lint-check.ps1                    # lint check only
#   .\android-lint-check.ps1 -OpenReports       # open HTML reports in browser on failure
# ============================================================================

param(
    [switch]$OpenReports
)

Set-StrictMode -Off
$ErrorActionPreference = "Stop"
$ROOT    = $PSScriptRoot
$GRADLEW = Join-Path $ROOT "gradlew.bat"

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
# Pre-flight: gradlew must exist
# ---------------------------------------------------------------------------
if (-not (Test-Path $GRADLEW)) {
    Write-Host "gradlew.bat not found at $ROOT. Run this script from the project root." -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------------------
# Step 1 — Ensure google-services.json exists (CI placeholder logic)
# ---------------------------------------------------------------------------
$GSJ = Join-Path $ROOT "app\google-services.json"
if (-not (Test-Path $GSJ)) {
    Write-Step "Writing placeholder google-services.json..."
    @'
{
  "project_info": {
    "project_number": "000000000000",
    "project_id": "ci-placeholder",
    "storage_bucket": "ci-placeholder.appspot.com"
  },
  "client": [
    {
      "client_info": {
        "mobilesdk_app_id": "1:000000000000:android:0000000000000000000000",
        "android_client_info": { "package_name": "com.aiassistant" }
      },
      "oauth_client": [],
      "api_key": [{ "current_key": "CI_PLACEHOLDER_KEY" }],
      "services": { "appinvite_service": { "other_platform_oauth_client": [] } }
    },
    {
      "client_info": {
        "mobilesdk_app_id": "1:000000000000:android:1111111111111111111111",
        "android_client_info": { "package_name": "com.aiassistant.debug" }
      },
      "oauth_client": [],
      "api_key": [{ "current_key": "CI_PLACEHOLDER_KEY" }],
      "services": { "appinvite_service": { "other_platform_oauth_client": [] } }
    }
  ],
  "configuration_version": "1"
}
'@ | Set-Content -Path $GSJ -Encoding UTF8
    Write-Ok "Placeholder google-services.json written."
} else {
    Write-Info "google-services.json already present — skipping placeholder."
}

# ---------------------------------------------------------------------------
# Step 2 — Run Android Lint on all modules
# ---------------------------------------------------------------------------
Push-Location $ROOT
try {
    Invoke-Step "Android Lint (lintDebug)" {
        & $GRADLEW lintDebug --stacktrace --continue
    }
} finally {
    Pop-Location
}

# ---------------------------------------------------------------------------
# Step 3 — Open reports on failure (optional)
# ---------------------------------------------------------------------------
if ($failures.Count -gt 0 -and $OpenReports) {
    Write-Step "Opening lint reports in browser..."
    Get-ChildItem -Path $ROOT -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match "lint-results.*\.html" } |
        ForEach-Object { Start-Process $_.FullName }
}

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
Write-Host ""
if ($failures.Count -eq 0) {
    Write-Host "======================================" -ForegroundColor Green
    Write-Host "  Android Lint PASSED" -ForegroundColor Green
    Write-Host "======================================" -ForegroundColor Green
    exit 0
} else {
    Write-Host "======================================" -ForegroundColor Red
    Write-Host "  Android Lint FAILED" -ForegroundColor Red
    foreach ($f in $failures) {
        Write-Host "    - $f" -ForegroundColor Red
    }
    Write-Host "======================================" -ForegroundColor Red
    Write-Host ""
    Write-Host "  Tips:" -ForegroundColor Yellow
    Write-Host "    -OpenReports  open HTML reports in your browser" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "  Report locations:" -ForegroundColor Yellow
    Write-Host "    <module>\build\reports\lint-results*.html" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "  For ktlint + Detekt, use:" -ForegroundColor Yellow
    Write-Host "    .\ktlint-detekt-check.ps1" -ForegroundColor Yellow
    exit 1
}
