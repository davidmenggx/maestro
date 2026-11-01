package io.github.davidmenggx.maestro.controller.core;

import io.github.davidmenggx.maestro.controller.env.Metrics;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Which nodes may be sent requests. A node leaves service on either signal: overdue heartbeats by a
 * phi accrual detector (a frozen or cut-off node), or a quiet request path (requests fell due
 * unanswered and none was answered within the tick budget, as with blocked datagrams or answers too
 * late to use). A node out of service gets no requests, so its mobs fall back to the server.
 * Heartbeats alone restore a suspected node, but requests that fell due unanswered before still
 * count toward a quiet path, which may close meanwhile. A quiet path is probed after a backoff that
 * doubles per failed probe, and any answer in time ends it.
 *
 * <p>A probe is a {@code PathProbe}, which waits in the node's queue and is answered by a worker
 * like a request. A node whose path nothing has shown to work for {@link #CHECK_TICKS}, as a spare
 * or an idle holder, is probed too, and a probe that goes unanswered, with no other answer in time
 * meanwhile, quiets its path. A node is verified once an answer has come on its current link since
 * its path last went quiet.
 *
 * <p>The detector uses the controller's clock at transport arrival, so server stalls do not stretch
 * learned intervals. Phi uses {@code StrictMath} so runs replay on every platform.
 */
public final class NodeHealth {

  /** Phi at which a node is suspected: about one false suspicion in 10^8 heartbeats. */
  public static final double PHI_THRESHOLD = 8.0;

  /** Heartbeat intervals the detector keeps. At one a second, the last 100 s. */
  static final int MAX_SAMPLES = 100;

  /** Heartbeat interval assumed until intervals are seen. The node sends every 1 s. */
  public static final long FIRST_INTERVAL_NANOS = 1_000_000_000L;

  /** Floor of the interval spread, so regular heartbeats are not suspected for a little jitter. */
  public static final long MIN_DEVIATION_NANOS = 200_000_000L;

  /** Pause added to the expected interval, such as a collection pause on the node. */
  public static final long ACCEPTABLE_PAUSE_NANOS = 1_000_000_000L;

  /**
   * Requests that fell due unanswered since the last timely answer before a quiet path takes the
   * node out of service. One lost request and its resends do not.
   */
  public static final int QUIET_REQUESTS = 2;

  /**
   * Ticks since the oldest of those requests first left before the path counts as quiet: past the
   * tick budget.
   */
  public static final int QUIET_TICKS = RequestTracker.MAX_TICKS_LATE + 1;

  /**
   * Ticks from its first send an unanswered request counts toward a quiet path. An older loss says
   * little about the path now.
   */
  public static final int QUIET_WINDOW_TICKS = 2 * QUIET_TICKS;

  /** Ticks a quiet request path waits before its first probe. Each failed probe doubles it. */
  public static final int FIRST_BACKOFF_TICKS = 20;

  public static final int MAX_BACKOFF_TICKS = 160;

  /** Ticks a probe has to be answered: sent the next tick, then the tick budget. */
  public static final int PROBE_TICKS = RequestTracker.SEND_TICKS + RequestTracker.MAX_TICKS_LATE;

  /** Ticks with no answer from a node before its path is probed: a second. */
  public static final int CHECK_TICKS = 20;

  private static final long NONE = Long.MIN_VALUE;

  /** The request path's circuit. */
  enum Circuit {
    /** Requests flow. */
    CLOSED,
    /** Quiet: no requests until the backoff ends. */
    OPEN,
    /** The backoff ended and a probe is out. Nothing else is sent until it is answered. */
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
     * Requests that fell due unanswered since the last timely answer, counted by the tick each
     * first left. They belong to their link, which forgets them.
     */
    private final TreeMap<Long, Integer> missed = new TreeMap<>();

    private int missedCount;

    private long reopensAt;
    private int backoff = FIRST_BACKOFF_TICKS;

    /** The probe out and the tick it was sent, or {@link #NONE}. */
    private long probeRequest = NONE;

    private long probeTick;

    /** The last tick an answer showed the path works, or {@link #NONE} if none has since. */
    private long verified = NONE;

    /** Whether the node was in service at the last {@link NodeHealth#evaluate}, or null before. */
    private Boolean inService;

    private void record(long interval) {
      intervals.addLast(interval);
      if (intervals.size() > MAX_SAMPLES) {
        intervals.removeFirst();
      }
    }

    private void forgetMissed() {
      missed.clear();
      missedCount = 0;
    }

    private void restart(long arrived) {
      intervals.clear();
      // As if two heartbeats had come a quarter of the assumed interval either side.
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
      // At most a hundred samples, so summing each time is cheap and never drifts.
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
   * Phi of a heartbeat {@code elapsed} after the last: minus the log10 of the chance the next one
   * is still to come, from a logistic approximation of the normal distribution. Infinite once that
   * rounds to zero.
   */
  public static double phi(double elapsed, double mean, double deviation) {
    double y = (elapsed - mean) / deviation;
    double e = StrictMath.exp(-y * (1.5976 + 0.070566 * y * y));
    double later = elapsed > mean ? e / (1.0 + e) : 1.0 - 1.0 / (1.0 + e);
    return -StrictMath.log10(later);
  }

  /** The node greeted on a new link, which starts its heartbeat history. */
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
   * The link is gone, with its heartbeats and the requests the node forgot. The circuit stays as
   * is, but a probe out is withdrawn: one that ended a backoff is sent again once a link is up. A
   * node with no link is not judged, so its phi reads 0 until one is up.
   */
  void disconnected(NodeId node) {
    Health health = health(node);
    if (health.lastArrival != NONE) {
      metrics.gauge("node.phi", 0, "node", node.toString());
    }
    health.lastArrival = NONE;
    health.suspected = false;
    health.forgetMissed();
    health.verified = NONE;
    health.probeRequest = NONE;
    if (health.circuit == Circuit.PROBING) {
      health.circuit = Circuit.OPEN;
      health.reopensAt = NONE;
    }
  }

  /**
   * A request first sent at {@code firstSent} fell due unanswered. No cancel was sent for it, so
   * the node could not have dropped it on purpose.
   */
  void missed(NodeId node, long firstSent) {
    Health health = health(node);
    health.missed.merge(firstSent, 1, Integer::sum);
    health.missedCount++;
  }

  /**
   * The node answered in time at {@code tick}, so its request path works, and a check out on it has
   * its answer. A late answer to an abandoned request does not count.
   */
  void answered(NodeId node, long tick) {
    Health health = health(node);
    health.forgetMissed();
    health.verified = tick;
    health.probeRequest = NONE;
    if (health.circuit != Circuit.CLOSED) {
      health.circuit = Circuit.CLOSED;
      health.backoff = FIRST_BACKOFF_TICKS;
      metrics.count("node.circuit", 1, "node", node.toString(), "state", "closed");
    }
  }

  /**
   * Whether the node may be sent requests and still holds those sent: not suspected, with its
   * request path flowing.
   */
  boolean inService(NodeId node) {
    return inService(health(node));
  }

  private static boolean inService(Health health) {
    return !health.suspected && health.circuit == Circuit.CLOSED;
  }

  /** Whether an answer has shown the node's request path works since it last went quiet. */
  boolean verified(NodeId node) {
    return health(node).verified != NONE;
  }

  /**
   * The nodes to probe now, in fleet order: one whose backoff ended, which then waits for its
   * probe, and one in service that has not answered for {@link #CHECK_TICKS}. Only {@code
   * reachable} nodes are probed, and none that is suspected or has a probe out.
   */
  List<NodeId> probesDue(long tick, Predicate<NodeId> reachable) {
    List<NodeId> due = new ArrayList<>();
    for (Map.Entry<NodeId, Health> entry : nodes.entrySet()) {
      NodeId node = entry.getKey();
      Health health = entry.getValue();
      if (health.probeRequest != NONE || health.suspected || !reachable.test(node)) {
        continue;
      }
      if (health.circuit == Circuit.OPEN && tick >= health.reopensAt) {
        health.circuit = Circuit.PROBING;
        metrics.count("node.circuit", 1, "node", node.toString(), "state", "probing");
        due.add(node);
      } else if (health.circuit == Circuit.CLOSED
          && (health.verified == NONE || tick - health.verified >= CHECK_TICKS)) {
        metrics.count("node.check", 1, "node", node.toString());
        due.add(node);
      }
    }
    return due;
  }

  /** {@code requestId} is the probe of a node {@link #probesDue} named, sent at {@code tick}. */
  void probeSent(NodeId node, long requestId, long tick) {
    Health health = health(node);
    health.probeRequest = requestId;
    health.probeTick = tick;
  }

  /** The node's probe out, or {@link Long#MIN_VALUE} if none. */
  long probeOut(NodeId node) {
    return health(node).probeRequest;
  }

  /** The probe was answered at {@code tick}. An answer to an earlier probe shows nothing. */
  void probeAnswered(NodeId node, long requestId, long tick) {
    Health health = health(node);
    if (health.probeRequest != requestId) {
      return;
    }
    answered(node, tick);
  }

  /**
   * Judges every node at the start of a tick and returns those that just left service, whose live
   * requests are then dropped.
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
      boolean probeLate = health.probeRequest != NONE && tick - health.probeTick > PROBE_TICKS;
      switch (health.circuit) {
        case CLOSED -> {
          TreeMap<Long, Integer> missed = health.missed;
          while (!missed.isEmpty() && tick - missed.firstKey() > QUIET_WINDOW_TICKS) {
            health.missedCount -= missed.pollFirstEntry().getValue();
          }
          if (health.missedCount >= QUIET_REQUESTS && tick - missed.firstKey() >= QUIET_TICKS) {
            metrics.count("node.quiet", 1, "node", label);
            open(health, tick, label);
          } else if (probeLate) {
            metrics.count("node.check.failed", 1, "node", label);
            open(health, tick, label);
          }
        }
        case OPEN -> {}
        case PROBING -> {
          if (probeLate) {
            health.backoff = Math.min(2 * health.backoff, MAX_BACKOFF_TICKS);
            open(health, tick, label);
          }
        }
      }
      boolean inService = inService(health);
      if (health.inService == null || inService != health.inService) {
        metrics.gauge("node.in_service", inService ? 1 : 0, "node", label);
        if (!inService) {
          // Its live requests are canceled now, so they show nothing. Those that fell due
          // unanswered before still do, even if heartbeats bring the node back first.
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
    health.probeRequest = NONE;
    health.verified = NONE;
    health.forgetMissed();
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
