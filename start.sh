#!/usr/bin/env bash
# Starts the backend (server mode, :8080) and the Vite dev server (:5173).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

if [[ ! -f target/case-intake.jar ]]; then
  echo "Building backend jar..."
  ./mvnw -q -DskipTests package
fi

if [[ ! -d frontend/node_modules ]]; then
  echo "Installing frontend dependencies..."
  npm --prefix frontend install
fi

BACKEND_PID=""
cleanup() {
  trap - INT TERM EXIT
  if [[ -n "$BACKEND_PID" ]] && kill -0 "$BACKEND_PID" 2>/dev/null; then
    kill "$BACKEND_PID" 2>/dev/null || true
    wait "$BACKEND_PID" 2>/dev/null || true
  fi
}
trap cleanup INT TERM EXIT

JAVA_BIN=java
if [[ -n "${JAVA_HOME:-}" ]]; then JAVA_BIN="$JAVA_HOME/bin/java"; fi

BACKEND_LOG=target/backend.log
BACKEND_URL=http://localhost:8080/api/catalog
STARTUP_TIMEOUT=60

echo "Starting backend (log: $BACKEND_LOG)..."
"$JAVA_BIN" -jar target/case-intake.jar >"$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!

# Wait until the backend answers; stop here (the trap cleans up) if it dies or never comes up.
waited=0
until curl -sf -o /dev/null "$BACKEND_URL"; do
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "Backend exited during startup. See $BACKEND_LOG:" >&2
    tail -n 20 "$BACKEND_LOG" >&2 || true
    exit 1
  fi
  if (( waited >= STARTUP_TIMEOUT )); then
    echo "Backend did not answer $BACKEND_URL within ${STARTUP_TIMEOUT}s. See $BACKEND_LOG." >&2
    exit 1
  fi
  sleep 1
  waited=$((waited + 1))
done
echo "Backend is up on http://localhost:8080 (log: $BACKEND_LOG)."

npm --prefix frontend run dev
