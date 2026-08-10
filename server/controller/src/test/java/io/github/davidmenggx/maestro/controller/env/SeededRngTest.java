package io.github.davidmenggx.maestro.controller.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeededRngTest {

  @Test
  void oneSeedGivesOneSequence() {
    assertEquals(draw(SeededRng.of(42L)), draw(SeededRng.of(42L)));
    assertNotEquals(draw(SeededRng.of(42L)), draw(SeededRng.of(43L)));
  }

  @Test
  void derivedStreamsAreNamedAndIndependent() {
    Rng parent = SeededRng.of(42L);
    assertEquals(draw(parent.derive("transport")), draw(SeededRng.of(42L).derive("transport")));
    assertNotEquals(draw(parent.derive("transport")), draw(parent.derive("workload")));
  }

  @Test
  void derivingDoesNotDisturbTheParent() {
    Rng parent = SeededRng.of(42L);
    parent.derive("a");
    parent.derive("b");
    assertEquals(draw(SeededRng.of(42L)), draw(parent));
  }

  private static List<Long> draw(Rng rng) {
    List<Long> values = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      values.add(rng.nextLong());
    }
    return values;
  }
}
