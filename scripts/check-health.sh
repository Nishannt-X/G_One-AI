#!/usr/bin/env bash
# Checks the backend health endpoint. Usage: scripts/check-health.sh [base-url]   (default http://localhost:8080)
set -euo pipefail
base="${1:-http://localhost:${PORT:-8080}}"
body="$(curl -fsS --max-time 10 "$base/api/health")"
echo "$base/api/health -> $body"
[ "$body" = '{"ok":true}' ]
