#!/usr/bin/env bash
# check-tls-pin.sh
# ─────────────────────────────────────────────────────────────────────────────
# CI check: verify that the primary certificate pin in network_security_config.xml
# matches the reference fingerprint recorded in .env.example.
#
# Usage: .github/scripts/check-tls-pin.sh
# Run from the repository root.
#
# Requirement 28.7: certificate rotation must update both files atomically.
# This script enforces that invariant in CI.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

NSC_FILE="app/src/main/res/xml/network_security_config.xml"
ENV_EXAMPLE=".env.example"

# ── Extract primary pin from network_security_config.xml ─────────────────────
# Matches the first <pin digest="SHA-256">VALUE</pin> line.
XML_PIN=$(grep -m1 '<pin digest="SHA-256">' "$NSC_FILE" \
  | sed 's/.*<pin digest="SHA-256">\([^<]*\)<\/pin>.*/\1/' \
  | tr -d '[:space:]')

if [[ -z "$XML_PIN" ]]; then
  echo "ERROR: No <pin digest=\"SHA-256\"> entry found in $NSC_FILE"
  echo "       Add a pin-set for the production backend domain (Req 28.7)."
  exit 1
fi

# ── Extract BACKEND_TLS_PIN_SHA256 from .env.example ─────────────────────────
ENV_PIN=$(grep '^BACKEND_TLS_PIN_SHA256=' "$ENV_EXAMPLE" \
  | cut -d'=' -f2- \
  | tr -d '[:space:]')

if [[ -z "$ENV_PIN" ]]; then
  echo "ERROR: BACKEND_TLS_PIN_SHA256 not found in $ENV_EXAMPLE"
  echo "       Add the field and set it to the SPKI SHA-256 fingerprint of the backend TLS certificate."
  exit 1
fi

# ── Compare ──────────────────────────────────────────────────────────────────
if [[ "$XML_PIN" == "$ENV_PIN" ]]; then
  echo "✅ TLS pin consistency check passed."
  echo "   network_security_config.xml primary pin : $XML_PIN"
  echo "   .env.example BACKEND_TLS_PIN_SHA256      : $ENV_PIN"
  exit 0
else
  echo "❌ TLS pin mismatch detected!"
  echo "   network_security_config.xml primary pin : $XML_PIN"
  echo "   .env.example BACKEND_TLS_PIN_SHA256      : $ENV_PIN"
  echo ""
  echo "   Per Requirement 28.7, both values must match."
  echo "   When rotating the backend TLS certificate:"
  echo "   1. Update the <pin> value in $NSC_FILE"
  echo "   2. Update BACKEND_TLS_PIN_SHA256 in $ENV_EXAMPLE"
  echo "   3. Deploy the updated APK and the new certificate in the same release."
  exit 1
fi
