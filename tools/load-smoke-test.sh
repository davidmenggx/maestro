#!/usr/bin/env bash
#
# Runs a load scenario beside the released mod with no nodes, via
# `runLoadServer` on a fresh copy of `worlds/perf`, and checks the load mod's
# work on a real server: its terrain is built once and then stands, a run
# places every entity, measures, and resets the mobs that catch the player they
# hunt, a walking player walks, and the end of the run removes what it placed
# and restores the game rules it changed.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$ROOT/server"
LOG="$SERVER_DIR/build/load-smoke-test.log"
CONFIG="$SERVER_DIR/run/config/maestro.json"

STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-300}"
SHUTDOWN_TIMEOUT="${SHUTDOWN_TIMEOUT:-120}"

READY_LINE='Done \('
SCENARIO='load-smoke'
SCENARIO_FILE="$SERVER_DIR/run/maestro-load/scenarios/$SCENARIO.json"
LOAD_TAG='maestro_load'
STARTED_LINE="Maestro load (started|failed): scenario=$SCENARIO\b"
MEASURING_LINE="Maestro load measuring: scenario=$SCENARIO\$"
ENDED_LINE="Maestro load (done|failed): scenario=$SCENARIO\b"
TERRAIN_LINE="Maestro load (terrain built|failed): scenario=$SCENARIO\b"
ENTITIES=12
DURATION_SECONDS=15
# A hunted player standing at the origin, on open ground, and a walking one
# that no mob hunts, by the names the load mod gives them.
HUNTED='maestro_load_0'
WALKER='maestro_load_1'
WALKER_X=20
WALKER_Z=-20
# Game rules a run turns off, set first to values it must restore.
RULES=(spawn_mobs=true mob_griefing=true max_entity_cramming=7)

fail() {
  echo "load-smoke-test: FAIL: $*" >&2
  echo "--- last 40 log lines ---" >&2
  tail -n 40 "$LOG" >&2 || true
  exit 1
}

command -v jq >/dev/null || fail "jq is not installed"
mkdir -p "$(dirname "$LOG")" "$(dirname "$CONFIG")" "$(dirname "$SCENARIO_FILE")"
: >"$LOG"

# No nodes, so nothing is offloaded. An existing config is restored afterwards.
BACKUP=""
if [[ -f "$CONFIG" ]]; then
  BACKUP="$(mktemp)"
  cp "$CONFIG" "$BACKUP"
fi
echo '{"nodes": []}' >"$CONFIG"

# The mobs are spread off the keep, which rises past their reach.
cat >"$SCENARIO_FILE" <<EOF
{
  "seed": 1,
  "warmupSeconds": 2,
  "durationSeconds": $DURATION_SECONDS,
  "terrain": [{"x": 0, "z": 0, "radius": 48}],
  "keeps": [{"x": 24, "z": 24, "radius": 2}],
  "players": [
    {"x": 0, "z": 0, "hunted": true},
    {"x": $WALKER_X, "z": $WALKER_Z, "walk": 8}
  ],
  "groups": [
    {"entity": "minecraft:husk", "count": $ENTITIES, "x": 0, "z": 0, "spread": 20,
     "nbt": "{DeathLootTable:\"minecraft:empty\"}"}
  ],
  "reset": {"within": 2, "away": 12}
}
EOF

FIFO="$(mktemp -u)"
mkfifo "$FIFO"
# Read-write open: a write-only FIFO open blocks until a reader attaches, and
# keeps the server's console reader from seeing EOF.
exec 3<>"$FIFO"

EXIT_FILE="$(mktemp)"
# Own session, so a failed run is torn down as a process group.
setsid bash -c "cd '$SERVER_DIR' && ./gradlew runLoadServer -Pworld=perf --console=plain; echo \$? >'$EXIT_FILE'" \
  <"$FIFO" >>"$LOG" 2>&1 &
GRADLE_PID=$!

cleanup() {
  exec 3>&- || true
  kill -TERM -"$GRADLE_PID" 2>/dev/null || true
  rm -f "$FIFO" "$EXIT_FILE"
  if [[ -n "$BACKUP" ]]; then
    mv "$BACKUP" "$CONFIG"
  else
    rm -f "$CONFIG"
  fi
}
trap cleanup EXIT

# Waits up to $2 s for more log lines matching regex $1 than $3 (default 0).
wait_for() {
  local expected="$1" timeout="$2" before="${3:-0}" i
  for ((i = 0; i < timeout * 5; i++)); do
    if (("$(grep -cE -- "$expected" "$LOG" || true)" > before)); then
      return 0
    fi
    kill -0 "$GRADLE_PID" 2>/dev/null || fail "server exited while waiting for '$expected'"
    sleep 0.2
  done
  fail "no '$expected' within ${timeout}s"
}

# Sends console command $1, waits up to $3 s (default 60) for a new log line
# matching regex $2, and prints the last such line.
run_command() {
  local command="$1" expected="$2" timeout="${3:-60}" before
  before="$(grep -cE -- "$expected" "$LOG" || true)"
  echo "$command" >&3
  wait_for "$expected" "$timeout" "$before"
  grep -E -- "$expected" "$LOG" | tail -n 1
}

# Whether command `execute $1` passes.
passes() {
  [[ "$(run_command "execute $1" "Test (passed|failed)")" == *"Test passed"* ]]
}

# The count `$2=<n>` in line $1.
count_in() {
  grep -oE "\b$2=[0-9]+" <<<"$1" | cut -d= -f2
}

echo "load-smoke-test: waiting up to ${STARTUP_TIMEOUT}s for the server to start"
wait_for "$READY_LINE" "$STARTUP_TIMEOUT"

for rule in "${RULES[@]}"; do
  run_command "gamerule ${rule%%=*} ${rule#*=}" "${rule%%=*} is now set to: ${rule#*=}" >/dev/null
done

echo "load-smoke-test: building the terrain twice"
BUILT="$(run_command "maestro load terrain $SCENARIO" "$TERRAIN_LINE")"
[[ "$BUILT" == *"terrain built"* ]] || fail "the terrain was not built: $BUILT"
(("$(count_in "$BUILT" blocks)" > 0)) || fail "building the terrain changed no block"
REBUILT="$(run_command "maestro load terrain $SCENARIO" "$TERRAIN_LINE")"
(("$(count_in "$REBUILT" blocks)" == 0)) || fail "the terrain changed when built again: $REBUILT"

echo "load-smoke-test: running the scenario for ${DURATION_SECONDS}s"
STARTED="$(run_command "maestro load start $SCENARIO" "$STARTED_LINE")"
[[ "$STARTED" == *"started"* ]] || fail "the run did not start: $STARTED"
(("$(count_in "$STARTED" players)" == 2)) || fail "the run placed the wrong players: $STARTED"
(("$(count_in "$STARTED" blocks)" == 0)) || fail "the run rebuilt the terrain: $STARTED"
wait_for "$MEASURING_LINE|$ENDED_LINE" 120
grep -qE -- "$MEASURING_LINE" "$LOG" || fail "the run did not start measuring"

# The walker stands in the level, and is off its first column at once; a
# second look rules out passing back through it.
passes "if entity @e[name=$WALKER]" || fail "the walking player is not in the level"
moved=""
for ((attempt = 0; attempt < 3; attempt++)); do
  if passes "unless entity @e[name=$WALKER,x=$WALKER_X,y=-64,z=$WALKER_Z,dx=0,dy=383,dz=0]"; then
    moved=1
    break
  fi
  sleep 1
done
[[ -n "$moved" ]] || fail "the walking player did not walk"

wait_for "$ENDED_LINE" $((DURATION_SECONDS + 60))
ENDED="$(grep -E -- "$ENDED_LINE" "$LOG" | tail -n 1)"
[[ "$ENDED" == *"done"* ]] || fail "the run failed: $ENDED"
REPORT="${ENDED##* report=}"
[[ -f "$REPORT" ]] || fail "the run wrote no report at $REPORT"
echo "load-smoke-test: report $REPORT: $(jq -c '{ticks: .ticks.measured, entities: .entities}' "$REPORT")"
(($(jq '.entities.spawned' "$REPORT") == ENTITIES)) || fail "the run did not place every entity"
(($(jq '.players' "$REPORT") == 2)) || fail "the report names the wrong players"
(($(jq '.ticks.measured' "$REPORT") == DURATION_SECONDS * 20)) || fail "the run measured the wrong ticks"
(($(jq '.entities.resets' "$REPORT") > 0)) || fail "no mob that caught its player was reset"
[[ "$(jq -r '.maestro.version' "$REPORT")" != "null" ]] || fail "the report does not describe the released mod"
[[ "$(jq -r '.maestro.offloading' "$REPORT")" == "false" ]] || fail "the report saw a fleet, with no nodes"

echo "load-smoke-test: checking what the run left"
if passes "if entity @e[tag=$LOAD_TAG]"; then
  fail "the run left entities it placed"
fi
for player in "$HUNTED" "$WALKER"; do
  if passes "if entity @e[name=$player]"; then
    fail "the run left player $player"
  fi
done
for rule in "${RULES[@]}"; do
  value="$(run_command "gamerule ${rule%%=*}" "${rule%%=*} is currently set to: ")"
  [[ "$value" == *": ${rule#*=}" ]] || fail "the run did not restore ${rule%%=*}: $value"
done

echo "load-smoke-test: stopping the server"
echo "stop" >&3
for ((i = 0; i < SHUTDOWN_TIMEOUT; i++)); do
  kill -0 "$GRADLE_PID" 2>/dev/null || break
  sleep 1
done
kill -0 "$GRADLE_PID" 2>/dev/null && fail "server did not stop within ${SHUTDOWN_TIMEOUT}s"
wait "$GRADLE_PID" 2>/dev/null || true
EXIT_CODE="$(cat "$EXIT_FILE" 2>/dev/null || echo 1)"
[[ "$EXIT_CODE" == "0" ]] || fail "runLoadServer exited with status $EXIT_CODE"

echo "load-smoke-test: PASS ($(jq '.entities.resets' "$REPORT") resets in ${DURATION_SECONDS}s)"
