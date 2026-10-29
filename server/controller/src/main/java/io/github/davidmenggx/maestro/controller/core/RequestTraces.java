package io.github.davidmenggx.maestro.controller.core;

import io.github.davidmenggx.maestro.controller.env.ControllerEnv;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.RequestTrace;
import io.github.davidmenggx.maestro.controller.env.SplitMix64;
import io.github.davidmenggx.maestro.controller.wire.Reply;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Follows sampled requests from admission to outcome and reports each to the env's tracing. A
 * request is sampled by a hash of the run and its id, so the rate changes nothing else the
 * controller does.
 *
 * <p>A finished request is reported at the next {@link #flush}, after a hand-over: a streamed
 * request's answer can be handed over before the send it answers, whose time comes with it. One
 * answered with no send yet waits one flush more, as its send can come a hand-over late.
 */
final class RequestTraces {

  private static final long NEVER = RequestTrace.NEVER;

  /** A sampled request's stages so far. {@code outcome} is null until its mob is told. */
  private static final class Stages {

    private final int mobId;
    private final short classId;
    private final NodeId node;
    private final int regionId;
    private final boolean hunting;
    private final long askedTick;
    private final long askedNanos;
    private long encodingNanos = NEVER;
    private long sentNanos = NEVER;
    private final List<Long> resentNanos = new ArrayList<>();
    private RequestTrace.Answer answer;
    private long endedTick;
    private long endedNanos = NEVER;
    private String outcome;
    private boolean heldBack;

    private Stages(
        int mobId,
        short classId,
        NodeId node,
        int regionId,
        boolean hunting,
        long askedTick,
        long askedNanos) {
      this.mobId = mobId;
      this.classId = classId;
      this.node = node;
      this.regionId = regionId;
      this.hunting = hunting;
      this.askedTick = askedTick;
      this.askedNanos = askedNanos;
    }
  }

  private final ControllerEnv env;
  private final double rate;

  /** Sampled requests by id, open or finished but not yet reported. */
  private final Map<Long, Stages> traced = new LinkedHashMap<>();

  /** Finished requests not yet reported, in the order they finished. */
  private final List<Long> finished = new ArrayList<>();

  RequestTraces(ControllerEnv env, double rate) {
    this.env = env;
    this.rate = rate;
  }

  /** Whether request {@code requestId} of run {@code run} is traced at {@code rate}, 0 to 1. */
  static boolean sampled(long run, long requestId, double rate) {
    if (rate >= 1) {
      return true;
    }
    if (!(rate > 0)) {
      return false;
    }
    // The top 53 bits as a fraction in [0, 1).
    return (SplitMix64.mix(SplitMix64.mix(run) ^ requestId) >>> 11) * 0x1.0p-53 < rate;
  }

  /** A request was admitted for {@code node}. */
  void admitted(
      long requestId,
      int mobId,
      short classId,
      NodeId node,
      int regionId,
      boolean hunting,
      long tick) {
    if (sampled(env.run(), requestId, rate)) {
      traced.put(
          requestId,
          new Stages(mobId, classId, node, regionId, hunting, tick, env.clock().nowNanos()));
    }
  }

  /** The request's first copy was handed to the encoder. */
  void encoding(long requestId) {
    Stages stages = traced.get(requestId);
    if (stages != null && stages.encodingNanos == NEVER) {
      stages.encodingNanos = env.clock().nowNanos();
    }
  }

  /** A copy of the request left at {@code nanos}. One that left after its outcome is ignored. */
  void sent(long requestId, long nanos) {
    Stages stages = traced.get(requestId);
    if (stages == null || (stages.outcome != null && nanos > stages.endedNanos)) {
      return;
    }
    if (stages.sentNanos == NEVER) {
      stages.sentNanos = nanos;
    } else {
      stages.resentNanos.add(nanos);
    }
  }

  /** The node answered the request while its mob waited. */
  void answered(long requestId, Reply.Answer answer, long arrivedNanos) {
    Stages stages = traced.get(requestId);
    if (stages != null && stages.outcome == null) {
      stages.answer =
          new RequestTrace.Answer(
              arrivedNanos, answer.waitMicros(), answer.searchMicros(), answer.worldSequence());
    }
  }

  /** The request's mob was told {@code outcome}. */
  void ended(long requestId, String outcome) {
    Stages stages = traced.get(requestId);
    if (stages == null || stages.outcome != null) {
      return;
    }
    stages.outcome = outcome;
    stages.endedTick = env.clock().tick();
    stages.endedNanos = env.clock().nowNanos();
    finished.add(requestId);
  }

  /**
   * Reports the requests finished since the last call, in the order they finished. One answered
   * with no send yet is held back to the next call, once.
   */
  void flush() {
    List<Long> held = new ArrayList<>();
    for (long requestId : finished) {
      Stages stages = traced.get(requestId);
      if (stages.answer != null && stages.sentNanos == NEVER && !stages.heldBack) {
        stages.heldBack = true;
        held.add(requestId);
        continue;
      }
      traced.remove(requestId);
      env.tracing()
          .finished(
              new RequestTrace(
                  env.run(),
                  requestId,
                  stages.mobId,
                  stages.classId,
                  stages.node,
                  stages.regionId,
                  stages.hunting,
                  stages.askedTick,
                  stages.endedTick,
                  stages.askedNanos,
                  stages.encodingNanos,
                  stages.sentNanos,
                  stages.resentNanos,
                  stages.answer,
                  stages.endedNanos,
                  stages.outcome));
    }
    finished.clear();
    finished.addAll(held);
  }

  /** Answered requests not yet reported, which are lost if the controller stops now. */
  int answeredUnreported() {
    int count = 0;
    for (long requestId : finished) {
      count += traced.get(requestId).outcome.equals(RequestTrace.ANSWERED) ? 1 : 0;
    }
    return count;
  }

  /** Every open trace is of a live request, so none is left unreported. */
  void check(Set<Long> live, List<String> found) {
    for (Map.Entry<Long, Stages> entry : traced.entrySet()) {
      if (entry.getValue().outcome == null && !live.contains(entry.getKey())) {
        found.add("request " + entry.getKey() + " is traced but no longer live");
      }
    }
  }
}
