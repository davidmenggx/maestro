package io.github.davidmenggx.maestro.controller.sim;

import io.github.davidmenggx.maestro.controller.core.Encoder;
import io.github.davidmenggx.maestro.controller.core.NodeHealth;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks that a node which cannot answer stops being sent requests, so its mobs are searched on the
 * server instead. Judged from what the controller puts on the wire, against when the node truly
 * could not answer.
 *
 * <p>An episode is one stretch in which a node cannot answer, within one controller run. Once
 * earlier answers have arrived, the controller may send only the requests that show the path is
 * quiet, those admitted before it noticed, and then probes, each after a failed probe and a
 * doubling backoff. A request shows the path is quiet once its first copy fell due unanswered,
 * which its resend shows on the wire: {@link NodeHealth#QUIET_REQUESTS} of them first sent within
 * {@link NodeHealth#QUIET_WINDOW_TICKS}, the oldest {@link NodeHealth#QUIET_TICKS} old. A request
 * that is never resent is not counted, so the bound errs late. A link heard going down takes its
 * requests with it, so only requests sent since show anything.
 */
final class FailOpenCheck {

  /** Ticks after an episode starts by which every answer sent before it has been heard. */
  static final int STRAY_ANSWER_TICKS = 2;

  /** Ticks a request admitted before the controller noticed may take to leave: its encode tries. */
  static final int LATE_SEND_TICKS = Encoder.MAX_ATTEMPTS + 1;

  private record Sent(long run, NodeId node, long requestId) {}

  /**
   * A request first sent at {@code firstSent} whose first copy fell due unanswered, as its resend
   * at {@code resent} shows. The controller saw it fall due by then.
   */
  record Missed(long firstSent, long resent) {}

  /**
   * Ticks at which requests were first sent to the node, its requests that fell due, and ticks at
   * which its link was heard down.
   */
  private static final class Episode {
    final long run;
    final long start;
    final List<Long> firstSends = new ArrayList<>();
    final List<Missed> missed = new ArrayList<>();
    final List<Long> linksLost = new ArrayList<>();

    Episode(long run, long start) {
      this.run = run;
      this.start = start;
    }
  }

  private final Map<NodeId, Episode> open = new LinkedHashMap<>();

  /** When each request was first sent. */
  private final Map<Sent, Long> firstSent = new LinkedHashMap<>();

  /** Requests sent again at least once. */
  private final Set<Sent> resent = new LinkedHashSet<>();

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

  /** A new controller process started; each open episode starts over for it. */
  void restarted(long run, long tick) {
    for (Map.Entry<NodeId, Episode> entry : List.copyOf(open.entrySet())) {
      judge(entry.getKey(), entry.getValue(), tick);
      open.put(entry.getKey(), new Episode(run, tick));
    }
  }

  /**
   * A request copy left for the node. Its first copy counts as a new request, and its second shows
   * that the first fell due unanswered.
   */
  void requestSent(NodeId node, long run, long requestId, long tick) {
    Sent request = new Sent(run, node, requestId);
    Long first = firstSent.putIfAbsent(request, tick);
    Episode episode = open.get(node);
    boolean counted = episode != null && episode.run == run;
    if (first == null) {
      if (counted) {
        episode.firstSends.add(tick);
      }
    } else if (resent.add(request) && counted) {
      episode.missed.add(new Missed(first, tick));
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
   * Probes a controller may send in {@code ticks} ticks: the first at any time, each later one
   * after the last failed and a backoff twice the last has passed, less its encode delay.
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
   * The tick by which the controller noticed a quiet path from the requests that fell due in {@code
   * blind}, or {@link Long#MAX_VALUE} if it could not have. It notices once it has seen {@link
   * NodeHealth#QUIET_REQUESTS} fall due, by their resends, the oldest is {@link
   * NodeHealth#QUIET_TICKS} old, and none is past the window yet.
   */
  static long noticed(List<Missed> blind) {
    List<Missed> byFirstSend = new ArrayList<>(blind);
    byFirstSend.sort(Comparator.comparingLong(Missed::firstSent));
    int last = NodeHealth.QUIET_REQUESTS - 1;
    long noticed = Long.MAX_VALUE;
    for (int i = 0; i + last < byFirstSend.size(); i++) {
      long oldest = byFirstSend.get(i).firstSent();
      long at = oldest + NodeHealth.QUIET_TICKS;
      for (int j = i; j <= i + last; j++) {
        at = Math.max(at, byFirstSend.get(j).resent());
      }
      if (at - oldest <= NodeHealth.QUIET_WINDOW_TICKS) {
        noticed = Math.min(noticed, at + LATE_SEND_TICKS);
      }
    }
    return noticed;
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
    long blindFrom = episode.start + STRAY_ANSWER_TICKS;
    List<Long> blind = new ArrayList<>();
    for (long tick : episode.firstSends) {
      if (tick >= blindFrom) {
        blind.add(tick);
      }
    }
    List<Missed> missed = new ArrayList<>();
    for (Missed request : episode.missed) {
      if (request.firstSent() >= blindFrom) {
        missed.add(request);
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
      long onLink =
          noticed(
              missed.stream()
                  .filter(request -> request.firstSent() > after && request.resent() <= until)
                  .toList());
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
