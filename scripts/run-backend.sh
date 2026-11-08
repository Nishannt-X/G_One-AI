#!/usr/bin/env bash
# Builds the backend jar, copies it to a private location and runs the copy with env from .env.local.
# Rebuilding under a running jar hangs it, so the running server never uses backend/target directly.
# Stop with Ctrl+C, or scripts/stop-backend.sh from another terminal. No secrets are printed.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_dir="${G_ONE_RUN_DIR:-${TMPDIR:-/tmp}/g-one-demo}"

if [ ! -f "$root/.env.local" ]; then
  echo "Missing $root/.env.local. Copy .env.example to .env.local and fill in values." >&2
  exit 1
fi
set -a; . "$root/.env.local"; set +a

(cd "$root/backend" && ./mvnw -q -DskipTests package)
jar="$(ls -t "$root"/backend/target/g_one-*.jar | grep -v '\.original$' | head -n 1)"
mkdir -p "$run_dir"
cp "$jar" "$run_dir/g-one.jar"

echo "Running a private copy of the jar: $run_dir/g-one.jar (port ${PORT:-8080}). Ctrl+C to stop."
cd "$root/backend"   # the default data dir ./data (SQLite) is then backend/data, which git ignores
java -jar "$run_dir/g-one.jar" &
echo $! > "$run_dir/backend.pid"
trap 'kill "$(cat "$run_dir/backend.pid")" 2>/dev/null || true' INT TERM
wait
