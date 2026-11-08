#!/usr/bin/env bash
# Stops the backend started by scripts/run-backend.sh.
set -euo pipefail
run_dir="${G_ONE_RUN_DIR:-${TMPDIR:-/tmp}/g-one-demo}"
pid_file="$run_dir/backend.pid"
if [ ! -f "$pid_file" ]; then echo "No pid file at $pid_file; nothing to stop."; exit 0; fi
pid="$(cat "$pid_file")"
if kill "$pid" 2>/dev/null; then echo "Stopped backend (pid $pid)."; else echo "Process $pid was not running."; fi
rm -f "$pid_file"
