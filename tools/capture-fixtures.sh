#!/usr/bin/env bash
#
# Boots a dev server on a fresh copy of the `perf` world, runs
# `/maestro capture <seed> <cases>` for each seed, stops the server, and copies
# each fixture to `fixtures/parity/seed-<seed>.bin`.
#
# Usage: tools/capture-fixtures.sh "<seed> [<seed>...]" <cases>

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$ROOT/server"
LOG="$SERVER_DIR/build/capture-fixtures.log"
CAPTURED="$SERVER_DIR/run/maestro-capture"
OUT="$ROOT/fixtures/parity"

SEEDS="${1:?usage: capture-fixtures.sh \"<seed> [<seed>...]\" <cases>}"
CASES="${2:?usage: capture-fixtures.sh \"<seed> [<seed>...]\" <cases>}"

STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-300}"
CAPTURE_TIMEOUT="${CAPTURE_TIMEOUT:-600}"
SHUTDOWN_TIMEOUT="${SHUTDOWN_TIMEOUT:-120}"

READY_LINE='Done \('

fail() {
  echo "capture-fixtures: FAIL: $*" >&2
  echo "--- last 40 log lines ---" >&2
  tail -n 40 "$LOG" >&2 || true
  exit 1
}

mkdir -p "$(dirname "$LOG")" "$OUT"
: >"$LOG"

FIFO="$(mktemp -u)"
mkfifo "$FIFO"
# Opened read-write: a write-only open on a FIFO blocks until a reader attaches,
# and holding it open keeps the server's console reader from seeing EOF.
exec 3<>"$FIFO"

# Own session, so a failed run can be torn down as a process group rather than
# orphaning the forked server JVM.
setsid bash -c "cd '$SERVER_DIR' && ./gradlew runServer -Pworld=perf --console=plain" \
  <"$FIFO" >>"$LOG" 2>&1 &
GRADLE_PID=$!

cleanup() {
  exec 3>&- || true
  kill -TERM -"$GRADLE_PID" 2>/dev/null || true
  rm -f "$FIFO"
}
trap cleanup EXIT

# Waits until the log has a line matching the first extended regex, and returns
# 0, or one matching the optional second, and returns 1.
await() {
  local done="$1" failed="$2" timeout="$3"
  for ((i = 0; i < timeout; i++)); do
    if grep -qE "$done" "$LOG"; then
      return 0
    fi
    if [[ -n "$failed" ]] && grep -qE "$failed" "$LOG"; then
      return 1
    fi
    kill -0 "$GRADLE_PID" 2>/dev/null || fail "server exited unexpectedly"
    sleep 1
  done
  fail "timed out after ${timeout}s waiting for '$done'"
}

echo "capture-fixtures: waiting up to ${STARTUP_TIMEOUT}s for the server to start"
await "$READY_LINE" "" "$STARTUP_TIMEOUT"

for seed in $SEEDS; do
  rm -f "$CAPTURED/seed-$seed.bin"
  echo "capture-fixtures: capturing seed $seed ($CASES cases)"
  echo "maestro capture $seed $CASES" >&3
  if ! await "Maestro capture done: seed=$seed " "Maestro capture failed: seed=$seed\$" \
    "$CAPTURE_TIMEOUT"; then
    fail "capture of seed $seed failed"
  fi
  grep -E "Maestro capture done: seed=$seed " "$LOG" | tail -n 1 | sed 's/.*Maestro/  Maestro/'
  cp "$CAPTURED/seed-$seed.bin" "$OUT/seed-$seed.bin"
done

echo "stop" >&3
for ((i = 0; i < SHUTDOWN_TIMEOUT; i++)); do
  kill -0 "$GRADLE_PID" 2>/dev/null || break
  sleep 1
done
kill -0 "$GRADLE_PID" 2>/dev/null && fail "server did not stop within ${SHUTDOWN_TIMEOUT}s"

echo "capture-fixtures: wrote $(wc -w <<<"$SEEDS") fixture(s) to $OUT"
