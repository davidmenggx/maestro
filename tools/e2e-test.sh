#!/usr/bin/env bash
#
# Runs the released mod against a real compute node: starts `maestro-node` on
# loopback, boots a dedicated server whose config points at it, lets husks chase
# villagers near spawn for a while, stops it, and checks what the fleet did from
# the summary the mod logs when it stops.
#
# OFFLOAD=shadow (the default) keeps vanilla's paths and compares the node's
# answers with them. OFFLOAD=on lets the node's answers move the mobs.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$ROOT/server"
NODE_BIN="${NODE_BIN:-$ROOT/build/node/maestro-node}"
OFFLOAD="${OFFLOAD:-shadow}"
LOG="$SERVER_DIR/build/e2e-test-$OFFLOAD.log"
NODE_LOG="$SERVER_DIR/build/e2e-node-$OFFLOAD.log"
CONFIG="$SERVER_DIR/run/config/maestro.json"

STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-300}"
SHUTDOWN_TIMEOUT="${SHUTDOWN_TIMEOUT:-120}"
# Seconds the mobs run at the normal tick rate. Sprinting would squeeze the
# node's tick budget into a few milliseconds.
RUN_SECONDS="${RUN_SECONDS:-20}"
# The fewest answers the run must get, and in shadow mode the share of them that
# must match vanilla's path.
MIN_ANSWERS="${MIN_ANSWERS:-20}"
MIN_MATCH_PERCENT="${MIN_MATCH_PERCENT:-80}"

WORLD_PORT=25710
CONTROL_PORT=25711
REQUEST_PORT=25712
READY_LINE='Done ('
SUMMARY_LINE='Maestro fleet stopped:'
MOB_TAG='maestro_e2e'

fail() {
  echo "e2e-test: FAIL: $*" >&2
  echo "--- last 40 server log lines ---" >&2
  tail -n 40 "$LOG" >&2 || true
  echo "--- last 20 node log lines ---" >&2
  tail -n 20 "$NODE_LOG" >&2 || true
  exit 1
}

case "$OFFLOAD" in
shadow | on) ;;
*) fail "OFFLOAD must be shadow or on, not '$OFFLOAD'" ;;
esac
[[ -x "$NODE_BIN" ]] || fail "no node binary at $NODE_BIN (run 'make node')"

mkdir -p "$(dirname "$LOG")" "$(dirname "$CONFIG")"
: >"$LOG"
: >"$NODE_LOG"

# The run writes its own config. One already there is put back afterwards.
BACKUP=""
if [[ -f "$CONFIG" ]]; then
  BACKUP="$(mktemp)"
  cp "$CONFIG" "$BACKUP"
fi
cat >"$CONFIG" <<EOF
{
  "mode": "stateful",
  "subMode": "batch",
  "offload": "$OFFLOAD",
  "nodes": [
    {
      "host": "127.0.0.1",
      "worldPort": $WORLD_PORT,
      "controlPort": $CONTROL_PORT,
      "requestPort": $REQUEST_PORT
    }
  ],
  "nodeCapacity": 100,
  "otlpEndpoint": "http://localhost:4317"
}
EOF

"$NODE_BIN" --bind 127.0.0.1 --world-port "$WORLD_PORT" --control-port "$CONTROL_PORT" \
  --request-port "$REQUEST_PORT" --workers 2 >>"$NODE_LOG" 2>&1 &
NODE_PID=$!

FIFO="$(mktemp -u)"
mkfifo "$FIFO"
# Opened read-write: a write-only open on a FIFO blocks until a reader attaches,
# and holding it open keeps the server's console reader from seeing EOF.
exec 3<>"$FIFO"

EXIT_FILE="$(mktemp)"
setsid bash -c "cd '$SERVER_DIR' && ./gradlew runReleaseServer --console=plain; echo \$? >'$EXIT_FILE'" \
  <"$FIFO" >>"$LOG" 2>&1 &
GRADLE_PID=$!

cleanup() {
  exec 3>&- || true
  kill -TERM -"$GRADLE_PID" 2>/dev/null || true
  kill -TERM "$NODE_PID" 2>/dev/null || true
  rm -f "$FIFO" "$EXIT_FILE"
  if [[ -n "$BACKUP" ]]; then
    mv "$BACKUP" "$CONFIG"
  else
    rm -f "$CONFIG"
  fi
}
trap cleanup EXIT

echo "e2e-test: offload=$OFFLOAD, waiting up to ${STARTUP_TIMEOUT}s for the server to start"
for ((i = 0; i < STARTUP_TIMEOUT; i++)); do
  if grep -qF "$READY_LINE" "$LOG"; then
    break
  fi
  kill -0 "$GRADLE_PID" 2>/dev/null || fail "server exited before it finished starting"
  kill -0 "$NODE_PID" 2>/dev/null || fail "the node exited"
  sleep 1
done
grep -qF "$READY_LINE" "$LOG" || fail "server did not start within ${STARTUP_TIMEOUT}s"

# Sends console command $1 and waits up to $3 seconds (default 60) for a new
# log line matching the extended regex $2.
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

# No chunk stays loaded without a player, so the area around spawn is force
# loaded for the mobs to tick. Husks do not burn in daylight, and hunt villagers.
echo "e2e-test: server up, summoning husks and villagers"
# A run that failed may have left them force loaded.
run_command "forceload add ~-32 ~-32 ~32 ~32" "force loaded|No chunks were marked"
for ((attempt = 0; ; attempt++)); do
  run_command "execute if loaded ~24 ~ ~24" "Test (passed|failed)"
  [[ "$(grep -oE 'Test (passed|failed)' "$LOG" | tail -n 1)" == "Test passed" ]] && break
  ((attempt < 120)) || fail "the spawn area did not load"
  sleep 0.5
done
for offset in "-12 ~ -8" "10 ~ -10" "-6 ~ 12" "14 ~ 6"; do
  read -r dx _ dz <<<"$offset"
  run_command "summon minecraft:villager ~$dx ~ ~$dz {Tags:[\"$MOB_TAG\"]}" "Summoned new"
done
for offset in "0 ~ 0" "3 ~ 3" "-3 ~ 3" "3 ~ -3" "-3 ~ -3" "6 ~ 0" "-6 ~ 0" "0 ~ 6"; do
  read -r dx _ dz <<<"$offset"
  run_command "summon minecraft:husk ~$dx ~ ~$dz {Tags:[\"$MOB_TAG\"],DeathLootTable:\"minecraft:empty\"}" \
    "Summoned new"
done
# A few block changes while the mobs move, which reach the node as deltas.
# The dev world is kept between runs, so a block may already be in place.
run_command "setblock ~2 ~ ~8 minecraft:oak_fence" "Changed the block|Could not set the block"
run_command "setblock ~-8 ~ ~2 minecraft:cobblestone" "Changed the block|Could not set the block"

echo "e2e-test: letting the mobs run for ${RUN_SECONDS}s"
sleep "$RUN_SECONDS"
kill -0 "$NODE_PID" 2>/dev/null || fail "the node exited during the run"

run_command "kill @e[tag=$MOB_TAG]" "Killed|No entity was found"
run_command "forceload remove ~-32 ~-32 ~32 ~32" "for force loading|no longer"

echo "e2e-test: stopping the server"
echo "stop" >&3
for ((i = 0; i < SHUTDOWN_TIMEOUT; i++)); do
  kill -0 "$GRADLE_PID" 2>/dev/null || break
  sleep 1
done
kill -0 "$GRADLE_PID" 2>/dev/null && fail "server did not stop within ${SHUTDOWN_TIMEOUT}s"
wait "$GRADLE_PID" 2>/dev/null || true
EXIT_CODE="$(cat "$EXIT_FILE" 2>/dev/null || echo 1)"
[[ "$EXIT_CODE" == "0" ]] || fail "runReleaseServer exited with status $EXIT_CODE"

if grep -qE 'org\.spongepowered\.asm\.mixin\.([a-z]+\.)?throwables|Mixin [a-z]+ for mod .* failed|FAILED during|Critical injection failure' "$LOG"; then
  fail "mixin application reported errors"
fi
if grep -qF "Maestro controller self-check:" "$LOG"; then
  fail "the controller found its own state inconsistent"
fi

SUMMARY="$(grep -F "$SUMMARY_LINE" "$LOG" | tail -n 1)"
[[ -n "$SUMMARY" ]] || fail "the fleet logged no summary"
echo "e2e-test: ${SUMMARY#*"$SUMMARY_LINE" }"
count() {
  local value
  value="$(grep -oE "\b$1=[0-9]+" <<<"$SUMMARY" | cut -d= -f2)"
  echo "${value:-0}"
}

ANSWERED="$(count answered)"
((ANSWERED >= MIN_ANSWERS)) || fail "only $ANSWERED answers, expected at least $MIN_ANSWERS"
if [[ "$OFFLOAD" == "shadow" ]]; then
  MATCHED="$(count shadow_matched)"
  DIFFERED="$(count shadow_differed)"
  ((MATCHED * 100 >= (MATCHED + DIFFERED) * MIN_MATCH_PERCENT)) ||
    fail "$MATCHED of $((MATCHED + DIFFERED)) answers matched vanilla, below ${MIN_MATCH_PERCENT}%"
else
  APPLIED="$(count applied)"
  ((APPLIED > 0)) || fail "no answer moved a mob"
fi

echo "e2e-test: PASS (offload=$OFFLOAD)"
