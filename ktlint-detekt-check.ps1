# ============================================================================
# Android AI Assistant — ktlint + Detekt Local Check Script
# ============================================================================
# Mirrors CI job #4 ("ktlint + Detekt") in .github/workflows/android-ci.yml
#
# Gate logic (identical to CI):
#   • Both tools run on the full source tree (Detekt needs cross-file context).
#   • After each run, Checkstyle XML reports are filtered to changed .kt files.
#   • Job FAILS (exit 1) if any changed file has an error-level violation.
#   • If no .kt files changed, fails only on Gradle non-zero exit (config/compile error).
#   • Detekt warnings are shown but do NOT fail the gate (errors only).
#
# Usage (run from project root):
#   .\ktlint-detekt-check.ps1                       # check only, diff vs main
#   .\ktlint-detekt-check.ps1 -Fix                  # auto-format with ktlintFormat, then check
#   .\ktlint-detekt-check.ps1 -Branch develop        # diff against a different branch
#   .\ktlint-detekt-check.ps1 -SkipKtlint            # Detekt only
#   .\ktlint-detekt-check.ps1 -SkipDetekt            # ktlint only
#   .\ktlint-detekt-check.ps1 -AllFiles              # gate on full tree, not just changed files
#   .\ktlint-detekt-check.ps1 -OpenReports           # open HTML reports in browser on failure
#   .\ktlint-detekt-check.ps1 -Fix -OpenReports      # format + check + open reports on failure
# ============================================================================

param(
    [switch]$Fix,
    [switch]$SkipKtlint,
    [switch]$SkipDetekt,
    [switch]$AllFiles,
    [switch]$OpenReports,
    [string]$Branch = "main"
)

Set-StrictMode -Off
$ErrorActionPreference = "Stop"
$ROOT    = $PSScriptRoot
$GRADLEW = Join-Path $ROOT "gradlew.bat"

# ─── Console helpers ─────────────────────────────────────────────────────────
function Write-Step([string]$msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "    OK  : $msg" -ForegroundColor Green }
function Write-Fail([string]$msg) { Write-Host "    FAIL: $msg" -ForegroundColor Red }
function Write-Info([string]$msg) { Write-Host "    $msg" -ForegroundColor Gray }
function Write-Warn([string]$msg) { Write-Host "    WARN: $msg" -ForegroundColor Yellow }

# ─── Gradle runner ───────────────────────────────────────────────────────────
# Returns $true on success, $false on failure. Never throws.
function Invoke-Gradle([string]$Label, [string]$Task) {
    Write-Step "$Label  →  gradlew $Task"
    $global:LASTEXITCODE = 0
    try {
        & $GRADLEW $Task --stacktrace
        if ($global:LASTEXITCODE -ne 0) {
            Write-Fail "$Label exited with code $global:LASTEXITCODE"
            return $false
        }
        Write-Ok $Label
        return $true
    } catch {
        Write-Fail "$Label threw: $_"
        return $false
    }
}

# ─── Checkstyle XML parser ───────────────────────────────────────────────────
# Returns array of [PSCustomObject] { Line Col Message Source Severity }
# for the given absolute file path across all XML reports under $ReportDir.
function Get-Violations([string]$AbsFilePath, [string]$ReportDir) {
    $results = @()
    $xmlFiles = Get-ChildItem -Path $ROOT -Recurse -Filter "*.xml" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -like "*$ReportDir*" }

    foreach ($xml in $xmlFiles) {
        try {
            [xml]$doc = Get-Content $xml.FullName -Raw -ErrorAction Stop
        } catch { continue }

        foreach ($fileNode in $doc.checkstyle.file) {
            if ($fileNode.name -ne $AbsFilePath) { continue }
            foreach ($err in $fileNode.error) {
                $results += [PSCustomObject]@{
                    Line     = $err.line
                    Col      = if ($err.column) { $err.column } else { "1" }
                    Message  = $err.message
                    Source   = $err.source
                    Severity = if ($err.severity) { $err.severity } else { "error" }
                }
            }
        }
    }
    return $results
}

# ─── Violation printer ───────────────────────────────────────────────────────
# Prints a per-file violation table for changed .kt files.
# Returns the count of error-level (non-warning) violations found.
function Show-ChangedFileViolations(
    [string[]]$ChangedFiles,
    [string]$ReportDir,
    [string]$ToolName
) {
    $totalErrors = 0

    foreach ($relPath in $ChangedFiles) {
        $absPath    = (Join-Path $ROOT $relPath).Replace("/", "\")
        $violations = Get-Violations -AbsFilePath $absPath -ReportDir $ReportDir

        if ($violations.Count -eq 0) { continue }

        Write-Host ""
        Write-Host "  [$ToolName] $relPath" -ForegroundColor Yellow

        foreach ($v in $violations) {
            $rule  = $v.Source -replace '^.*\.', ''
            $color = if ($v.Severity -eq "warning") { "Yellow" } else { "Red" }
            $line  = "    Line {0,-5} Col {1,-4} [{2}] {3}  ({4})" -f `
                     $v.Line, $v.Col, $v.Severity.ToUpper(), $v.Message, $rule
            Write-Host $line -ForegroundColor $color

            if ($v.Severity -ne "warning") { $totalErrors++ }
        }
    }

    return $totalErrors
}

# ─────────────────────────────────────────────────────────────────────────────
# Pre-flight
# ─────────────────────────────────────────────────────────────────────────────
if (-not (Test-Path $GRADLEW)) {
    Write-Host "ERROR: gradlew.bat not found. Run this script from the project root." -ForegroundColor Red
    exit 1
}

# ─────────────────────────────────────────────────────────────────────────────
# Step 1 — google-services.json (CI placeholder if missing)
# ─────────────────────────────────────────────────────────────────────────────
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

# ─────────────────────────────────────────────────────────────────────────────
# Step 2 — Compute changed .kt files vs target branch
# ─────────────────────────────────────────────────────────────────────────────
Write-Step "Changed .kt files vs origin/$Branch..."
Push-Location $ROOT

$changedKt = @()
$scopeLabel = ""

try {
    if (-not $AllFiles) {
        $raw = git diff --name-only "origin/$Branch...HEAD" 2>$null |
               Where-Object { $_ -match '\.kt$' }

        if ($raw) {
            $changedKt  = @($raw)
            $scopeLabel = "$($changedKt.Count) changed .kt file(s)"
            $changedKt | ForEach-Object { Write-Info "  $_" }
            Write-Info ""
            Write-Info "Gate: only violations in these files will fail the check."
        } else {
            Write-Info "(no changed .kt files detected — running on full tree)"
            $scopeLabel = "full tree (no changed .kt files)"
        }
    } else {
        Write-Info "(-AllFiles) Running gate on full source tree."
        $scopeLabel = "full tree (-AllFiles)"
    }
} catch {
    Write-Warn "git diff failed — running on full tree. ($_)"
    $scopeLabel = "full tree (git unavailable)"
}

Pop-Location

# ─────────────────────────────────────────────────────────────────────────────
# Step 3 — ktlint
# ─────────────────────────────────────────────────────────────────────────────
Push-Location $ROOT
$ktlintPassed      = $true
$ktlintViolations  = 0
$detektPassed      = $true
$detektViolations  = 0

try {
    if (-not $SkipKtlint) {
        if ($Fix) {
            $null = Invoke-Gradle "ktlint format" "ktlintFormat"
        }

        $ktlintPassed = Invoke-Gradle "ktlint check" "ktlintCheck"

        if ($changedKt.Count -gt 0) {
            Write-Step "ktlint violations in changed files..."
            $ktlintViolations = Show-ChangedFileViolations `
                -ChangedFiles $changedKt `
                -ReportDir    "build\reports\ktlint" `
                -ToolName     "ktlint"

            if ($ktlintViolations -gt 0) {
                Write-Host ""
                Write-Fail "ktlint: $ktlintViolations error-level violation(s) in changed files."
            } elseif (-not $ktlintPassed) {
                Write-Warn "ktlint failed outside your change set — see full reports."
            } else {
                Write-Ok "No ktlint violations in changed files."
            }
        }
    } else {
        Write-Info "Skipping ktlint (-SkipKtlint)"
    }

    # ─────────────────────────────────────────────────────────────────────────
    # Step 4 — Detekt
    # ─────────────────────────────────────────────────────────────────────────
    if (-not $SkipDetekt) {
        $detektPassed = Invoke-Gradle "Detekt" "detekt"

        if ($changedKt.Count -gt 0) {
            Write-Step "Detekt violations in changed files..."
            $detektViolations = Show-ChangedFileViolations `
                -ChangedFiles $changedKt `
                -ReportDir    "build\reports\detekt" `
                -ToolName     "detekt"

            if ($detektViolations -gt 0) {
                Write-Host ""
                Write-Fail "Detekt: $detektViolations error-level violation(s) in changed files."
            } elseif (-not $detektPassed) {
                Write-Warn "Detekt failed outside your change set — see full reports."
            } else {
                Write-Ok "No Detekt violations in changed files."
            }
        }
    } else {
        Write-Info "Skipping Detekt (-SkipDetekt)"
    }

} finally {
    Pop-Location
}

# ─────────────────────────────────────────────────────────────────────────────
# Step 5 — Gate decision (mirrors CI exactly)
# ─────────────────────────────────────────────────────────────────────────────
$gateFailed = $false

if ($changedKt.Count -gt 0) {
    # Scoped mode: fail only when changed files have errors
    if ($ktlintViolations -gt 0 -or $detektViolations -gt 0) {
        $gateFailed = $true
    }
} else {
    # Full-tree mode: fall back to Gradle exit codes
    if (-not $ktlintPassed -or -not $detektPassed) {
        $gateFailed = $true
    }
}

# ─────────────────────────────────────────────────────────────────────────────
# Step 6 — Open reports on failure (optional)
# ─────────────────────────────────────────────────────────────────────────────
if ($gateFailed -and $OpenReports) {
    Write-Step "Opening failure reports in browser..."

    Get-ChildItem -Path $ROOT -Recurse -Filter "*.html" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "build.reports.ktlint" } |
        ForEach-Object { Start-Process $_.FullName }

    Get-ChildItem -Path $ROOT -Recurse -Filter "detekt.html" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "build.reports.detekt" } |
        ForEach-Object { Start-Process $_.FullName }
}

# ─────────────────────────────────────────────────────────────────────────────
# Summary
# ─────────────────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "  Scope : $scopeLabel" -ForegroundColor Gray
Write-Host ""

if (-not $gateFailed) {
    Write-Host "==========================================" -ForegroundColor Green
    Write-Host "  ktlint + Detekt gate PASSED" -ForegroundColor Green
    Write-Host "==========================================" -ForegroundColor Green
    exit 0
} else {
    Write-Host "==========================================" -ForegroundColor Red
    Write-Host "  ktlint + Detekt gate FAILED" -ForegroundColor Red
    Write-Host "==========================================" -ForegroundColor Red
    Write-Host ""
    Write-Host "  Quick fixes:" -ForegroundColor Yellow
    Write-Host "    -Fix          auto-format with ktlintFormat, then re-check" -ForegroundColor Yellow
    Write-Host "    -OpenReports  open HTML reports in your browser" -ForegroundColor Yellow
    Write-Host "    -AllFiles     run gate on full tree, not just your changes" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "  Report locations:" -ForegroundColor Yellow
    Write-Host "    ktlint : <module>\build\reports\ktlint\" -ForegroundColor Yellow
    Write-Host "    Detekt : <module>\build\reports\detekt\" -ForegroundColor Yellow
    exit 1
}
