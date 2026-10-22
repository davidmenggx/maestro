package io.github.davidmenggx.maestro.controller.core;

import io.github.davidmenggx.maestro.controller.env.Metrics;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which nodes may be sent requests. Two signals take a node out of service, and either alone is
 * enough:
 *
 * <ul>
 *   <li>Its heartbeats are overdue, as a phi accrual detector over their arrival times judges. This
 *       finds a node that froze or was cut off, with or without requests in flight.
 *   <li>Its request path went quiet: requests left for it and none was answered within the tick
 *       budget. This finds a request stream the link does not share, such as datagrams that are
 *       blocked or answers that never come back, and a node that answers too late to be of use.
 * </ul>
 *
 * A node out of service is sent no requests, so its mobs fall back to the server. Heartbeats bring
 * a suspected node back by themselves. A quiet request path is tried again with one probe request
 * after a backoff that doubles with each failed probe, and any answer in time ends the quiet.
 *
 * <p>The detector reads the time the transport heard each heartbeat, on the controller's clock, so
 * a server stall between hand-overs does not stretch the intervals it learns. Phi is computed with
 * {@code StrictMath}, so a run replays the same on every platform.
 */
public final class NodeHealth {

  /** Phi at which a node is suspected: about one false suspicion in 10^8 heartbeats. */
  public static final double PHI_THRESHOLD = 8.0;

  /** Heartbeat intervals the detector keeps. At one a second, the last 100 s. */
  static final int MAX_SAMPLES = 100;

  /**
   * The heartbeat interval assumed until intervals are seen. The node sets its own, 1 s, and the
   * detector learns it from arrivals.
   */
  public static final long FIRST_INTERVAL_NANOS = 1_000_000_000L;

  /**
   * Floor of the interval spread, so a node whose heartbeats have been regular is not suspected for
   * a little jitter.
   */
  public static final long MIN_DEVIATION_NANOS = 200_000_000L;

  /** A pause added to the expected interval, such as a collection pause on the node. */
  public static final long ACCEPTABLE_PAUSE_NANOS = 1_000_000_000L;

  /**
   * Requests first sent since the last answer in time before a quiet request path takes its node
   * out of service. One lost request is not enough, so the loss of one request and its resends to a
   * lossy network does not.
   */
  public static final int QUIET_REQUESTS = 2;

  /**
   * Ticks since the oldest of those requests left before the path counts as quiet: past the tick
   * budget, by which a working node has answered.
   */
  public static final int QUIET_TICKS = RequestTracker.MAX_TICKS_LATE + 1;

  /**
   * Ticks an unanswered request counts toward a quiet path. One whose answer was lost longer ago
   * says little about the path now, so the next request is given its own time.
   */
  public static final int QUIET_WINDOW_TICKS = 2 * QUIET_TICKS;

  /** Ticks a quiet request path waits before its first probe. Each failed probe doubles it. */
  public static final int FIRST_BACKOFF_TICKS = 20;

  public static final int MAX_BACKOFF_TICKS = 160;

  /** Ticks a probe has to be answered: sent the next tick, then the tick budget. */
  public static final int PROBE_TICKS = RequestTracker.SEND_TICKS + RequestTracker.MAX_TICKS_LATE;

  private static final long NONE = Long.MIN_VALUE;

  /** The request path's circuit. */
  enum Circuit {
    /** Requests flow. */
    CLOSED,
    /** Quiet: no requests until the backoff ends. */
    OPEN,
    /** The backoff ended: the next request admitted is the probe. */
    HALF_OPEN,
    /** A probe is out, and nothing else is sent until it is answered or its time runs out. */
    PROBING
  }

  private static final class Health {

    // The heartbeat detector, reset with each link.
    private final Deque<Long> intervals = new ArrayDeque<>();
    private long lastArrival = NONE;
    private boolean suspected;

    // The request path, which outlives links: datagrams do not travel on them.
    private Circuit circuit = Circuit.CLOSED;

    /**
     * Ticks at which requests were first sent since the last answer in time, the first {@link
     * #QUIET_REQUESTS} of them. They belong to the link they were sent on, which forgets them.
     */
    private final List<Long> quietSends = new ArrayList<>(QUIET_REQUESTS);

    private long reopensAt;
    private long probeTick;
    private long probeRequest;
    private int backoff = FIRST_BACKOFF_TICKS;

    /** Whether the node was in service at the last {@link NodeHealth#evaluate}, or null before. */
    private Boolean inService;

    private void record(long interval) {
      intervals.addLast(interval);
      if (intervals.size() > MAX_SAMPLES) {
        intervals.removeFirst();
      }
    }

    private void restart(long arrived) {
      intervals.clear();
      // As if two heartbeats had come a quarter of the assumed interval either side of it.
      long spread = FIRST_INTERVAL_NANOS / 4;
      record(FIRST_INTERVAL_NANOS - spread);
      record(FIRST_INTERVAL_NANOS + spread);
      lastArrival = arrived;
      suspected = false;
    }

    private double phi(long now) {
      if (lastArrival == NONE) {
        return 0;
      }
      // A hundred samples at most, so summing them each time costs little and never drifts.
      double sum = 0;
      for (long interval : intervals) {
        sum += interval;
      }
      double mean = sum / intervals.size();
      double squares = 0;
      for (long interval : intervals) {
        squares += (interval - mean) * (interval - mean);
      }
      double deviation = Math.max(StrictMath.sqrt(squares / intervals.size()), MIN_DEVIATION_NANOS);
      return NodeHealth.phi(now - lastArrival, mean + ACCEPTABLE_PAUSE_NANOS, deviation);
    }
  }

  private final Metrics metrics;

  /** In fleet order, so evaluation order never depends on arrival order. */
  private final Map<NodeId, Health> nodes = new LinkedHashMap<>();

  NodeHealth(Metrics metrics, List<NodeId> fleet) {
    this.metrics = metrics;
    for (NodeId node : fleet) {
      nodes.put(node, new Health());
    }
  }

  /**
   * Phi of a heartbeat {@code elapsed} after the last, for arrivals whose intervals have {@code
   * mean} and {@code deviation}: minus the log10 of the chance that the next one is still to come,
   * from a logistic approximation of the normal distribution. Infinite once that chance rounds to
   * zero.
   */
  public static double phi(double elapsed, double mean, double deviation) {
    double y = (elapsed - mean) / deviation;
    double e = StrictMath.exp(-y * (1.5976 + 0.070566 * y * y));
    double later = elapsed > mean ? e / (1.0 + e) : 1.0 - 1.0 / (1.0 + e);
    return -StrictMath.log10(later);
  }

  /**
   * The node greeted the controller on a new link, which starts its heartbeat history. {@code
   * arrived} is when the transport heard the greeting.
   */
  void greeted(NodeId node, long arrived) {
    health(node).restart(arrived);
  }

  /** A heartbeat the transport heard at {@code arrived}. */
  void heartbeat(NodeId node, long arrived) {
    Health health = health(node);
    if (health.lastArrival == NONE || arrived <= health.lastArrival) {
      return;
    }
    health.record(arrived - health.lastArrival);
    health.lastArrival = arrived;
  }

  /**
   * The link is gone, and its heartbeats with it, and the requests the node forgot with it. Its
   * circuit stays as it is.
   */
  void disconnected(NodeId node) {
    Health health = health(node);
    health.lastArrival = NONE;
    health.suspected = false;
    health.quietSends.clear();
  }

  /** Requests left for the node: {@code first} of them for the first time. */
  void sent(NodeId node, long tick, int first) {
    List<Long> sends = health(node).quietSends;
    for (int i = 0; i < first && sends.size() < QUIET_REQUESTS; i++) {
      sends.add(tick);
    }
  }

  /**
   * The node answered a request it was sent in time, so its request path works. A late answer, to a
   * request the controller already gave up on, does not count: a node that only answers late is no
   * use.
   */
  void answered(NodeId node) {
    Health health = health(node);
    health.quietSends.clear();
    if (health.circuit != Circuit.CLOSED) {
      health.circuit = Circuit.CLOSED;
      health.backoff = FIRST_BACKOFF_TICKS;
      metrics.count("node.circuit", 1, "node", node.toString(), "state", "closed");
    }
  }

  /** Whether the node may be sent a new request now, if it is linked. */
  boolean admits(NodeId node) {
    Health health = health(node);
    return !health.suspected
        && (health.circuit == Circuit.CLOSED || health.circuit == Circuit.HALF_OPEN);
  }

  /**
   * Whether the requests the node holds may still be answered: it is not suspected, and its request
   * path is not quiet. A probe out keeps it in service, so the probe is not dropped.
   */
  private static boolean inService(Health health) {
    return !health.suspected && health.circuit != Circuit.OPEN;
  }

  /** A request was admitted for the node. While it is half open, that request is its probe. */
  void admitted(NodeId node, long requestId, long tick) {
    Health health = health(node);
    if (health.circuit == Circuit.HALF_OPEN) {
      health.circuit = Circuit.PROBING;
      health.probeTick = tick;
      health.probeRequest = requestId;
      metrics.count("node.circuit", 1, "node", node.toString(), "state", "probing");
    }
  }

  /**
   * A request was given up on before it left. If it was the probe, the node was never tried, so the
   * next request admitted is the probe instead.
   */
  void withdrawn(NodeId node, long requestId) {
    Health health = health(node);
    if (health.circuit == Circuit.PROBING && health.probeRequest == requestId) {
      health.circuit = Circuit.HALF_OPEN;
      metrics.count("node.circuit", 1, "node", node.toString(), "state", "half_open");
    }
  }

  /**
   * Judges every node at the start of a tick, after what arrived before it was handed over, and
   * returns those that just went out of service, whose live requests are then dropped.
   */
  List<NodeId> evaluate(long tick, long now) {
    List<NodeId> lost = new ArrayList<>();
    for (Map.Entry<NodeId, Health> entry : nodes.entrySet()) {
      NodeId node = entry.getKey();
      Health health = entry.getValue();
      String label = node.toString();
      if (health.lastArrival != NONE) {
        double phi = health.phi(now);
        metrics.gauge("node.phi", phi, "node", label);
        boolean suspected = phi >= PHI_THRESHOLD;
        if (suspected && !health.suspected) {
          metrics.count("node.suspected", 1, "node", label);
        }
        health.suspected = suspected;
      }
      switch (health.circuit) {
        case CLOSED -> {
          List<Long> sends = health.quietSends;
          while (!sends.isEmpty() && tick - sends.get(0) > QUIET_WINDOW_TICKS) {
            sends.remove(0);
          }
          if (sends.size() >= QUIET_REQUESTS && tick - sends.get(0) >= QUIET_TICKS) {
            metrics.count("node.quiet", 1, "node", label);
            open(health, tick, label);
          }
        }
        case OPEN -> {
          if (tick >= health.reopensAt) {
            health.circuit = Circuit.HALF_OPEN;
            metrics.count("node.circuit", 1, "node", label, "state", "half_open");
          }
        }
        case HALF_OPEN -> {}
        case PROBING -> {
          if (tick - health.probeTick > PROBE_TICKS) {
            health.backoff = Math.min(2 * health.backoff, MAX_BACKOFF_TICKS);
            open(health, tick, label);
          }
        }
      }
      boolean inService = inService(health);
      if (health.inService == null || inService != health.inService) {
        metrics.gauge("node.in_service", inService ? 1 : 0, "node", label);
        if (!inService) {
          // Its requests are canceled now, so those left unanswered show nothing.
          health.quietSends.clear();
          lost.add(node);
        }
      }
      health.inService = inService;
    }
    return lost;
  }

  private void open(Health health, long tick, String label) {
    health.circuit = Circuit.OPEN;
    health.reopensAt = tick + health.backoff;
    health.quietSends.clear();
    metrics.count("node.circuit", 1, "node", label, "state", "open");
  }

  Circuit circuit(NodeId node) {
    return health(node).circuit;
  }

  /** Heartbeat intervals the detector holds for the node, the two it starts with included. */
  int samples(NodeId node) {
    return health(node).intervals.size();
  }

  boolean suspected(NodeId node) {
    return health(node).suspected;
  }

  private Health health(NodeId node) {
    Health health = nodes.get(node);
    if (health == null) {
      throw new IllegalStateException(node + " is not in the fleet");
    }
    return health;
  }
}
