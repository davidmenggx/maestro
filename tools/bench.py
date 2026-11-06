#!/usr/bin/env python3

import argparse
import collections
import datetime
import hashlib
import ipaddress
import json
import os
import platform
import re
import shlex
import shutil
import signal
import statistics
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCENARIOS = ROOT / "bench" / "scenarios"
WORLD = ROOT / "worlds" / "perf"
MODS = ROOT / "server" / "build" / "bench-mods"
GRADLE_PROPERTIES = ROOT / "server" / "gradle.properties"
NODE_BIN = ROOT / "build" / "node" / "maestro-node"
BENCH_BIN = ROOT / "build" / "node" / "maestro-bench"
CHAOS = ROOT / "tools" / "chaos.py"
# Kept across runs, so the launcher's downloads are made once.
SERVER_DIR = ROOT / "build" / "bench" / "server"

FABRIC_INSTALLER = "1.1.2"
# Of Fabric's server launcher for FABRIC_INSTALLER and the Minecraft and loader
# versions in gradle.properties, so it changes with them.
LAUNCHER_SHA256 = "95ae040a73cd30a5b60a2da590c47b5c6edb4d3d7f8ca65947bc81ece0c49d9e"
CONFIGS = ("vanilla", "idle", "batch", "stream")
OFFLOADING = ("batch", "stream")
TICKS_PER_SECOND = 20
TICK_MILLIS = 50.0
# Share of a run's measured ticks that may take longer than TICK_MILLIS.
MAX_OVER_BUDGET = 0.01
# Ticks a second a run's measured ticks must keep to over its wall time, which
# also counts the server's work between ticks.
MIN_TPS = 19.8
# A run that missed the SLO shedding while every node measured at least this
# many times `nodeCapacity` was held back by that assumption, not the nodes.
CAPACITY_HEADROOM = 1.1
# `Terrain.KEEP_MARGIN`: blocks around a keep its terrain keeps clear.
KEEP_MARGIN = 2
# `Priority.SLO_TICKS`: an answer must reach the server in fewer ticks than this.
SLO_TICKS = 3
# The percentile of an offloading run's requests that must meet the SLO.
SLO_PERCENTILE = 99
# Fewest mobs tried when the start count does not hold.
MIN_MOBS = 16
# Entities the load mod places a tick.
SPAWNS_PER_TICK = 64
FIRST_PORT = 25800
# A call the fleet could not take, one count for each way the failover served it.
FAILOVER_COUNTS = ("failover_recent", "failover_full", "failover_cheap")
# Every config's server runs on these, so a collection pauses it for well under
# a tick and a slow tick is the server's own work.
SERVER_JVM_FLAGS = ("-XX:+UseZGC", "-XX:+AlwaysPreTouch")
# How maestro-bench measures the search rate.
SEARCH_BENCH_FLAGS = (
    "--benchmark_filter=^search/all/",
    "--benchmark_min_time=2s",
    "--benchmark_repetitions=3",
    "--benchmark_report_aggregates_only=true",
)
# Local node containers sit on this Docker network, each at a fixed address, so
# one that tools/chaos.py restarts comes back where the server expects it.
NODE_NETWORK = "maestro-bench"
NODE_SUBNET = "10.213.0.0/24"
# A node's own ports, which a container on a remote Docker host publishes.
NODE_PORTS = {"worldPort": 25700, "controlPort": 25701, "requestPort": 25702}
# Where `node/Dockerfile` puts the parity fixtures maestro-bench replays.
IMAGE_FIXTURES = "/usr/local/share/maestro/parity"
# Seconds the nodes may take to come back into service after a run's faults.
RECOVERY_SECONDS = 120
# Seconds tools/chaos.py may take to heal what it has on once told to stop.
CHAOS_STOP_SECONDS = 300
# The mod's line when a node's link or service changes.
NODE_STATE = re.compile(
    r"Maestro: (node-\d+) is (not linked|linked and in service|linked but out of service);"
)
IN_SERVICE = "linked and in service"

SERVER_PROPERTIES = {
    "level-type": "minecraft:flat",
    "level-seed": "maestro",
    "level-name": "world",
    "online-mode": "false",
    "spawn-protection": "0",
    "max-tick-time": "-1",
    "pause-when-empty-seconds": "0",
    "view-distance": "10",
    "simulation-distance": "10",
}


class BenchError(Exception):
    pass


def log(message):
    print(f"bench: {message}", flush=True)


def parse_cpus(text):
    """The CPUs of a taskset list such as `0-2,5`, in order."""
    cpus = []
    for part in text.split(","):
        first, dash, last = part.partition("-")
        last = last if dash else first
        if not first.isdigit() or not last.isdigit() or int(last) < int(first):
            raise ValueError(f"'{text}' is not a CPU list such as 0-2,5")
        cpus.extend(range(int(first), int(last) + 1))
    if not cpus or len(set(cpus)) != len(cpus):
        raise ValueError(f"'{text}' names no CPU or one twice")
    return cpus


def cpu_list(cpus):
    return ",".join(map(str, cpus))


def split_cpus(cpus, parts):
    """`cpus` cut into `parts` runs of consecutive entries, as even as can be."""
    size, extra = divmod(len(cpus), parts)
    runs, start = [], 0
    for part in range(parts):
        end = start + size + (1 if part < extra else 0)
        runs.append(cpus[start:end])
        start = end
    return runs


def core_siblings(cpu):
    """The CPUs on `cpu`'s physical core, itself included, or only itself if
    the kernel does not say."""
    topology = Path(f"/sys/devices/system/cpu/cpu{cpu}/topology/thread_siblings_list")
    try:
        return set(parse_cpus(topology.read_text().strip()))
    except (OSError, ValueError):
        return {cpu}


def shared_cores(server_cpus, node_cpus, siblings=core_siblings):
    """Each pair of a server CPU and a node CPU on the same physical core, by
    `siblings`."""
    return [
        (server, node)
        for server in server_cpus
        for node in node_cpus
        if node in siblings(server)
    ]


def squares_meet(a, b, grow=0):
    """Whether the squares of blocks `a` and `b`, `b` grown by `grow` blocks,
    share a column."""
    reach = a["radius"] + b["radius"] + grow
    return abs(a["x"] - b["x"]) <= reach and abs(a["z"] - b["z"]) <= reach


def keeps_covered(scenarios):
    """Each pair of scenario names where the first's terrain covers a keep of
    the second, or the clear ground around it, which building the first on
    their shared world would undo."""
    return [
        (name, other)
        for name, spec in scenarios.items()
        for other, other_spec in scenarios.items()
        if other != name
        and any(
            squares_meet(area, keep, KEEP_MARGIN)
            for area in spec.get("terrain", [])
            for keep in other_spec.get("keeps", [])
        )
    ]


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as data:
        for block in iter(lambda: data.read(1 << 16), b""):
            digest.update(block)
    return digest.hexdigest()


def scaled(scenario, mobs, warmup, duration):
    """`scenario` with its group counts, taken as weights, scaled to `mobs` in
    all by largest remainder. A group left with none is dropped."""
    weights = [group["count"] for group in scenario["groups"]]
    exact = [mobs * weight / sum(weights) for weight in weights]
    counts = [int(share) for share in exact]
    by_remainder = sorted(range(len(counts)), key=lambda i: (counts[i] - exact[i], i))
    for i in by_remainder[: mobs - sum(counts)]:
        counts[i] += 1
    groups = [
        dict(group, count=count)
        for group, count in zip(scenario["groups"], counts)
        if count > 0
    ]
    return dict(scenario, groups=groups, warmupSeconds=warmup, durationSeconds=duration)


def metrics(report):
    """What the table and the verdict read from a load report. `tps` is the
    measured ticks over their wall time. `drift` is the share by which the
    second half of the measured ticks took longer on average than the first.
    `nodeRate` is the lowest rate a node measured, in requests a tick. Of the
    requests offloaded, `sloRequests` are those the fleet answered or should
    have, and `sloLate` those of them answered SLO_TICKS or more ticks after
    they were asked, or shed, dropped or served by the failover."""
    ticks = report["ticks"]
    measured = ticks["measured"]
    wall = ticks["wallSeconds"]
    maestro = report["maestro"]
    rates = [rate for rate in report["nodeRates"] or [] if rate is not None]
    out = {
        "measured": measured,
        "tps": measured / wall if wall else None,
        "meanMillis": ticks["meanMillis"],
        "p50Millis": ticks["p50Millis"],
        "p99Millis": ticks["p99Millis"],
        "overBudget": ticks["overBudget"] / measured if measured else None,
        "drift": drift(ticks["firstHalfMeanMillis"], ticks["secondHalfMeanMillis"]),
        "resetsPerSecond": report["entities"]["resets"] / wall if wall else None,
        "spawned": report["entities"]["spawned"],
        "alive": report["entities"]["alive"],
        "offloading": bool(maestro and maestro["offloading"]),
        "nodeCapacity": maestro["nodeCapacity"] if maestro else None,
        "nodeRate": min(rates) if rates else None,
    }
    requests = report["requests"]
    if requests is not None and wall:
        shed = requests.get("shed", 0)
        dropped = requests.get("dropped", 0)
        failover = sum(requests.get(name, 0) for name in FAILOVER_COUNTS)
        out["answeredPerSecond"] = requests.get("answered", 0) / wall
        out["shedPerSecond"] = shed / wall
        out["failoverPerSecond"] = failover / wall
        out["lateP99"] = report["ticksLate"]["p99"]
        histogram = report["ticksLate"]["histogram"]
        lost = shed + dropped + failover
        out["sloRequests"] = sum(histogram) + lost
        out["sloLate"] = sum(histogram[SLO_TICKS:]) + lost
    return out


def drift(first, second):
    """How much longer `second` is than `first`, as a share, or None without
    both."""
    if not first or second is None:
        return None
    return second / first - 1


def late_share(run):
    """The share of a run's requests that missed the SLO, or None if it
    offloaded none."""
    if not run.get("sloRequests"):
        return None
    return run["sloLate"] / run["sloRequests"]


def failures(run, mobs, offloading):
    """What a run failed on, or nothing if it held: measuring a tick
    (`unmeasured`), placing every mob (`mobs`), the mean tick (`mean`), the
    share of ticks over budget (`tail`), the ticks a second over its wall time
    (`tps`), and, if it should be `offloading`, an active fleet with requests
    (`fleet`) and the SLO (`slo`). A run that missed the SLO shedding while
    every node measured well above `nodeCapacity` is also marked `capacity`."""
    if run is None or not run["measured"]:
        return ["unmeasured"]
    failed = []
    if run["spawned"] != mobs:
        failed.append("mobs")
    if run["meanMillis"] > TICK_MILLIS:
        failed.append("mean")
    if run["overBudget"] > MAX_OVER_BUDGET:
        failed.append("tail")
    if run["tps"] is not None and run["tps"] < MIN_TPS:
        failed.append("tps")
    if offloading:
        if not run["offloading"] or not run.get("sloRequests"):
            failed.append("fleet")
        # The nearest-rank SLO_PERCENTILE, as `Report` ranks it, is under SLO_TICKS.
        elif 100 * run["sloLate"] > (100 - SLO_PERCENTILE) * run["sloRequests"]:
            failed.append("slo")
            if (
                run["shedPerSecond"] > 0
                and run["nodeRate"] is not None
                and run["nodeRate"] >= CAPACITY_HEADROOM * run["nodeCapacity"]
            ):
                failed.append("capacity")
    return failed


def most_mobs(holds_at, start, limit, precision):
    """The most mobs that hold by `holds_at`, and the fewest found not to, or
    None if every count tried held. Doubles from `start` up to `limit` while
    counts hold, or halves down to MIN_MOBS while they do not, then bisects
    until the two are within `precision` of each other."""
    held, failed = 0, None
    mobs = start
    if holds_at(mobs):
        held = mobs
        while mobs * 2 <= limit:
            mobs *= 2
            if not holds_at(mobs):
                failed = mobs
                break
            held = mobs
    else:
        failed = mobs
        while mobs > MIN_MOBS:
            mobs = max(mobs // 2, MIN_MOBS)
            if holds_at(mobs):
                held = mobs
                break
            failed = mobs
    while held and failed is not None and failed - held > held * precision:
        mobs = round((held + failed) / 2)
        if mobs in (held, failed):
            break
        if holds_at(mobs):
            held = mobs
        else:
            failed = mobs
    return held, failed


def decided(held, failed, repeats):
    """Whether `held` runs that held and `failed` that did not settle whether
    most of `repeats` hold, whatever the rest do."""
    return held * 2 > repeats or failed * 2 >= repeats


def node_capacity(benchmarks, workers=None):
    """Requests a node with `workers` workers serves a tick, and its searches a
    second, from maestro-bench's JSON output. With `workers` None, the node has
    a worker per thread of the run on the most threads, as maestro-bench and
    the node both default to one per CPU they may use."""
    rows = [
        row
        for row in benchmarks["benchmarks"]
        if row["run_name"].startswith("search/all/")
        and row.get("aggregate_name", "median") == "median"
    ]
    if workers is None and rows:
        workers = max(row["threads"] for row in rows)
    for row in rows:
        if row["threads"] == workers:
            # A multi-threaded run's rate is per thread.
            rate = row["items_per_second"] * workers
            return max(1, int(rate // TICKS_PER_SECOND)), rate
    raise BenchError(f"maestro-bench reported no run on {workers} threads")


def median(values):
    values = [value for value in values if value is not None]
    return statistics.median(values) if values else None


def number(value, digits=1, suffix=""):
    return "-" if value is None else f"{value:.{digits}f}{suffix}"


def table(results):
    """The results as text: the nodes' assumed capacity, then per scenario the
    most mobs each config held and every count tried, with each figure the
    median of its runs and what the runs that did not hold failed on."""
    header = [
        "config",
        "mobs",
        "held",
        "tick ms (range)",
        "p50",
        "p99",
        ">50 ms",
        "drift",
        "TPS",
        "late p99",
        "missed SLO",
        "answered/s",
        "shed/s",
        "node rate",
        "failover/s",
        "reset/s",
        "failed on",
    ]
    text = []
    nodes = results["nodes"]
    if nodes is not None:
        text.append(
            f"{nodes.get('count', '?')} node(s) at nodeCapacity {nodes.get('capacity')}"
        )
        text.append("")
    for scenario, configs in results["scenarios"].items():
        most = []
        for config, result in configs.items():
            if result["most"] is None:
                continue
            above = result["firstFailed"] is None and result["searched"]
            most.append(f"{config} {result['most']}{'+' if above else ''}")
        text.append(f"{scenario}: most mobs at 20 TPS: {', '.join(most) or 'none'}")
        rows = [header]
        for config, result in configs.items():
            for point in sorted(result["points"], key=lambda p: p["mobs"]):
                runs = [run["metrics"] for run in point["runs"] if run["metrics"]]
                means = [run["meanMillis"] for run in runs]
                spread = (
                    f" ({min(means):.1f}-{max(means):.1f})" if len(means) > 1 else ""
                )
                over = median(run.get("overBudget") for run in runs)
                drifted = median(run.get("drift") for run in runs)
                missed = median(late_share(run) for run in runs)
                failed = sorted(
                    {why for run in point["runs"] for why in run["failed"]}
                )
                rows.append(
                    [
                        config,
                        str(point["mobs"]),
                        f"{sum(run['held'] for run in point['runs'])}/{len(point['runs'])}",
                        number(median(means)) + spread,
                        number(median(run.get("p50Millis") for run in runs)),
                        number(median(run.get("p99Millis") for run in runs)),
                        number(None if over is None else over * 100, 1, "%"),
                        "-" if drifted is None else f"{drifted * 100:+.1f}%",
                        number(median(run.get("tps") for run in runs), 2),
                        number(median(run.get("lateP99") for run in runs), 0),
                        number(None if missed is None else missed * 100, 2, "%"),
                        number(median(run.get("answeredPerSecond") for run in runs), 0),
                        number(median(run.get("shedPerSecond") for run in runs), 0),
                        number(median(run.get("nodeRate") for run in runs), 0),
                        number(median(run.get("failoverPerSecond") for run in runs), 0),
                        number(median(run.get("resetsPerSecond") for run in runs), 0),
                        ",".join(failed),
                    ]
                )
        widths = [max(len(row[i]) for row in rows) for i in range(len(header))]
        for row in rows:
            text.append(
                "  ".join(
                    cell.ljust(width) for cell, width in zip(row, widths)
                ).rstrip()
            )
        text.append("")
    return "\n".join(text)


def gradle_properties():
    properties = {}
    for line in GRADLE_PROPERTIES.read_text().splitlines():
        key, sep, value = line.partition("=")
        if sep and not key.lstrip().startswith("#"):
            properties[key.strip()] = value.strip()
    return properties


def launcher():
    """Fabric's server launcher for the mod's Minecraft and loader versions,
    downloaded once and checked against LAUNCHER_SHA256. On its first start it
    downloads the server and libraries."""
    properties = gradle_properties()
    minecraft, loader = properties["minecraft_version"], properties["loader_version"]
    jar = SERVER_DIR / f"fabric-server-{minecraft}-{loader}-{FABRIC_INSTALLER}.jar"
    if not jar.exists():
        url = (
            f"https://meta.fabricmc.net/v2/versions/loader/{minecraft}/{loader}/"
            f"{FABRIC_INSTALLER}/server/jar"
        )
        log(f"downloading Fabric's server launcher from {url}")
        SERVER_DIR.mkdir(parents=True, exist_ok=True)
        partial = jar.with_suffix(".part")
        with urllib.request.urlopen(url, timeout=60) as response:
            with open(partial, "wb") as out:
                shutil.copyfileobj(response, out)
        check_launcher(partial)
        partial.replace(jar)
    check_launcher(jar)
    return jar


def check_launcher(path):
    """Refuses, and removes, a launcher other than the one LAUNCHER_SHA256
    names."""
    digest = sha256(path)
    if digest != LAUNCHER_SHA256:
        path.unlink()
        raise BenchError(
            f"{path.name} has SHA-256 {digest}, not LAUNCHER_SHA256; "
            "update LAUNCHER_SHA256 only if the versions it is for changed"
        )


def node_state(line):
    """The node and its new state if `line` logs a change to one, or None."""
    match = NODE_STATE.search(line)
    return match.groups() if match else None


class Server:
    """A server run from SERVER_DIR, its console on stdin and its output in
    `log`."""

    def __init__(self, java, jar, cpus, heap, log_file):
        self.log_file = log_file
        self.output = open(log_file, "wb")
        self.process = subprocess.Popen(
            ["taskset", "-c", cpu_list(cpus), java, f"-Xms{heap}", f"-Xmx{heap}"]
            + list(SERVER_JVM_FLAGS)
            + ["-jar", jar.name, "nogui"],
            cwd=SERVER_DIR,
            stdin=subprocess.PIPE,
            stdout=self.output,
            stderr=subprocess.STDOUT,
        )
        self.reader = open(log_file, "rb")
        self.partial = b""
        self.unread = collections.deque()
        # Each node's link and service, by its name, from the last line on it.
        self.states = {}

    def _read(self):
        self.partial += self.reader.read()
        *lines, self.partial = self.partial.split(b"\n")
        for line in lines:
            text = line.decode("utf-8", "replace")
            state = node_state(text)
            if state:
                self.states[state[0]] = state[1]
            self.unread.append(text)

    def in_service(self):
        return sum(state == IN_SERVICE for state in self.states.values())

    def skip(self):
        """Forgets the output so far, so a wait sees only what follows."""
        self._read()
        self.unread.clear()

    def wait_for(self, pattern, timeout, what, required=True):
        """The match of the first line of output from here on matching
        `pattern`, or None after `timeout` seconds unless `required`."""
        regex = re.compile(pattern)
        deadline = time.monotonic() + timeout
        while True:
            self._read()
            while self.unread:
                match = regex.search(self.unread.popleft())
                if match:
                    return match
            if self.process.poll() is not None:
                raise BenchError(
                    f"the server exited waiting for {what}; see {self.log_file}"
                )
            if time.monotonic() > deadline:
                if required:
                    raise BenchError(
                        f"no {what} within {timeout:.0f}s; see {self.log_file}"
                    )
                return None
            time.sleep(0.2)

    def wait_until(self, done, timeout, what):
        """Waits until `done()`, which the output read so far may change."""
        deadline = time.monotonic() + timeout
        while True:
            self._read()
            if done():
                return
            if self.process.poll() is not None:
                raise BenchError(
                    f"the server exited waiting for {what}; see {self.log_file}"
                )
            if time.monotonic() > deadline:
                raise BenchError(
                    f"no {what} within {timeout:.0f}s; see {self.log_file}"
                )
            time.sleep(0.2)

    def command(self, text):
        self.process.stdin.write(f"{text}\n".encode())
        self.process.stdin.flush()

    def stop(self, timeout=180):
        if self.process.poll() is None:
            try:
                self.command("stop")
                self.process.wait(timeout)
            except (OSError, subprocess.TimeoutExpired):
                self.process.kill()
                self.process.wait()
        self.output.close()
        self.reader.close()


class Nodes:
    """Local nodes, each pinned to its own CPUs with one worker per CPU."""

    def __init__(self, cpu_sets, logs):
        self.processes = []
        self.addresses = []
        for index, cpus in enumerate(cpu_sets):
            port = FIRST_PORT + 3 * index
            output = open(logs / f"node-{index}.log", "wb")
            self.processes.append(
                subprocess.Popen(
                    [
                        "taskset",
                        "-c",
                        cpu_list(cpus),
                        str(NODE_BIN),
                        "--bind",
                        "127.0.0.1",
                    ]
                    + ["--world-port", str(port), "--control-port", str(port + 1)]
                    + ["--request-port", str(port + 2), "--workers", str(len(cpus))],
                    stdout=output,
                    stderr=subprocess.STDOUT,
                )
            )
            output.close()
            self.addresses.append(
                {
                    "host": "127.0.0.1",
                    "worldPort": port,
                    "controlPort": port + 1,
                    "requestPort": port + 2,
                }
            )

    def check(self):
        for index, process in enumerate(self.processes):
            if process.poll() is not None:
                raise BenchError(
                    f"node-{index} exited with status {process.returncode}"
                )

    def stop(self):
        for process in self.processes:
            process.terminate()
        for process in self.processes:
            try:
                process.wait(10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


def docker(host):
    """The Docker CLI, aimed at `host`, or at the local daemon if None."""
    return ["docker"] + (["--host", host] if host else [])


def container_name(index):
    return f"maestro-bench-node-{index}"


def node_ip(index):
    """Local node container `index`'s address on NODE_NETWORK."""
    return str(ipaddress.ip_network(NODE_SUBNET).network_address + 10 + index)


def container_address(index, host):
    """Where the server reaches node container `index` on `host`: its own
    address on NODE_NETWORK if local, or else the host, which publishes its
    ports."""
    if host is None:
        return {"host": node_ip(index), **NODE_PORTS}
    hostname = urllib.parse.urlsplit(host).hostname
    if not hostname:
        raise BenchError(f"'{host}' names no host the server can reach")
    return {"host": hostname, **NODE_PORTS}


def container_command(image, index, host, cpus):
    """The command that starts node container `index` of `image` on `host`,
    pinned to `cpus` with a worker each, or else on every CPU. It may run `tc`,
    and a remote one stays on a bridge network too, so `tc` slows only the node
    and not the SSH it is driven over."""
    argv = docker(host) + ["run", "--detach", "--name", container_name(index)]
    argv += ["--cap-add", "NET_ADMIN"]
    if host is None:
        argv += ["--network", NODE_NETWORK, "--ip", node_ip(index)]
    else:
        world, control, request = NODE_PORTS.values()
        for port in (f"{world}:{world}", f"{control}:{control}"):
            argv += ["--publish", port]
        argv += ["--publish", f"{request}:{request}/udp"]
    if cpus:
        argv += ["--cpuset-cpus", cpu_list(cpus)]
    argv.append(image)
    if cpus:
        argv += ["--workers", str(len(cpus))]
    return argv


def checked(argv, what):
    """The output of `argv`, or a BenchError naming `what` failed."""
    done = subprocess.run(argv, capture_output=True, text=True, check=False)
    if done.returncode != 0:
        raise BenchError(f"could not {what}: {done.stderr.strip()}")
    return done.stdout


class Containers:
    """Nodes run as containers of `image`, one per placement: a Docker host,
    None for the local one, and the CPUs to pin it to, or None for all."""

    def __init__(self, image, placements, logs):
        self.placements = placements
        self.logs = logs
        self.names = [container_name(index) for index in range(len(placements))]
        self.addresses = [
            container_address(index, host) for index, (host, _) in enumerate(placements)
        ]
        if any(host is None for host, _ in placements):
            present = subprocess.run(
                docker(None) + ["network", "inspect", NODE_NETWORK],
                capture_output=True,
                check=False,
            )
            if present.returncode != 0:
                checked(
                    docker(None)
                    + ["network", "create", "--subnet", NODE_SUBNET, NODE_NETWORK],
                    f"create the Docker network {NODE_NETWORK}",
                )
        self.started = []
        try:
            for index, (host, cpus) in enumerate(placements):
                # One left by an interrupted run.
                subprocess.run(
                    docker(host) + ["rm", "--force", self.names[index]],
                    capture_output=True,
                    check=False,
                )
                checked(
                    container_command(image, index, host, cpus),
                    f"start {self.names[index]}",
                )
                self.started.append(index)
        except BaseException:
            self.stop()
            raise

    def targets(self):
        return [
            chaos_target(name, host)
            for name, (host, _) in zip(self.names, self.placements)
        ]

    def check(self):
        for name, (host, _) in zip(self.names, self.placements):
            running = checked(
                docker(host) + ["inspect", "--format", "{{.State.Running}}", name],
                f"inspect {name}",
            )
            if running.strip() != "true":
                raise BenchError(f"{name} is not running")

    def stop(self):
        """Stops each container, keeping its output in `logs`, and removes
        it."""
        for index in self.started:
            host, _ = self.placements[index]
            name = self.names[index]
            subprocess.run(
                docker(host) + ["stop", "--time", "10", name],
                capture_output=True,
                check=False,
            )
            with open(self.logs / f"node-{index}.log", "wb") as output:
                subprocess.run(
                    docker(host) + ["logs", name],
                    stdout=output,
                    stderr=subprocess.STDOUT,
                    check=False,
                )
            subprocess.run(
                docker(host) + ["rm", "--force", name], capture_output=True, check=False
            )
        self.started = []


def chaos_target(name, host):
    """Container `name` on Docker host `host` as tools/chaos.py names it."""
    return name if host is None else f"{name}@{host}"


def chaos_command(seed, duration, targets, log, extra):
    """The tools/chaos.py run of `seed` over `duration` seconds on `targets`,
    logging to `log`, with `extra` arguments."""
    argv = [sys.executable, "-I", str(CHAOS), "run", "--seed", str(seed)]
    argv += ["--duration", str(duration), "--log", str(log)]
    for target in targets:
        argv += ["--node", target]
    return argv + list(extra)


def end_chaos(chaos, chaos_log):
    """Stops tools/chaos.py, which first heals the faults it has on."""
    if chaos.poll() is None:
        chaos.send_signal(signal.SIGTERM)
    try:
        chaos.wait(CHAOS_STOP_SECONDS)
    except subprocess.TimeoutExpired:
        chaos.kill()
        chaos.wait()
        raise BenchError(f"faults may still be on; see {chaos_log}")
    if chaos.returncode != 0:
        raise BenchError(f"a fault did not apply or heal; see {chaos_log}")


class Bench:
    def __init__(self, args):
        self.args = args
        self.server_cpus = parse_cpus(args.server_cpus)
        # Nodes on remote hosts have their hosts' CPUs, and local ones a share
        # of --node-cpus each.
        self.hosts = args.node_hosts[: args.nodes] if args.node_hosts else None
        self.node_cpus = (
            None if self.hosts else split_cpus(parse_cpus(args.node_cpus), args.nodes)
        )
        self.out = Path(args.out)
        self.scenarios = {}
        for name in args.scenarios:
            self.scenarios[name] = json.loads((SCENARIOS / f"{name}.json").read_text())
        self.results = {
            "started": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "settings": {
                **{key: value for key, value in vars(args).items() if key != "command"},
                "jvmFlags": list(SERVER_JVM_FLAGS),
            },
            "machine": {
                "cpus": os.cpu_count(),
                "allowedCpus": sorted(os.sched_getaffinity(0)),
                "kernel": platform.release(),
                "java": java_version(args.java),
            },
            "criterion": {
                "tickMillis": TICK_MILLIS,
                "maxOverBudget": MAX_OVER_BUDGET,
                "minTps": MIN_TPS,
                "sloTicks": SLO_TICKS,
                "sloPercentile": SLO_PERCENTILE,
                "capacityHeadroom": CAPACITY_HEADROOM,
            },
            "nodes": None,
            "scenarios": {name: {} for name in args.scenarios},
        }

    def placements(self):
        """Each node's Docker host, None for the local one, and its CPUs, None
        for all of them."""
        if self.hosts:
            return [(host, None) for host in self.hosts]
        return [(None, cpus) for cpus in self.node_cpus]

    def check(self):
        allowed = os.sched_getaffinity(0)
        node_cpus = [cpu for cpus in self.node_cpus or [] for cpu in cpus]
        if self.node_cpus and any(not cpus for cpus in self.node_cpus):
            raise BenchError(f"{self.args.nodes} nodes need at least as many node CPUs")
        if set(self.server_cpus) & set(node_cpus):
            raise BenchError("the server and node CPUs overlap")
        shared = shared_cores(self.server_cpus, node_cpus)
        if shared:
            server, node = shared[0]
            raise BenchError(f"server CPU {server} and node CPU {node} share a core")
        if not set(self.server_cpus + node_cpus) <= allowed:
            raise BenchError(
                f"a CPU is not among those this process may use: {sorted(allowed)}"
            )
        if shutil.which("taskset") is None:
            raise BenchError("taskset is not installed")
        covered = keeps_covered(self.scenarios)
        if covered:
            raise BenchError(
                f"the terrain of {covered[0][0]} covers a keep of {covered[0][1]}, "
                "so they cannot share a world"
            )
        for jar in ("fabric-api.jar", "maestro-load.jar", "maestro.jar"):
            if not (MODS / jar).is_file():
                raise BenchError(
                    f"no {MODS / jar} (run `make bench` or `gradlew benchMods`)"
                )
        offloading = any(config in OFFLOADING for config in self.args.configs)
        image = self.args.node_image
        if offloading and image:
            if shutil.which("docker") is None:
                raise BenchError("docker is not installed")
            # A remote host pulls the image if it lacks it.
            if not self.hosts:
                checked(
                    docker(None) + ["image", "inspect", image],
                    f"find the image {image} (run `make node-image`)",
                )
        elif offloading:
            if not os.access(NODE_BIN, os.X_OK):
                raise BenchError(f"no {NODE_BIN} (run `make node`)")
            if not self.args.node_capacity and not os.access(BENCH_BIN, os.X_OK):
                raise BenchError(f"no {BENCH_BIN} (run `make bench-node`)")
        if self.args.chaos is not None:
            checked(
                [sys.executable, "-I", str(CHAOS), "plan", "--node", "node"]
                + ["--duration", str(self.args.duration)]
                + self.chaos_args(),
                "plan faults from --chaos-args",
            )

    def save(self):
        partial = self.out / "results.json.part"
        partial.write_text(json.dumps(self.results, indent=2) + "\n")
        partial.replace(self.out / "results.json")

    def run(self):
        self.check()
        (self.out / "logs").mkdir(parents=True, exist_ok=True)
        (self.out / "reports").mkdir(exist_ok=True)
        jar = launcher()
        self.world = self.build_terrain(jar)
        for config in self.args.configs:
            for scenario in self.scenarios:
                self.run_scenario(config, scenario, jar)
        self.save()
        print(table(self.results))
        log(f"results in {self.out / 'results.json'}")

    def capacity(self):
        """The `nodeCapacity` of every node, measured on the smallest's CPUs,
        or the first remote node's host, unless --node-capacity sets it."""
        if self.results["nodes"] is None:
            nodes = {"count": self.args.nodes, "cpus": self.node_cpus}
            if self.args.node_image:
                nodes["image"] = self.args.node_image
            if self.hosts:
                nodes["hosts"] = self.hosts
            if self.args.node_capacity:
                nodes["capacity"] = self.args.node_capacity
            else:
                host, cpus = (
                    self.placements()[0]
                    if self.hosts
                    else (None, min(self.node_cpus, key=len))
                )
                where = host or f"CPUs {cpu_list(cpus)}"
                log(f"measuring the search rate on {where}")
                capacity, rate = node_capacity(
                    self.search_rates(host, cpus), len(cpus) if cpus else None
                )
                nodes["capacity"] = capacity
                nodes["searchesPerSecond"] = round(rate)
                log(f"{round(rate)} searches/s on {where}: nodeCapacity {capacity}")
            self.results["nodes"] = nodes
            self.save()
        return self.results["nodes"]["capacity"]

    def search_rates(self, host, cpus):
        """maestro-bench's results on `host`'s `cpus`, from the node image if
        the nodes run in containers, also kept in --out."""
        out = self.out / "node-bench.json"
        bench_log = self.out / "logs" / "node-bench.log"
        with open(bench_log, "wb") as output:
            if self.args.node_image:
                pinned = ["--cpuset-cpus", cpu_list(cpus)] if cpus else []
                done = subprocess.run(
                    docker(host)
                    + ["run", "--rm", *pinned, "--entrypoint", "maestro-bench"]
                    + [self.args.node_image, *SEARCH_BENCH_FLAGS]
                    + ["--benchmark_format=json", IMAGE_FIXTURES],
                    check=False,
                    stdout=subprocess.PIPE,
                    stderr=output,
                )
                out.write_bytes(done.stdout)
            else:
                done = subprocess.run(
                    ["taskset", "-c", cpu_list(cpus), str(BENCH_BIN)]
                    + list(SEARCH_BENCH_FLAGS)
                    + [f"--benchmark_out={out}", "--benchmark_out_format=json"],
                    check=False,
                    stdout=output,
                    stderr=subprocess.STDOUT,
                )
        if done.returncode != 0:
            raise BenchError(
                f"maestro-bench exited with status {done.returncode}; see {bench_log}"
            )
        return json.loads(out.read_text())

    def chaos_args(self):
        return shlex.split(self.args.chaos_args or "")

    def prepare(self, config, logs, world):
        """Sets SERVER_DIR up for `config` on a fresh copy of `world`, then
        starts and returns its nodes, if any."""
        copy = SERVER_DIR / SERVER_PROPERTIES["level-name"]
        shutil.rmtree(copy, ignore_errors=True)
        shutil.copytree(world, copy, ignore=shutil.ignore_patterns("session.lock"))
        (SERVER_DIR / "eula.txt").write_text("eula=true\n")
        (SERVER_DIR / "server.properties").write_text(
            "".join(f"{key}={value}\n" for key, value in SERVER_PROPERTIES.items())
        )

        mods = SERVER_DIR / "mods"
        shutil.rmtree(mods, ignore_errors=True)
        mods.mkdir(parents=True)
        jars = ["fabric-api.jar", "maestro-load.jar"]
        if config != "vanilla":
            jars.append("maestro.jar")
        for jar in jars:
            shutil.copy2(MODS / jar, mods / jar)

        settings_file = SERVER_DIR / "config" / "maestro.json"
        settings_file.unlink(missing_ok=True)
        if config == "vanilla":
            return None
        settings_file.parent.mkdir(parents=True, exist_ok=True)
        settings = {"nodes": [], "traceSampleRate": self.args.trace_sample_rate}
        if config not in OFFLOADING:
            settings_file.write_text(json.dumps(settings, indent=2) + "\n")
            return None
        capacity = self.capacity()
        if self.args.node_image:
            nodes = Containers(self.args.node_image, self.placements(), logs)
        else:
            nodes = Nodes(self.node_cpus, logs)
        settings.update(
            subMode=config,
            offload="on",
            nodes=nodes.addresses,
            nodeCapacity=capacity,
            areaCells=self.args.area_cells,
            spares=self.args.spares,
        )
        settings_file.write_text(json.dumps(settings, indent=2) + "\n")
        return nodes

    def build_terrain(self, jar):
        """A copy of WORLD in --out with every scenario's terrain built on it."""
        logs = self.out / "logs" / "terrain"
        logs.mkdir(parents=True, exist_ok=True)
        self.prepare("vanilla", logs, WORLD)
        scenarios = SERVER_DIR / "maestro-load" / "scenarios"
        scenarios.mkdir(parents=True, exist_ok=True)
        for name, spec in self.scenarios.items():
            (scenarios / f"{name}.json").write_text(json.dumps(spec, indent=2) + "\n")
        log(f"building the scenarios' terrain on CPUs {cpu_list(self.server_cpus)}")
        server = Server(
            self.args.java, jar, self.server_cpus, self.args.heap, logs / "server.log"
        )
        try:
            server.wait_for(
                r"Done \(", self.args.startup_timeout, "the server to start"
            )
            for name in self.scenarios:
                server.command(f"maestro load terrain {name}")
                match = server.wait_for(
                    rf"Maestro load (terrain built|failed): scenario={re.escape(name)}\b(.*)",
                    self.args.startup_timeout,
                    f"the terrain of {name}",
                )
                if match.group(1) == "failed":
                    raise BenchError(
                        f"could not build the terrain of {name}{match.group(2)}"
                    )
                log(f"{name}: terrain built, {match.group(2).strip()}")
            server.command("save-all flush")
            server.wait_for(
                r"Saved the game", self.args.stop_timeout, "the world to save"
            )
        finally:
            server.stop(self.args.stop_timeout)
        world = self.out / "world"
        shutil.rmtree(world, ignore_errors=True)
        shutil.copytree(
            SERVER_DIR / SERVER_PROPERTIES["level-name"],
            world,
            ignore=shutil.ignore_patterns("session.lock"),
        )
        return world

    def start(self, jar, nodes, logs):
        """A server set up by `prepare`, once it has started and its nodes, if
        any, are in service."""
        server = Server(
            self.args.java, jar, self.server_cpus, self.args.heap, logs / "server.log"
        )
        try:
            # The states count what is read before the server is done starting,
            # as the nodes may come into service first.
            deadline = time.monotonic() + self.args.startup_timeout
            server.wait_for(
                r"Done \(", self.args.startup_timeout, "the server to start"
            )
            wanted = len(nodes.addresses) if nodes else 0
            server.wait_until(
                lambda: server.in_service() >= wanted,
                max(0.0, deadline - time.monotonic()),
                "the nodes in service",
            )
        except BaseException:
            server.stop()
            raise
        return server

    def run_scenario(self, config, scenario, jar):
        """Finds the most mobs `scenario` holds on `config`, on a server and
        nodes of its own, so no other scenario's runs carry over into its."""
        result = {
            "most": None,
            "firstFailed": None,
            "searched": not self.args.mobs,
            "points": [],
        }
        self.results["scenarios"][scenario][config] = result
        logs = self.out / "logs" / config / scenario
        logs.mkdir(parents=True, exist_ok=True)
        nodes = self.prepare(config, logs, self.world)
        server = None
        try:
            log(
                f"{config} {scenario}: starting the server on CPUs "
                f"{cpu_list(self.server_cpus)}"
            )
            server = self.start(jar, nodes, logs)
            self.search(config, scenario, server, nodes, result)
        finally:
            if server:
                server.stop()
            if nodes:
                nodes.stop()
        self.save()

    def search(self, config, scenario, server, nodes, result):
        """Fills in `result` with the mob counts tried and the most that held."""

        def holds_at(mobs):
            point = {"mobs": mobs, "runs": []}
            result["points"].append(point)
            for repeat in range(self.args.repeats):
                if nodes:
                    nodes.check()
                run = self.run_once(config, scenario, server, nodes, mobs, repeat)
                point["runs"].append(run)
                self.save()
                time.sleep(self.args.settle)
                held = sum(run["held"] for run in point["runs"])
                if decided(held, len(point["runs"]) - held, self.args.repeats):
                    break
            point["held"] = held * 2 > self.args.repeats
            log(
                f"{config} {scenario} {mobs} mobs: {held}/{len(point['runs'])} runs held"
            )
            return point["held"]

        if self.args.mobs:
            held = [mobs for mobs in self.args.mobs if holds_at(mobs)]
            failed = [mobs for mobs in self.args.mobs if mobs not in held]
            result["most"] = max(held, default=0)
            result["firstFailed"] = min(failed, default=None)
        else:
            result["most"], result["firstFailed"] = most_mobs(
                holds_at, self.args.start, self.args.limit, self.args.precision
            )

    def run_once(self, config, scenario, server, nodes, mobs, repeat):
        """One load run of `mobs` mobs: its report and what it measured. With
        --chaos, faults seeded by it plus `repeat` hit the nodes while the run
        measures, so each config and mob count meets the same faults, and the
        nodes are all back in service before it returns."""
        args = self.args
        spec = scaled(self.scenarios[scenario], mobs, args.warmup, args.duration)
        spec_file = SERVER_DIR / "maestro-load" / "scenarios" / f"{scenario}.json"
        spec_file.parent.mkdir(parents=True, exist_ok=True)
        spec_file.write_text(json.dumps(spec, indent=2) + "\n")
        reports = self.out / "reports" / config
        reports.mkdir(parents=True, exist_ok=True)
        name = f"{scenario}-{mobs}-{repeat + 1}"

        run = {"report": None, "metrics": None, "held": False, "failed": ["run"]}
        named = rf"scenario={re.escape(scenario)}\b(.*)"
        ended = rf"Maestro load (done|failed): {named}"
        server.skip()
        server.command(f"maestro load start {scenario}")
        expected = (
            mobs / SPAWNS_PER_TICK / TICKS_PER_SECOND + args.warmup + args.duration
        )
        timeout = 2 * expected + 60
        chaos = None
        try:
            if args.chaos is not None and nodes:
                match = server.wait_for(
                    rf"Maestro load (measuring|done|failed): {named}",
                    timeout,
                    "the load run to measure",
                    required=False,
                )
                if match is not None and match.group(1) == "measuring":
                    chaos_log = reports / f"{name}-chaos.jsonl"
                    run["chaos"] = str(chaos_log.relative_to(self.out))
                    log(f"{config} {name}: faults seeded {args.chaos + repeat}")
                    chaos = subprocess.Popen(
                        chaos_command(
                            args.chaos + repeat,
                            args.duration,
                            nodes.targets(),
                            chaos_log,
                            self.chaos_args(),
                        )
                    )
                    match = server.wait_for(
                        ended, timeout, "end of the load run", required=False
                    )
            else:
                match = server.wait_for(
                    ended, timeout, "end of the load run", required=False
                )
            if match is None:
                # The server fell too far behind to finish in time; what it measured stands.
                server.command("maestro load stop")
                match = server.wait_for(ended, args.stop_timeout, "end of the load run")
        finally:
            if chaos:
                end_chaos(chaos, chaos_log)
        if chaos:
            server.wait_until(
                lambda: server.in_service() >= len(nodes.addresses),
                RECOVERY_SECONDS,
                "every node back in service after the faults",
            )

        if match.group(1) == "failed":
            run["failure"] = match.group(2).lstrip(": ")
            log(
                f"{config} {scenario} {mobs} mobs, run {repeat + 1}: failed: {run['failure']}"
            )
            return run
        report_file = Path(re.search(r"report=(\S+)", match.group(2)).group(1))
        if not report_file.is_absolute():
            report_file = SERVER_DIR / report_file
        kept = reports / f"{name}.json"
        shutil.copy2(report_file, kept)
        run["report"] = str(kept.relative_to(self.out))
        run["metrics"] = metrics(json.loads(kept.read_text()))
        run["failed"] = failures(run["metrics"], mobs, config in OFFLOADING)
        run["held"] = not run["failed"]
        measured = run["metrics"]
        missed = late_share(measured)
        verdict = (
            "held" if run["held"] else f"did not hold ({', '.join(run['failed'])})"
        )
        log(
            f"{config} {scenario} {mobs} mobs, run {repeat + 1}: mean "
            f"{number(measured['meanMillis'])} ms, "
            f"{number(None if measured['overBudget'] is None else measured['overBudget'] * 100)}% "
            f"over 50 ms"
            + ("" if missed is None else f", {number(missed * 100, 2)}% missed the SLO")
            + f": {verdict}"
        )
        return run


def refusal(args):
    """What is wrong with the settings of a `run`, or None."""
    if not args.mobs and args.start > args.limit:
        return "--start must not be above --limit"
    if args.node_hosts is not None and not args.node_image:
        return "--node-hosts needs --node-image"
    if args.node_hosts is not None and args.nodes > len(args.node_hosts):
        return f"--nodes {args.nodes} needs as many --node-hosts"
    if not 0 <= args.spares < args.nodes:
        return "--spares must leave a node holding a region"
    if args.chaos is not None and not args.node_image:
        return "--chaos needs --node-image"
    if args.chaos is not None and not args.mobs:
        return "--chaos needs --mobs, as faults decide no mob count"
    if args.chaos_args is not None and args.chaos is None:
        return "--chaos-args needs --chaos"
    return None


def java_version(java):
    try:
        done = subprocess.run(
            [java, "-version"], capture_output=True, text=True, check=False
        )
    except OSError:
        return None
    lines = (done.stderr or done.stdout).splitlines()
    return lines[0] if lines else None


def positive(text):
    value = int(text)
    if value < 1:
        raise argparse.ArgumentTypeError(f"{text} is not a positive whole number")
    return value


def share(text):
    value = float(text)
    if not 0 <= value <= 1:
        raise argparse.ArgumentTypeError(f"{text} is not 0 to 1")
    return value


def names(choices):
    def parse(text):
        picked = [name for name in text.split(",") if name]
        unknown = [name for name in picked if name not in choices]
        if not picked or unknown:
            raise argparse.ArgumentTypeError(
                f"'{text}' must list some of {', '.join(choices)}"
            )
        return picked

    return parse


DESCRIPTION = """\
Finds the most mobs a server holds at 20 TPS, on vanilla and with Maestro.

`run` boots a production Fabric server from the built jars for each config:

  vanilla  fabric-api and the load mod, without the released mod
  idle     the released mod with no nodes, so nothing is offloaded
  batch    the released mod offloading to --nodes nodes, batch sub-mode
  stream   the same in stream sub-mode

and each scenario of bench/scenarios, a fresh one per scenario so no
scenario's runs carry over into another's. A scenario's group counts are
weights scaled to the mob count tried. Each is built on seeded terrain of
hills, mesas, walls and ruins, and its husks hunt players walking around it:

  chase    husks hunting nine players, each sent away from the player it
           catches, so the chases go on
  crowd    husks hunting nine players
  mixed    husks hunting nine players, among cows and sheep
  spread   four crowds of husks and cows, each hunting four players in
           another cell
  siege    husks around four players on keeps they cannot climb

The `-far` ones search further, as their husks hunt from up to 64 blocks, not
35, so each search may visit 1024 nodes, not 560:

  chase-far   husks hunting four players, each sent at least 40 blocks from
              every player once it catches one
  siege-far   husks around four players on keeps, from further out
  spread-far  a chase of husks and four players in each of four cells

Before the configs, a vanilla server builds every scenario's terrain into a
copy of the world, which each server then starts from, so no measured server
builds terrain. Mobs cannot change blocks during a run, so it stays as built,
and no scenario's terrain may cover another's keep.

A run holds when its measured ticks take at most 50 ms on average, at most 1%
of them take longer, and they come at 19.8 a second or more over their wall
time, which also counts the server's work between ticks. A run that offloads
must also answer at least 99% of its requests within the SLO, under 3 ticks
after they were asked; a request shed, dropped or served by the failover
misses it. A mob count holds when most of its --repeats runs do, and its runs
stop once that is settled. The count doubles from --start while it holds (or
halves while it does not), then bisects between the most that held and the
fewest that did not until they are within --precision of each other. --mobs
runs fixed counts instead.

The server and the nodes are pinned with taskset to CPUs on separate physical
cores, and each node runs one worker per CPU it is given. Before the first config that
offloads, maestro-bench measures a node's search rate on its CPUs with every
worker busy, on the parity fixtures' searches, which sets `nodeCapacity`
unless --node-capacity does. The controller only ever lowers it, from the
rates the nodes report, so if the scenarios' searches are much cheaper the
fleet sheds before it must. A run that missed the SLO shedding while every
node reported at least 10% above `nodeCapacity` also fails on `capacity`, and
the table's `node rate` column shows the rate to rerun with in
--node-capacity. No request is traced unless --trace-sample-rate says so.
Every node holds a region unless --spares keeps some spare.

With --node-image the nodes run as containers of that image (`make
node-image`), pinned to their CPUs the same way, and maestro-bench runs from
it. With --node-hosts too, the first --nodes of those Docker hosts run one
each on all of its CPUs, publishing the node's ports, which the server reaches
at the host's address; each host must have the image or be able to pull it,
and the first measures the search rate. The nodes and the server ask for 8 MiB
UDP buffers, so every host should allow them (`sysctl net.core.rmem_max=8388608
net.core.wmem_max=8388608`); their logs warn if one does not.

With --chaos, tools/chaos.py faults the containers while each run measures:
pauses, crashes and restarts, and `tc` delay, loss and partitions (see
`tools/chaos.py --help`, whose plan options --chaos-args takes). A run's
faults are seeded by --chaos plus its repeat, so every config and mob count
meets the same ones, and its fault log lands beside its report. The verdict
is the same, so the table shows what the faults cost, and the next run waits
for every node to be back in service.

Every run's load report, the logs and results.json land in --out, and a table
is printed at the end, with what the runs of each count that did not hold
failed on. `table <results.json>` prints it again.
"""


def main(argv):
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    scenarios = sorted(path.stem for path in SCENARIOS.glob("*.json"))
    parser = argparse.ArgumentParser(
        description=DESCRIPTION, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    commands = parser.add_subparsers(dest="command", required=True)

    run = commands.add_parser("run", help="benchmark the configs on the scenarios")
    run.add_argument("--configs", type=names(CONFIGS), default=list(CONFIGS))
    run.add_argument("--scenarios", type=names(scenarios), default=scenarios)
    run.add_argument(
        "--nodes", type=positive, default=1, help="local nodes (default 1)"
    )
    run.add_argument(
        "--server-cpus", default="0-2", help="the server's CPUs (default 0-2)"
    )
    run.add_argument(
        "--node-cpus", default="3-5", help="CPUs split among the nodes (default 3-5)"
    )
    run.add_argument("--node-image", help="run the nodes as containers of this image")
    run.add_argument(
        "--node-hosts",
        type=lambda text: [host for host in text.split(",") if host],
        help="Docker hosts the first --nodes of which run a node each, such as "
        "ssh://ubuntu@10.0.1.12, instead of the local one",
    )
    run.add_argument(
        "--spares", type=int, default=0, help="nodes that start with no region"
    )
    run.add_argument(
        "--chaos",
        type=int,
        help="fault the nodes while runs measure, seeded by this plus the repeat",
    )
    run.add_argument(
        "--chaos-args", help="further tools/chaos.py plan options, such as '--gap 30'"
    )
    run.add_argument(
        "--repeats", type=positive, default=3, help="runs per mob count (default 3)"
    )
    run.add_argument(
        "--mobs",
        type=lambda text: sorted({positive(count) for count in text.split(",")}),
        help="fixed mob counts, such as 500,1000, instead of a search",
    )
    run.add_argument(
        "--start", type=positive, default=250, help="first count tried (default 250)"
    )
    run.add_argument(
        "--limit", type=positive, default=32000, help="most mobs tried (default 32000)"
    )
    run.add_argument(
        "--precision",
        type=float,
        default=0.1,
        help="bisect until within this share (default 0.1)",
    )
    run.add_argument(
        "--warmup",
        type=positive,
        default=10,
        help="seconds before measuring (default 10)",
    )
    run.add_argument(
        "--duration",
        type=positive,
        default=120,
        help="seconds measured (default 120, so 1%% of ticks is 24)",
    )
    run.add_argument(
        "--settle", type=float, default=5, help="seconds between runs (default 5)"
    )
    run.add_argument(
        "--node-capacity", type=positive, help="nodeCapacity, measured if unset"
    )
    run.add_argument(
        "--area-cells", type=positive, default=3, help="areaCells (default 3)"
    )
    run.add_argument(
        "--trace-sample-rate",
        type=share,
        default=0.0,
        help="traceSampleRate (default 0)",
    )
    run.add_argument("--heap", default="4G", help="the server's heap (default 4G)")
    run.add_argument("--java", default="java", help="the java the server runs on")
    run.add_argument("--startup-timeout", type=positive, default=600)
    run.add_argument("--stop-timeout", type=positive, default=300)
    run.add_argument("--out", default=str(ROOT / "build" / "bench" / stamp))

    shown = commands.add_parser("table", help="print the table of a results file")
    shown.add_argument("results", type=Path)

    args = parser.parse_args(argv)
    if args.command == "run":
        refused = refusal(args)
        if refused:
            parser.error(refused)
    if args.command == "table":
        print(table(json.loads(args.results.read_text())))
        return 0
    try:
        Bench(args).run()
    except (BenchError, ValueError) as error:
        log(f"FAIL: {error}")
        return 1
    except KeyboardInterrupt:
        log(f"interrupted; partial results in {args.out}")
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
