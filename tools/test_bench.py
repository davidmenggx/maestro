import contextlib
import hashlib
import io
import math
import json
import tempfile
import unittest
import unittest.mock
from pathlib import Path

import bench


def report(
    mean=10.0,
    measured=600,
    over=0,
    spawned=100,
    requests=None,
    late=None,
    offloading=None,
    halves=(None, None),
    resets=0,
    wall=None,
    rates=(),
    capacity=100,
):
    """A load report as the load mod writes it, with what `metrics` reads. A
    report with `offloading` set comes from a server with the released mod,
    whose nodes measured `rates`, and one that measured its ticks on time
    unless `wall` says otherwise."""
    return {
        "maestro": (
            None
            if offloading is None
            else {"offloading": offloading, "nodeCapacity": capacity}
        ),
        "ticks": {
            "measured": measured,
            "wallSeconds": measured / 20 if wall is None else wall,
            "meanMillis": mean,
            "firstHalfMeanMillis": halves[0],
            "secondHalfMeanMillis": halves[1],
            "p50Millis": mean,
            "p99Millis": mean * 2,
            "overBudget": over,
        },
        "entities": {"spawned": spawned, "alive": spawned, "resets": resets},
        "requests": requests,
        "ticksLate": late,
        "nodeRates": None if offloading is None else list(rates),
    }


def offloaded(
    histogram, shed=0, dropped=0, failover=0, offloading=True, mean=10.0, rates=()
):
    """The metrics of a run that offloaded, its answers by ticks late in
    `histogram`, on nodes at `nodeCapacity` 100 that measured `rates`."""
    requests = {
        "requested": sum(histogram) + shed + dropped + failover,
        "answered": sum(histogram),
        "shed": shed,
        "dropped": dropped,
        "canceled": 7,
        "refused": 50 + shed,
        "failover_recent": failover,
        "failover_full": 0,
        "failover_cheap": 0,
        "failover_later": 3,
    }
    late = {"p99": None, "histogram": histogram}
    return bench.metrics(
        report(
            mean=mean, requests=requests, late=late, offloading=offloading, rates=rates
        )
    )


class CpuListTest(unittest.TestCase):
    def test_ranges_and_single_cpus_in_order(self):
        self.assertEqual(bench.parse_cpus("0-2,5"), [0, 1, 2, 5])
        self.assertEqual(bench.parse_cpus("4"), [4])
        self.assertEqual(bench.cpu_list([0, 1, 5]), "0,1,5")

    def test_refuses_what_is_not_a_cpu_list(self):
        for text in ("", "a", "1-", "-1", "3-1", "0,,1", "2,2", "0-2,1"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                bench.parse_cpus(text)

    def test_splits_into_runs_of_consecutive_cpus_as_even_as_can_be(self):
        self.assertEqual(bench.split_cpus([3, 4, 5], 1), [[3, 4, 5]])
        self.assertEqual(bench.split_cpus([3, 4, 5], 3), [[3], [4], [5]])
        self.assertEqual(bench.split_cpus([0, 1, 2, 3, 4], 2), [[0, 1, 2], [3, 4]])
        # More nodes than CPUs leaves one with none, which the bench refuses.
        self.assertEqual(bench.split_cpus([3, 4], 3), [[3], [4], []])


class CoresTest(unittest.TestCase):
    # Two threads a core, each core's second numbered past every first.
    SIBLINGS = {0: {0, 3}, 1: {1, 4}, 2: {2, 5}, 3: {0, 3}, 4: {1, 4}, 5: {2, 5}}

    def test_finds_a_server_cpu_and_a_node_cpu_on_one_core(self):
        siblings = self.SIBLINGS.__getitem__
        self.assertEqual(bench.shared_cores([0, 1], [4, 5], siblings), [(1, 4)])
        self.assertEqual(bench.shared_cores([0, 1, 2], [3], siblings), [(0, 3)])
        self.assertEqual(bench.shared_cores([0], [1, 2], siblings), [])

    def test_with_one_thread_a_core_disjoint_cpus_share_none(self):
        self.assertEqual(
            bench.shared_cores([0, 1, 2], [3, 4, 5], lambda cpu: {cpu}), []
        )

    def test_a_cpu_the_kernel_says_nothing_of_is_its_own_core(self):
        self.assertEqual(bench.core_siblings(10**6), {10**6})


class KeepsTest(unittest.TestCase):
    KEEP = {"x": 100, "z": 0, "radius": 3}
    SIEGE = {"terrain": [{"x": 100, "z": 0, "radius": 20}], "keeps": [KEEP]}

    def test_squares_meet_when_they_share_a_column(self):
        square = {"x": 0, "z": 0, "radius": 10}
        self.assertTrue(bench.squares_meet(square, {"x": 21, "z": 0, "radius": 11}))
        self.assertFalse(bench.squares_meet(square, {"x": 22, "z": 0, "radius": 11}))
        self.assertFalse(bench.squares_meet(square, {"x": 0, "z": -22, "radius": 11}))
        self.assertTrue(
            bench.squares_meet(square, {"x": 0, "z": 23, "radius": 11}, grow=2)
        )
        self.assertFalse(
            bench.squares_meet(square, {"x": 0, "z": 24, "radius": 11}, grow=2)
        )

    def test_no_terrain_may_cover_another_scenarios_keep_or_its_margin(self):
        # The keep and its margin reach down to x 95.
        clear = {"terrain": [{"x": 0, "z": 0, "radius": 94}]}
        near = {"terrain": [{"x": 0, "z": 0, "radius": 95}]}

        self.assertEqual(bench.keeps_covered({"siege": self.SIEGE, "a": clear}), [])
        self.assertEqual(
            bench.keeps_covered({"siege": self.SIEGE, "a": near}), [("a", "siege")]
        )

    def test_a_scenarios_own_keeps_and_terrain_without_keeps_may_overlap(self):
        square = {"terrain": [{"x": 0, "z": 0, "radius": 50}]}
        self.assertEqual(bench.keeps_covered({"siege": self.SIEGE}), [])
        self.assertEqual(bench.keeps_covered({"a": square, "b": square}), [])

    def test_the_bundled_scenarios_can_share_a_world(self):
        scenarios = {
            path.stem: json.loads(path.read_text())
            for path in bench.SCENARIOS.glob("*.json")
        }
        self.assertIn("siege", scenarios)
        self.assertEqual(bench.keeps_covered(scenarios), [])


class LauncherTest(unittest.TestCase):
    def test_keeps_the_launcher_it_expects_and_removes_another(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "launcher.jar"
            jar.write_bytes(b"another launcher")
            with self.assertRaises(bench.BenchError):
                bench.check_launcher(jar)
            self.assertFalse(jar.exists())

            jar.write_bytes(b"the launcher")
            expected = hashlib.sha256(b"the launcher").hexdigest()
            with unittest.mock.patch.object(bench, "LAUNCHER_SHA256", expected):
                bench.check_launcher(jar)
            self.assertTrue(jar.exists())


class ArgumentsTest(unittest.TestCase):
    def test_refuses_a_start_above_the_limit_unless_counts_are_fixed(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            bench.main(["run", "--start", "500", "--limit", "100"])


class ScaledTest(unittest.TestCase):
    SCENARIO = {
        "seed": 1,
        "players": [{"x": 0, "z": 0}],
        "groups": [
            {"entity": "minecraft:husk", "count": 3, "x": 0, "z": 0, "spread": 8},
            {"entity": "minecraft:villager", "count": 1, "x": 0, "z": 0, "spread": 8},
        ],
    }

    def counts(self, mobs):
        spec = bench.scaled(self.SCENARIO, mobs, 10, 30)
        return [(group["entity"], group["count"]) for group in spec["groups"]]

    def test_scales_the_weights_to_the_count(self):
        self.assertEqual(
            self.counts(1000), [("minecraft:husk", 750), ("minecraft:villager", 250)]
        )

    def test_largest_remainders_round_up_earlier_groups_first(self):
        # 7.5 and 2.5: equal remainders, so the first group takes the mob left.
        self.assertEqual(
            self.counts(10), [("minecraft:husk", 8), ("minecraft:villager", 2)]
        )
        self.assertEqual(
            self.counts(7), [("minecraft:husk", 5), ("minecraft:villager", 2)]
        )

    def test_the_counts_always_add_up(self):
        for mobs in range(1, 300):
            self.assertEqual(sum(count for _, count in self.counts(mobs)), mobs)

    def test_a_group_left_with_none_is_dropped(self):
        self.assertEqual(self.counts(1), [("minecraft:husk", 1)])

    def test_sets_the_durations_and_keeps_the_rest(self):
        spec = bench.scaled(self.SCENARIO, 100, 10, 30)
        self.assertEqual(spec["warmupSeconds"], 10)
        self.assertEqual(spec["durationSeconds"], 30)
        self.assertEqual(spec["players"], self.SCENARIO["players"])
        self.assertEqual(spec["groups"][0]["spread"], 8)
        # The template is left as it was.
        self.assertEqual(self.SCENARIO["groups"][0]["count"], 3)


class VerdictTest(unittest.TestCase):
    def test_a_run_without_the_released_mod_has_no_request_rates(self):
        measured = bench.metrics(report())
        self.assertEqual(measured["meanMillis"], 10.0)
        self.assertEqual(measured["overBudget"], 0)
        self.assertNotIn("answeredPerSecond", measured)

    def test_request_counts_become_rates_over_the_measured_wall_time(self):
        requests = {
            "requested": 3000,
            "answered": 2400,
            "shed": 300,
            "failover_recent": 30,
            "failover_full": 60,
            "failover_cheap": 0,
            "failover_waiting": 99,
        }
        late = {"p99": 2, "histogram": [0, 2000, 390, 10]}
        measured = bench.metrics(report(requests=requests, late=late))
        # 600 ticks over 30 s.
        self.assertEqual(measured["answeredPerSecond"], 80)
        self.assertEqual(measured["shedPerSecond"], 10)
        self.assertEqual(measured["failoverPerSecond"], 3)
        self.assertEqual(measured["lateP99"], 2)

    def test_the_lowest_node_rate_and_the_assumed_capacity_come_from_the_report(self):
        measured = bench.metrics(report(offloading=True, rates=[30.0, None, 20.0]))
        self.assertEqual(measured["nodeRate"], 20.0)
        self.assertEqual(measured["nodeCapacity"], 100)
        self.assertIsNone(bench.metrics(report(offloading=True))["nodeRate"])
        vanilla = bench.metrics(report())
        self.assertIsNone(vanilla["nodeRate"])
        self.assertIsNone(vanilla["nodeCapacity"])

    def test_the_drift_and_resets_come_from_the_ticks_and_entities(self):
        measured = bench.metrics(report(halves=(20.0, 22.0), resets=60))
        self.assertAlmostEqual(measured["drift"], 0.1)
        # 600 ticks over 30 s.
        self.assertEqual(measured["resetsPerSecond"], 2)

    def test_no_drift_without_both_halves(self):
        self.assertAlmostEqual(bench.drift(40.0, 30.0), -0.25)
        self.assertIsNone(bench.drift(None, 30.0))
        self.assertIsNone(bench.drift(0.0, 30.0))
        self.assertIsNone(bench.drift(40.0, None))

    def test_holds_at_up_to_50_ms_and_1_percent_of_ticks_over(self):
        self.assertEqual(
            bench.failures(bench.metrics(report(mean=50.0, over=6)), 100, False), []
        )
        self.assertEqual(
            bench.failures(bench.metrics(report(mean=50.1)), 100, False), ["mean"]
        )
        self.assertEqual(
            bench.failures(bench.metrics(report(over=7)), 100, False), ["tail"]
        )

    def test_holds_only_at_nearly_20_ticks_a_second_over_the_wall_time(self):
        # 600 ticks over 30.3 s, then over 30.4 s.
        self.assertAlmostEqual(bench.metrics(report(wall=30.0))["tps"], 20.0)
        self.assertEqual(
            bench.failures(bench.metrics(report(wall=30.3)), 100, False), []
        )
        self.assertEqual(
            bench.failures(bench.metrics(report(wall=30.4)), 100, False), ["tps"]
        )

    def test_does_not_hold_without_every_mob_or_a_measured_tick(self):
        self.assertEqual(
            bench.failures(bench.metrics(report(spawned=99)), 100, False), ["mobs"]
        )
        self.assertEqual(
            bench.failures(bench.metrics(report(measured=0)), 100, False),
            ["unmeasured"],
        )
        self.assertEqual(bench.failures(None, 100, False), ["unmeasured"])

    def test_names_every_way_a_run_failed(self):
        run = offloaded([0, 90, 0, 10], mean=60.0, shed=5, rates=[150.0])
        run["spawned"] = 99
        run["tps"] = 19.0

        self.assertEqual(
            bench.failures(run, 100, True), ["mobs", "mean", "tps", "slo", "capacity"]
        )


class SloTest(unittest.TestCase):
    def test_an_answer_is_late_from_the_slo_and_a_lost_request_always(self):
        histogram = [0, 500, 300, 20, 5, 0, 0, 0, 0, 0, 1]
        run = offloaded(histogram, shed=4, dropped=2, failover=3)

        self.assertEqual(run["sloRequests"], 826 + 4 + 2 + 3)
        self.assertEqual(run["sloLate"], 26 + 4 + 2 + 3)
        self.assertAlmostEqual(bench.late_share(run), 35 / 835)

    def test_holds_while_at_most_1_percent_miss_it(self):
        self.assertEqual(bench.failures(offloaded([0, 990, 0, 10]), 100, True), [])
        self.assertEqual(
            bench.failures(offloaded([0, 990, 0, 11]), 100, True), ["slo"]
        )
        # Of 300, 3 late make the nearest-rank p99 the 297th, which is on time.
        self.assertEqual(
            bench.failures(offloaded([0, 297, 0, 2], shed=1), 100, True), []
        )
        self.assertEqual(
            bench.failures(offloaded([0, 296, 0, 3], shed=1), 100, True), ["slo"]
        )

    def test_agrees_with_the_nearest_rank_p99_the_report_gives_its_answers(self):
        for on_time in range(0, 700, 3):
            for late in range(12):
                if on_time + late == 0:
                    continue
                # `Report.percentile`: the value at rank ceil(0.99 n).
                rank = max(1, math.ceil(99 / 100 * (on_time + late)))
                p99 = 2 if rank <= on_time else bench.SLO_TICKS
                held = not bench.failures(offloaded([0, 0, on_time, late]), 100, True)
                with self.subTest(on_time=on_time, late=late):
                    self.assertEqual(held, p99 < bench.SLO_TICKS)

    def test_shed_dropped_and_failover_requests_each_miss_it(self):
        for lost in ("shed", "dropped", "failover"):
            with self.subTest(lost=lost):
                self.assertEqual(
                    bench.failures(offloaded([0, 98], **{lost: 2}), 100, True), ["slo"]
                )
                self.assertEqual(
                    bench.failures(offloaded([0, 99], **{lost: 1}), 100, True), []
                )

    def test_a_run_shedding_on_nodes_well_above_the_assumed_capacity_is_marked(self):
        # `nodeCapacity` is 100.
        run = offloaded([0, 90], shed=10, rates=[111.0, 130.0])
        self.assertEqual(bench.failures(run, 100, True), ["slo", "capacity"])
        # A node near the assumption, a node not measured yet, or no request shed.
        for run in (
            offloaded([0, 90], shed=10, rates=[109.0, 130.0]),
            offloaded([0, 90], shed=10, rates=[None]),
            offloaded([0, 90, 0, 10], rates=[150.0]),
        ):
            with self.subTest(run=run):
                self.assertEqual(bench.failures(run, 100, True), ["slo"])
        # Within the SLO, whatever the nodes measured.
        self.assertEqual(
            bench.failures(offloaded([0, 99], shed=1, rates=[150.0]), 100, True), []
        )

    def test_a_run_meant_to_offload_holds_only_with_a_fleet_and_requests(self):
        self.assertEqual(
            bench.failures(offloaded([0, 10], offloading=False), 100, True), ["fleet"]
        )
        self.assertEqual(bench.failures(offloaded([0, 0]), 100, True), ["fleet"])
        # No request counts, as when the fleet changed while the run measured.
        self.assertEqual(
            bench.failures(bench.metrics(report(offloading=True)), 100, True), ["fleet"]
        )

    def test_a_run_not_meant_to_offload_is_not_held_to_the_slo(self):
        self.assertEqual(bench.failures(offloaded([0, 0, 0, 99]), 100, False), [])
        self.assertIsNone(bench.late_share(bench.metrics(report())))


class DecidedTest(unittest.TestCase):
    def test_settled_once_most_runs_have_held_or_cannot(self):
        self.assertTrue(bench.decided(2, 0, 3))
        self.assertTrue(bench.decided(0, 2, 3))
        self.assertFalse(bench.decided(1, 1, 3))
        self.assertFalse(bench.decided(1, 0, 3))
        # Half of an even count holding is not most.
        self.assertTrue(bench.decided(0, 1, 2))
        self.assertFalse(bench.decided(1, 0, 2))
        self.assertTrue(bench.decided(1, 0, 1))
        self.assertTrue(bench.decided(0, 1, 1))

    def test_never_settles_otherwise_than_all_the_runs_would(self):
        for repeats in range(1, 7):
            for outcomes in range(2**repeats):
                runs = [bool(outcomes >> i & 1) for i in range(repeats)]
                held = failed = 0
                for run in runs:
                    held, failed = held + run, failed + (not run)
                    if bench.decided(held, failed, repeats):
                        break
                with self.subTest(runs=runs):
                    self.assertTrue(bench.decided(held, failed, repeats))
                    self.assertEqual(held * 2 > repeats, sum(runs) * 2 > repeats)


class MostMobsTest(unittest.TestCase):
    def search(self, most_that_holds, start=250, limit=32000, precision=0.1):
        tried = []

        def holds_at(mobs):
            self.assertNotIn(mobs, tried, "a count was tried twice")
            tried.append(mobs)
            return mobs <= most_that_holds

        return bench.most_mobs(holds_at, start, limit, precision), tried

    def test_doubles_then_bisects_to_within_the_precision(self):
        (held, failed), tried = self.search(1234)
        self.assertEqual(tried[:4], [250, 500, 1000, 2000])
        self.assertLessEqual(held, 1234)
        self.assertGreater(failed, 1234)
        self.assertLessEqual(failed - held, held * 0.1)

    def test_halves_when_the_start_does_not_hold(self):
        (held, failed), tried = self.search(100)
        self.assertEqual(tried[:2], [250, 125])
        self.assertLessEqual(held, 100)
        self.assertGreater(failed, 100)
        self.assertLessEqual(failed - held, held * 0.1)

    def test_stops_at_the_fewest_mobs_tried(self):
        (held, failed), tried = self.search(10)
        self.assertEqual((held, failed), (0, bench.MIN_MOBS))
        self.assertEqual(tried, [250, 125, 62, 31, 16])

    def test_every_count_up_to_the_limit_holding_finds_no_failure(self):
        (held, failed), tried = self.search(10**9, limit=4000)
        self.assertEqual((held, failed), (4000, None))
        self.assertEqual(tried, [250, 500, 1000, 2000, 4000])


class NodeCapacityTest(unittest.TestCase):
    def row(self, threads, rate, aggregate=None):
        row = {
            "run_name": f"search/all/real_time/threads:{threads}",
            "threads": threads,
            "items_per_second": rate,
        }
        if aggregate:
            row["aggregate_name"] = aggregate
        return row

    def test_takes_the_median_on_the_nodes_workers_times_its_threads(self):
        benchmarks = {
            "benchmarks": [
                self.row(1, 7000.0, "median"),
                self.row(3, 4000.0, "mean"),
                self.row(3, 4100.0, "median"),
                self.row(3, 50.0, "stddev"),
                {"run_name": "search/minecraft:cow/real_time", "threads": 3},
            ]
        }
        # 4100 a thread on 3 threads is 12300 a second, 615 a tick.
        self.assertEqual(bench.node_capacity(benchmarks, 3), (615, 12300.0))
        self.assertEqual(bench.node_capacity(benchmarks, 1), (350, 7000.0))

    def test_reads_a_run_without_repetitions(self):
        benchmarks = {"benchmarks": [self.row(2, 1000.0)]}
        self.assertEqual(bench.node_capacity(benchmarks, 2), (100, 2000.0))

    def test_never_below_one_and_refuses_a_missing_thread_count(self):
        self.assertEqual(
            bench.node_capacity({"benchmarks": [self.row(1, 5.0)]}, 1), (1, 5.0)
        )
        with self.assertRaises(bench.BenchError):
            bench.node_capacity({"benchmarks": [self.row(1, 5.0)]}, 4)


class TableTest(unittest.TestCase):
    def test_names_the_most_mobs_and_the_median_of_each_count(self):
        def run(mean, held):
            return {
                "held": held,
                "failed": [] if held else ["mean"],
                "metrics": bench.metrics(report(mean=mean)),
            }

        results = {
            "nodes": None,
            "scenarios": {
                "crowd": {
                    "vanilla": {
                        "most": 1000,
                        "firstFailed": 2000,
                        "searched": True,
                        "points": [
                            {"mobs": 2000, "runs": [run(70, False), run(80, False)]},
                            {"mobs": 1000, "runs": [run(30, True), run(40, True)]},
                        ],
                    },
                    "batch": {
                        "most": 2000,
                        "firstFailed": None,
                        "searched": True,
                        "points": [{"mobs": 2000, "runs": [run(20, True)]}],
                    },
                }
            }
        }

        lines = bench.table(results).splitlines()

        self.assertEqual(
            lines[0], "crowd: most mobs at 20 TPS: vanilla 1000, batch 2000+"
        )
        self.assertTrue(lines[1].startswith("config"))
        # Smallest count first, and the range of the means beside their median.
        self.assertRegex(lines[2], r"^vanilla\s+1000\s+2/2\s+35\.0 \(30\.0-40\.0\)")
        self.assertRegex(lines[3], r"^vanilla\s+2000\s+0/2\s+75\.0 \(70\.0-80\.0\)")
        self.assertRegex(lines[4], r"^batch\s+2000\s+1/1\s+20\.0\s")
        self.assertTrue(lines[3].endswith("  mean"))
        self.assertFalse(lines[2].endswith("mean"))

    def test_shows_the_share_that_missed_the_slo_and_what_failed(self):
        late = {"held": False, "failed": ["slo"], "metrics": offloaded([0, 97, 0, 3])}
        results = {
            "nodes": None,
            "scenarios": {
                "crowd": {
                    "batch": {
                        "most": 0,
                        "firstFailed": 500,
                        "searched": True,
                        "points": [{"mobs": 500, "runs": [late]}],
                    }
                }
            }
        }

        row = bench.table(results).splitlines()[2]

        self.assertRegex(row, r"\s3\.00%\s")
        self.assertTrue(row.endswith("  slo"))

    def test_shows_the_drift_and_the_resets(self):
        run = {
            "held": True,
            "failed": [],
            "metrics": bench.metrics(report(halves=(20.0, 22.0), resets=60)),
        }
        results = {
            "nodes": None,
            "scenarios": {
                "chase": {
                    "vanilla": {
                        "most": 500,
                        "firstFailed": None,
                        "searched": False,
                        "points": [{"mobs": 500, "runs": [run]}],
                    }
                }
            }
        }

        lines = bench.table(results).splitlines()

        self.assertRegex(lines[1], r"\s>50 ms\s+drift\s")
        self.assertRegex(lines[1], r"\sfailover/s\s+reset/s\s")
        self.assertRegex(lines[2], r"\s0\.0%\s+\+10\.0%\s")
        self.assertRegex(lines[2], r"\s2$")


    def test_names_the_assumed_capacity_and_shows_the_tps_and_node_rate(self):
        run = {
            "held": False,
            "failed": ["slo", "capacity"],
            "metrics": offloaded([0, 90], shed=10, rates=[140.0, 160.0]),
        }
        run["metrics"]["tps"] = 19.5
        results = {
            "nodes": {"count": 2, "capacity": 100},
            "scenarios": {
                "crowd": {
                    "batch": {
                        "most": 0,
                        "firstFailed": 500,
                        "searched": True,
                        "points": [{"mobs": 500, "runs": [run]}],
                    }
                }
            },
        }

        lines = bench.table(results).splitlines()

        self.assertEqual(lines[0], "2 node(s) at nodeCapacity 100")
        self.assertEqual(lines[1], "")
        self.assertTrue(lines[2].startswith("crowd: most mobs"))
        self.assertRegex(lines[3], r"\sdrift\s+TPS\s+late p99\s")
        self.assertRegex(lines[3], r"\sshed/s\s+node rate\s+failover/s\s")
        self.assertRegex(lines[4], r"\s19\.50\s")
        self.assertRegex(lines[4], r"\s140\s+0\s+0\s+capacity,slo$")

if __name__ == "__main__":
    unittest.main()
