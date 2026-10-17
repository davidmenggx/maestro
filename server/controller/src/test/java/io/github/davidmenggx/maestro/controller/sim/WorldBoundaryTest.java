package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.flatbuffers.FlatBufferBuilder;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.github.davidmenggx.maestro.controller.wire.Frames;
import io.github.davidmenggx.maestro.controller.world.Chunks;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldOutput.DropReason;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import maestro.fbs.PathNode;
import maestro.fbs.PathResponse;
import maestro.fbs.PathType;
import maestro.fbs.ResponseFrame;
import maestro.fbs.ResponseMsg;
import org.junit.jupiter.api.Test;

/**
 * Boundary cases driven by a scripted trace. A generated workload reaches these only by chance.
 * Each checks a guarantee made to the mod: exactly one callback per request.
 */
class WorldBoundaryTest {

  private static final long TICKS = 30;
  private static final short MOB_CLASS = 1;
  private static final int MOB = 1;
  private static final int WARMUP_MOB = 2;
  private static final short OTHER_CLASS = 2;
  private static final Pos HERE = new Pos(4, 40, 4);
  private static final Pos THERE = new Pos(20, 40, 20);
  private static final WorldEvent.Search SEARCH = new WorldEvent.Search(0, 16f, 256, 3);

  /** Registration spends a request without an id, so the first one admitted gets 1. */
  private static final long FIRST_REQUEST = 1;

  /** Owns the region holding {@code HERE}. */
  private static final NodeId OWNER = NodeId.of(0);

  @Test
  void aMobRemovedWhileWaitingIsDroppedExactlyOnce() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH));
    script.add(new WorldEvent.MobRemoved(3, MOB));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(2, run.controller.requestsIssued(), run.describe());
    assertEquals(0, run.out.answered(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.MOB_GONE), run.describe());
  }

  @Test
  void aRequestForAnUnknownMobIsDropped() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.PathRequested(2, 99, MOB_CLASS, HERE, THERE, SEARCH));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.MOB_GONE), run.describe());
    assertEquals(0, run.out.answered(), run.describe());
  }

  @Test
  void aSecondRequestForOneMobSupersedesTheFirst() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH));
    script.add(new WorldEvent.PathRequested(3, MOB, MOB_CLASS, HERE, HERE, SEARCH));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(3, run.controller.requestsIssued(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.SUPERSEDED), run.describe());
    assertEquals(1, run.out.answered(), run.describe());
  }

  @Test
  void aRequestIsDroppedWhileNoNodeHoldsTheRegion() {
    // No chunks load, so no region can be snapshotted and no node can answer.
    List<WorldEvent> script = new ArrayList<>();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.NO_NODE), run.describe());
    for (ModeledNode node : run.sim.nodes()) {
      assertFalse(node.hasState(), run.describe());
    }
  }

  @Test
  void aBlockChangeBeforeTheSnapshotIsAbsorbedByIt() {
    // The change happens while the region is unreadable, so it must not be sent as
    // a delta. The snapshot that follows already contains it.
    List<WorldEvent> script = new ArrayList<>();
    script.add(new WorldEvent.BlockChanged(2, new Pos(4, 40, 4), (byte) PathType.WATER, 0));
    script.addAll(chunkLoads(5));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(0, run.sim.metrics().counter("offload.submitted", "job", "delta"), run.describe());
    for (ModeledNode node : run.sim.nodes()) {
      assertTrue(node.hasState(), run.describe());
      assertEquals(1, node.lastSequence(), run.describe());
    }
  }

  @Test
  void aCanceledRequestIsDroppedRatherThanAnswered() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH));
    script.add(new WorldEvent.PathCanceled(3, MOB));
    // A cancel for a mob with nothing outstanding changes nothing.
    script.add(new WorldEvent.PathCanceled(8, MOB));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(2, run.controller.requestsIssued(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.CANCELED), run.describe());
    assertEquals(0, run.out.answered(), run.describe());
  }

  /**
   * A chunk that leaves is treated as air on the node, as vanilla treats a chunk the server does
   * not hold, so only that chunk is resent. A search cannot start in it until it returns.
   */
  @Test
  void anUnloadedChunkIsClearedOnTheNodeAndSentAgainWhenItReturns() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH));
    script.add(new WorldEvent.ChunkUnloaded(6, Chunks.of(HERE.x()), Chunks.of(HERE.z())));
    script.add(new WorldEvent.PathRequested(8, MOB, MOB_CLASS, HERE, THERE, SEARCH));
    script.add(new WorldEvent.ChunkLoaded(10, Chunks.of(HERE.x()), Chunks.of(HERE.z())));
    script.add(new WorldEvent.PathRequested(14, MOB, MOB_CLASS, HERE, THERE, SEARCH));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(4, run.controller.requestsIssued(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.NO_NODE), run.describe());
    assertEquals(2, run.out.answered(), run.describe());
    // One frame to clear the chunk and one to fill it again, with no new snapshot.
    assertEquals(
        run.chunkCount() + 2,
        run.sim.metrics().counter("offload.submitted", "job", "chunk"),
        run.describe());
    assertEquals(
        run.regions.regionCount(), run.sim.metrics().total("world.resnapshot"), run.describe());
  }

  @Test
  void theFirstRequestNamingAProfileRegistersItAndIsGivenUp() {
    List<WorldEvent> script = new ArrayList<>(chunkLoads(0));
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH));
    script.add(new WorldEvent.PathRequested(6, MOB, MOB_CLASS, HERE, THERE, SEARCH));
    // A second profile costs its own request, because registration is per profile.
    script.add(new WorldEvent.PathRequested(10, MOB, OTHER_CLASS, HERE, THERE, SEARCH));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(3, run.controller.requestsIssued(), run.describe());
    assertEquals(2, run.out.dropped(DropReason.CLASS_UNREGISTERED), run.describe());
    assertEquals(1, run.out.answered(), run.describe());
    assertEquals(2, run.sim.metrics().total("mobclass.registered"), run.describe());
  }

  /** A profile the world cannot produce is a controller bug, not a node bug. */
  @Test
  void aRequestNamingAProfileTheWorldDoesNotHoldIsRefused() {
    List<WorldEvent> script = new ArrayList<>(chunkLoads(0));
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, (short) 99, HERE, THERE, SEARCH));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertTrue(
        run.violations().stream().anyMatch(v -> v.contains("which the world does not hold")),
        "an unproducible profile went unreported: " + run.violations());
    assertEquals(1, run.out.dropped(DropReason.CLASS_UNREGISTERED), run.describe());
  }

  /** Encoding such a target would fail on every retry, so the request is never sent. */
  @Test
  void aRequestTheWireCannotCarryIsDroppedBeforeItIsEncoded() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(
        new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, new Pos(40_000, 40, 4), SEARCH));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.NO_NODE), run.describe());
    assertEquals(0, run.sim.metrics().total("offload.retried"), run.describe());
  }

  @Test
  void aMalformedAnswerLeavesItsRequestOutstanding() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH));
    // Ends before the node serves the request.
    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), 3, script).go();
    assertEquals(1, run.controller.outstandingCount(), run.describe());
    NodeId owner = run.regions.owner(run.regions.regionAt(HERE.x(), HERE.z()));

    run.controller.onFrame(owner, Channel.REQUEST, answer(run.controller.run, FIRST_REQUEST, 200));
    // A datagram that is not a frame at all, as from a sender that is not a node.
    run.controller.onFrame(owner, Channel.REQUEST, ByteBuffer.wrap(new byte[] {9, 0, 0, 0, 1}));
    assertEquals(1, run.controller.outstandingCount(), run.describe());
    assertEquals(2, run.sim.metrics().total("controller.malformed.frame"), run.describe());

    // A well-formed answer from a node the request did not go to.
    NodeId other = run.regions.owner(run.regions.regionAt(THERE.x(), THERE.z()));
    run.controller.onFrame(
        other, Channel.REQUEST, answer(run.controller.run, FIRST_REQUEST, PathType.WALKABLE));
    assertEquals(1, run.controller.outstandingCount(), run.describe());
    assertEquals(1, run.sim.metrics().total("path.response.misrouted"), run.describe());

    run.controller.onFrame(
        owner, Channel.REQUEST, answer(run.controller.run, FIRST_REQUEST, PathType.WALKABLE));
    assertEquals(0, run.controller.outstandingCount(), run.describe());
    assertEquals(1, run.out.answered(), run.describe());
  }

  /** The node answered before the chunk the search starts in reached it, so it searched air. */
  @Test
  void anAnswerSearchedBeforeItsStartChunkArrivedIsDropped() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH));
    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), 3, script).go();
    NodeId owner = run.regions.owner(run.regions.regionAt(HERE.x(), HERE.z()));

    run.controller.onFrame(
        owner, Channel.REQUEST, answer(run.controller.run, FIRST_REQUEST, PathType.WALKABLE, 0));

    assertEquals(0, run.controller.outstandingCount(), run.describe());
    assertEquals(0, run.out.answered(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.NO_NODE), run.describe());
  }

  /**
   * The first request's batch is on its way when the mob asks again, so the node will still serve
   * it. It counts against the node until then, though the mob no longer hears about it.
   */
  @Test
  void aSupersededRequestCountsAgainstItsNodeUntilItIsAnswered() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH));
    script.add(new WorldEvent.PathRequested(3, MOB, MOB_CLASS, HERE, HERE, SEARCH));
    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), 4, script).go();
    NodeId owner = run.regions.owner(run.regions.regionAt(HERE.x(), HERE.z()));
    assertEquals(1, run.out.dropped(DropReason.SUPERSEDED), run.describe());
    assertEquals(2, run.controller.outstandingCount(), run.describe());

    run.controller.onFrame(
        owner, Channel.REQUEST, answer(run.controller.run, FIRST_REQUEST, PathType.WALKABLE));
    assertEquals(1, run.controller.outstandingCount(), run.describe());
    assertEquals(0, run.out.answered(), run.describe());

    run.controller.onFrame(
        owner, Channel.REQUEST, answer(run.controller.run, FIRST_REQUEST + 1, PathType.WALKABLE));
    assertEquals(0, run.controller.outstandingCount(), run.describe());
    assertEquals(1, run.out.answered(), run.describe());
    assertEquals(List.of(), run.controller.selfCheck(), run.describe());
  }

  /** A request canceled in the tick it was made never leaves, so it costs the node nothing. */
  @Test
  void aRequestCanceledBeforeItsBatchIsBuiltIsNeverSent() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH));
    script.add(new WorldEvent.PathCanceled(2, MOB));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.CANCELED), run.describe());
    assertEquals(
        0, run.sim.metrics().counter("offload.submitted", "job", "requests"), run.describe());
  }

  /** The mob no longer wants the older answer, whether or not its new request is admitted. */
  @Test
  void aRepeatRequestReplacesTheOutstandingOneEvenWhenItIsRefused() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(2, MOB, MOB_CLASS, HERE, THERE, SEARCH));
    script.add(
        new WorldEvent.PathRequested(3, MOB, MOB_CLASS, HERE, new Pos(40_000, 40, 4), SEARCH));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.out.dropped(DropReason.SUPERSEDED), run.describe());
    assertEquals(1, run.out.dropped(DropReason.NO_NODE), run.describe());
    assertEquals(0, run.out.answered(), run.describe());
  }

  /**
   * Admission keeps the node's answers within the budget: with one request served a tick, and the
   * first served two ticks after it is made, nine fit in ten ticks.
   */
  @Test
  void admissionLeavesRoomForTheTicksABatchTakesToLeave() {
    List<WorldEvent> script = prelude();
    int mobs = 20;
    for (int mob = 10; mob < 10 + mobs; mob++) {
      script.add(new WorldEvent.MobSpawned(0, mob, HERE));
      script.add(new WorldEvent.PathRequested(5, mob, MOB_CLASS, HERE, THERE, SEARCH));
    }
    // One worker serves exactly one request a tick, as admission assumes.
    Scenario oneAtATime =
        Scenario.DEFAULT
            .with(Scenario.Count.WORKER_CAPACITY, 1)
            .with(Scenario.Count.WORKERS, 1)
            .with(Scenario.Count.COST_SPREAD_PERCENT, 0);

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, oneAtATime, script).go();

    // Each admitted request is answered once the ones ahead of it and itself are served,
    // and the last within the budget.
    int admitted = Controller.MAX_TICKS_LATE - Controller.SEND_TICKS;
    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(admitted, run.out.answered(), run.describe());
    assertEquals(mobs - admitted, run.out.dropped(DropReason.QUEUE_DEPTH), run.describe());
    assertEquals(0, run.out.dropped(DropReason.TIMEOUT), run.describe());
  }

  /**
   * A tick of requests to one node leaves in several frames, each small enough for a datagram,
   * which the network may deliver in any order.
   */
  @Test
  void aTickOfRequestsLeavesInDatagramSizedFrames() {
    List<WorldEvent> script = prelude();
    int mobs = 2 * Frames.MAX_REQUESTS_PER_FRAME + 1;
    for (int mob = 10; mob < 10 + mobs; mob++) {
      script.add(new WorldEvent.MobSpawned(0, mob, HERE));
      script.add(new WorldEvent.PathRequested(3, mob, MOB_CLASS, HERE, THERE, SEARCH));
    }

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(mobs, run.out.answered(), run.describe());
    assertEquals(3, run.sim.metrics().total("requests.frames"), run.describe());
    // The frames may arrive in any order, so no request is sent again for waiting behind them.
    assertEquals(0, run.sim.metrics().total("path.resent"), run.describe());
    assertTrue(
        run.sim.metrics().percentile("frame.bytes", 1.0, "channel", Channel.REQUEST.name())
            <= Frames.MAX_REQUEST_FRAME_BYTES,
        run.describe());
  }

  /** A region with every chunk unloaded is all air on its node and on a fresh one alike. */
  @Test
  void aNodeWhoseChunksAreAllUnloadedMatchesAFreshOne() {
    List<WorldEvent> script = new ArrayList<>(chunkLoads(0));
    script.addAll(chunkUnloads(2));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertTrue(
        run.sim.nodes().stream().anyMatch(node -> Invariants.caughtUp(run.controller, node)),
        run.describe());
  }

  /**
   * The chunk leaves after the change, so the node is sent it as cleared. The change must not
   * follow as a delta, which would put the block back into a chunk the node holds as air.
   */
  @Test
  void aBlockChangedInAChunkThatLeavesInTheSameTickIsNotSentAfterIt() {
    List<WorldEvent> script = new ArrayList<>(chunkLoads(0));
    script.add(new WorldEvent.BlockChanged(3, HERE, (byte) PathType.WATER, 0));
    script.add(new WorldEvent.ChunkUnloaded(3, Chunks.of(HERE.x()), Chunks.of(HERE.z())));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(0, run.sim.metrics().counter("offload.submitted", "job", "delta"), run.describe());
    assertEquals(
        List.of(), Invariants.checkAgainstFreshSnapshot(run, run.sim.node(OWNER)), run.describe());
  }

  /**
   * The chunk was sent as cleared, so the node holds it as air. A change reported in it must not be
   * sent, which would put a block into a chunk the node holds as air.
   */
  @Test
  void aBlockChangedInAChunkSentAsClearedIsNotSent() {
    List<WorldEvent> script = new ArrayList<>(chunkLoads(0));
    script.add(new WorldEvent.ChunkUnloaded(2, Chunks.of(HERE.x()), Chunks.of(HERE.z())));
    script.add(new WorldEvent.BlockChanged(5, HERE, (byte) PathType.WATER, 0));

    SimRun run = SimRun.scripted(1L, FaultPlan.reliable(), TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(0, run.sim.metrics().counter("offload.submitted", "job", "delta"), run.describe());
    assertEquals(
        List.of(), Invariants.checkAgainstFreshSnapshot(run, run.sim.node(OWNER)), run.describe());
  }

  /**
   * The request leaves at a tick boundary that the partition covers, so it is lost, and a resend
   * after the partition is answered.
   */
  @Test
  void aLostRequestIsSentAgainAndAnswered() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(4, MOB, MOB_CLASS, HERE, THERE, SEARCH));
    FaultPlan plan =
        FaultPlan.reliable()
            .with(
                new FaultPlan.Partitioned(4, OWNER, true),
                new FaultPlan.Partitioned(6, OWNER, false));

    SimRun run = SimRun.scripted(1L, plan, TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(1, run.out.answered(), run.describe());
    assertTrue(run.sim.metrics().total("path.resent") > 0, run.describe());
    assertEquals(0, run.out.dropped(DropReason.TIMEOUT), run.describe());
  }

  @Test
  void aRequestIsSentAgainAFewTimesAtMostThenTimesOut() {
    List<WorldEvent> script = prelude();
    script.add(new WorldEvent.MobSpawned(0, MOB, HERE));
    script.add(new WorldEvent.PathRequested(4, MOB, MOB_CLASS, HERE, THERE, SEARCH));
    FaultPlan plan =
        FaultPlan.reliable()
            .with(
                new FaultPlan.Partitioned(4, OWNER, true),
                new FaultPlan.Partitioned(20, OWNER, false));

    SimRun run = SimRun.scripted(1L, plan, TICKS, script).go();

    assertEquals(List.of(), run.violations(), run.describe());
    assertEquals(Controller.MAX_RESENDS, run.sim.metrics().total("path.resent"));
    assertEquals(1, run.out.dropped(DropReason.TIMEOUT), run.describe());
    assertEquals(0, run.out.answered(), run.describe());
  }

  /**
   * Loads the world and registers {@code MOB_CLASS} with the node that owns it. Registration
   * abandons the request that triggers it, so every script spends one request on registration.
   */
  private static List<WorldEvent> prelude() {
    List<WorldEvent> script = new ArrayList<>(chunkLoads(0));
    script.add(new WorldEvent.MobSpawned(0, WARMUP_MOB, HERE));
    script.add(new WorldEvent.PathRequested(1, WARMUP_MOB, MOB_CLASS, HERE, THERE, SEARCH));
    script.add(new WorldEvent.MobRemoved(1, WARMUP_MOB));
    return script;
  }

  /** An answer to {@code requestId} whose one node has the type {@code type}. */
  private static ByteBuffer answer(long run, long requestId, int type) {
    return answer(run, requestId, type, 1);
  }

  /**
   * As above, searched at {@code worldSequence}. Each region of the default grid is one chunk,
   * which its first frame, sequence 1, carries.
   */
  private static ByteBuffer answer(long run, long requestId, int type, long worldSequence) {
    FlatBufferBuilder builder = new FlatBufferBuilder(128);
    PathResponse.startNodesVector(builder, 1);
    PathNode.createPathNode(builder, (short) HERE.x(), (short) HERE.y(), (short) HERE.z(), type);
    int nodes = builder.endVector();
    int body =
        PathResponse.createPathResponse(builder, requestId, true, nodes, 1.0f, worldSequence);
    builder.finish(ResponseFrame.createResponseFrame(builder, ResponseMsg.PathResponse, body, run));
    return ByteBuffer.wrap(builder.sizedByteArray());
  }

  private static List<WorldEvent> chunkUnloads(long tick) {
    List<WorldEvent> events = new ArrayList<>();
    for (WorldEvent loaded : chunkLoads(tick)) {
      WorldEvent.ChunkLoaded chunk = (WorldEvent.ChunkLoaded) loaded;
      events.add(new WorldEvent.ChunkUnloaded(tick, chunk.chunkX(), chunk.chunkZ()));
    }
    return events;
  }

  private static List<WorldEvent> chunkLoads(long tick) {
    int chunksPerSide = Topology.DEFAULT.chunksPerSide();
    List<WorldEvent> events = new ArrayList<>();
    for (int chunkX = 0; chunkX < chunksPerSide; chunkX++) {
      for (int chunkZ = 0; chunkZ < chunksPerSide; chunkZ++) {
        events.add(new WorldEvent.ChunkLoaded(tick, chunkX, chunkZ));
      }
    }
    return events;
  }
}
