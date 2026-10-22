package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.core.Encoder;
import io.github.davidmenggx.maestro.controller.core.NodeHealth;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks that a node which cannot answer stops being sent requests, so the mobs it would serve are
 * searched on the server instead of waiting on it again and again. This is judged from what the
 * controller puts on the wire, against when the node truly could not answer, which the controller
 * cannot see.
 *
 * <p>An episode is one stretch in which a node cannot answer, within one controller run. Once the
 * answers that were already on their way have arrived, the controller hears nothing more from the
 * node's request path. It may send the requests that show the path is quiet, {@link
 * NodeHealth#QUIET_REQUESTS} first sent within {@link NodeHealth#QUIET_WINDOW_TICKS} and the oldest
 * of them {@link NodeHealth#QUIET_TICKS} old, and the requests it admitted before it noticed, whose
 * encodes may take a few retries to leave. After that it may send only probes, each after a failed
 * probe and a backoff that doubles each time. A link the controller hears go down takes the
 * requests sent on it with it, and the node forgets them, so unless the controller had noticed by
 * then, only the requests sent since show anything.
 */
final class FailOpenCheck {

  /** Ticks after an episode starts by which every answer sent before it has been heard. */
  static final int STRAY_ANSWER_TICKS = 2;

  /** Ticks a request admitted before the controller noticed may take to leave: its encode tries. */
  static final int LATE_SEND_TICKS = Encoder.MAX_ATTEMPTS + 1;

  private record Sent(long run, NodeId node, long requestId) {}

  /**
   * Ticks at which requests were first sent to the node during the episode, and at which the
   * controller heard the node's link go down.
   */
  private static final class Episode {
    final long run;
    final long start;
    final List<Long> firstSends = new ArrayList<>();
    final List<Long> linksLost = new ArrayList<>();

    Episode(long run, long start) {
      this.run = run;
      this.start = start;
    }
  }

  private final Map<NodeId, Episode> open = new LinkedHashMap<>();
  private final Set<Sent> sent = new LinkedHashSet<>();
  private final List<String> violations = new ArrayList<>();
  private long judged;

  /** The node started or stopped being able to answer. */
  void unresponsive(NodeId node, long run, long tick, boolean active) {
    if (active) {
      open.put(node, new Episode(run, tick));
      return;
    }
    Episode episode = open.remove(node);
    if (episode != null) {
      judge(node, episode, tick);
    }
  }

  /**
   * A new controller process started, which knows nothing of the old one's view of the fleet: each
   * episode still open starts over for it.
   */
  void restarted(long run, long tick) {
    for (Map.Entry<NodeId, Episode> entry : List.copyOf(open.entrySet())) {
      judge(entry.getKey(), entry.getValue(), tick);
      open.put(entry.getKey(), new Episode(run, tick));
    }
  }

  /** A request copy left for the node. Only its first copy counts. */
  void requestSent(NodeId node, long run, long requestId, long tick) {
    if (!sent.add(new Sent(run, node, requestId))) {
      return;
    }
    Episode episode = open.get(node);
    if (episode != null && episode.run == run) {
      episode.firstSends.add(tick);
    }
  }

  /** The controller heard the node's link go down at {@code tick}. */
  void linkLost(NodeId node, long run, long tick) {
    Episode episode = open.get(node);
    if (episode != null && episode.run == run) {
      episode.linksLost.add(tick);
    }
  }

  /** Every violation, with the episodes still open judged as of {@code tick}. */
  List<String> violations(long tick) {
    List<String> all = new ArrayList<>(violations);
    for (Map.Entry<NodeId, Episode> entry : open.entrySet()) {
      judge(entry.getKey(), entry.getValue(), tick, all);
    }
    return all;
  }

  /** Episodes that ended after the controller sent enough to notice, so the bound applied. */
  long judged() {
    return judged;
  }

  /**
   * Probes a controller may send in {@code ticks} ticks: the first at any time, and each later one
   * once the last has failed and a backoff twice the last has passed, less the ticks the last may
   * have taken to leave.
   */
  static long probesAllowed(long ticks) {
    if (ticks < 0) {
      return 0;
    }
    long probes = 1;
    long backoff = NodeHealth.FIRST_BACKOFF_TICKS;
    long at = 0;
    while (true) {
      backoff = Math.min(2 * backoff, NodeHealth.MAX_BACKOFF_TICKS);
      at += NodeHealth.PROBE_TICKS + 1 + backoff - LATE_SEND_TICKS;
      if (at > ticks) {
        return probes;
      }
      probes++;
    }
  }

  /**
   * The tick by which the controller noticed a quiet path from requests first sent at {@code
   * blind}, or {@link Long#MAX_VALUE} if it could not have: the first ones close enough together
   * that it kept all of them, once the oldest has gone unanswered long enough and those admitted
   * before have had their encode tries.
   */
  static long noticed(List<Long> blind) {
    int last = NodeHealth.QUIET_REQUESTS - 1;
    for (int i = 0; i + last < blind.size(); i++) {
      if (blind.get(i + last) - blind.get(i) < NodeHealth.QUIET_WINDOW_TICKS) {
        return Math.max(blind.get(i) + NodeHealth.QUIET_TICKS, blind.get(i + last) + 1)
            + LATE_SEND_TICKS;
      }
    }
    return Long.MAX_VALUE;
  }

  private void judge(NodeId node, Episode episode, long end) {
    if (judge(node, episode, end, violations)) {
      judged++;
    }
  }

  /**
   * Adds the episode's violation to {@code found}, if any, and returns whether the bound applied.
   */
  private static boolean judge(NodeId node, Episode episode, long end, List<String> found) {
    List<Long> blind = new ArrayList<>();
    for (long tick : episode.firstSends) {
      if (tick >= episode.start + STRAY_ANSWER_TICKS) {
        blind.add(tick);
      }
    }
    // Requests sent on each link, up to the tick its loss was heard, show the quiet only to that
    // link. The first link on which the controller must have noticed decides.
    long noticed = Long.MAX_VALUE;
    long from = Long.MIN_VALUE;
    List<Long> ends = new ArrayList<>(episode.linksLost);
    ends.add(Long.MAX_VALUE);
    for (long until : ends) {
      long after = from;
      long onLink = noticed(blind.stream().filter(tick -> tick > after && tick <= until).toList());
      if (onLink <= until) {
        noticed = onLink;
        break;
      }
      from = until;
    }
    if (noticed == Long.MAX_VALUE) {
      return false;
    }
    long settled = noticed;
    long late = blind.stream().filter(tick -> tick > settled).count();
    long probes = end <= noticed ? 0 : probesAllowed(end - noticed);
    if (late > probes) {
      found.add(
          node
              + " could not answer from tick "
              + episode.start
              + " to "
              + end
              + " but was sent "
              + late
              + " new requests after tick "
              + noticed
              + ", where probes allow "
              + probes);
    }
    return true;
  }
}
