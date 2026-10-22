package io.github.davidmenggx.maestro.controller.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Controller bugs the controller noticed itself, such as a node replying on a channel it never
 * replies on. Bounded, since a misbehaving node could otherwise grow it without end: past {@link
 * #MAX_KEPT} only a count is kept.
 */
final class Violations {

  static final int MAX_KEPT = 64;

  private final List<String> kept = new ArrayList<>();
  private long dropped;

  void add(String violation) {
    if (kept.size() < MAX_KEPT) {
      kept.add(violation);
    } else {
      dropped++;
    }
  }

  /** Every violation kept, then how many were not. */
  List<String> list() {
    List<String> all = new ArrayList<>(kept);
    if (dropped > 0) {
      all.add(dropped + " more violations");
    }
    return all;
  }
}
