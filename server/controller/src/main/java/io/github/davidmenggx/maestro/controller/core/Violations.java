package io.github.davidmenggx.maestro.controller.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Controller bugs noticed by the controller itself. Bounded: past {@link #MAX_KEPT} only a count is
 * kept.
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
