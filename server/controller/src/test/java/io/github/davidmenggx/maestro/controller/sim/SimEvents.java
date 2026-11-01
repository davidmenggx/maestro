package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.Events;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.region.RegionLayout;
import io.github.davidmenggx.maestro.controller.region.Regions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Records the events of every controller process in a run, and checks one process's topology. */
public final class SimEvents implements Events {

  /** One event, with its attributes by key in the order given. */
  public record Event(String name, Map<String, String> attributes) {

    public String get(String key) {
      String value = attributes.get(key);
      if (value == null) {
        throw new IllegalArgumentException(name + " has no " + key);
      }
      return value;
    }

    int number(String key) {
      return Integer.parseInt(get(key));
    }
  }

  private final List<Event> events = new ArrayList<>();

  @Override
  public void event(String name, String... attributes) {
    if (attributes.length % 2 != 0) {
      throw new IllegalArgumentException(
          "attributes must be key/value pairs: " + attributes.length);
    }
    Map<String, String> byKey = new LinkedHashMap<>();
    for (int i = 0; i < attributes.length; i += 2) {
      if (byKey.put(attributes[i], attributes[i + 1]) != null) {
        throw new IllegalArgumentException(name + " has " + attributes[i] + " twice");
      }
    }
    events.add(new Event(name, Collections.unmodifiableMap(byKey)));
  }

  /** The events of controller run {@code run}, in the order reported. */
  public List<Event> of(long run) {
    String id = Long.toHexString(run);
    return events.stream().filter(event -> id.equals(event.attributes().get("run"))).toList();
  }

  /** The events of every run named {@code name} and given for {@code reason}, in order. */
  public List<Event> named(String name, String reason) {
    return events.stream()
        .filter(event -> event.name().equals(name))
        .filter(event -> reason.equals(event.attributes().get("reason")))
        .toList();
  }

  /** Cells of the region {@code event} names, as its run added it. */
  public int cells(Event event) {
    for (Event added : events) {
      if (added.name().equals("region.added")
          && added.get("run").equals(event.get("run"))
          && added.get("region").equals(event.get("region"))) {
        Region region = region(added);
        return region.sizeX() / RegionLayout.CELL * (region.sizeZ() / RegionLayout.CELL);
      }
    }
    throw new IllegalArgumentException("no region.added for " + event);
  }

  /**
   * Holders returned to the spares, in every run, that went down while holding a region and were
   * still down or had been back up fewer than {@code stableTicks}, so the controller still counted
   * them down.
   */
  public int returnedBeforeStable(long stableTicks) {
    // By run and node. Looked up, never iterated.
    Map<List<String>, Boolean> holding = new LinkedHashMap<>();
    // The tick each such node last came up, or -1 while it is down.
    Map<List<String>, Long> upSince = new LinkedHashMap<>();
    int found = 0;
    for (Event event : events) {
      List<String> node = List.of(event.get("run"), event.attributes().getOrDefault("node", ""));
      long tick = Long.parseLong(event.get("tick"));
      switch (event.name()) {
        case "holder.added" -> holding.put(node, true);
        case "holder.removed" -> {
          Long since = upSince.get(node);
          if (event.get("reason").equals("return")
              && since != null
              && (since < 0 || tick - since < stableTicks)) {
            found++;
          }
          holding.put(node, false);
        }
        case "node.down" -> {
          if (holding.getOrDefault(node, false)) {
            upSince.put(node, -1L);
          }
        }
        case "node.up" -> upSince.computeIfPresent(node, (key, since) -> since < 0 ? tick : since);
        default -> {}
      }
    }
    return found;
  }

  /**
   * Replays the topology events of controller run {@code run} from its start. Each must name a
   * reason and apply to what came before it, in tick order, and together they must give the regions
   * and holders {@code regions} has now and the nodes {@code up} when the controller last judged
   * them. Every gauge they name must hold its last reported value in {@code metrics}, with each
   * node of {@code fleet} that was never reported up still down.
   */
  public List<String> checkTopology(
      long run, Regions regions, List<NodeId> fleet, Set<NodeId> up, SimMetrics metrics) {
    List<String> violations = new ArrayList<>();
    String runId = Long.toHexString(run);
    List<Event> reported = of(run);
    Map<Integer, Region> live = new TreeMap<>();
    Set<Integer> seen = new TreeSet<>();
    Map<Integer, List<String>> holders = new TreeMap<>();
    Map<String, Integer> held = new LinkedHashMap<>();
    Set<String> replayedUp = new LinkedHashSet<>();
    // Gauge name and labels, then the value its events leave it at.
    Map<List<String>, Double> gauges = new LinkedHashMap<>();
    long lastTick = Long.MIN_VALUE;
    for (Event event : reported) {
      long tick = Long.parseLong(event.get("tick"));
      if (tick < lastTick) {
        violations.add(event + " came after an event of tick " + lastTick);
      }
      lastTick = tick;
      if (event.attributes().getOrDefault("reason", "").isEmpty()) {
        violations.add(event + " names no reason");
      }
      switch (event.name()) {
        case "region.added" -> {
          Region region = region(event);
          if (!seen.add(region.id())) {
            violations.add("region " + region.id() + " was added twice");
          }
          live.put(region.id(), region);
          holders.put(region.id(), new ArrayList<>());
          gauges.put(info(runId, region), 1.0);
        }
        case "region.removed" -> {
          Region region = region(event);
          if (!region.equals(live.get(region.id()))) {
            violations.add("region " + region + " was removed but not live");
          } else if (!holders.get(region.id()).isEmpty()) {
            violations.add(
                "region " + region.id() + " was removed while held by " + holders.get(region.id()));
          }
          live.remove(region.id());
          holders.remove(region.id());
          gauges.put(info(runId, region), 0.0);
        }
        case "holder.added" -> {
          int region = event.number("region");
          String node = event.get("node");
          if (!live.containsKey(region)) {
            violations.add(node + " was added to region " + region + ", which is not live");
          } else if (held.containsKey(node)) {
            violations.add(node + " was added to region " + region + " holding " + held.get(node));
          } else {
            holders.get(region).add(node);
            held.put(node, region);
          }
          gauges.put(holder(runId, region, node), 1.0);
        }
        case "holder.removed" -> {
          int region = event.number("region");
          String node = event.get("node");
          Integer holding = held.get(node);
          if (holding == null || holding != region) {
            violations.add(node + " was removed from region " + region + " holding " + holding);
          } else {
            holders.get(region).remove(node);
            held.remove(node);
          }
          gauges.put(holder(runId, region, node), 0.0);
        }
        case "node.up", "node.down" -> {
          String node = event.get("node");
          boolean nowUp = event.name().equals("node.up");
          if (replayedUp.contains(node) == nowUp) {
            violations.add(node + " was reported " + event.name() + " twice in a row");
          }
          if (nowUp) {
            replayedUp.add(node);
          } else {
            replayedUp.remove(node);
          }
          gauges.put(List.of("node.up", "node", node), nowUp ? 1.0 : 0.0);
        }
        default -> violations.add("unknown event " + event);
      }
    }

    Map<Integer, Region> now = new TreeMap<>();
    for (Region region : regions.regions()) {
      now.put(region.id(), region);
      List<String> actual = regions.holders(region.id()).stream().map(NodeId::toString).toList();
      List<String> replayed = holders.getOrDefault(region.id(), List.of());
      if (!actual.equals(replayed)) {
        violations.add(
            "events give region "
                + region.id()
                + " to "
                + replayed
                + ", but it is held by "
                + actual);
      }
    }
    if (!now.equals(live)) {
      violations.add("events give regions " + live.values() + ", but there are " + now.values());
    }
    Set<String> actualUp = new TreeSet<>();
    for (NodeId node : up) {
      actualUp.add(node.toString());
    }
    if (!actualUp.equals(new TreeSet<>(replayedUp))) {
      violations.add("events leave " + replayedUp + " up, but " + actualUp + " were");
    }

    for (NodeId node : fleet) {
      gauges.putIfAbsent(List.of("node.up", "node", node.toString()), 0.0);
    }
    for (Map.Entry<List<String>, Double> gauge : gauges.entrySet()) {
      List<String> key = gauge.getKey();
      String[] labels = key.subList(1, key.size()).toArray(String[]::new);
      double value = metrics.gauge(key.get(0), labels);
      if (value != gauge.getValue()) {
        violations.add(key + " is " + value + ", but its events leave it at " + gauge.getValue());
      }
    }
    return violations;
  }

  private static Region region(Event event) {
    return new Region(
        event.number("region"),
        event.number("min_x"),
        event.number("min_z"),
        event.number("size_x"),
        event.number("size_z"));
  }

  private static List<String> info(String run, Region region) {
    return List.of(
        "region.info",
        "run",
        run,
        "region",
        Integer.toString(region.id()),
        "min_x",
        Integer.toString(region.minX()),
        "min_z",
        Integer.toString(region.minZ()),
        "size_x",
        Integer.toString(region.sizeX()),
        "size_z",
        Integer.toString(region.sizeZ()));
  }

  private static List<String> holder(String run, int region, String node) {
    return List.of("region.holder", "run", run, "region", Integer.toString(region), "node", node);
  }
}
