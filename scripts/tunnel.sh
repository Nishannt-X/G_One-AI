#!/usr/bin/env bash
# OPTIONAL (stretch / sharing only): expose the backend on your ngrok static domain. The browser demo does not need it.
# Usage: scripts/tunnel.sh <your-static-domain>      Stop with Ctrl+C.
set -euo pipefail
domain="${1:-}"
if [ -z "$domain" ]; then echo "Usage: scripts/tunnel.sh <ngrok-static-domain>" >&2; exit 1; fi
echo "Tunnel: https://$domain -> http://localhost:${PORT:-8080}. Ctrl+C to stop."
exec ngrok http --url="$domain" "${PORT:-8080}"
