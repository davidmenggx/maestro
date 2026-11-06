import argparse
import contextlib
import io
import json
import time
import unittest

import chaos

NODES = [("node-0", None), ("node-1", "ssh://ubuntu@10.0.1.12"), ("node-2", None)]


def fault(start, end, node=0, kind="pause", **settings):
    return chaos.Fault(start, end, node, kind, **settings)


class Clock:
    """Time that passes only when a runner waits."""

    def __init__(self):
        self.now = 100.0
        # The times at which `stop` returns true from a wait.
        self.stop_at = None

    def __call__(self):
        return self.now

    def wait(self, seconds):
        if self.stop_at is not None and self.now + seconds >= self.stop_at:
            self.now = max(self.now, self.stop_at)
            return True
        self.now += seconds
        return False

    def sleep(self, seconds):
        self.now += seconds


class Docker:
    """Records each command with the time it ran, failing those whose
    arguments include an entry of `fail`, the first `fail_times` times."""

    def __init__(self, clock, fail=(), fail_times=None):
        self.clock = clock
        self.fail = set(fail)
        self.fail_times = fail_times
        self.commands = []

    def __call__(self, argv):
        self.commands.append((self.clock.now - 100.0, argv))
        if self.fail & set(argv):
            if self.fail_times is None or self.fail_times > 0:
                if self.fail_times is not None:
                    self.fail_times -= 1
                return "no such container"
        return None


def runner(clock, docker):
    log = io.StringIO()
    made = chaos.Runner(
        NODES, log, execute=docker, clock=clock, wait=clock.wait, pause=clock.sleep
    )
    return made, log


def events(log):
    return [json.loads(line) for line in log.getvalue().splitlines()]


class PlanTest(unittest.TestCase):
    def test_a_seed_always_gives_the_same_faults(self):
        self.assertEqual(chaos.plan(7, 3, 600), chaos.plan(7, 3, 600))
        self.assertNotEqual(chaos.plan(7, 3, 600), chaos.plan(8, 3, 600))

    def test_faults_start_in_order_and_are_over_by_the_duration(self):
        for seed in range(20):
            faults = chaos.plan(seed, 3, 300, length=(5, 60), concurrent=2)
            self.assertEqual(faults, sorted(faults, key=lambda f: f.start))
            for each in faults:
                self.assertLessEqual(0, each.start)
                self.assertLess(each.start, each.end)
                self.assertLessEqual(each.end, 300)

    def test_a_node_holds_one_fault_and_the_fleet_at_most_concurrent(self):
        for seed in range(20):
            faults = chaos.plan(seed, 3, 600, gap=2, length=(5, 30), concurrent=2)
            for each in faults:
                active = [f for f in faults if f.start <= each.start < f.end]
                self.assertLessEqual(len(active), 2)
                nodes = [f.node for f in active]
                self.assertEqual(len(nodes), len(set(nodes)))

    def test_never_more_at_once_than_there_are_nodes(self):
        faults = chaos.plan(1, 2, 600, gap=1, length=(30, 60), concurrent=5)
        for each in faults:
            active = [f for f in faults if f.start <= each.start < f.end]
            self.assertLessEqual(len(active), 2)

    def test_draws_only_the_kinds_given_with_settings_in_range(self):
        faults = chaos.plan(
            3, 2, 3000, kinds=["delay", "loss"], delay_ms=(10, 20), loss_percent=(2, 4)
        )
        self.assertTrue(faults)
        self.assertEqual({f.kind for f in faults}, {"delay", "loss"})
        for each in faults:
            if each.kind == "delay":
                self.assertTrue(10 <= each.delay_ms <= 20)
                self.assertEqual(each.jitter_ms, each.delay_ms // 4)
            else:
                self.assertTrue(2 <= each.loss_percent <= 4)

    def test_faults_come_about_one_a_gap(self):
        count = sum(len(chaos.plan(seed, 4, 1000, gap=20)) for seed in range(20))
        # 1000 s of 20 s gaps, less the time faults block one another.
        self.assertTrue(20 * 30 <= count <= 20 * 55, count)


class CommandsTest(unittest.TestCase):
    def test_each_kind_starts_and_ends_with_its_own_command(self):
        cases = {
            "pause": (["pause", "n"], ["unpause", "n"]),
            "crash": (["kill", "n"], ["start", "n"]),
        }
        for kind, (start, end) in cases.items():
            self.assertEqual(chaos.inject_command(fault(0, 1, kind=kind), "n"), start)
            self.assertEqual(chaos.heal_command(fault(0, 1, kind=kind), "n"), end)

    def test_network_faults_set_netem_on_the_interface_then_clear_it(self):
        tc = ["exec", "--user", "0", "n", "tc", "qdisc"]
        rule = tc + ["replace", "dev", "eth0", "root", "netem"]
        self.assertEqual(
            chaos.inject_command(
                fault(0, 1, kind="delay", delay_ms=20, jitter_ms=5), "n"
            ),
            rule + ["delay", "20ms", "5ms"],
        )
        self.assertEqual(
            chaos.inject_command(fault(0, 1, kind="loss", loss_percent=2.5), "n"),
            rule + ["loss", "2.5%"],
        )
        self.assertEqual(
            chaos.inject_command(fault(0, 1, kind="partition"), "n"),
            rule + ["loss", "100%"],
        )
        for kind in ("delay", "loss", "partition"):
            self.assertEqual(
                chaos.heal_command(fault(0, 1, kind=kind), "n"),
                tc + ["del", "dev", "eth0", "root"],
            )

    def test_aims_at_a_remote_docker_host_only_when_given_one(self):
        self.assertEqual(chaos.docker(None), ["docker"])
        self.assertEqual(
            chaos.docker("ssh://ubuntu@h"), ["docker", "--host", "ssh://ubuntu@h"]
        )


class RunnerTest(unittest.TestCase):
    def test_injects_and_heals_each_fault_on_time_on_its_node(self):
        clock = Clock()
        docker = Docker(clock)
        made, log = runner(clock, docker)

        self.assertTrue(
            made.run([fault(2, 5, node=1), fault(6, 9, node=0, kind="crash")])
        )

        remote = ["docker", "--host", "ssh://ubuntu@10.0.1.12"]
        self.assertEqual(
            docker.commands,
            [
                (2, remote + ["pause", "node-1"]),
                (5, remote + ["unpause", "node-1"]),
                (6, ["docker", "kill", "node-0"]),
                (9, ["docker", "start", "node-0"]),
            ],
        )
        logged = events(log)
        self.assertEqual(
            [(e["at"], e["event"], e.get("node")) for e in logged],
            [
                (2, "inject", "node-1"),
                (5, "heal", "node-1"),
                (6, "inject", "node-0"),
                (9, "heal", "node-0"),
                (9, "end", None),
            ],
        )
        self.assertEqual(logged[0]["until"], 5)
        self.assertFalse(logged[-1]["failed"])

    def test_heals_a_node_before_faulting_it_again_at_the_same_time(self):
        clock = Clock()
        docker = Docker(clock)
        made, _ = runner(clock, docker)

        made.run([fault(1, 4, kind="pause"), fault(4, 6, kind="crash")])

        self.assertEqual(
            [argv[-2] for _, argv in docker.commands],
            ["pause", "unpause", "kill", "start"],
        )

    def test_a_stop_heals_every_fault_still_on_and_starts_no_other(self):
        clock = Clock()
        clock.stop_at = 100.0 + 3
        docker = Docker(clock)
        made, log = runner(clock, docker)

        made.run(
            [
                fault(1, 10, node=0),
                fault(2, 10, node=2, kind="partition"),
                fault(5, 8, node=1),
            ]
        )

        self.assertEqual(
            [(at, argv[1]) for at, argv in docker.commands],
            [(1, "pause"), (2, "exec"), (3, "exec"), (3, "unpause")],
        )
        self.assertEqual(docker.commands[2][1][-4:], ["del", "dev", "eth0", "root"])
        self.assertEqual(events(log)[-1], {"at": 3, "event": "end", "failed": False})

    def test_a_failed_injection_is_logged_never_healed_and_fails_the_run(self):
        clock = Clock()
        docker = Docker(clock, fail={"kill"})
        made, log = runner(clock, docker)

        self.assertFalse(made.run([fault(1, 2, kind="crash")]))

        self.assertEqual([argv[1] for _, argv in docker.commands], ["kill"])
        error = events(log)[0]
        self.assertEqual(
            (error["event"], error["action"], error["error"]),
            ("error", "inject", "no such container"),
        )

    def test_a_heal_is_tried_again_a_second_later(self):
        clock = Clock()
        docker = Docker(clock, fail={"unpause"}, fail_times=2)
        made, log = runner(clock, docker)

        self.assertTrue(made.run([fault(1, 2)]))

        self.assertEqual(
            [(at, argv[1]) for at, argv in docker.commands],
            [(1, "pause"), (2, "unpause"), (3, "unpause"), (4, "unpause")],
        )
        self.assertEqual(events(log)[1]["event"], "heal")

    def test_a_heal_after_a_stop_still_waits_a_second_between_tries(self):
        clock = Clock()
        clock.stop_at = 100.0 + 3
        docker = Docker(clock, fail={"unpause"}, fail_times=2)
        made, _ = runner(clock, docker)

        self.assertTrue(made.run([fault(1, 10)]))

        self.assertEqual(
            [(at, argv[1]) for at, argv in docker.commands],
            [(1, "pause"), (3, "unpause"), (4, "unpause"), (5, "unpause")],
        )

    def test_a_stop_ends_a_wait_at_once(self):
        made = chaos.Runner(NODES, io.StringIO())
        self.assertFalse(made.wait(0.01))

        made.stop()
        started = time.monotonic()
        self.assertTrue(made.wait(30))
        self.assertLess(time.monotonic() - started, 1)

    def test_a_heal_that_never_works_is_given_up_and_fails_the_run(self):
        clock = Clock()
        docker = Docker(clock, fail={"start"})
        made, log = runner(clock, docker)

        self.assertFalse(made.run([fault(1, 2, kind="crash"), fault(3, 4)]))

        self.assertEqual(
            [argv[1] for _, argv in docker.commands],
            ["kill"] + ["start"] * chaos.HEAL_TRIES + ["pause", "unpause"],
        )
        self.assertEqual(
            [e["event"] for e in events(log)],
            ["inject", "error", "inject", "heal", "end"],
        )


class ArgumentsTest(unittest.TestCase):
    def parse(self, *argv):
        with contextlib.redirect_stderr(io.StringIO()):
            return chaos.parser().parse_args(argv)

    def test_a_range_or_one_value(self):
        self.assertEqual(chaos.span("3-10"), (3.0, 10.0))
        self.assertEqual(chaos.span("5"), (5.0, 5.0))
        self.assertEqual(chaos.span("0.5-1.5"), (0.5, 1.5))
        for bad in ("10-3", "-1", "a-b", ""):
            with self.assertRaises(argparse.ArgumentTypeError):
                chaos.span(bad)

    def test_a_node_ends_at_its_first_at_sign(self):
        self.assertEqual(chaos.node("n"), ("n", None))
        self.assertEqual(
            chaos.node("n@ssh://ubuntu@10.0.1.12"), ("n", "ssh://ubuntu@10.0.1.12")
        )
        with self.assertRaises(argparse.ArgumentTypeError):
            chaos.node("@ssh://h")

    def test_refuses_an_unknown_kind_or_a_plan_without_nodes(self):
        with self.assertRaises(SystemExit):
            self.parse("plan", "--node", "n", "--duration", "5", "--kinds", "melt")
        with self.assertRaises(SystemExit):
            self.parse("plan", "--duration", "5")

    def test_plan_prints_each_fault_with_its_node_and_settings(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            status = chaos.main(
                ["plan", "--node", "a", "--node", "b", "--duration", "600"]
                + ["--kinds", "delay", "--seed", "4"]
            )
        self.assertEqual(status, 0)
        lines = [json.loads(line) for line in out.getvalue().splitlines()]
        faults = chaos.plan(4, 2, 600, kinds=["delay"])
        self.assertEqual(len(lines), len(faults))
        self.assertEqual(
            lines[0],
            {
                "start": round(faults[0].start, 3),
                "end": round(faults[0].end, 3),
                "node": "ab"[faults[0].node],
                "kind": "delay",
                "delayMs": faults[0].delay_ms,
                "jitterMs": faults[0].jitter_ms,
            },
        )

    def test_refuses_a_loss_above_all_packets(self):
        with self.assertRaises(SystemExit):
            chaos.main(
                ["plan", "--node", "a", "--duration", "5", "--loss-percent", "50-150"]
            )


if __name__ == "__main__":
    unittest.main()
