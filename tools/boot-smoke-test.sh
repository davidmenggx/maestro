#!/usr/bin/env bash
#
# Boots a dedicated 26.1 server through Loom's `runServer`, waits for it to come
# up, stops it, and asserts that the Maestro mixins were applied.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$ROOT/server"
LOG="$SERVER_DIR/build/boot-smoke-test.log"

# Generating the superflat overworld on a cold cache dominates this.
STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-300}"
SHUTDOWN_TIMEOUT="${SHUTDOWN_TIMEOUT:-120}"

PROOF_LINE='Maestro mixin applied'
READY_LINE='Done ('

fail() {
  echo "boot-smoke-test: FAIL: $*" >&2
  echo "--- last 40 log lines ---" >&2
  tail -n 40 "$LOG" >&2 || true
  exit 1
}

mkdir -p "$(dirname "$LOG")"
: >"$LOG"

FIFO="$(mktemp -u)"
mkfifo "$FIFO"
# Opened read-write: a write-only open on a FIFO blocks until a reader attaches,
# and holding it open keeps the server's console reader from seeing EOF.
exec 3<>"$FIFO"

EXIT_FILE="$(mktemp)"
# Own session, so a timed-out or failed run can be torn down as a process group
# rather than orphaning the forked server JVM.
setsid bash -c "cd '$SERVER_DIR' && ./gradlew runServer --console=plain; echo \$? >'$EXIT_FILE'" \
  <"$FIFO" >>"$LOG" 2>&1 &
GRADLE_PID=$!

cleanup() {
  exec 3>&- || true
  kill -TERM -"$GRADLE_PID" 2>/dev/null || true
  rm -f "$FIFO" "$EXIT_FILE"
}
trap cleanup EXIT

echo "boot-smoke-test: waiting up to ${STARTUP_TIMEOUT}s for the server to start"
for ((i = 0; i < STARTUP_TIMEOUT; i++)); do
  if grep -qF "$READY_LINE" "$LOG"; then
    break
  fi
  if ! kill -0 "$GRADLE_PID" 2>/dev/null; then
    fail "server exited before it finished starting"
  fi
  sleep 1
done
grep -qF "$READY_LINE" "$LOG" || fail "server did not start within ${STARTUP_TIMEOUT}s"

echo "boot-smoke-test: server up, stopping it"
echo "stop" >&3

for ((i = 0; i < SHUTDOWN_TIMEOUT; i++)); do
  if ! kill -0 "$GRADLE_PID" 2>/dev/null; then
    break
  fi
  sleep 1
done
kill -0 "$GRADLE_PID" 2>/dev/null && fail "server did not stop within ${SHUTDOWN_TIMEOUT}s"

wait "$GRADLE_PID" 2>/dev/null || true
EXIT_CODE="$(cat "$EXIT_FILE" 2>/dev/null || echo 1)"
[[ "$EXIT_CODE" == "0" ]] || fail "runServer exited with status $EXIT_CODE"

grep -qF "$PROOF_LINE" "$LOG" || fail "proof mixin did not fire (no '$PROOF_LINE' in the log)"

# Mixin reports application problems without necessarily killing the server, so
# an otherwise clean boot is not on its own proof that the hooks took.
if grep -qE 'org\.spongepowered\.asm\.mixin\.(injection\.)?throwables|Mixin apply failed|Critical injection failure' "$LOG"; then
  fail "mixin application reported errors"
fi

echo "boot-smoke-test: PASS (proof mixin fired, no mixin errors)"
