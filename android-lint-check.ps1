# ============================================================================
# Android AI Assistant -- Local ktlint + Detekt Check Script
# ============================================================================
#
# Mirrors the CI "ktlint + Detekt" job exactly:
#   1. Writes a placeholder google-services.json  (if not present)
#   2. Lists changed .kt files vs the target branch
#   3. Runs ./gradlew ktlintCheck   (blocks on any error)
#   4. Runs ./gradlew detekt        (blocks on any error)
#   5. Opens HTML reports in the browser on failure (optional)
#
# Run this before pushing to catch Kotlin lint/style failures locally.
#
# Usage (from project root):
#   .\android-lint-check.ps1                         # check only
#   .\android-lint-check.ps1 -Fix                    # run ktlintFormat first, then check
#   .\android-lint-check.ps1 -Branch main            # diff against a specific branch (default: main)
#   .\android-lint-check.ps1 -Fix -SkipDetekt        # format only, skip detekt
#   .\android-lint-check.ps1 -OpenReports            # open HTML reports in browser after failure
# ============================================================================

param(
    [switch]$Fix,
    [switch]$SkipDetekt,
    [switch]$SkipKtlint,
    [switch]$OpenReports,
    [string]$Branch = "main"
)

$ErrorActionPreference = "Stop"
$ROOT     = $PSScriptRoot
$GRADLEW  = Join-Path $ROOT "gradlew.bat"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
function Write-Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "    OK: $msg" -ForegroundColor Green }
function Write-Fail($msg) { Write-Host "    FAIL: $msg" -ForegroundColor Red }
function Write-Info($msg) { Write-Host "    $msg" -ForegroundColor Gray }

$failures = [System.Collections.Generic.List[string]]::new()

function Invoke-Step {
    param([string]$Name, [scriptblock]$Block)
    Write-Step $Name
    try {
        & $Block
        if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) {
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
# Pre-flight: gradlew must exist
# ---------------------------------------------------------------------------
if (-not (Test-Path $GRADLEW)) {
    Write-Error "gradlew.bat not found at $ROOT. Run this script from the project root."
}

# ---------------------------------------------------------------------------
# Step 1 — Ensure google-services.json exists (CI placeholder logic)
# ---------------------------------------------------------------------------
$GSJ = Join-Path $ROOT "app\google-services.json"
if (-not (Test-Path $GSJ)) {
    Write-Step "Writing placeholder google-services.json..."
    $placeholder = @'
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
'@
    $placeholder | Set-Content -Path $GSJ -Encoding UTF8
    Write-Ok "Placeholder google-services.json written to app\"
} else {
    Write-Info "google-services.json already present — skipping placeholder."
}

# ---------------------------------------------------------------------------
# Step 2 — Show changed .kt files vs target branch
# ---------------------------------------------------------------------------
Write-Step "Changed .kt files vs origin/$Branch..."
Push-Location $ROOT
try {
    $changedKt = git diff --name-only "origin/$Branch...HEAD" 2>$null |
        Where-Object { $_ -match '\.kt$' }

    if ($changedKt) {
        $changedKt | ForEach-Object { Write-Info "  $_" }
    } else {
        Write-Info "  (no changed .kt files detected — running checks on full tree)"
    }
} catch {
    Write-Info "  (git diff failed — likely no remote or no commits yet; running on full tree)"
}
Pop-Location

# ---------------------------------------------------------------------------
# Step 3 — ktlint
# ---------------------------------------------------------------------------
Push-Location $ROOT
try {
    if (-not $SkipKtlint) {
        if ($Fix) {
            Invoke-Step "ktlint format (ktlintFormat)" {
                & $GRADLEW ktlintFormat --stacktrace
            }
        }

        Invoke-Step "ktlint check (ktlintCheck)" {
            & $GRADLEW ktlintCheck --stacktrace
        }
    } else {
        Write-Info "Skipping ktlint (-SkipKtlint flag set)"
    }

    # -------------------------------------------------------------------------
    # Step 4 — Detekt
    # -------------------------------------------------------------------------
    if (-not $SkipDetekt) {
        Invoke-Step "Detekt (detekt)" {
            & $GRADLEW detekt --stacktrace
        }
    } else {
        Write-Info "Skipping Detekt (-SkipDetekt flag set)"
    }
} finally {
    Pop-Location
}

# ---------------------------------------------------------------------------
# Step 5 — Open reports on failure (optional)
# ---------------------------------------------------------------------------
if ($failures.Count -gt 0 -and $OpenReports) {
    Write-Step "Opening failure reports in browser..."

    # ktlint reports
    $ktlintReports = Get-ChildItem -Path $ROOT -Recurse -Filter "*.html" |
        Where-Object { $_.FullName -match "build.reports.ktlint" }
    $ktlintReports | ForEach-Object { Start-Process $_.FullName }

    # Detekt HTML report
    $detektReports = Get-ChildItem -Path $ROOT -Recurse -Filter "detekt.html" |
        Where-Object { $_.FullName -match "build.reports.detekt" }
    $detektReports | ForEach-Object { Start-Process $_.FullName }
}

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
Write-Host ""
if ($failures.Count -eq 0) {
    Write-Host "======================================" -ForegroundColor Green
    Write-Host "  All Android lint checks passed!" -ForegroundColor Green
    Write-Host "======================================" -ForegroundColor Green
    exit 0
} else {
    Write-Host "======================================" -ForegroundColor Red
    Write-Host "  Failed checks:" -ForegroundColor Red
    foreach ($f in $failures) {
        Write-Host "    - $f" -ForegroundColor Red
    }
    Write-Host "======================================" -ForegroundColor Red
    Write-Host ""
    Write-Host "  Tips:" -ForegroundColor Yellow
    Write-Host "    -Fix          run ktlintFormat to auto-fix style issues" -ForegroundColor Yellow
    Write-Host "    -OpenReports  open HTML reports in your browser" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "  Report locations:" -ForegroundColor Yellow
    Write-Host "    ktlint:  <module>\build\reports\ktlint\" -ForegroundColor Yellow
    Write-Host "    Detekt:  <module>\build\reports\detekt\" -ForegroundColor Yellow
    exit 1
}
