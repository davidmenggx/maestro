#!/usr/bin/env python3
"""Injects seeded faults into node containers.

Each fault takes one node out for a while, then heals: `pause` freezes its
container, `crash` kills it and starts it again, `delay` and `loss` slow or
drop what it sends with `tc netem`, and `partition` drops all of it, so the
node falls silent while what is sent to it still arrives. A node
is a container NAME, or NAME@HOST on the Docker host HOST, such as
maestro-bench-node-0@ssh://ubuntu@10.0.1.12.

  plan   prints the faults a seed gives over --duration seconds, one JSON line
         each
  run    applies them, logging each change as a JSON line, and heals every
         fault still on when it ends or is stopped
  fault  applies one fault to one node now, for --seconds

Faults start every --gap seconds on average, each lasting a time drawn from
--length, on a node no other fault holds, with at most --concurrent at once,
and all are over by --duration. A container needs `tc` and NET_ADMIN, as
`node/Dockerfile` and `tools/bench.py` give it. Any command that fails makes
the exit status 1.
"""

import argparse
import dataclasses
import json
import random
import signal
import subprocess
import sys
import time

KINDS = ("pause", "crash", "delay", "loss", "partition")
# The interface Docker gives a container on a bridge network.
INTERFACE = "eth0"
# Tries at a heal before it counts as failed, a second apart.
HEAL_TRIES = 3
# Longest a stopped run sleeps on before it notices.
STOP_POLL_SECONDS = 0.1
COMMAND_TIMEOUT = 60


@dataclasses.dataclass(frozen=True)
class Fault:
    """A fault on the node at index `node` from `start` to `end` seconds. A
    `delay` adds `delay_ms` give or take `jitter_ms`; a `loss` drops
    `loss_percent` of the packets."""

    start: float
    end: float
    node: int
    kind: str
    delay_ms: int = 0
    jitter_ms: int = 0
    loss_percent: float = 0.0


def drawn(rng, kind, delay_ms, loss_percent):
    """The settings of a `kind` fault, drawn from the ranges given."""
    if kind == "delay":
        delay = round(rng.uniform(*delay_ms))
        return {"delay_ms": delay, "jitter_ms": delay // 4}
    if kind == "loss":
        return {"loss_percent": round(rng.uniform(*loss_percent), 1)}
    return {}


def plan(
    seed,
    nodes,
    duration,
    kinds=KINDS,
    gap=20.0,
    length=(3.0, 10.0),
    concurrent=1,
    delay_ms=(5.0, 50.0),
    loss_percent=(1.0, 20.0),
):
    """The faults `seed` gives on `nodes` nodes over `duration` seconds, by
    start."""
    rng = random.Random(seed)
    most = min(concurrent, nodes)
    faults = []
    at = rng.expovariate(1 / gap)
    while at < duration:
        active = [fault for fault in faults if fault.end > at]
        if len(active) >= most:
            at = min(fault.end for fault in active)
            continue
        held = {fault.node for fault in active}
        node = rng.choice([index for index in range(nodes) if index not in held])
        kind = rng.choice(kinds)
        end = min(at + rng.uniform(*length), duration)
        settings = drawn(rng, kind, delay_ms, loss_percent)
        faults.append(Fault(at, end, node, kind, **settings))
        at += rng.expovariate(1 / gap)
    return faults


def described(fault, nodes):
    """`fault` as JSON fields, naming its node from `nodes`."""
    out = {"node": nodes[fault.node][0], "kind": fault.kind}
    if fault.kind == "delay":
        out.update(delayMs=fault.delay_ms, jitterMs=fault.jitter_ms)
    elif fault.kind == "loss":
        out["lossPercent"] = fault.loss_percent
    return out


def docker(host):
    """The Docker CLI, aimed at `host`, or at the local daemon if None."""
    return ["docker"] + (["--host", host] if host else [])


def qdisc(name, *args):
    """`tc qdisc` run as root in container `name`'s network namespace."""
    return ["exec", "--user", "0", name, "tc", "qdisc", *args]


def inject_command(fault, name):
    """The Docker arguments that start `fault` on container `name`."""
    if fault.kind == "pause":
        return ["pause", name]
    if fault.kind == "crash":
        return ["kill", name]
    if fault.kind == "delay":
        rule = ["delay", f"{fault.delay_ms}ms", f"{fault.jitter_ms}ms"]
    elif fault.kind == "loss":
        rule = ["loss", f"{fault.loss_percent}%"]
    else:
        rule = ["loss", "100%"]
    return qdisc(name, "replace", "dev", INTERFACE, "root", "netem", *rule)


def heal_command(fault, name):
    """The Docker arguments that end `fault` on container `name`."""
    if fault.kind == "pause":
        return ["unpause", name]
    if fault.kind == "crash":
        return ["start", name]
    return qdisc(name, "del", "dev", INTERFACE, "root")


def execute(argv):
    """Runs `argv`: None if it succeeded, or else what went wrong."""
    try:
        done = subprocess.run(
            argv, capture_output=True, text=True, timeout=COMMAND_TIMEOUT, check=False
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        return str(error)
    if done.returncode != 0:
        return (done.stderr or done.stdout).strip() or f"exit status {done.returncode}"
    return None


class Runner:
    """Applies faults to `nodes`, each a container name and its Docker host,
    logging each change to `log`. `wait(seconds)` returns early, and true, once
    `stop` is called. `pause(seconds)` spaces the tries at a heal, and a stop
    does not cut it short."""

    def __init__(
        self,
        nodes,
        log,
        execute=execute,
        clock=time.monotonic,
        wait=None,
        pause=time.sleep,
    ):
        self.nodes = nodes
        self.log = log
        self.execute = execute
        self.clock = clock
        self.stopping = False
        self.wait = wait or self.sleep_unless_stopped
        self.pause = pause
        self.started = clock()
        # Faults injected and not yet healed, in the order they started.
        self.on = []
        self.failed = False

    def stop(self):
        """Ends the run, which heals every fault still on. Takes no lock, so a
        signal handler may call it."""
        self.stopping = True

    def sleep_unless_stopped(self, seconds):
        """Sleeps `seconds`: false, or true at once if stopped meanwhile."""
        deadline = self.clock() + seconds
        while not self.stopping:
            left = deadline - self.clock()
            if left <= 0:
                return False
            time.sleep(min(left, STOP_POLL_SECONDS))
        return True

    def record(self, event, **fields):
        line = {"at": round(self.clock() - self.started, 3), "event": event, **fields}
        self.log.write(json.dumps(line) + "\n")
        self.log.flush()

    def command(self, fault, argv):
        return self.execute(docker(self.nodes[fault.node][1]) + argv)

    def inject(self, fault):
        name = self.nodes[fault.node][0]
        error = self.command(fault, inject_command(fault, name))
        if error:
            self.failed = True
            self.record(
                "error", action="inject", **described(fault, self.nodes), error=error
            )
            return
        self.on.append(fault)
        self.record("inject", **described(fault, self.nodes), until=round(fault.end, 3))

    def heal(self, fault):
        """Heals `fault`, trying HEAL_TRIES times. It counts as healed either
        way, so it is not tried again."""
        self.on.remove(fault)
        name = self.nodes[fault.node][0]
        for attempt in range(HEAL_TRIES):
            if attempt:
                self.pause(1)
            error = self.command(fault, heal_command(fault, name))
            if not error:
                self.record("heal", **described(fault, self.nodes))
                return
        self.failed = True
        self.record("error", action="heal", **described(fault, self.nodes), error=error)

    def run(self, faults):
        """Applies `faults`, timed from when this runner was made, healing at
        each instant before injecting. Returns whether every command
        succeeded."""
        events = sorted(
            [(fault.start, 1, index, fault) for index, fault in enumerate(faults)]
            + [(fault.end, 0, index, fault) for index, fault in enumerate(faults)],
            key=lambda event: event[:3],
        )
        for at, starts, _, fault in events:
            delay = self.started + at - self.clock()
            if self.stopping or (delay > 0 and self.wait(delay)):
                break
            if starts:
                self.inject(fault)
            elif fault in self.on:
                self.heal(fault)
        for fault in reversed(list(self.on)):
            self.heal(fault)
        self.record("end", failed=self.failed)
        return not self.failed


def span(text):
    """A range of non-negative numbers such as `3-10`, or one such as `5`."""
    first, dash, last = text.partition("-")
    try:
        low = float(first)
        high = float(last) if dash else low
    except ValueError:
        raise argparse.ArgumentTypeError(f"'{text}' is not a range such as 3-10")
    if low < 0 or high < low:
        raise argparse.ArgumentTypeError(f"'{text}' is not a range such as 3-10")
    return low, high


def kinds(text):
    picked = [kind for kind in text.split(",") if kind]
    unknown = [kind for kind in picked if kind not in KINDS]
    if not picked or unknown:
        raise argparse.ArgumentTypeError(
            f"'{text}' must list some of {', '.join(KINDS)}"
        )
    return picked


def node(text):
    """A node's container name and its Docker host, or None for the local
    one. A container name has no `@`, so the first one ends it."""
    name, _, host = text.partition("@")
    if not name:
        raise argparse.ArgumentTypeError(f"'{text}' is not NAME or NAME@HOST")
    return name, host or None


def positive(text):
    value = float(text)
    if value <= 0:
        raise argparse.ArgumentTypeError(f"{text} is not above 0")
    return value


def parser():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    commands = parser.add_subparsers(dest="command", required=True)
    settings = argparse.ArgumentParser(add_help=False)
    settings.add_argument("--seed", type=int, default=1)
    settings.add_argument(
        "--delay-ms", type=span, default=(5.0, 50.0), help="delay added (default 5-50)"
    )
    settings.add_argument(
        "--loss-percent",
        type=span,
        default=(1.0, 20.0),
        help="packets lost (default 1-20)",
    )
    planned = argparse.ArgumentParser(add_help=False, parents=[settings])
    planned.add_argument("--node", type=node, action="append", required=True)
    planned.add_argument("--duration", type=positive, required=True)
    planned.add_argument("--kinds", type=kinds, default=list(KINDS))
    planned.add_argument(
        "--gap", type=positive, default=20.0, help="mean seconds between faults"
    )
    planned.add_argument(
        "--length", type=span, default=(3.0, 10.0), help="seconds (default 3-10)"
    )
    planned.add_argument("--concurrent", type=int, default=1)
    commands.add_parser("plan", parents=[planned], help="print the faults a seed gives")
    run = commands.add_parser("run", parents=[planned], help="apply the faults")
    run.add_argument("--log", help="where the changes go (default stdout)")
    one = commands.add_parser("fault", parents=[settings], help="apply one fault now")
    one.add_argument("--node", type=node, required=True)
    one.add_argument("--kind", choices=KINDS, required=True)
    one.add_argument("--seconds", type=positive, required=True)
    one.add_argument("--log", help="where the changes go (default stdout)")
    return parser


def main(argv):
    args = parser().parse_args(argv)
    if args.loss_percent[1] > 100:
        raise SystemExit("--loss-percent must be at most 100")
    if args.command == "fault":
        nodes = [args.node]
        settings = drawn(
            random.Random(args.seed), args.kind, args.delay_ms, args.loss_percent
        )
        faults = [Fault(0.0, args.seconds, 0, args.kind, **settings)]
    else:
        if args.length[1] <= 0:
            raise SystemExit("--length must reach above 0")
        if args.concurrent < 1:
            raise SystemExit("--concurrent must be at least 1")
        nodes = args.node
        faults = plan(
            args.seed,
            len(nodes),
            args.duration,
            args.kinds,
            args.gap,
            args.length,
            args.concurrent,
            args.delay_ms,
            args.loss_percent,
        )
    if args.command == "plan":
        for fault in faults:
            line = {"start": round(fault.start, 3), "end": round(fault.end, 3)}
            print(json.dumps({**line, **described(fault, nodes)}))
        return 0

    log = open(args.log, "a") if args.log else sys.stdout
    try:
        runner = Runner(nodes, log)
        for stop in (signal.SIGTERM, signal.SIGINT):
            signal.signal(stop, lambda *_: runner.stop())
        runner.record("start", seed=args.seed, faults=len(faults))
        return 0 if runner.run(faults) else 1
    finally:
        if log is not sys.stdout:
            log.close()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
