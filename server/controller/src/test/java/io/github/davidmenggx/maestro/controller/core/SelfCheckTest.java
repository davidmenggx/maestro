package io.github.davidmenggx.maestro.controller.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.flatbuffers.FlatBufferBuilder;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.region.Region;
import io.github.davidmenggx.maestro.controller.sim.FaultPlan;
import io.github.davidmenggx.maestro.controller.sim.SimRun;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import maestro.fbs.ControlReplyFrame;
import maestro.fbs.ControlReplyMsg;
import maestro.fbs.Hello;
import maestro.fbs.Pathfinder;
import org.junit.jupiter.api.Test;

/**
 * Each finding of {@link Controller#selfCheck} fires when its bookkeeping breaks. A finding a
 * transport can provoke is provoked through the controller's own inputs. One only a controller bug
 * can cause is provoked by corrupting the state it reads.
 */
class SelfCheckTest {

  private static final NodeId NODE = NodeId.of(0);
  private static final NodeId OTHER = NodeId.of(1);

  @Test
  void framesFromANodeOnTheWorldChannelAreReportedAndPastTheCapOnlyCounted() {
    SimRun run = clean();
    int extra = 5;

    for (int i = 0; i < Violations.MAX_KEPT + extra; i++) {
      run.controller.onFrame(NODE, Channel.WORLD, ByteBuffer.wrap(new byte[8]), 0);
    }

    List<String> found = run.controller.selfCheck();
    assertEquals(Violations.MAX_KEPT + 1, found.size(), found.toString());
    assertEquals(
        Collections.nCopies(Violations.MAX_KEPT, NODE + " sent a frame on the world channel"),
        found.subList(0, Violations.MAX_KEPT));
    assertEquals(extra + " more violations", found.get(Violations.MAX_KEPT));
  }

  /** A transport must not hand over a {@code Hello} once it reported the node's link lost. */
  @Test
  void aGreetingFromANodeWithNoLinkIsReported() {
    SimRun run = clean();
    run.controller.onDisconnected(NODE);
    assertFalse(run.controller.linked(NODE));
    assertEquals(List.of(), run.controller.selfCheck());

    run.controller.onFrame(NODE, Channel.CONTROL, hello(0, 0, 0, Pathfinder.VANILLA), 0);

    assertEquals(List.of(NODE + " is greeted but not connected"), run.controller.selfCheck());
  }

  /** Answers of a fleet that runs two searches could not be attributed to one. */
  @Test
  void aFleetRunningTwoSearchesIsReported() {
    SimRun run = clean();
    assertTrue(run.controller.linked(NODE) && run.controller.linked(OTHER), run.describe());

    run.controller.onFrame(OTHER, Channel.CONTROL, hello(0, 0, 0, Pathfinder.VANILLA + 1), 0);

    assertTrue(
        run.controller.selfCheck().contains(OTHER + " runs pathfinder 1 but the fleet runs 0"),
        run.controller.selfCheck().toString());
  }

  @Test
  void requestBookkeepingThatDisagreesWithItsRequestsIsReported() throws Exception {
    SimRun run = clean();
    Object requests = field(run.controller, "requests");

    Object load = ((Map<?, ?>) field(requests, "loads")).get(NODE);
    Field unsent = load.getClass().getDeclaredField("unsent");
    unsent.setAccessible(true);
    unsent.setInt(load, 2);
    @SuppressWarnings("unchecked")
    Map<Integer, Long> requestByMob = (Map<Integer, Long>) field(requests, "requestByMob");
    requestByMob.put(77, 9_999L);

    List<String> found = run.controller.selfCheck();
    assertTrue(found.contains(NODE + " counts 2 requests unsent, but 0 are"), found.toString());
    assertTrue(found.contains("mob index holds 1 but 0 requests are live"), found.toString());
    assertTrue(found.contains("mob 77 points at a request that is not live"), found.toString());
  }

  @Test
  void aRegionRetainingMoreFramesThanItsReplayWindowIsReported() throws Exception {
    SimRun run = clean();
    Map<?, ?> logs = (Map<?, ?>) field(field(run.controller, "streams"), "logs");
    Object log = null;
    for (Object candidate : logs.values()) {
      if (!((Map<?, ?>) field(candidate, "frames")).isEmpty()) {
        log = candidate;
        break;
      }
    }
    assertTrue(log != null, "no region retains a frame: " + run.describe());
    @SuppressWarnings("unchecked")
    Map<Long, Object> frames = (Map<Long, Object>) field(log, "frames");
    int regionId = ((Region) field(log, "region")).id();

    // Frames behind the ready point that eviction left in place.
    Object frame = frames.values().iterator().next();
    for (long sequence = -1; sequence >= -WorldStreams.RETENTION_FRAMES - 1; sequence--) {
      frames.put(sequence, frame);
    }

    List<String> found = run.controller.selfCheck();
    assertTrue(
        found.stream()
            .anyMatch(
                finding ->
                    finding.startsWith("region " + regionId + " retains ")
                        && finding.endsWith(" frames, past " + WorldStreams.RETENTION_FRAMES)),
        found.toString());
  }

  /** A run with no fault, which leaves the controller consistent. */
  private static SimRun clean() {
    SimRun run = SimRun.of(3L, FaultPlan.reliable(), 150).go();
    assertEquals(List.of(), run.controller.selfCheck(), run.describe());
    return run;
  }

  private static ByteBuffer hello(long run, long regionId, long lastSequence, int pathfinder) {
    FlatBufferBuilder builder = new FlatBufferBuilder(64);
    int hello = Hello.createHello(builder, run, regionId, lastSequence, pathfinder);
    builder.finish(
        ControlReplyFrame.createControlReplyFrame(builder, ControlReplyMsg.Hello, hello));
    return ByteBuffer.wrap(builder.sizedByteArray());
  }

  /** A private field, which only a finding no input can provoke needs reaching into. */
  private static Object field(Object owner, String name) throws ReflectiveOperationException {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }
}
