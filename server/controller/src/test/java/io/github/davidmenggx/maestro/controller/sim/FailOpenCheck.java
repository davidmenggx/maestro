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
 * NodeHealth#QUIET_REQUESTS} first sent and the oldest of them {@link NodeHealth#QUIET_TICKS} old,
 * and the requests it admitted before it noticed, whose encodes may take a few retries to leave.
 * After that it may send only probes, at most one per {@link NodeHealth#FIRST_BACKOFF_TICKS}.
 */
final class FailOpenCheck {

  /** Ticks after an episode starts by which every answer sent before it has been heard. */
  static final int STRAY_ANSWER_TICKS = 2;

  /** Ticks a request admitted before the controller noticed may take to leave: its encode tries. */
  static final int LATE_SEND_TICKS = Encoder.MAX_ATTEMPTS + 1;

  private record Sent(long run, NodeId node, long requestId) {}

  /** Ticks at which requests were first sent to the node during the episode. */
  private record Episode(long run, long start, List<Long> firstSends) {}

  private final Map<NodeId, Episode> open = new LinkedHashMap<>();
  private final Set<Sent> sent = new LinkedHashSet<>();
  private final List<String> violations = new ArrayList<>();
  private long judged;

  /** The node started or stopped being able to answer. */
  void unresponsive(NodeId node, long run, long tick, boolean active) {
    if (active) {
      open.put(node, new Episode(run, tick, new ArrayList<>()));
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
      open.put(entry.getKey(), new Episode(run, tick, new ArrayList<>()));
    }
  }

  /** A request copy left for the node. Only its first copy counts. */
  void requestSent(NodeId node, long run, long requestId, long tick) {
    if (!sent.add(new Sent(run, node, requestId))) {
      return;
    }
    Episode episode = open.get(node);
    if (episode != null && episode.run() == run) {
      episode.firstSends().add(tick);
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
    for (long tick : episode.firstSends()) {
      if (tick >= episode.start() + STRAY_ANSWER_TICKS) {
        blind.add(tick);
      }
    }
    if (blind.size() < NodeHealth.QUIET_REQUESTS) {
      return false;
    }
    // By here the controller has seen enough requests go unanswered for long enough.
    long noticed =
        Math.max(
                blind.get(0) + NodeHealth.QUIET_TICKS, blind.get(NodeHealth.QUIET_REQUESTS - 1) + 1)
            + LATE_SEND_TICKS;
    long late = blind.stream().filter(tick -> tick > noticed).count();
    long probes = end <= noticed ? 0 : 1 + (end - noticed) / NodeHealth.FIRST_BACKOFF_TICKS;
    if (late > probes) {
      found.add(
          node
              + " could not answer from tick "
              + episode.start()
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
