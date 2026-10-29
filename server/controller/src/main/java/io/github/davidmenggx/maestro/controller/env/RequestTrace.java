package io.github.davidmenggx.maestro.controller.env;

import java.util.List;
import java.util.Objects;

/**
 * One sampled request, from its admission to the outcome its mob was told. Times are {@link
 * Clock#nowNanos} readings, or {@link #NEVER} for a stage the request did not reach: handed to the
 * encoder, its first copy sent, each resend, and the outcome. {@code outcome} is {@link #ANSWERED}
 * or the name of the reason it was dropped. {@code answer} is null if none arrived while the mob
 * waited.
 */
public record RequestTrace(
    long run,
    long requestId,
    int mobId,
    short classId,
    NodeId node,
    int regionId,
    boolean hunting,
    long askedTick,
    long endedTick,
    long askedNanos,
    long encodingNanos,
    long sentNanos,
    List<Long> resentNanos,
    Answer answer,
    long endedNanos,
    String outcome) {

  public static final long NEVER = -1;

  public static final String ANSWERED = "ANSWERED";

  /**
   * An answer the node sent: when the transport heard it, how long the request waited at the node
   * for a worker, how long its search took, and the world sequence it searched.
   */
  public record Answer(long arrivedNanos, long waitMicros, long searchMicros, long worldSequence) {}

  public RequestTrace {
    Objects.requireNonNull(node, "node");
    Objects.requireNonNull(outcome, "outcome");
    resentNanos = List.copyOf(resentNanos);
  }
}
