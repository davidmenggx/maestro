#!/usr/bin/env bash
#
# Runs the released mod against a real `maestro-node` on loopback, with the load
# mod placing husks that chase villagers near the origin and a player by them,
# and checks the summary the mod logs on stop and the load run's report.
#
# OFFLOAD=shadow (default) keeps vanilla's paths and requires every node answer
# to match them. OFFLOAD=on lets answers move the mobs. Either way no admitted
# request may be dropped.
#
# FAULTS=failover (with OFFLOAD=on) pauses the node, then kills and restarts
# it. While it cannot answer the failover must serve the mobs, and after each
# fault the node must come back into service and its answers move mobs again.
#
# FAULTS=split (with OFFLOAD=on) adds a spare node and lets the one node serve a
# request a tick, with a crowd of husks in each half of its region, so the
# region sheds until it is cut between them. The node must keep the lower half,
# as its log shows, without refusing a frame, the spare must search for the
# crowd in the upper half, and answers must move mobs after the cut.
#
# FAULTS=replica (with OFFLOAD=on) is the same with one crowd, which no cut can
# divide, so the spare becomes a replica and must search for some of its mobs.
#
# SUBMODE=stream sends each request as soon as it is encoded instead of
# batching a tick's requests (SUBMODE=batch, the default).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$ROOT/server"
NODE_BIN="${NODE_BIN:-$ROOT/build/node/maestro-node}"
OFFLOAD="${OFFLOAD:-shadow}"
SUBMODE="${SUBMODE:-batch}"
FAULTS="${FAULTS:-}"
NAME="$OFFLOAD-$SUBMODE${FAULTS:+-$FAULTS}"
LOG="$SERVER_DIR/build/e2e-test-$NAME.log"
NODE_LOG="$SERVER_DIR/build/e2e-node-$NAME.log"
SPARE_LOG="$SERVER_DIR/build/e2e-spare-$NAME.log"
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
# Seconds the region may take to be halved: 10 s of shedding, and the warm-up.
SPLIT_TIMEOUT="${SPLIT_TIMEOUT:-120}"

WORLD_PORT=25710
CONTROL_PORT=25711
REQUEST_PORT=25712
SPARE_WORLD_PORT=25713
SPARE_CONTROL_PORT=25714
SPARE_REQUEST_PORT=25715
READY_LINE='Done ('
SUMMARY_LINE='Maestro fleet stopped:'
STATE_LINE='Maestro: node-0 is'
# Logged once no node is spare, which in a split or replica run means the
# region took the spare.
SPLIT_LINE='Maestro: spare nodes []'
# The spare node's last line, with the searches it ran.
SEARCHES_LINE='maestro-node: stopped after'
# The node's line when it keeps the lower half of region 1 as region 2.
KEPT_LINE='maestro-node: kept region 1 as region 2'
# The load mod's lines for the run's scenario, and the tag on what it places.
SCENARIO='e2e'
MEASURING_LINE="Maestro load measuring: scenario=$SCENARIO\$"
DONE_LINE="Maestro load done: scenario=$SCENARIO "
LOAD_TAG='maestro_load'
SCENARIO_FILE="$SERVER_DIR/run/maestro-load/scenarios/$SCENARIO.json"

fail() {
  echo "e2e-test: FAIL: $*" >&2
  echo "--- last 40 server log lines ---" >&2
  tail -n 40 "$LOG" >&2 || true
  echo "--- last 20 node log lines ---" >&2
  tail -n 20 "$NODE_LOG" >&2 || true
  if [[ "$FAULTS" == "split" || "$FAULTS" == "replica" ]]; then
    echo "--- last 20 spare node log lines ---" >&2
    tail -n 20 "$SPARE_LOG" >&2 || true
  fi
  exit 1
}

case "$OFFLOAD" in
shadow | on) ;;
*) fail "OFFLOAD must be shadow or on, not '$OFFLOAD'" ;;
esac
case "$SUBMODE" in
batch | stream) ;;
*) fail "SUBMODE must be batch or stream, not '$SUBMODE'" ;;
esac
case "$FAULTS" in
"") ;;
failover | split | replica) [[ "$OFFLOAD" == "on" ]] || fail "FAULTS=$FAULTS needs OFFLOAD=on" ;;
*) fail "FAULTS must be empty, failover, split or replica, not '$FAULTS'" ;;
esac
[[ -x "$NODE_BIN" ]] || fail "no node binary at $NODE_BIN (run 'make node')"

mkdir -p "$(dirname "$LOG")" "$(dirname "$CONFIG")"
: >"$LOG"
: >"$NODE_LOG"
: >"$SPARE_LOG"

# An existing config is restored afterwards.
BACKUP=""
if [[ -f "$CONFIG" ]]; then
  BACKUP="$(mktemp)"
  cp "$CONFIG" "$BACKUP"
fi
NODES="{\"host\": \"127.0.0.1\", \"worldPort\": $WORLD_PORT, \"controlPort\": $CONTROL_PORT, \"requestPort\": $REQUEST_PORT}"
NODE_CAPACITY=100
AREA_CELLS=5
# A split or replica run has a spare, one request a tick so the region sheds, and
# one region of two by two cells.
SPARE_RUN=""
if [[ "$FAULTS" == "split" || "$FAULTS" == "replica" ]]; then
  SPARE_RUN=1
  NODES="$NODES, {\"host\": \"127.0.0.1\", \"worldPort\": $SPARE_WORLD_PORT, \"controlPort\": $SPARE_CONTROL_PORT, \"requestPort\": $SPARE_REQUEST_PORT}"
  NODE_CAPACITY=1
  AREA_CELLS=2
fi
cat >"$CONFIG" <<EOF
{
  "subMode": "$SUBMODE",
  "offload": "$OFFLOAD",
  "nodes": [$NODES],
  "nodeCapacity": $NODE_CAPACITY,
  "areaCells": $AREA_CELLS,
  "spares": 1,
  "otlpEndpoint": "http://localhost:4317"
}
EOF

start_node() {
  "$NODE_BIN" --bind 127.0.0.1 --world-port "$WORLD_PORT" --control-port "$CONTROL_PORT" \
    --request-port "$REQUEST_PORT" --workers 2 >>"$NODE_LOG" 2>&1 &
  NODE_PID=$!
}
start_node
SPARE_PID=""
if [[ -n "$SPARE_RUN" ]]; then
  "$NODE_BIN" --bind 127.0.0.1 --world-port "$SPARE_WORLD_PORT" --control-port "$SPARE_CONTROL_PORT" \
    --request-port "$SPARE_REQUEST_PORT" --workers 2 >>"$SPARE_LOG" 2>&1 &
  SPARE_PID=$!
fi

FIFO="$(mktemp -u)"
mkfifo "$FIFO"
# Read-write open: a write-only FIFO open blocks until a reader attaches, and
# keeps the server's console reader from seeing EOF.
exec 3<>"$FIFO"

EXIT_FILE="$(mktemp)"
setsid bash -c "cd '$SERVER_DIR' && ./gradlew runLoadServer --console=plain; echo \$? >'$EXIT_FILE'" \
  <"$FIFO" >>"$LOG" 2>&1 &
GRADLE_PID=$!

cleanup() {
  exec 3>&- || true
  kill -TERM -"$GRADLE_PID" 2>/dev/null || true
  # A paused node handles the signal only once it runs again.
  kill -CONT "$NODE_PID" 2>/dev/null || true
  kill -TERM "$NODE_PID" 2>/dev/null || true
  [[ -z "$SPARE_PID" ]] || kill -TERM "$SPARE_PID" 2>/dev/null || true
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

# Husks do not burn in daylight. In a failover, split or replica run Resistance
# V keeps every mob from being hurt, so the chases last through every phase,
# and an iron golem chases the husks with an attack goal that never gives up. An
# invulnerable mob would not be chased at all.
MOB_NBT=""
if [[ "$FAULTS" == "failover" || -n "$SPARE_RUN" ]]; then
  MOB_NBT='active_effects:[{id:"minecraft:resistance",amplifier:4b,duration:-1,show_particles:0b}]'
fi
HUSK_NBT="DeathLootTable:\"minecraft:empty\"${MOB_NBT:+,$MOB_NBT}"
# Where the mobs gather, as x coordinates. A split run copies every group 256
# blocks east: the region it runs on covers x and z from -128 to 383, so the
# copies stand in the upper half along x, and both halves shed alike from the
# start.
ORIGINS="0"
if [[ "$FAULTS" == "split" ]]; then
  ORIGINS="0 256"
fi

# One scenario group: `$2` of `$1` within `$5` blocks of x `$3`, z `$4`, built
# from SNBT compound `$6`.
GROUPS_JSON=()
ENTITIES=0
group() {
  GROUPS_JSON+=("$(jq -nc --arg entity "minecraft:$1" --argjson count "$2" --argjson x "$3" \
    --argjson z "$4" --argjson spread "$5" --arg nbt "$6" \
    '{entity: $entity, count: $count, x: $x, z: $z, spread: $spread, nbt: $nbt}')")
  ENTITIES=$((ENTITIES + $2))
}

# A player by each gathering keeps its chunks loaded, as a player would.
PLAYERS_JSON=()
for origin in $ORIGINS; do
  PLAYERS_JSON+=("{\"x\": $origin, \"z\": 24}")
  group villager 4 "$origin" 0 14 "{$MOB_NBT}"
  group husk 8 "$origin" 0 6 "{$HUSK_NBT}"
  if [[ "$FAULTS" == "failover" || -n "$SPARE_RUN" ]]; then
    group iron_golem 1 $((origin - 10)) -10 0 "{$MOB_NBT}"
  fi
  # A crowd of villagers and husks, so the region sheds every second.
  if [[ -n "$SPARE_RUN" ]]; then
    group villager 8 "$origin" 0 12 "{$MOB_NBT}"
    group husk 30 "$origin" 0 10 "{$HUSK_NBT}"
  fi
done
mkdir -p "$(dirname "$SCENARIO_FILE")"
jq -n --argjson players "[$(IFS=,; echo "${PLAYERS_JSON[*]}")]" \
  --argjson groups "[$(IFS=,; echo "${GROUPS_JSON[*]}")]" \
  '{seed: 1, players: $players, groups: $groups}' >"$SCENARIO_FILE"

echo "e2e-test: server up, making block changes"
# The area of the block changes, which a failed run may have left force loaded.
run_command "forceload add -32 -32 32 32" "force loaded|No chunks were marked"
for ((attempt = 0; ; attempt++)); do
  run_command "execute if loaded 24 ~ 24" "Test (passed|failed)"
  [[ "$(grep -oE 'Test (passed|failed)' "$LOG" | tail -n 1)" == "Test passed" ]] && break
  ((attempt < 120)) || fail "the area of the block changes did not load"
  sleep 0.5
done
# Mobs a failed run left would race the block changes below.
run_command "kill @e[tag=$LOAD_TAG]" "Killed|No entity was found"
# Block changes that reach the node as deltas, made before any mob exists. The
# dev world persists, so a block may already be in place.
run_command "setblock 2 ~ 8 minecraft:oak_fence" "Changed the block|Could not set the block"
run_command "setblock -8 ~ 2 minecraft:cobblestone" "Changed the block|Could not set the block"

echo "e2e-test: placing $ENTITIES husks, villagers and golems"
run_command "maestro load start $SCENARIO" "$MEASURING_LINE|Maestro load failed" 120
grep -qE "$MEASURING_LINE" "$LOG" || fail "the load run did not start measuring"
if [[ "$FAULTS" == "failover" ]]; then
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
elif [[ -n "$SPARE_RUN" ]]; then
  wait_state "linked and in service" 0 >/dev/null
  echo "e2e-test: waiting up to ${SPLIT_TIMEOUT}s for the shedding region to take the spare"
  for ((i = 0; i < SPLIT_TIMEOUT * 5; i++)); do
    grep -qF "$SPLIT_LINE" "$LOG" && break
    kill -0 "$GRADLE_PID" 2>/dev/null || fail "server exited while waiting for the spare to be taken"
    sleep 0.2
  done
  SPLIT="$(grep -F "$SPLIT_LINE" "$LOG" | tail -n 1)"
  [[ -n "$SPLIT" ]] || fail "the region did not take the spare within ${SPLIT_TIMEOUT}s"
  echo "e2e-test: letting the mobs run for ${AFTER_SECONDS}s"
  sleep "$AFTER_SECONDS"
  kill -0 "$SPARE_PID" 2>/dev/null || fail "the spare node exited during the run"
else
  echo "e2e-test: letting the mobs run for ${RUN_SECONDS}s"
  sleep "$RUN_SECONDS"
fi
kill -0 "$NODE_PID" 2>/dev/null || fail "the node exited during the run"

# Ending the run removes what it placed and writes its report.
run_command "maestro load stop" "$DONE_LINE|Maestro load failed"
REPORT="$(grep -F "$DONE_LINE" "$LOG" | tail -n 1 | sed 's/.* report=//')"
[[ -n "$REPORT" && -f "$REPORT" ]] || fail "the load run wrote no report"
run_command "forceload remove -32 -32 32 32" "for force loading|no longer"

echo "e2e-test: stopping the server"
echo "stop" >&3
for ((i = 0; i < SHUTDOWN_TIMEOUT; i++)); do
  kill -0 "$GRADLE_PID" 2>/dev/null || break
  sleep 1
done
kill -0 "$GRADLE_PID" 2>/dev/null && fail "server did not stop within ${SHUTDOWN_TIMEOUT}s"
wait "$GRADLE_PID" 2>/dev/null || true
EXIT_CODE="$(cat "$EXIT_FILE" 2>/dev/null || echo 1)"
[[ "$EXIT_CODE" == "0" ]] || fail "runLoadServer exited with status $EXIT_CODE"

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

# The report covers the run from when every entity stood, so its counts are part
# of the summary's.
echo "e2e-test: load report $REPORT: $(jq -c '{ticks: .ticks, entities: .entities, ticksLate: .ticksLate}' "$REPORT")"
[[ "$(jq -r '.maestro.offloading' "$REPORT")" == "true" ]] || fail "the report saw no fleet"
SPAWNED="$(jq '.entities.spawned' "$REPORT")"
((SPAWNED == ENTITIES)) || fail "the load run placed $SPAWNED entities, not $ENTITIES"
(($(jq '.players' "$REPORT") == $(wc -w <<<"$ORIGINS"))) || fail "the load run placed the wrong players"
(($(jq '.ticks.measured' "$REPORT") > 0)) || fail "the load run measured no tick"
REPORT_ANSWERED="$(jq '.requests.answered' "$REPORT")"
((REPORT_ANSWERED > 0 && REPORT_ANSWERED <= ANSWERED)) ||
  fail "the report counts $REPORT_ANSWERED answers, the summary $ANSWERED"
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
elif [[ -n "$SPARE_RUN" ]]; then
  if [[ "$FAULTS" == "split" ]]; then
    # Requests in flight at the split are dropped, since they name the region cut.
    grep -qE "Maestro: region 2 at x -128\.\.127, .* is held by \[node-0 " "$LOG" ||
      fail "node-0 does not hold the lower half"
    grep -qE "Maestro: region 3 at x 128\.\.383, .* is held by \[node-1 " "$LOG" ||
      fail "the spare does not hold the upper half"
    # The node logs the area it keeps, rather than one sent afresh.
    grep -qF "$KEPT_LINE" "$NODE_LOG" || fail "node-0 did not keep its half of the region"
  else
    grep -qE "Maestro: region 1 at .* is held by \[node-0 at [^,]*, node-1 at " "$LOG" ||
      fail "the spare is not a replica of the region"
    if grep -qE "Maestro: region 2 at " "$LOG"; then
      fail "the region was cut, though its load is in one cell"
    fi
  fi
  if grep -F "refused a world frame" "$NODE_LOG" "$SPARE_LOG" >&2; then
    fail "a node refused a world frame, so it did not keep or take its area"
  fi
  # The spare logs how much it searched when it stops.
  kill -TERM "$SPARE_PID" 2>/dev/null || true
  wait "$SPARE_PID" 2>/dev/null || true
  SPARE_PID=""
  SEARCHES="$(grep -F "$SEARCHES_LINE" "$SPARE_LOG" | tail -n 1 | grep -oE '[0-9]+ searches' | grep -oE '[0-9]+' || echo 0)"
  ((SEARCHES > 0)) || fail "the spare ran no search for the mobs it took"
  echo "e2e-test: SPARE TAKEN: ${SPLIT#*"; so far "}"
  APPLIED_AFTER=$(($(count applied) - $(count_in "$SPLIT" applied)))
  ((APPLIED_AFTER > 0)) || fail "no answer moved a mob after the spare was taken"
  echo "e2e-test: $(count shed) calls shed; the spare ran $SEARCHES searches;" \
    "answers moved $APPLIED_AFTER mobs after it was taken"
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

echo "e2e-test: PASS (offload=$OFFLOAD subMode=$SUBMODE faults=${FAULTS:-none})"
