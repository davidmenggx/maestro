#!/usr/bin/env bash
#
# Boots the released mod alone via `runReleaseServer`, spawns a mob so
# `PathFinder` loads, and asserts the mixins applied and the dev mod did not
# load.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$ROOT/server"
LOG="$SERVER_DIR/build/boot-smoke-test.log"

# Generating the superflat overworld on a cold cache dominates this.
STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-300}"
SHUTDOWN_TIMEOUT="${SHUTDOWN_TIMEOUT:-120}"

PROOF_LINE='Maestro mixin applied'
DEV_MOD='maestro-dev'
READY_LINE='Done ('
MOB_TAG='maestro_boot_smoke'

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
# Read-write open: a write-only FIFO open blocks until a reader attaches, and
# keeps the server's console reader from seeing EOF.
exec 3<>"$FIFO"

EXIT_FILE="$(mktemp)"
# Own session, so a failed run is torn down as a process group.
setsid bash -c "cd '$SERVER_DIR' && ./gradlew runReleaseServer --console=plain; echo \$? >'$EXIT_FILE'" \
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

# Sends console command $1, waits up to $3 s (default 60) for a new log line
# matching regex $2.
run_command() {
  local command="$1" expected="$2" timeout="${3:-60}" before i
  before="$(grep -cE -- "$expected" "$LOG" || true)"
  echo "$command" >&3
  for ((i = 0; i < timeout * 5; i++)); do
    if (("$(grep -cE -- "$expected" "$LOG" || true)" > before)); then
      return 0
    fi
    kill -0 "$GRADLE_PID" 2>/dev/null || fail "server exited while running '$command'"
    sleep 0.2
  done
  fail "no '$expected' within ${timeout}s of '$command'"
}

# `PathFinderMixin` applies when `PathFinder` first loads, which any mob does.
# The spawn chunk is force loaded so the mobs tick, then mobs and ticket are
# removed.
echo "boot-smoke-test: server up, making mobs so PathFinder loads"
run_command "forceload add ~ ~" "to be force loaded"
for ((attempt = 0; ; attempt++)); do
  run_command "execute if loaded ~ ~ ~" "Test (passed|failed)"
  [[ "$(grep -oE 'Test (passed|failed)' "$LOG" | tail -n 1)" == "Test passed" ]] && break
  ((attempt < 120)) || fail "the spawn chunk did not load"
  sleep 0.5
done
run_command "summon minecraft:villager ~ ~ ~ {Tags:[\"$MOB_TAG\"]}" "Summoned new"
run_command "summon minecraft:zombie ~ ~ ~ {Tags:[\"$MOB_TAG\"],DeathLootTable:\"minecraft:empty\"}" \
  "Summoned new"
run_command "tick sprint 40" "Sprint completed"
run_command "kill @e[tag=$MOB_TAG]" "Killed|No entity was found"
# Lets a killed mob finish dying.
run_command "tick sprint 40" "Sprint completed"
run_command "forceload remove ~ ~" "for force loading"

echo "boot-smoke-test: stopping the server"
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
[[ "$EXIT_CODE" == "0" ]] || fail "runReleaseServer exited with status $EXIT_CODE"

grep -qF "$PROOF_LINE" "$LOG" || fail "proof mixin did not fire (no '$PROOF_LINE' in the log)"

# The released jar holds no development code, so neither may this boot.
if grep -qF "$DEV_MOD" "$LOG"; then
  fail "the development mod '$DEV_MOD' was loaded"
fi

# A failed mixin apply need not kill the server, so a clean boot proves nothing.
# It logs "Mixin apply for mod <mod> failed ..." or "... FAILED during APPLY".
if grep -qE 'org\.spongepowered\.asm\.mixin\.([a-z]+\.)?throwables|Mixin [a-z]+ for mod .* failed|FAILED during|Critical injection failure' "$LOG"; then
  fail "mixin application reported errors"
fi

echo "boot-smoke-test: PASS (proof mixin fired, PathFinder loaded, no mixin errors)"
