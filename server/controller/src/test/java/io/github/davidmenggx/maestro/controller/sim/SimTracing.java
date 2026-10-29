package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.env.RequestTrace;
import io.github.davidmenggx.maestro.controller.env.Tracing;
import io.github.davidmenggx.maestro.controller.world.WorldOutput.DropReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Records the traces of every controller process in a run, and checks each against the run. */
public final class SimTracing implements Tracing {

  private static final long NEVER = RequestTrace.NEVER;

  private final List<RequestTrace> traces = new ArrayList<>();

  @Override
  public void finished(RequestTrace trace) {
    traces.add(trace);
  }

  public List<RequestTrace> traces() {
    return List.copyOf(traces);
  }

  /**
   * Each trace is reported once, its stages are in time order, and its mob was told its outcome at
   * the tick it ended. With every request traced, every answer has a trace but the {@code lost}
   * ones a controller still held back when it restarted.
   */
  public List<String> check(RecordingOutput out, boolean everyRequest, int lost) {
    List<String> violations = new ArrayList<>();
    Set<String> seen = new TreeSet<>();
    Map<String, Integer> told = new TreeMap<>();
    for (RecordingOutput.Told outcome : out.told()) {
      told.merge(key(outcome.tick(), outcome.mobId(), outcome.outcome()), 1, Integer::sum);
    }
    int answered = 0;
    for (RequestTrace trace : traces) {
      String name = "run " + Long.toHexString(trace.run()) + " request " + trace.requestId();
      if (!seen.add(name)) {
        violations.add(name + " was traced twice");
      }
      String order = order(trace);
      if (order != null) {
        violations.add(name + " " + order + ": " + trace);
      }
      boolean answer = trace.answer() != null;
      boolean wasAnswered = trace.outcome().equals(RequestTrace.ANSWERED);
      answered += wasAnswered ? 1 : 0;
      // Only an answer the controller turned down for its world sequence ends a request without
      // being its outcome.
      if (wasAnswered ? !answer : answer && !trace.outcome().equals(DropReason.NO_NODE.name())) {
        violations.add(name + " ended " + trace.outcome() + " with answer " + trace.answer());
      }
      String key = key(trace.endedTick(), trace.mobId(), trace.outcome());
      if (told.merge(key, -1, Integer::sum) < 0) {
        violations.add(
            name
                + " ended "
                + trace.outcome()
                + " at tick "
                + trace.endedTick()
                + ", which mob "
                + trace.mobId()
                + " was not told then");
      }
    }
    if (everyRequest && answered + lost != out.answered()) {
      violations.add(
          answered
              + " answered requests were traced and "
              + lost
              + " lost to a restart, but "
              + out.answered()
              + " were answered");
    }
    return violations;
  }

  /** What is out of order in the trace's stages, or null. */
  private static String order(RequestTrace trace) {
    if (trace.askedNanos() == NEVER || trace.endedNanos() == NEVER) {
      return "has no ask or end";
    }
    if (trace.askedTick() > trace.endedTick()) {
      return "ended before it was asked";
    }
    if (trace.sentNanos() != NEVER && trace.encodingNanos() == NEVER) {
      return "was sent without being encoded";
    }
    if (!trace.resentNanos().isEmpty() && trace.sentNanos() == NEVER) {
      return "was resent without being sent";
    }
    List<Long> stages = new ArrayList<>();
    stages.add(trace.askedNanos());
    for (long stage : List.of(trace.encodingNanos(), trace.sentNanos())) {
      if (stage != NEVER) {
        stages.add(stage);
      }
    }
    stages.addAll(trace.resentNanos());
    stages.add(trace.endedNanos());
    for (int i = 1; i < stages.size(); i++) {
      if (stages.get(i) < stages.get(i - 1)) {
        return "has stages out of order";
      }
    }
    RequestTrace.Answer answer = trace.answer();
    if (answer != null
        && (trace.sentNanos() == NEVER
            || answer.arrivedNanos() < trace.sentNanos()
            || answer.arrivedNanos() > trace.endedNanos())) {
      return "has an answer that arrived outside its send and end";
    }
    return null;
  }

  private static String key(long tick, int mobId, String outcome) {
    return tick + "/" + mobId + "/" + outcome;
  }
}
