package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.region.RegionLayout;
import io.github.davidmenggx.maestro.controller.region.Regions;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link SimEvents#checkTopology} passes a faithful report and catches each kind of fault. */
class SimEventsTest {

  private static final long RUN = 0xab;
  private static final String RUN_ID = Long.toHexString(RUN);
  private static final List<NodeId> FLEET = List.of(NodeId.of(0), NodeId.of(1));

  private final SimEvents events = new SimEvents();
  private final SimMetrics metrics = new SimMetrics();

  /** The nodes the controller found up when it last judged them. */
  private final Set<NodeId> judgedUp = new LinkedHashSet<>();

  /** One region, held by the first node, with the second spare. */
  private final Regions regions = new RegionLayout(1).initial(FLEET, 1);

  private final Region region = regions.region(1);

  @Test
  void aFaithfulReportPasses() {
    layout();
    up("node-0", true);
    assertEquals(List.of(), check());
  }

  @Test
  void anotherRunsEventsAreNotReplayed() {
    layout();
    events.event("holder.added", "run", "ff", "tick", "1", "region", "9", "node", "node-1");
    assertEquals(List.of(), check());
  }

  @Test
  void aHolderOfARegionThatIsNotLiveIsCaught() {
    layout();
    holder(2, "node-1", true);
    assertCaught("which is not live");
  }

  @Test
  void aRegionRemovedWhileHeldIsCaught() {
    layout();
    regionEvent("region.removed", 0);
    // The controller still has the region, so the replay also disagrees with it.
    List<String> found = check();
    assertTrue(found.get(0).contains("removed while held"), found::toString);
  }

  @Test
  void aChangeTheEventsMissedIsCaught() {
    regionEvent("region.added", 1);
    assertCaught("but it is held by [node-0]");
  }

  @Test
  void eventsOutOfTickOrderAreCaught() {
    regionEvent("region.added", 1);
    events.event(
        "holder.added", "run", RUN_ID, "tick", "0", "region", "1", "node", "node-0", "reason", "x");
    metrics.gauge("region.holder", 1, "run", RUN_ID, "region", "1", "node", "node-0");
    assertCaught("came after an event of tick 1");
  }

  @Test
  void aNodeReportedUpTwiceInARowIsCaught() {
    layout();
    up("node-0", true);
    up("node-0", true);
    assertCaught("twice in a row");
  }

  @Test
  void aGaugeTheEventsDisagreeWithIsCaught() {
    layout();
    metrics.gauge("region.holder", 0, "run", RUN_ID, "region", "1", "node", "node-0");
    assertCaught("[region.holder, run, ab, region, 1, node, node-0] is 0.0");
  }

  @Test
  void aNodeNeverReportedUpMustReadDown() {
    layout();
    metrics.gauge("node.up", 1, "node", "node-1");
    assertCaught("[node.up, node, node-1] is 1.0");
  }

  @Test
  void anEventWithNoReasonIsCaught() {
    layout();
    metrics.gauge("node.up", 1, "node", "node-0");
    events.event("node.up", "run", RUN_ID, "tick", "1", "node", "node-0");
    judgedUp.add(NodeId.of(0));
    assertCaught("names no reason");
  }

  @Test
  void aNodeUpTheEventsMissedIsCaught() {
    layout();
    judgedUp.add(NodeId.of(0));
    assertCaught("events leave [] up, but [node-0] were");
  }

  @Test
  void eventsAreFoundByNameAndReasonWithTheCellsOfTheirRegion() {
    layout();
    List<SimEvents.Event> added = events.named("holder.added", "initial");
    assertEquals(1, added.size());
    assertEquals("node-0", added.get(0).get("node"));
    assertEquals(1, events.cells(added.get(0)));
    assertEquals(List.of(), events.named("holder.added", "split"));
  }

  @Test
  void aHolderReturnedBeforeItIsStableAfterGoingDownIsFound() {
    layout();
    up("node-0", true);
    returned("node-0", 2);
    // Up, never down while holding.
    assertEquals(0, events.returnedBeforeStable(10));

    holder(1, "node-0", true);
    up("node-0", false);
    returned("node-0", 2);
    assertEquals(1, events.returnedBeforeStable(10));

    // Back at tick 1, and returned before it has been up for ten ticks, then after.
    holder(1, "node-0", true);
    up("node-0", false);
    up("node-0", true);
    returned("node-0", 10);
    returned("node-0", 11);
    assertEquals(2, events.returnedBeforeStable(10));
  }

  @Test
  void attributesComeInPairsAndOnceEach() {
    assertThrows(IllegalArgumentException.class, () -> events.event("node.up", "run"));
    assertThrows(
        IllegalArgumentException.class, () -> events.event("node.up", "run", "1", "run", "2"));
  }

  private List<String> check() {
    return events.checkTopology(RUN, regions, FLEET, judgedUp, metrics);
  }

  /** The check finds one violation, which mentions {@code fragment}. */
  private void assertCaught(String fragment) {
    List<String> found = check();
    assertEquals(1, found.size(), found::toString);
    assertTrue(found.get(0).contains(fragment), found::toString);
  }

  /** The starting layout, reported as {@code Placement} reports it. */
  private void layout() {
    for (NodeId node : FLEET) {
      metrics.gauge("node.up", 0, "node", node.toString());
    }
    regionEvent("region.added", 1);
    holder(region.id(), "node-0", true);
  }

  private void regionEvent(String name, int exists) {
    String[] bounds = {
      "region",
      Integer.toString(region.id()),
      "min_x",
      Integer.toString(region.minX()),
      "min_z",
      Integer.toString(region.minZ()),
      "size_x",
      Integer.toString(region.sizeX()),
      "size_z",
      Integer.toString(region.sizeZ())
    };
    String[] labels = new String[bounds.length + 2];
    labels[0] = "run";
    labels[1] = RUN_ID;
    System.arraycopy(bounds, 0, labels, 2, bounds.length);
    metrics.gauge("region.info", exists, labels);
    String[] attributes = new String[bounds.length + 6];
    attributes[0] = "run";
    attributes[1] = RUN_ID;
    attributes[2] = "tick";
    attributes[3] = "1";
    System.arraycopy(bounds, 0, attributes, 4, bounds.length);
    attributes[attributes.length - 2] = "reason";
    attributes[attributes.length - 1] = "initial";
    events.event(name, attributes);
  }

  private void holder(int regionId, String node, boolean holds) {
    String id = Integer.toString(regionId);
    metrics.gauge("region.holder", holds ? 1 : 0, "run", RUN_ID, "region", id, "node", node);
    events.event(
        holds ? "holder.added" : "holder.removed",
        "run",
        RUN_ID,
        "tick",
        "1",
        "region",
        id,
        "node",
        node,
        "reason",
        "initial");
  }

  /** The node returned to the spares from region 1 at {@code tick}. */
  private void returned(String node, long tick) {
    events.event(
        "holder.removed",
        "run",
        RUN_ID,
        "tick",
        Long.toString(tick),
        "region",
        "1",
        "node",
        node,
        "reason",
        "return");
  }

  /** The node came up or went down, reported faithfully. */
  private void up(String node, boolean up) {
    metrics.gauge("node.up", up ? 1 : 0, "node", node);
    events.event(
        up ? "node.up" : "node.down",
        "run",
        RUN_ID,
        "tick",
        "1",
        "node",
        node,
        "reason",
        up ? "verified" : "not_linked");
    NodeId id = NodeId.of(Integer.parseInt(node.substring("node-".length())));
    if (up) {
      judgedUp.add(id);
    } else {
      judgedUp.remove(id);
    }
  }
}
