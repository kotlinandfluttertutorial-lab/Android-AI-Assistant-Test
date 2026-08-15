#!/usr/bin/env bash
# =============================================================================
# check-coverage.sh — JaCoCo Combined Coverage Gate
# =============================================================================
#
# Purpose:
#   Parse the JaCoCo XML reports produced by the :domain and :data modules,
#   calculate their combined INSTRUCTION coverage, and fail the build if the
#   combined percentage is below the 70% threshold (Requirement 19.5).
#
# Usage:
#   .github/scripts/check-coverage.sh [project-root] [threshold]
#
#   project-root  defaults to the current working directory.
#   threshold     defaults to 70 (percent). Pass e.g. "80" to raise the bar.
#
# Expected report locations (Gradle default):
#   <project-root>/domain/build/reports/jacoco/test/jacocoTestReport.xml
#   <project-root>/data/build/reports/jacoco/test/jacocoTestReport.xml
#
# Output:
#   Prints per-module and combined coverage percentages. Exits 0 if the
#   combined coverage meets or exceeds the threshold, 1 otherwise.
# =============================================================================

set -euo pipefail

PROJECT_ROOT="${1:-.}"
THRESHOLD="${2:-70}"

DOMAIN_REPORT="${PROJECT_ROOT}/domain/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml"
DATA_REPORT="${PROJECT_ROOT}/data/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml"

# ---------------------------------------------------------------------------
# parse_instruction_counts <xml-file>
#
# Prints two space-separated integers: <covered> <missed>
# Returns "0 0" if the file does not exist.
# ---------------------------------------------------------------------------
parse_instruction_counts() {
  local xml_file="$1"

  if [[ ! -f "$xml_file" ]]; then
    echo "::warning::JaCoCo report not found: ${xml_file}"
    echo "0 0"
    return
  fi

  python3 - "$xml_file" <<'PYEOF'
import sys
import xml.etree.ElementTree as ET

try:
    # JaCoCo reports sometimes contain an HTML entity that Python's parser
    # does not recognise. Strip the DOCTYPE declaration to be safe.
    with open(sys.argv[1], "r", encoding="utf-8") as fh:
        content = fh.read()

    # Remove any DOCTYPE / entity declarations that trip up ElementTree
    import re
    content = re.sub(r"<!DOCTYPE[^>]*>", "", content)
    content = re.sub(r"<!ENTITY[^>]*>", "", content)

    root = ET.fromstring(content)
    covered = missed = 0

    for counter in root.iter("counter"):
        if counter.get("type") == "INSTRUCTION":
            covered += int(counter.get("covered", 0))
            missed  += int(counter.get("missed",  0))

    print(covered, missed)
except Exception as e:
    print(f"::error::Failed to parse {sys.argv[1]}: {e}", file=sys.stderr)
    print("0 0")
    sys.exit(1)
PYEOF
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

echo "📊 JaCoCo Coverage Gate (threshold: ${THRESHOLD}%)"
echo ""
echo "  domain report : ${DOMAIN_REPORT}"
echo "  data   report : ${DATA_REPORT}"
echo ""

read -r domain_covered domain_missed <<< "$(parse_instruction_counts "$DOMAIN_REPORT")"
read -r data_covered   data_missed   <<< "$(parse_instruction_counts "$DATA_REPORT")"

domain_total=$(( domain_covered + domain_missed ))
data_total=$(( data_covered   + data_missed   ))

combined_covered=$(( domain_covered + data_covered ))
combined_total=$(( domain_total + data_total ))

# Per-module coverage percentages (informational)
if [[ "$domain_total" -gt 0 ]]; then
  domain_pct=$(awk "BEGIN { printf \"%.2f\", ($domain_covered / $domain_total) * 100 }")
else
  domain_pct="N/A (no data)"
fi

if [[ "$data_total" -gt 0 ]]; then
  data_pct=$(awk "BEGIN { printf \"%.2f\", ($data_covered / $data_total) * 100 }")
else
  data_pct="N/A (no data)"
fi

echo "  :domain  — ${domain_covered}/${domain_total} instructions covered  →  ${domain_pct}%"
echo "  :data    — ${data_covered}/${data_total} instructions covered  →  ${data_pct}%"
echo ""

if [[ "$combined_total" -eq 0 ]]; then
  echo "::error::No JaCoCo coverage data found for :domain or :data modules."
  echo "Ensure both modules have JaCoCo configured and jacocoTestReport has been run."
  exit 1
fi

combined_pct=$(awk "BEGIN { printf \"%.2f\", ($combined_covered / $combined_total) * 100 }")

echo "  Combined — ${combined_covered}/${combined_total} instructions covered  →  ${combined_pct}%"
echo "  Threshold: ${THRESHOLD}%"
echo ""

# Export for GitHub Actions step summary / env
echo "coverage_pct=${combined_pct}" >> "${GITHUB_ENV:-/dev/null}" 2>/dev/null || true

# Pass/fail decision
awk -v pct="$combined_pct" -v threshold="$THRESHOLD" 'BEGIN {
  if (pct + 0 < threshold + 0) {
    print "❌ FAILED: Combined instruction coverage " pct "% is below the " threshold "% minimum threshold."
    exit 1
  }
  print "✅ PASSED: Combined instruction coverage " pct "% meets the " threshold "% threshold."
  exit 0
}'
