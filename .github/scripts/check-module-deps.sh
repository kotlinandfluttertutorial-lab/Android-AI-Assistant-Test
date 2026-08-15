#!/usr/bin/env bash
# =============================================================================
# check-module-deps.sh — Module Dependency Graph Linter
# =============================================================================
#
# Purpose:
#   Scan every build.gradle.kts in the Android project and enforce Clean
#   Architecture dependency rules. Fails with a non-zero exit code if any
#   forbidden dependency edge is found.
#
# Forbidden edges (Requirement 19.2):
#   • feature  → feature   (feature modules must not depend on other features)
#   • domain   → data       (domain layer must not know about the data layer)
#   • domain   → feature    (domain layer must not depend on any feature)
#   • data     → feature    (data layer must not depend on any feature)
#
# Usage:
#   .github/scripts/check-module-deps.sh [project-root]
#
#   project-root defaults to the current working directory.
#
# Output:
#   Prints each violation with the file path, line number, and the offending
#   dependency declaration. Exits 0 if clean, 1 if any violation is found.
# =============================================================================

set -euo pipefail

PROJECT_ROOT="${1:-.}"
VIOLATIONS=0

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# Returns the module "category" for a given project path string.
# e.g. ":feature-chat"  → "feature"
#      ":domain"        → "domain"
#      ":data"          → "data"
#      ":core-ui"       → "core"
#      ":app"           → "app"
module_category() {
  local path="$1"
  # Strip leading colon and extract the first segment before the next dash/colon
  local name
  name=$(echo "$path" | sed 's/^://; s/".*//')
  if [[ "$name" == feature-* ]]; then
    echo "feature"
  elif [[ "$name" == "domain" ]]; then
    echo "domain"
  elif [[ "$name" == "data" ]]; then
    echo "data"
  elif [[ "$name" == core-* ]]; then
    echo "core"
  elif [[ "$name" == "app" ]]; then
    echo "app"
  else
    echo "other"
  fi
}

# ---------------------------------------------------------------------------
# Main scan
# ---------------------------------------------------------------------------

echo "🔍 Scanning build.gradle.kts files under: ${PROJECT_ROOT}"
echo ""

# Find every build.gradle.kts, skipping generated build directories
while IFS= read -r gradle_file; do

  # Derive the owner module's directory name relative to project root
  module_dir=$(dirname "$gradle_file")
  module_dir_rel="${module_dir#"$PROJECT_ROOT"/}"
  module_name=$(basename "$module_dir")

  # Determine which category this module belongs to
  owner_category=$(module_category ":${module_name}")

  # Only check files that belong to a restricted source category
  if [[ "$owner_category" != "feature" && "$owner_category" != "domain" && "$owner_category" != "data" ]]; then
    continue
  fi

  # Read the file line by line looking for project(":...") dependency declarations
  lineno=0
  while IFS= read -r line; do
    lineno=$((lineno + 1))

    # Match lines like: implementation(project(":feature-chat"))
    # or:               api(projects.featureChat)   ← version catalog accessor
    # We look for the canonical project(":<name>") form.
    if [[ "$line" =~ project\(\"(:[a-zA-Z0-9_-]+)\"\) ]]; then
      dep_path="${BASH_REMATCH[1]}"
      dep_category=$(module_category "$dep_path")

      # Apply the forbidden-edge rules
      forbidden=false
      reason=""

      if [[ "$owner_category" == "feature" && "$dep_category" == "feature" ]]; then
        forbidden=true
        reason="feature → feature dependency is forbidden"
      elif [[ "$owner_category" == "domain" && "$dep_category" == "data" ]]; then
        forbidden=true
        reason="domain → data dependency is forbidden"
      elif [[ "$owner_category" == "domain" && "$dep_category" == "feature" ]]; then
        forbidden=true
        reason="domain → feature dependency is forbidden"
      elif [[ "$owner_category" == "data" && "$dep_category" == "feature" ]]; then
        forbidden=true
        reason="data → feature dependency is forbidden"
      fi

      if [[ "$forbidden" == "true" ]]; then
        echo "::error file=${gradle_file},line=${lineno}::${reason}"
        echo "  ❌ ${gradle_file}:${lineno}"
        echo "     Owner : :${module_name} (${owner_category})"
        echo "     Dep   : ${dep_path} (${dep_category})"
        echo "     Rule  : ${reason}"
        echo "     Line  : ${line// /  }"
        echo ""
        VIOLATIONS=$((VIOLATIONS + 1))
      fi
    fi

  done < "$gradle_file"

done < <(find "$PROJECT_ROOT" \
           -name "build.gradle.kts" \
           -not -path "*/build/*" \
           -not -path "*/.gradle/*" \
           | sort)

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

if [[ "$VIOLATIONS" -eq 0 ]]; then
  echo "✅ Dependency graph check passed — no forbidden edges found."
  exit 0
else
  echo "::error::Dependency graph check FAILED — ${VIOLATIONS} forbidden dependency edge(s) detected."
  echo ""
  echo "Forbidden dependency rules (Requirement 19.2):"
  echo "  • feature  → feature  : feature modules must not depend on each other"
  echo "  • domain   → data     : domain layer must not depend on the data layer"
  echo "  • domain   → feature  : domain layer must not depend on any feature module"
  echo "  • data     → feature  : data layer must not depend on any feature module"
  exit 1
fi
