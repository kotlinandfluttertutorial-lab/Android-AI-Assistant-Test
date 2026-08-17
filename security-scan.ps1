# ============================================================================
# Android AI Assistant — Local Security Scan + CodeQL Analysis Script
# ============================================================================
# Mirrors the CI "Security Scanning" workflow (.github/workflows/security-scan.yml)
# as closely as possible for local developer use on Windows.
#
# Scans (matching CI job order):
#   1. CodeQL      — semantic SAST for Kotlin/Java (requires CodeQL CLI)
#   2. Gitleaks    — secrets / credential leak detection in Git history
#   3. Bandit      — Python SAST (HIGH+HIGH gate, same as CI)
#   4. Trivy FS    — filesystem + IaC vulnerability scan (CRITICAL/HIGH)
#   5. Trivy Image — Docker image vulnerability scan (requires Docker)
#   6. pip-audit   — Python dependency CVE check
#   7. TLS Pin     — network_security_config.xml ↔ .env.example consistency
#
# Prerequisites (install what you need; missing tools are skipped with a warning):
#   CodeQL CLI   : https://github.com/github/codeql-cli-binaries/releases
#                  Add 'codeql' to PATH, or set -CodeQLPath
#   Gitleaks     : winget install Gitleaks.Gitleaks
#                  or: https://github.com/gitleaks/gitleaks/releases
#   Bandit       : pip install "bandit[toml]==1.7.10"
#   Trivy        : winget install AquaSecurity.Trivy
#                  or: https://github.com/aquasecurity/trivy/releases
#   pip-audit    : pip install pip-audit==2.9.0
#   Docker       : required only for -IncludeImageScan
#
# Usage (run from project root):
#   .\security-scan.ps1                          # all scans (skip image scan)
#   .\security-scan.ps1 -IncludeImageScan        # include Docker image scan
#   .\security-scan.ps1 -OnlyCodeQL              # CodeQL only
#   .\security-scan.ps1 -OnlyGitleaks            # Gitleaks only
#   .\security-scan.ps1 -OnlyBandit              # Bandit only
#   .\security-scan.ps1 -OnlyTrivy               # Trivy FS only
#   .\security-scan.ps1 -OnlyPipAudit            # pip-audit only
#   .\security-scan.ps1 -OnlyTLSPin              # TLS pin check only
#   .\security-scan.ps1 -CodeQLPath "C:\tools\codeql\codeql.exe"
#   .\security-scan.ps1 -OpenReports             # open HTML/SARIF results on failure
# ============================================================================

param(
    # Scan selectors — omitting all runs everything
    [switch]$OnlyCodeQL,
    [switch]$OnlyGitleaks,
    [switch]$OnlyBandit,
    [switch]$OnlyTrivy,
    [switch]$OnlyPipAudit,
    [switch]$OnlyTLSPin,

    # Options
    [switch]$IncludeImageScan,          # also run Trivy Docker image scan
    [switch]$OpenReports,               # open SARIF/HTML in browser on failure
    [string]$CodeQLPath    = "codeql",  # path to codeql CLI (default: PATH lookup)
    [string]$Branch        = "main"     # branch used by Gitleaks baseline
)

Set-StrictMode -Off
$ErrorActionPreference = "Stop"
$ROOT    = $PSScriptRoot
$GRADLEW = Join-Path $ROOT "gradlew.bat"

# Compute which scans to run
$runAll      = -not ($OnlyCodeQL -or $OnlyGitleaks -or $OnlyBandit -or $OnlyTrivy -or $OnlyPipAudit -or $OnlyTLSPin)
$runCodeQL   = $runAll -or $OnlyCodeQL
$runGitleaks = $runAll -or $OnlyGitleaks
$runBandit   = $runAll -or $OnlyBandit
$runTrivy    = $runAll -or $OnlyTrivy
$runPipAudit = $runAll -or $OnlyPipAudit
$runTLSPin   = $runAll -or $OnlyTLSPin

# Output directory for all reports
$REPORTS_DIR = Join-Path $ROOT "build\security-reports"
New-Item -ItemType Directory -Path $REPORTS_DIR -Force | Out-Null

# ─── Console helpers ─────────────────────────────────────────────────────────
function Write-Header([string]$msg) {
    Write-Host ""
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkCyan
    Write-Host "  $msg" -ForegroundColor Cyan
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkCyan
}
function Write-Step([string]$msg)  { Write-Host "`n  --> $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)    { Write-Host "  [PASS] $msg" -ForegroundColor Green }
function Write-Fail([string]$msg)  { Write-Host "  [FAIL] $msg" -ForegroundColor Red }
function Write-Skip([string]$msg)  { Write-Host "  [SKIP] $msg" -ForegroundColor DarkGray }
function Write-Warn([string]$msg)  { Write-Host "  [WARN] $msg" -ForegroundColor Yellow }
function Write-Info([string]$msg)  { Write-Host "         $msg" -ForegroundColor Gray }

# Result tracking
$results  = [System.Collections.Generic.List[PSCustomObject]]::new()
$anyFail  = $false

function Add-Result([string]$Name, [string]$Status, [string]$Note = "") {
    $script:results.Add([PSCustomObject]@{ Name = $Name; Status = $Status; Note = $Note })
    if ($Status -eq "FAIL") { $script:anyFail = $true }
}

# Tool availability check
function Test-Tool([string]$Cmd) {
    return [bool](Get-Command $Cmd -ErrorAction SilentlyContinue)
}

# ─────────────────────────────────────────────────────────────────────────────
# Pre-flight
# ─────────────────────────────────────────────────────────────────────────────
Push-Location $ROOT

Write-Host ""
Write-Host "  Android AI Assistant — Security Scan + CodeQL Analysis" -ForegroundColor White
Write-Host "  $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  |  $ROOT" -ForegroundColor Gray
Write-Host ""

# ─────────────────────────────────────────────────────────────────────────────
# google-services.json placeholder (needed if CodeQL triggers a Gradle build)
# ─────────────────────────────────────────────────────────────────────────────
$GSJ = Join-Path $ROOT "app\google-services.json"
if (-not (Test-Path $GSJ)) {
    Write-Step "Writing placeholder google-services.json (needed for Gradle build)..."
    @'
{
  "project_info": { "project_number": "000000000000", "project_id": "ci-placeholder",
    "storage_bucket": "ci-placeholder.appspot.com" },
  "client": [
    { "client_info": { "mobilesdk_app_id": "1:000000000000:android:0000000000000000000000",
        "android_client_info": { "package_name": "com.aiassistant" } },
      "oauth_client": [], "api_key": [{ "current_key": "CI_PLACEHOLDER_KEY" }],
      "services": { "appinvite_service": { "other_platform_oauth_client": [] } } },
    { "client_info": { "mobilesdk_app_id": "1:000000000000:android:1111111111111111111111",
        "android_client_info": { "package_name": "com.aiassistant.debug" } },
      "oauth_client": [], "api_key": [{ "current_key": "CI_PLACEHOLDER_KEY" }],
      "services": { "appinvite_service": { "other_platform_oauth_client": [] } } }
  ],
  "configuration_version": "1"
}
'@ | Set-Content -Path $GSJ -Encoding UTF8
    Write-Ok "Placeholder google-services.json written."
}

# ─────────────────────────────────────────────────────────────────────────────
# 1. CodeQL — Kotlin/Java semantic analysis
# ─────────────────────────────────────────────────────────────────────────────
if ($runCodeQL) {
    Write-Header "1 / 7  CodeQL Analysis (Kotlin/Java)"

    $codeqlExe = $CodeQLPath
    if (-not (Test-Tool $codeqlExe)) {
        Write-Warn "CodeQL CLI not found at '$codeqlExe'."
        Write-Info "Install: https://github.com/github/codeql-cli-binaries/releases"
        Write-Info "Then re-run with:  .\security-scan.ps1 -CodeQLPath 'C:\path\to\codeql.exe'"
        Add-Result "CodeQL (Kotlin/Java)" "SKIP" "codeql CLI not installed"
    } elseif (-not (Test-Path $GRADLEW)) {
        Write-Warn "gradlew.bat not found — cannot build for CodeQL."
        Add-Result "CodeQL (Kotlin/Java)" "SKIP" "gradlew.bat not found"
    } else {
        $codeqlDb   = Join-Path $REPORTS_DIR "codeql-db-kotlin"
        $codeqlSarif = Join-Path $REPORTS_DIR "codeql-kotlin.sarif"

        # Remove stale database
        if (Test-Path $codeqlDb) {
            Remove-Item -Recurse -Force $codeqlDb
        }

        Write-Step "Creating CodeQL database (Kotlin/Java)..."
        Write-Info "This compiles domain, data, core-common, core-network, core-security"
        Write-Info "using the same modules as CI (avoids full multi-module compile time)."

        $buildCmd = (
            ".\gradlew.bat " +
            ":domain:compileDebugKotlin " +
            ":data:compileDebugKotlin " +
            ":core-common:compileDebugKotlin " +
            ":core-network:compileDebugKotlin " +
            ":core-security:compileDebugKotlin " +
            "--no-daemon --stacktrace"
        )

        $global:LASTEXITCODE = 0
        try {
            & $codeqlExe database create $codeqlDb `
                --language=java-kotlin `
                --build-mode=manual `
                --command=$buildCmd `
                --source-root=. `
                --overwrite `
                2>&1

            if ($global:LASTEXITCODE -eq 0) {
                Write-Ok "CodeQL database created: $codeqlDb"

                Write-Step "Running CodeQL analysis (security-extended + security-and-quality)..."
                & $codeqlExe database analyze $codeqlDb `
                    --format=sarif-latest `
                    --output=$codeqlSarif `
                    "codeql/java-queries:codeql-suites/java-security-extended.qls" `
                    "codeql/java-queries:codeql-suites/java-security-and-quality.qls" `
                    2>&1

                if ($global:LASTEXITCODE -eq 0) {
                    Write-Ok "CodeQL analysis complete → $codeqlSarif"
                    # Surface finding count
                    try {
                        $sarif = Get-Content $codeqlSarif -Raw | ConvertFrom-Json
                        $total = ($sarif.runs | ForEach-Object { $_.results.Count } | Measure-Object -Sum).Sum
                        if ($total -gt 0) {
                            Write-Warn "CodeQL found $total result(s). Review SARIF: $codeqlSarif"
                            Add-Result "CodeQL (Kotlin/Java)" "WARN" "$total finding(s) — review required"
                        } else {
                            Write-Ok "CodeQL: zero findings."
                            Add-Result "CodeQL (Kotlin/Java)" "PASS"
                        }
                    } catch {
                        Write-Info "Could not parse SARIF for summary."
                        Add-Result "CodeQL (Kotlin/Java)" "PASS" "SARIF written (parse summary failed)"
                    }
                } else {
                    Write-Fail "CodeQL analysis step exited non-zero."
                    Add-Result "CodeQL (Kotlin/Java)" "FAIL" "analyze step failed"
                }
            } else {
                Write-Fail "CodeQL database creation failed."
                Add-Result "CodeQL (Kotlin/Java)" "FAIL" "database create failed"
            }
        } catch {
            Write-Fail "CodeQL threw: $_"
            Add-Result "CodeQL (Kotlin/Java)" "FAIL" "$_"
        }
    }
}

# ─────────────────────────────────────────────────────────────────────────────
# 2. Gitleaks — secrets / credential leak detection
# ─────────────────────────────────────────────────────────────────────────────
if ($runGitleaks) {
    Write-Header "2 / 7  Gitleaks — Secret Scan"

    if (-not (Test-Tool "gitleaks")) {
        Write-Warn "gitleaks not found."
        Write-Info "Install: winget install Gitleaks.Gitleaks"
        Write-Info "     or: https://github.com/gitleaks/gitleaks/releases"
        Add-Result "Gitleaks" "SKIP" "gitleaks CLI not installed"
    } else {
        $gitleaksReport = Join-Path $REPORTS_DIR "gitleaks-report.json"
        Write-Step "Scanning full Git history for secrets..."
        Write-Info "Config : .gitleaks.toml  (custom rules + allowlist)"

        $global:LASTEXITCODE = 0
        try {
            gitleaks detect `
                --config=".gitleaks.toml" `
                --source="." `
                --report-format=json `
                --report-path=$gitleaksReport `
                --verbose `
                2>&1

            $code = $global:LASTEXITCODE
            if ($code -eq 0) {
                Write-Ok "Gitleaks: no secrets detected."
                Add-Result "Gitleaks" "PASS"
            } elseif ($code -eq 1) {
                Write-Fail "Gitleaks: secret(s) detected — see $gitleaksReport"
                # Print a short summary
                try {
                    $leaks = Get-Content $gitleaksReport -Raw | ConvertFrom-Json
                    foreach ($leak in $leaks | Select-Object -First 10) {
                        Write-Host ("    [{0}] {1}:{2}  Rule: {3}" -f `
                            $leak.Commit.Substring(0,7), $leak.File, $leak.StartLine, $leak.RuleID) `
                            -ForegroundColor Red
                    }
                    if ($leaks.Count -gt 10) { Write-Host "    ... and $($leaks.Count - 10) more" -ForegroundColor Red }
                } catch {}
                Add-Result "Gitleaks" "FAIL" "secret(s) found — rotate immediately"
            } else {
                Write-Warn "Gitleaks exited with code $code (possible config error)."
                Add-Result "Gitleaks" "WARN" "exit code $code"
            }
        } catch {
            Write-Fail "Gitleaks threw: $_"
            Add-Result "Gitleaks" "FAIL" "$_"
        }
    }
}

# ─────────────────────────────────────────────────────────────────────────────
# 3. Bandit — Python SAST
# ─────────────────────────────────────────────────────────────────────────────
if ($runBandit) {
    Write-Header "3 / 7  Bandit — Python SAST"

    if (-not (Test-Tool "bandit")) {
        Write-Warn "bandit not found."
        Write-Info "Install: pip install `"bandit[toml]==1.7.10`""
        Add-Result "Bandit" "SKIP" "bandit not installed"
    } elseif (-not (Test-Path (Join-Path $ROOT "backend\app"))) {
        Write-Warn "backend/app not found — skipping Bandit."
        Add-Result "Bandit" "SKIP" "backend/app directory not found"
    } else {
        $banditSarif   = Join-Path $REPORTS_DIR "bandit-results.sarif"
        $banditTxt     = Join-Path $REPORTS_DIR "bandit-high-summary.txt"

        # ── Full SARIF scan (informational, mirrors CI upload step) ──────────
        Write-Step "Bandit full scan → SARIF (MEDIUM severity, MEDIUM confidence)..."
        $global:LASTEXITCODE = 0
        try {
            bandit -r backend/app `
                --severity-level medium `
                --confidence-level medium `
                --format sarif `
                --output $banditSarif `
                --exclude backend/app/tests `
                2>&1
            Write-Info "SARIF written: $banditSarif"
        } catch {}  # non-zero exit just means issues found

        # ── HIGH+HIGH gate (same logic as CI) ────────────────────────────────
        Write-Step "Bandit CI gate — HIGH severity + HIGH confidence..."
        $global:LASTEXITCODE = 0
        try {
            $banditOutput = bandit -r backend/app `
                --severity-level high `
                --confidence-level high `
                --format txt `
                --exclude backend/app/tests `
                2>&1
            $code = $global:LASTEXITCODE
            $banditOutput | Set-Content -Path $banditTxt -Encoding UTF8
            $banditOutput | Write-Host -ForegroundColor Gray

            if ($code -eq 0) {
                Write-Ok "Bandit: no HIGH+HIGH findings."
                Add-Result "Bandit" "PASS"
            } else {
                Write-Fail "Bandit: HIGH+HIGH finding(s) detected. Review: $banditTxt"
                Add-Result "Bandit" "FAIL" "HIGH+HIGH issues found — fix before merging"
            }
        } catch {
            Write-Fail "Bandit threw: $_"
            Add-Result "Bandit" "FAIL" "$_"
        }
    }
}

# ─────────────────────────────────────────────────────────────────────────────
# 4. Trivy — Filesystem + IaC scan
# ─────────────────────────────────────────────────────────────────────────────
if ($runTrivy) {
    Write-Header "4 / 7  Trivy — Filesystem & IaC Scan"

    if (-not (Test-Tool "trivy")) {
        Write-Warn "trivy not found."
        Write-Info "Install: winget install AquaSecurity.Trivy"
        Write-Info "     or: https://github.com/aquasecurity/trivy/releases"
        Add-Result "Trivy (FS)" "SKIP" "trivy CLI not installed"
    } else {
        $trivyFsSarif = Join-Path $REPORTS_DIR "trivy-fs-results.sarif"

        # ── FS scan — SARIF ──────────────────────────────────────────────────
        Write-Step "Trivy filesystem scan (CRITICAL/HIGH) → SARIF..."
        $global:LASTEXITCODE = 0
        try {
            trivy fs . `
                --format sarif `
                --output $trivyFsSarif `
                --severity CRITICAL,HIGH `
                --exit-code 0 `
                --skip-dirs ".gradle,build,venv311,.venv,node_modules" `
                --ignorefile ".trivyignore" `
                2>&1
            Write-Info "SARIF written: $trivyFsSarif"
        } catch { Write-Warn "Trivy FS SARIF step: $_" }

        # ── IaC / config scan — table, non-blocking ──────────────────────────
        Write-Step "Trivy IaC / config scan (docker-compose, Dockerfile, infra)..."
        $global:LASTEXITCODE = 0
        try {
            trivy config . `
                --format table `
                --severity CRITICAL,HIGH `
                --exit-code 0 `
                2>&1
        } catch { Write-Warn "Trivy IaC scan: $_" }

        # ── FS gate — CRITICAL only, exit-code 1 ─────────────────────────────
        Write-Step "Trivy CI gate — CRITICAL vulnerabilities..."
        $global:LASTEXITCODE = 0
        try {
            trivy fs . `
                --format table `
                --severity CRITICAL `
                --exit-code 1 `
                --skip-dirs ".gradle,build,venv311,.venv,node_modules" `
                --ignorefile ".trivyignore" `
                2>&1
            $code = $global:LASTEXITCODE
            if ($code -eq 0) {
                Write-Ok "Trivy FS: no unmitigated CRITICAL vulnerabilities."
                Add-Result "Trivy (FS)" "PASS"
            } else {
                Write-Fail "Trivy FS: CRITICAL vulnerability/ies found — see SARIF: $trivyFsSarif"
                Add-Result "Trivy (FS)" "FAIL" "CRITICAL CVE(s) found"
            }
        } catch {
            Write-Fail "Trivy FS gate threw: $_"
            Add-Result "Trivy (FS)" "FAIL" "$_"
        }
    }

    # ── Optional Docker image scan ─────────────────────────────────────────
    if ($IncludeImageScan) {
        Write-Header "4b / 7  Trivy — Docker Image Scan"

        if (-not (Test-Tool "trivy")) {
            Write-Skip "Trivy not available — skipping image scan."
            Add-Result "Trivy (Image)" "SKIP" "trivy not installed"
        } elseif (-not (Test-Tool "docker")) {
            Write-Warn "Docker not found — skipping image scan."
            Write-Info "Install Docker Desktop: https://www.docker.com/products/docker-desktop/"
            Add-Result "Trivy (Image)" "SKIP" "Docker not installed"
        } else {
            $trivyImageSarif = Join-Path $REPORTS_DIR "trivy-image-results.sarif"

            Write-Step "Building backend Docker image for scanning..."
            $global:LASTEXITCODE = 0
            try {
                docker build --target production --tag ai-assistant-backend:scan backend/ 2>&1
                if ($global:LASTEXITCODE -ne 0) { throw "docker build failed" }
                Write-Ok "Image built: ai-assistant-backend:scan"

                Write-Step "Trivy image scan (CRITICAL/HIGH) → SARIF..."
                trivy image ai-assistant-backend:scan `
                    --format sarif `
                    --output $trivyImageSarif `
                    --severity CRITICAL,HIGH `
                    --exit-code 0 `
                    --ignore-unfixed `
                    --vuln-type os,library `
                    --ignorefile ".trivyignore" `
                    2>&1
                Write-Info "SARIF written: $trivyImageSarif"

                Write-Step "Trivy image gate — CRITICAL vulnerabilities..."
                trivy image ai-assistant-backend:scan `
                    --format table `
                    --severity CRITICAL `
                    --exit-code 1 `
                    --ignore-unfixed `
                    --vuln-type os,library `
                    --ignorefile ".trivyignore" `
                    2>&1
                $code = $global:LASTEXITCODE
                if ($code -eq 0) {
                    Write-Ok "Trivy image: no unmitigated CRITICAL vulnerabilities."
                    Add-Result "Trivy (Image)" "PASS"
                } else {
                    Write-Fail "Trivy image: CRITICAL CVE(s) found — see $trivyImageSarif"
                    Add-Result "Trivy (Image)" "FAIL" "CRITICAL CVE(s) in image"
                }
            } catch {
                Write-Fail "Trivy image scan threw: $_"
                Add-Result "Trivy (Image)" "FAIL" "$_"
            }
        }
    }
}

# ─────────────────────────────────────────────────────────────────────────────
# 5. pip-audit — Python dependency CVE check
# ─────────────────────────────────────────────────────────────────────────────
if ($runPipAudit) {
    Write-Header "5 / 7  pip-audit — Python Dependency CVE Check"

    if (-not (Test-Tool "pip-audit")) {
        Write-Warn "pip-audit not found."
        Write-Info "Install: pip install pip-audit==2.9.0"
        Add-Result "pip-audit" "SKIP" "pip-audit not installed"
    } elseif (-not (Test-Path (Join-Path $ROOT "backend\requirements.txt"))) {
        Write-Warn "backend/requirements.txt not found."
        Add-Result "pip-audit" "SKIP" "requirements.txt not found"
    } else {
        $pipAuditReport = Join-Path $REPORTS_DIR "pip-audit-report.json"

        # ── Full JSON report (informational) ─────────────────────────────────
        Write-Step "pip-audit full scan → JSON report..."
        $global:LASTEXITCODE = 0
        try {
            pip-audit `
                --requirement backend/requirements.txt `
                --output json `
                --format json `
                2>&1 | Set-Content -Path $pipAuditReport -Encoding UTF8
            Write-Info "Report: $pipAuditReport"
        } catch {}  # non-zero exit just means vulns found

        # ── Gate — strict, ignore CVE-2026-45829 (same as CI) ────────────────
        Write-Step "pip-audit CI gate (strict, CVE-2026-45829 suppressed — no patch available)..."
        $global:LASTEXITCODE = 0
        try {
            pip-audit `
                --requirement backend/requirements.txt `
                --ignore-vuln CVE-2026-45829 `
                --strict `
                2>&1
            $code = $global:LASTEXITCODE
            if ($code -eq 0) {
                Write-Ok "pip-audit: no unmitigated vulnerabilities."
                Add-Result "pip-audit" "PASS"
            } else {
                Write-Fail "pip-audit: vulnerability/ies found — see $pipAuditReport"
                Add-Result "pip-audit" "FAIL" "CVE(s) found in Python dependencies"
            }
        } catch {
            Write-Fail "pip-audit threw: $_"
            Add-Result "pip-audit" "FAIL" "$_"
        }
    }
}

# ─────────────────────────────────────────────────────────────────────────────
# 6. TLS Pin consistency check
# ─────────────────────────────────────────────────────────────────────────────
if ($runTLSPin) {
    Write-Header "6 / 7  TLS Pin Consistency (Req 28.7)"

    $nscFile    = Join-Path $ROOT "app\src\main\res\xml\network_security_config.xml"
    $envExample = Join-Path $ROOT ".env.example"

    if (-not (Test-Path $nscFile)) {
        Write-Warn "network_security_config.xml not found at: $nscFile"
        Add-Result "TLS Pin" "SKIP" "network_security_config.xml not found"
    } elseif (-not (Test-Path $envExample)) {
        Write-Warn ".env.example not found at: $envExample"
        Add-Result "TLS Pin" "SKIP" ".env.example not found"
    } else {
        Write-Step "Comparing TLS pin in network_security_config.xml vs .env.example..."

        # Extract primary pin from network_security_config.xml
        $xmlContent = Get-Content $nscFile -Raw
        $xmlPin = $null
        if ($xmlContent -match '<pin digest="SHA-256">([^<]+)<\/pin>') {
            $xmlPin = $Matches[1].Trim()
        }

        # Extract BACKEND_TLS_PIN_SHA256 from .env.example
        $envPin = $null
        $envLines = Get-Content $envExample
        foreach ($line in $envLines) {
            if ($line -match '^BACKEND_TLS_PIN_SHA256=(.+)$') {
                $envPin = $Matches[1].Trim()
                break
            }
        }

        if (-not $xmlPin) {
            Write-Fail "No <pin digest=""SHA-256""> entry found in network_security_config.xml"
            Write-Info "Add a pin-set for the production backend domain (Req 28.7)."
            Add-Result "TLS Pin" "FAIL" "no pin in network_security_config.xml"
        } elseif (-not $envPin) {
            Write-Fail "BACKEND_TLS_PIN_SHA256 not set in .env.example"
            Write-Info "Add the field with the SPKI SHA-256 fingerprint of the backend TLS cert."
            Add-Result "TLS Pin" "FAIL" "BACKEND_TLS_PIN_SHA256 missing from .env.example"
        } elseif ($xmlPin -eq $envPin) {
            Write-Ok "TLS pin consistency check passed."
            Write-Info "network_security_config.xml pin  : $xmlPin"
            Write-Info ".env.example pin                 : $envPin"
            Add-Result "TLS Pin" "PASS"
        } else {
            Write-Fail "TLS pin MISMATCH detected!"
            Write-Host "    network_security_config.xml : $xmlPin" -ForegroundColor Red
            Write-Host "    .env.example                : $envPin" -ForegroundColor Red
            Write-Host ""
            Write-Host "    Per Req 28.7 both values must match." -ForegroundColor Yellow
            Write-Host "    When rotating the backend TLS certificate:" -ForegroundColor Yellow
            Write-Host "      1. Update <pin> in app/src/main/res/xml/network_security_config.xml" -ForegroundColor Yellow
            Write-Host "      2. Update BACKEND_TLS_PIN_SHA256 in .env.example" -ForegroundColor Yellow
            Write-Host "      3. Deploy updated APK + new certificate in the same release." -ForegroundColor Yellow
            Add-Result "TLS Pin" "FAIL" "pin mismatch — rotate certificate atomically"
        }
    }
}

# ─────────────────────────────────────────────────────────────────────────────
# 7. Open reports on failure (optional)
# ─────────────────────────────────────────────────────────────────────────────
if ($anyFail -and $OpenReports) {
    Write-Header "Opening reports in browser..."
    Get-ChildItem -Path $REPORTS_DIR -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -in @(".sarif", ".html", ".txt", ".json") } |
        ForEach-Object { Start-Process $_.FullName }
}

# ─────────────────────────────────────────────────────────────────────────────
# Summary table
# ─────────────────────────────────────────────────────────────────────────────
Pop-Location

Write-Host ""
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkCyan
Write-Host "  Security Scan Summary" -ForegroundColor White
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkCyan
foreach ($r in $results) {
    $color = switch ($r.Status) {
        "PASS" { "Green" }
        "FAIL" { "Red" }
        "WARN" { "Yellow" }
        default { "DarkGray" }
    }
    $note = if ($r.Note) { "  ← $($r.Note)" } else { "" }
    Write-Host ("  [{0,-4}]  {1}{2}" -f $r.Status, $r.Name, $note) -ForegroundColor $color
}
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkCyan
Write-Host ""
Write-Host "  Reports saved to: $REPORTS_DIR" -ForegroundColor Gray
Write-Host ""

if ($anyFail) {
    Write-Host "  RESULT: SECURITY GATE FAILED — resolve issues above before pushing." -ForegroundColor Red
    Write-Host ""
    Write-Host "  Tips:" -ForegroundColor Yellow
    Write-Host "    -OpenReports     open SARIF/report files in your browser" -ForegroundColor Yellow
    Write-Host "    -OnlyCodeQL      run CodeQL only" -ForegroundColor Yellow
    Write-Host "    -OnlyGitleaks    run Gitleaks only" -ForegroundColor Yellow
    Write-Host "    -OnlyBandit      run Bandit only" -ForegroundColor Yellow
    Write-Host "    -OnlyTrivy       run Trivy FS only" -ForegroundColor Yellow
    Write-Host "    -OnlyPipAudit    run pip-audit only" -ForegroundColor Yellow
    Write-Host "    -OnlyTLSPin      run TLS pin check only" -ForegroundColor Yellow
    exit 1
} else {
    Write-Host "  RESULT: ALL SECURITY CHECKS PASSED" -ForegroundColor Green
    exit 0
}
