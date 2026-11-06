#!/usr/bin/env bash
#
# Checks the node image: its binaries run, maestro-bench finds the parity
# fixtures, and each fault tools/chaos.py injects takes hold in a node
# container, then heals.
#
# IMAGE names the image (default maestro-node).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${IMAGE:-maestro-node}"
NAME="maestro-image-smoke-test"
# Seconds each fault lasts, and how far into it it is checked.
FAULT_SECONDS=3
CHECK_AFTER=1.5
READY_LINE='maestro-node: listening on'

fail() {
  echo "image-smoke-test: FAIL: $*" >&2
  echo "--- node log ---" >&2
  docker logs "$NAME" 2>&1 | tail -n 20 >&2 || true
  exit 1
}

remove() {
  docker rm --force "$NAME" > /dev/null 2>&1 || true
}
trap remove EXIT

paused() { docker inspect --format '{{.State.Paused}}' "$NAME"; }
running() { docker inspect --format '{{.State.Running}}' "$NAME"; }
qdisc() { docker exec "$NAME" tc qdisc show dev eth0; }
starts() { docker logs "$NAME" 2>&1 | grep -c "$READY_LINE" || true; }

wait_ready() {
  local want=$1
  for _ in $(seq 50); do
    if [[ "$(starts)" -ge "$want" ]]; then
      return
    fi
    sleep 0.2
  done
  fail "the node did not start $want time(s)"
}

# `fault KIND CHECK WANT [chaos options]` runs a KIND fault and requires the
# output of CHECK, partway through it, to contain WANT.
fault() {
  local kind=$1 check=$2 want=$3
  shift 3
  python3 -I "$ROOT/tools/chaos.py" fault --node "$NAME" --kind "$kind" \
    --seconds "$FAULT_SECONDS" --log /dev/null "$@" &
  local chaos=$!
  sleep "$CHECK_AFTER"
  local seen
  seen="$($check 2>&1 || true)"
  if [[ "$seen" != *"$want"* ]]; then
    wait "$chaos" || true
    fail "$kind: wanted '$want' from $check, saw '$seen'"
  fi
  wait "$chaos" || fail "$kind did not apply or heal"
}

docker run --rm "$IMAGE" --help > /dev/null || fail "maestro-node --help failed"
docker run --rm --entrypoint maestro-bench "$IMAGE" \
  --benchmark_list_tests /usr/local/share/maestro/parity | grep -q '^search/all/' \
  || fail "maestro-bench found no parity cases"

remove
docker run --detach --name "$NAME" --cap-add NET_ADMIN "$IMAGE" > /dev/null
wait_ready 1

fault pause paused true
[[ "$(paused)" == false ]] || fail "pause did not heal"

fault delay qdisc 'delay 20ms' --delay-ms 20
fault loss qdisc 'loss 5%' --loss-percent 5
fault partition qdisc 'loss 100%'
[[ "$(qdisc)" != *netem* ]] || fail "netem stayed on: $(qdisc)"

fault crash running false
[[ "$(running)" == true ]] || fail "crash did not heal"
wait_ready 2

echo "image-smoke-test: PASS"
