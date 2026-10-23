#!/usr/bin/env bash
#
# Runs the released mod against a real `maestro-node` on loopback, lets husks
# chase villagers near spawn, and checks the summary the mod logs on stop.
#
# OFFLOAD=shadow (default) keeps vanilla's paths and requires every node answer
# to match them. OFFLOAD=on lets answers move the mobs. Either way no admitted
# request may be dropped.
#
# FAULTS=failover (with OFFLOAD=on) pauses the node, then kills and restarts
# it. While it cannot answer the failover must serve the mobs, and after each
# fault the node must come back into service and its answers move mobs again.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$ROOT/server"
NODE_BIN="${NODE_BIN:-$ROOT/build/node/maestro-node}"
OFFLOAD="${OFFLOAD:-shadow}"
FAULTS="${FAULTS:-}"
NAME="$OFFLOAD${FAULTS:+-$FAULTS}"
LOG="$SERVER_DIR/build/e2e-test-$NAME.log"
NODE_LOG="$SERVER_DIR/build/e2e-node-$NAME.log"
CONFIG="$SERVER_DIR/run/config/maestro.json"

STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-300}"
SHUTDOWN_TIMEOUT="${SHUTDOWN_TIMEOUT:-120}"
# Seconds the mobs run at the normal tick rate; sprinting would shrink the
# node's tick budget.
RUN_SECONDS="${RUN_SECONDS:-20}"
# Minimum answers, and in shadow mode the required share matching vanilla.
# The world changes only before the mobs arrive, so all answers must match.
MIN_ANSWERS="${MIN_ANSWERS:-20}"
MIN_MATCH_PERCENT="${MIN_MATCH_PERCENT:-100}"
# Seconds of each failover phase: answers before the first fault, the pause,
# answers between the faults, the node down, and answers after it returns.
WARM_SECONDS="${WARM_SECONDS:-8}"
PAUSE_SECONDS="${PAUSE_SECONDS:-5}"
BETWEEN_SECONDS="${BETWEEN_SECONDS:-6}"
DOWN_SECONDS="${DOWN_SECONDS:-3}"
AFTER_SECONDS="${AFTER_SECONDS:-10}"
# Seconds a fault or a recovery may take to show in the node's state.
STATE_TIMEOUT="${STATE_TIMEOUT:-40}"

WORLD_PORT=25710
CONTROL_PORT=25711
REQUEST_PORT=25712
READY_LINE='Done ('
SUMMARY_LINE='Maestro fleet stopped:'
STATE_LINE='Maestro: node-0 is'
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
case "$FAULTS" in
"") ;;
failover) [[ "$OFFLOAD" == "on" ]] || fail "FAULTS=failover needs OFFLOAD=on" ;;
*) fail "FAULTS must be empty or failover, not '$FAULTS'" ;;
esac
[[ -x "$NODE_BIN" ]] || fail "no node binary at $NODE_BIN (run 'make node')"

mkdir -p "$(dirname "$LOG")" "$(dirname "$CONFIG")"
: >"$LOG"
: >"$NODE_LOG"

# An existing config is restored afterwards.
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

start_node() {
  "$NODE_BIN" --bind 127.0.0.1 --world-port "$WORLD_PORT" --control-port "$CONTROL_PORT" \
    --request-port "$REQUEST_PORT" --workers 2 >>"$NODE_LOG" 2>&1 &
  NODE_PID=$!
}
start_node

FIFO="$(mktemp -u)"
mkfifo "$FIFO"
# Read-write open: a write-only FIFO open blocks until a reader attaches, and
# keeps the server's console reader from seeing EOF.
exec 3<>"$FIFO"

EXIT_FILE="$(mktemp)"
setsid bash -c "cd '$SERVER_DIR' && ./gradlew runReleaseServer --console=plain; echo \$? >'$EXIT_FILE'" \
  <"$FIFO" >>"$LOG" 2>&1 &
GRADLE_PID=$!

cleanup() {
  exec 3>&- || true
  kill -TERM -"$GRADLE_PID" 2>/dev/null || true
  # A paused node handles the signal only once it runs again.
  kill -CONT "$NODE_PID" 2>/dev/null || true
  kill -TERM "$NODE_PID" 2>/dev/null || true
  rm -f "$FIFO" "$EXIT_FILE"
  if [[ -n "$BACKUP" ]]; then
    mv "$BACKUP" "$CONFIG"
  else
    rm -f "$CONFIG"
  fi
}
trap cleanup EXIT

echo "e2e-test: offload=$OFFLOAD faults=${FAULTS:-none}, waiting up to ${STARTUP_TIMEOUT}s for the server to start"
for ((i = 0; i < STARTUP_TIMEOUT; i++)); do
  if grep -qF "$READY_LINE" "$LOG"; then
    break
  fi
  kill -0 "$GRADLE_PID" 2>/dev/null || fail "server exited before it finished starting"
  kill -0 "$NODE_PID" 2>/dev/null || fail "the node exited"
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

# How many times the node was logged as in state $1.
state_count() {
  grep -cF -- "$STATE_LINE $1;" "$LOG" || true
}

# Waits up to STATE_TIMEOUT s for the node to be logged in state $1 more than
# $2 times, and prints that log line.
wait_state() {
  local state="$1" before="$2" i
  for ((i = 0; i < STATE_TIMEOUT * 5; i++)); do
    if (("$(state_count "$state")" > before)); then
      grep -F -- "$STATE_LINE $state;" "$LOG" | tail -n 1
      return 0
    fi
    kill -0 "$GRADLE_PID" 2>/dev/null || fail "server exited while waiting for the node to be $state"
    sleep 0.2
  done
  fail "the node was not logged as $state within ${STATE_TIMEOUT}s"
}

# The count named $2 in summary text $1, or 0.
count_in() {
  local value
  value="$(grep -oE "\b$2=[0-9]+" <<<"$1" | cut -d= -f2)"
  echo "${value:-0}"
}

# Mobs served by the failover so far, by summary text $1.
failover_count() {
  echo $(($(count_in "$1" failover_recent) + $(count_in "$1" failover_full) + $(count_in "$1" failover_cheap)))
}

# Force load the spawn area so the mobs tick. Husks do not burn in daylight.
# In a failover run Resistance V keeps every mob from being hurt, so the chases
# last through every phase, and an iron golem chases the husks with an attack
# goal that never gives up. An invulnerable mob would not be chased at all.
MOB_DATA=""
if [[ "$FAULTS" == "failover" ]]; then
  MOB_DATA=',active_effects:[{id:"minecraft:resistance",amplifier:4b,duration:-1,show_particles:0b}]'
fi
echo "e2e-test: server up, summoning husks and villagers"
# A failed run may have left them force loaded.
run_command "forceload add ~-32 ~-32 ~32 ~32" "force loaded|No chunks were marked"
for ((attempt = 0; ; attempt++)); do
  run_command "execute if loaded ~24 ~ ~24" "Test (passed|failed)"
  [[ "$(grep -oE 'Test (passed|failed)' "$LOG" | tail -n 1)" == "Test passed" ]] && break
  ((attempt < 120)) || fail "the spawn area did not load"
  sleep 0.5
done
# Leftover mobs would race the block changes below.
run_command "kill @e[tag=$MOB_TAG]" "Killed|No entity was found"
# Block changes that reach the node as deltas, made before any mob exists. The
# dev world persists, so a block may already be in place.
run_command "setblock ~2 ~ ~8 minecraft:oak_fence" "Changed the block|Could not set the block"
run_command "setblock ~-8 ~ ~2 minecraft:cobblestone" "Changed the block|Could not set the block"
for offset in "-12 ~ -8" "10 ~ -10" "-6 ~ 12" "14 ~ 6"; do
  read -r dx _ dz <<<"$offset"
  run_command "summon minecraft:villager ~$dx ~ ~$dz {Tags:[\"$MOB_TAG\"]$MOB_DATA}" "Summoned new"
done
for offset in "0 ~ 0" "3 ~ 3" "-3 ~ 3" "3 ~ -3" "-3 ~ -3" "6 ~ 0" "-6 ~ 0" "0 ~ 6"; do
  read -r dx _ dz <<<"$offset"
  run_command "summon minecraft:husk ~$dx ~ ~$dz {Tags:[\"$MOB_TAG\"],DeathLootTable:\"minecraft:empty\"$MOB_DATA}" \
    "Summoned new"
done
if [[ "$FAULTS" == "failover" ]]; then
  run_command "summon minecraft:iron_golem ~-10 ~ ~-10 {Tags:[\"$MOB_TAG\"]$MOB_DATA}" "Summoned new"
  wait_state "linked and in service" 0 >/dev/null

  echo "e2e-test: letting the mobs run for ${WARM_SECONDS}s"
  sleep "$WARM_SECONDS"
  echo "e2e-test: pausing the node"
  before="$(state_count "linked but out of service")"
  kill -STOP "$NODE_PID"
  PAUSED="$(wait_state "linked but out of service" "$before")"
  sleep "$PAUSE_SECONDS"
  echo "e2e-test: resuming the node"
  before="$(state_count "linked and in service")"
  kill -CONT "$NODE_PID"
  RESUMED="$(wait_state "linked and in service" "$before")"

  sleep "$BETWEEN_SECONDS"
  echo "e2e-test: killing the node"
  before="$(state_count "not linked")"
  kill -KILL "$NODE_PID"
  wait "$NODE_PID" 2>/dev/null || true
  KILLED="$(wait_state "not linked" "$before")"
  sleep "$DOWN_SECONDS"
  echo "e2e-test: restarting the node"
  before="$(state_count "linked and in service")"
  start_node
  RESTARTED="$(wait_state "linked and in service" "$before")"
  echo "e2e-test: letting the mobs run for ${AFTER_SECONDS}s"
  sleep "$AFTER_SECONDS"
else
  echo "e2e-test: letting the mobs run for ${RUN_SECONDS}s"
  sleep "$RUN_SECONDS"
fi
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
  count_in "$SUMMARY" "$1"
}

ANSWERED="$(count answered)"
((ANSWERED >= MIN_ANSWERS)) || fail "only $ANSWERED answers, expected at least $MIN_ANSWERS"
if [[ "$FAULTS" == "failover" ]]; then
  # Requests in flight when the node fails are dropped, and their mobs ask again.
  for phase in PAUSED RESUMED KILLED RESTARTED; do
    echo "e2e-test: $phase: ${!phase#*"; so far "}"
  done
  SERVED_PAUSED=$(($(failover_count "$RESUMED") - $(failover_count "$PAUSED")))
  ((SERVED_PAUSED > 0)) || fail "the failover served no mob while the node was paused"
  SERVED_DOWN=$(($(failover_count "$RESTARTED") - $(failover_count "$KILLED")))
  ((SERVED_DOWN > 0)) || fail "the failover served no mob while the node was down"
  APPLIED_BETWEEN=$(($(count_in "$KILLED" applied) - $(count_in "$RESUMED" applied)))
  ((APPLIED_BETWEEN > 0)) || fail "no answer moved a mob after the node resumed"
  APPLIED_AFTER=$(($(count applied) - $(count_in "$RESTARTED" applied)))
  ((APPLIED_AFTER > 0)) || fail "no answer moved a mob after the node restarted"
  echo "e2e-test: failover served $SERVED_PAUSED while paused and $SERVED_DOWN while down;" \
    "answers moved $APPLIED_BETWEEN mobs between the faults and $APPLIED_AFTER after"
else
  DROPPED="$(count dropped)"
  ((DROPPED == 0)) || fail "$DROPPED admitted requests were dropped instead of answered"
fi
if [[ "$OFFLOAD" == "shadow" ]]; then
  MATCHED="$(count shadow_matched)"
  DIFFERED="$(count shadow_differed)"
  if ((MATCHED * 100 < (MATCHED + DIFFERED) * MIN_MATCH_PERCENT)); then
    grep -F "Maestro shadow mismatch" "$LOG" >&2 || true
    fail "$MATCHED of $((MATCHED + DIFFERED)) answers matched vanilla, below ${MIN_MATCH_PERCENT}%"
  fi
else
  APPLIED="$(count applied)"
  # An answer goes unapplied only if its mob stopped waiting that tick.
  ((APPLIED * 2 >= ANSWERED)) || fail "only $APPLIED of $ANSWERED answers moved a mob"
fi

echo "e2e-test: PASS (offload=$OFFLOAD faults=${FAULTS:-none})"
